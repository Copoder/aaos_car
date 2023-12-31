/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package android.car.media;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.car.Car;
import android.car.CarLibLog;
import android.car.CarManagerBase;
import android.car.annotation.AddedInOrBefore;
import android.car.annotation.ApiRequirements;
import android.media.AudioAttributes;
import android.media.AudioDeviceAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.util.Log;

import com.android.car.internal.annotation.AttributeUsage;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * APIs for handling audio in a car.
 *
 * In a car environment, we introduced the support to turn audio dynamic routing on /off by
 * setting the "audioUseDynamicRouting" attribute in config.xml
 *
 * When audio dynamic routing is enabled:
 * - Audio devices are grouped into zones
 * - There is at least one primary zone, and extra secondary zones such as RSE
 *   (Reat Seat Entertainment)
 * - Within each zone, audio devices are grouped into volume groups for volume control
 * - Audio is assigned to an audio device based on its AudioAttributes usage
 *
 * When audio dynamic routing is disabled:
 * - There is exactly one audio zone, which is the primary zone
 * - Each volume group represents a controllable STREAM_TYPE, same as AudioManager
 */
public final class CarAudioManager extends CarManagerBase {

    /**
     * Zone id of the primary audio zone.
     * @hide
     */
    @SystemApi
    @AddedInOrBefore(majorVersion = 33)
    public static final int PRIMARY_AUDIO_ZONE = 0x0;

    /**
     * Zone id of the invalid audio zone.
     * @hide
     */
    @SystemApi
    @AddedInOrBefore(majorVersion = 33)
    public static final int INVALID_AUDIO_ZONE = 0xffffffff;

    /**
     * This is used to determine if dynamic routing is enabled via
     * {@link #isAudioFeatureEnabled()}
     */
    @AddedInOrBefore(majorVersion = 33)
    public static final int AUDIO_FEATURE_DYNAMIC_ROUTING = 0x1;

    /**
     * This is used to determine if volume group muting is enabled via
     * {@link #isAudioFeatureEnabled()}
     *
     * <p>
     * If enabled, car volume group muting APIs can be used to mute each volume group,
     * also car volume group muting changed callback will be called upon group mute changes. If
     * disabled, car volume will toggle master mute instead.
     */
    @AddedInOrBefore(majorVersion = 33)
    public static final int AUDIO_FEATURE_VOLUME_GROUP_MUTING = 0x2;

    /** @hide */
    @IntDef(flag = false, prefix = "AUDIO_FEATURE", value = {
            AUDIO_FEATURE_DYNAMIC_ROUTING,
            AUDIO_FEATURE_VOLUME_GROUP_MUTING
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface CarAudioFeature {}

    /**
     * Volume Group ID when volume group not found.
     * @hide
     */
    @AddedInOrBefore(majorVersion = 33)
    public static final int INVALID_VOLUME_GROUP_ID = -1;

    /**
     * Extra for {@link android.media.AudioAttributes.Builder#addBundle(Bundle)}: when used in an
     * {@link android.media.AudioFocusRequest}, the requester should receive all audio focus events,
     * including {@link android.media.AudioManager#AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK}.
     * The requester must hold {@link Car#PERMISSION_RECEIVE_CAR_AUDIO_DUCKING_EVENTS}; otherwise,
     * this extra is ignored.
     *
     * @hide
     */
    @SystemApi
    @AddedInOrBefore(majorVersion = 33)
    public static final String AUDIOFOCUS_EXTRA_RECEIVE_DUCKING_EVENTS =
            "android.car.media.AUDIOFOCUS_EXTRA_RECEIVE_DUCKING_EVENTS";

    /**
     * Extra for {@link android.media.AudioAttributes.Builder#addBundle(Bundle)}: when used in an
     * {@link android.media.AudioFocusRequest}, the requester should receive all audio focus for the
     * the zone. If the zone id is not defined: the audio focus request will default to the
     * currently mapped zone for the requesting uid or {@link CarAudioManager.PRIMARY_AUDIO_ZONE}
     * if no uid mapping currently exist.
     *
     * @hide
     */
    @AddedInOrBefore(majorVersion = 33)
    public static final String AUDIOFOCUS_EXTRA_REQUEST_ZONE_ID =
            "android.car.media.AUDIOFOCUS_EXTRA_REQUEST_ZONE_ID";

    private final ICarAudio mService;
    private final CopyOnWriteArrayList<CarVolumeCallback> mCarVolumeCallbacks;
    private final AudioManager mAudioManager;

    private final EventHandler mEventHandler;

    private final ICarVolumeCallback mCarVolumeCallbackImpl =
            new android.car.media.ICarVolumeCallback.Stub() {
        @Override
        public void onGroupVolumeChanged(int zoneId, int groupId, int flags) {
            mEventHandler.dispatchOnGroupVolumeChanged(zoneId, groupId, flags);
        }

        @Override
        public void onGroupMuteChanged(int zoneId, int groupId, int flags) {
            mEventHandler.dispatchOnGroupMuteChanged(zoneId, groupId, flags);
        }

        @Override
        public void onMasterMuteChanged(int zoneId, int flags) {
            mEventHandler.dispatchOnMasterMuteChanged(zoneId, flags);
        }
    };

    /**
     * @return Whether dynamic routing is enabled or not.
     *
     * @deprecated use {@link #isAudioFeatureEnabled(AUDIO_FEATURE_DYNAMIC_ROUTING)} instead.
     *
     * @hide
     */
    @TestApi
    @Deprecated
    @AddedInOrBefore(majorVersion = 33)
    public boolean isDynamicRoutingEnabled() {
        return isAudioFeatureEnabled(AUDIO_FEATURE_DYNAMIC_ROUTING);
    }

    /**
     * Determines if an audio feature is enabled.
     *
     * @param audioFeature audio feature to query, can be {@link #AUDIO_FEATURE_DYNAMIC_ROUTING} or
     * {@link #AUDIO_FEATURE_VOLUME_GROUP_MUTING}
     * @return Returns {@code true} if the feature is enabled, {@code false} otherwise.
     */
    @AddedInOrBefore(majorVersion = 33)
    public boolean isAudioFeatureEnabled(@CarAudioFeature int audioFeature) {
        try {
            return mService.isAudioFeatureEnabled(audioFeature);
        } catch (RemoteException e) {
            return handleRemoteExceptionFromCarService(e, false);
        }
    }

    /**
     * Sets the volume index for a volume group in primary zone.
     *
     * @see {@link #setGroupVolume(int, int, int, int)}
     * @hide
     */
    @SystemApi
    @RequiresPermission(Car.PERMISSION_CAR_CONTROL_AUDIO_VOLUME)
    @AddedInOrBefore(majorVersion = 33)
    public void setGroupVolume(int groupId, int index, int flags) {
        setGroupVolume(PRIMARY_AUDIO_ZONE, groupId, index, flags);
    }

    /**
     * Sets the volume index for a volume group.
     *
     * @param zoneId The zone id whose volume group is affected.
     * @param groupId The volume group id whose volume index should be set.
     * @param index The volume index to set. See
     *            {@link #getGroupMaxVolume(int, int)} for the largest valid value.
     * @param flags One or more flags (e.g., {@link android.media.AudioManager#FLAG_SHOW_UI},
     *              {@link android.media.AudioManager#FLAG_PLAY_SOUND})
     * @hide
     */
    @SystemApi
    @RequiresPermission(Car.PERMISSION_CAR_CONTROL_AUDIO_VOLUME)
    @AddedInOrBefore(majorVersion = 33)
    public void setGroupVolume(int zoneId, int groupId, int index, int flags) {
        try {
            mService.setGroupVolume(zoneId, groupId, index, flags);
        } catch (RemoteException e) {
            handleRemoteExceptionFromCarService(e);
        }
    }

    /**
     * Returns the maximum volume index for a volume group in primary zone.
     *
     * @see {@link #getGroupMaxVolume(int, int)}
     * @hide
     */
    @SystemApi
    @RequiresPermission(Car.PERMISSION_CAR_CONTROL_AUDIO_VOLUME)
    @AddedInOrBefore(majorVersion = 33)
    public int getGroupMaxVolume(int groupId) {
        return getGroupMaxVolume(PRIMARY_AUDIO_ZONE, groupId);
    }

    /**
     * Returns the maximum volume index for a volume group.
     *
     * @param zoneId The zone id whose volume group is queried.
     * @param groupId The volume group id whose maximum volume index is returned.
     * @return The maximum valid volume index for the given group.
     * @hide
     */
    @SystemApi
    @RequiresPermission(Car.PERMISSION_CAR_CONTROL_AUDIO_VOLUME)
    @AddedInOrBefore(majorVersion = 33)
    public int getGroupMaxVolume(int zoneId, int groupId) {
        try {
            return mService.getGroupMaxVolume(zoneId, groupId);
        } catch (RemoteException e) {
            return handleRemoteExceptionFromCarService(e, 0);
        }
    }

    /**
     * Returns the minimum volume index for a volume group in primary zone.
     *
     * @see {@link #getGroupMinVolume(int, int)}
     * @hide
     */
    @SystemApi
    @RequiresPermission(Car.PERMISSION_CAR_CONTROL_AUDIO_VOLUME)
    @AddedInOrBefore(majorVersion = 33)
    public int getGroupMinVolume(int groupId) {
        return getGroupMinVolume(PRIMARY_AUDIO_ZONE, groupId);
    }

    /**
     * Returns the minimum volume index for a volume group.
     *
     * @param zoneId The zone id whose volume group is queried.
     * @param groupId The volume group id whose minimum volume index is returned.
     * @return The minimum valid volume index for the given group, non-negative
     * @hide
     */
    @SystemApi
    @RequiresPermission(Car.PERMISSION_CAR_CONTROL_AUDIO_VOLUME)
    @AddedInOrBefore(majorVersion = 33)
    public int getGroupMinVolume(int zoneId, int groupId) {
        try {
            return mService.getGroupMinVolume(zoneId, groupId);
        } catch (RemoteException e) {
            return handleRemoteExceptionFromCarService(e, 0);
        }
    }

    /**
     * Returns the current volume index for a volume group in primary zone.
     *
     * @see {@link #getGroupVolume(int, int)}
     * @hide
     */
    @SystemApi
    @RequiresPermission(Car.PERMISSION_CAR_CONTROL_AUDIO_VOLUME)
    @AddedInOrBefore(majorVersion = 33)
    public int getGroupVolume(int groupId) {
        return getGroupVolume(PRIMARY_AUDIO_ZONE, groupId);
    }

    /**
     * Returns the current volume index for a volume group.
     *
     * @param zoneId The zone id whose volume groups is queried.
     * @param groupId The volume group id whose volume index is returned.
     * @return The current volume index for the given group.
     *
     * @see #getGroupMaxVolume(int, int)
     * @see #setGroupVolume(int, int, int, int)
     * @hide
     */
    @SystemApi
    @RequiresPermission(Car.PERMISSION_CAR_CONTROL_AUDIO_VOLUME)
    @AddedInOrBefore(majorVersion = 33)
    public int getGroupVolume(int zoneId, int groupId) {
        try {
            return mService.getGroupVolume(zoneId, groupId);
        } catch (RemoteException e) {
            return handleRemoteExceptionFromCarService(e, 0);
        }
    }

    /**
     * Adjust the relative volume in the front vs back of the vehicle cabin.
     *
     * @param value in the range -1.0 to 1.0 for fully toward the back through
     *              fully toward the front.  0.0 means evenly balanced.
     *
     * @see #setBalanceTowardRight(float)
     * @hide
     */
    @SystemApi
    @RequiresPermission(Car.PERMISSION_CAR_CONTROL_AUDIO_VOLUME)
    @AddedInOrBefore(majorVersion = 33)
    public void setFadeTowardFront(float value) {
        try {
            mService.setFadeTowardFront(value);
        } catch (RemoteException e) {
            handleRemoteExceptionFromCarService(e);
        }
    }

    /**
     * Adjust the relative volume on the left vs right side of the vehicle cabin.
     *
     * @param value in the range -1.0 to 1.0 for fully toward the left through
     *              fully toward the right.  0.0 means evenly balanced.
     *
     * @see #setFadeTowardFront(float)
     * @hide
     */
    @SystemApi
    @RequiresPermission(Car.PERMISSION_CAR_CONTROL_AUDIO_VOLUME)
    @AddedInOrBefore(majorVersion = 33)
    public void setBalanceTowardRight(float value) {
        try {
            mService.setBalanceTowardRight(value);
        } catch (RemoteException e) {
            handleRemoteExceptionFromCarService(e);
        }
    }

    /**
     * Queries the system configuration in order to report the available, non-microphone audio
     * input devices.
     *
     * @return An array of strings representing the available input ports.
     * Each port is identified by it's "address" tag in the audioPolicyConfiguration xml file.
     * Empty array if we find nothing.
     *
     * @see #createAudioPatch(String, int, int)
     * @see #releaseAudioPatch(CarAudioPatchHandle)
     *
     * @deprecated use {@link AudioManager#getDevices(int)} with
     * {@link AudioManager#GET_DEVICES_INPUTS} instead
     * @hide
     */
    @SystemApi
    @RequiresPermission(Car.PERMISSION_CAR_CONTROL_AUDIO_SETTINGS)
    @Deprecated
    @AddedInOrBefore(majorVersion = 33)
    public @NonNull String[] getExternalSources() {
        try {
            return mService.getExternalSources();
        } catch (RemoteException e) {
            handleRemoteExceptionFromCarService(e);
            return new String[0];
        }
    }

    /**
     * Given an input port identified by getExternalSources(), request that it's audio signal
     * be routed below the HAL to the output port associated with the given usage.  For example,
     * The output of a tuner might be routed directly to the output buss associated with
     * AudioAttributes.USAGE_MEDIA while the tuner is playing.
     *
     * @param sourceAddress the input port name obtained from getExternalSources().
     * @param usage the type of audio represented by this source (usually USAGE_MEDIA).
     * @param gainInMillibels How many steps above the minimum value defined for the source port to
     *                       set the gain when creating the patch.
     *                       This may be used for source balancing without affecting the user
     *                       controlled volumes applied to the destination ports.  A value of
     *                       0 indicates no gain change is requested.
     * @return A handle for the created patch which can be used to later remove it.
     *
     * @see #getExternalSources()
     * @see #releaseAudioPatch(CarAudioPatchHandle)
     *
     * @deprecated use {@link android.media.HwAudioSource} instead
     * @hide
     */
    @SystemApi
    @RequiresPermission(Car.PERMISSION_CAR_CONTROL_AUDIO_SETTINGS)
    @Deprecated
    @AddedInOrBefore(majorVersion = 33)
    public CarAudioPatchHandle createAudioPatch(String sourceAddress, @AttributeUsage int usage,
            int gainInMillibels) {
        try {
            return mService.createAudioPatch(sourceAddress, usage, gainInMillibels);
        } catch (RemoteException e) {
            return handleRemoteExceptionFromCarService(e, null);
        }
    }

    /**
     * Removes the association between an input port and an output port identified by the provided
     * handle.
     *
     * @param patch CarAudioPatchHandle returned from createAudioPatch().
     *
     * @see #getExternalSources()
     * @see #createAudioPatch(String, int, int)
     *
     * @deprecated use {@link android.media.HwAudioSource} instead
     * @hide
     */
    @SystemApi
    @RequiresPermission(Car.PERMISSION_CAR_CONTROL_AUDIO_SETTINGS)
    @Deprecated
    @AddedInOrBefore(majorVersion = 33)
    public void releaseAudioPatch(CarAudioPatchHandle patch) {
        try {
            mService.releaseAudioPatch(patch);
        } catch (RemoteException e) {
            handleRemoteExceptionFromCarService(e);
        }
    }

    /**
     * Gets the count of available volume groups in primary zone.
     *
     * @see {@link #getVolumeGroupCount(int)}
     * @hide
     */
    @SystemApi
    @RequiresPermission(Car.PERMISSION_CAR_CONTROL_AUDIO_VOLUME)
    @AddedInOrBefore(majorVersion = 33)
    public int getVolumeGroupCount() {
        return getVolumeGroupCount(PRIMARY_AUDIO_ZONE);
    }

    /**
     * Gets the count of available volume groups in the system.
     *
     * @param zoneId The zone id whois count of volume groups is queried.
     * @return Count of volume groups
     * @hide
     */
    @SystemApi
    @RequiresPermission(Car.PERMISSION_CAR_CONTROL_AUDIO_VOLUME)
    @AddedInOrBefore(majorVersion = 33)
    public int getVolumeGroupCount(int zoneId) {
        try {
            return mService.getVolumeGroupCount(zoneId);
        } catch (RemoteException e) {
            return handleRemoteExceptionFromCarService(e, 0);
        }
    }

    /**
     * Gets the volume group id for a given {@link AudioAttributes} usage in primary zone.
     *
     * @see {@link #getVolumeGroupIdForUsage(int, int)}
     * @hide
     */
    @SystemApi
    @RequiresPermission(Car.PERMISSION_CAR_CONTROL_AUDIO_VOLUME)
    @AddedInOrBefore(majorVersion = 33)
    public int getVolumeGroupIdForUsage(@AttributeUsage int usage) {
        return getVolumeGroupIdForUsage(PRIMARY_AUDIO_ZONE, usage);
    }

    /**
     * Gets the volume group id for a given {@link AudioAttributes} usage.
     *
     * @param zoneId The zone id whose volume group is queried.
     * @param usage The {@link AudioAttributes} usage to get a volume group from.
     * @return The volume group id where the usage belongs to
     * @hide
     */
    @SystemApi
    @RequiresPermission(Car.PERMISSION_CAR_CONTROL_AUDIO_VOLUME)
    @AddedInOrBefore(majorVersion = 33)
    public int getVolumeGroupIdForUsage(int zoneId, @AttributeUsage int usage) {
        try {
            return mService.getVolumeGroupIdForUsage(zoneId, usage);
        } catch (RemoteException e) {
            return handleRemoteExceptionFromCarService(e, 0);
        }
    }

    /**
     * Gets array of {@link AudioAttributes} usages for a volume group in primary zone.
     *
     * @see {@link #getUsagesForVolumeGroupId(int, int)}
     * @hide
     */
    @SystemApi
    @RequiresPermission(Car.PERMISSION_CAR_CONTROL_AUDIO_VOLUME)
    @AddedInOrBefore(majorVersion = 33)
    public @NonNull int[] getUsagesForVolumeGroupId(int groupId) {
        return getUsagesForVolumeGroupId(PRIMARY_AUDIO_ZONE, groupId);
    }

    /**
     * Returns the volume group info associated with the zone id and group id.
     *
     * <p>The volume information, including mute, blocked, limited state will reflect the state
     * of the volume group at the time of query.
     *
     * @param zoneId zone id for the group to query
     * @param groupId group id for the group to query
     * @throws IllegalArgumentException if the audio zone or group id are invalid
     *
     * @return the current volume group info
     *
     * @hide
     */
    @SystemApi
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.TIRAMISU_3,
            minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
    @RequiresPermission(Car.PERMISSION_CAR_CONTROL_AUDIO_VOLUME)
    @Nullable
    public CarVolumeGroupInfo getVolumeGroupInfo(int zoneId, int groupId) {
        try {
            return mService.getVolumeGroupInfo(zoneId, groupId);
        } catch (RemoteException e) {
            return handleRemoteExceptionFromCarService(e, null);
        }
    }

    /**
     * Returns a list of volume group info associated with the zone id.
     *
     * <p>The volume information, including mute, blocked, limited state will reflect the state
     * of the volume group at the time of query.
     *
     * @param zoneId zone id for the group to query
     * @throws IllegalArgumentException if the audio zone is invalid
     *
     * @return all the current volume group info's for the zone id
     *
     * @hide
     */
    @SystemApi
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.TIRAMISU_3,
            minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
    @RequiresPermission(Car.PERMISSION_CAR_CONTROL_AUDIO_VOLUME)
    @NonNull
    public List<CarVolumeGroupInfo> getVolumeGroupInfosForZone(int zoneId) {
        try {
            return mService.getVolumeGroupInfosForZone(zoneId);
        } catch (RemoteException e) {
            return handleRemoteExceptionFromCarService(e, Collections.EMPTY_LIST);
        }
    }

    /**
     * Returns a list of audio attributes associated with the volume group info.
     *
     * @param groupInfo group info to query
     * @throws NullPointerException if the volume group info is {@code null}
     *
     * @return list of audio attributes associated with the volume group info
     *
     * @hide
     */
    @SystemApi
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.TIRAMISU_3,
            minPlatformVersion = ApiRequirements.PlatformVersion.TIRAMISU_0)
    @RequiresPermission(Car.PERMISSION_CAR_CONTROL_AUDIO_VOLUME)
    @NonNull
    public List<AudioAttributes> getAudioAttributesForVolumeGroup(
            @NonNull CarVolumeGroupInfo groupInfo) {
        try {
            return mService.getAudioAttributesForVolumeGroup(groupInfo);
        } catch (RemoteException e) {
            return handleRemoteExceptionFromCarService(e, Collections.EMPTY_LIST);
        }
    }

    /**
     * Gets array of {@link AudioAttributes} usages for a volume group in a zone.
     *
     * @param zoneId The zone id whose volume group is queried.
     * @param groupId The volume group id whose associated audio usages is returned.
     * @return Array of {@link AudioAttributes} usages for a given volume group id
     * @hide
     */
    @SystemApi
    @RequiresPermission(Car.PERMISSION_CAR_CONTROL_AUDIO_VOLUME)
    @AddedInOrBefore(majorVersion = 33)
    public @NonNull int[] getUsagesForVolumeGroupId(int zoneId, int groupId) {
        try {
            return mService.getUsagesForVolumeGroupId(zoneId, groupId);
        } catch (RemoteException e) {
            return handleRemoteExceptionFromCarService(e, new int[0]);
        }
    }

    /**
     * Determines if a particular volume group has any audio playback in a zone
     *
     * @param zoneId The zone id whose volume group is queried.
     * @param groupId The volume group id whose associated audio usages is returned.
     * @return {@code true} if the group has active playback, {@code false} otherwise
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(Car.PERMISSION_CAR_CONTROL_AUDIO_VOLUME)
    @AddedInOrBefore(majorVersion = 33)
    public boolean isPlaybackOnVolumeGroupActive(int zoneId, int groupId) {
        try {
            return mService.isPlaybackOnVolumeGroupActive(zoneId, groupId);
        } catch (RemoteException e) {
            return handleRemoteExceptionFromCarService(e, false);
        }
    }

    /**
     * Gets the audio zones currently available
     *
     * @return audio zone ids
     * @hide
     */
    @SystemApi
    @RequiresPermission(Car.PERMISSION_CAR_CONTROL_AUDIO_SETTINGS)
    @AddedInOrBefore(majorVersion = 33)
    public @NonNull List<Integer> getAudioZoneIds() {
        try {
            int[] zoneIdArray = mService.getAudioZoneIds();
            List<Integer> zoneIdList = new ArrayList<Integer>(zoneIdArray.length);
            for (int zoneIdValue : zoneIdArray) {
                zoneIdList.add(zoneIdValue);
            }
            return zoneIdList;
        } catch (RemoteException e) {
            return handleRemoteExceptionFromCarService(e, Collections.emptyList());
        }
    }

    /**
     * Gets the audio zone id currently mapped to uId,
     * defaults to PRIMARY_AUDIO_ZONE if no mapping exist
     *
     * @param uid The uid to map
     * @return zone id mapped to uid
     * @hide
     */
    @RequiresPermission(Car.PERMISSION_CAR_CONTROL_AUDIO_SETTINGS)
    @AddedInOrBefore(majorVersion = 33)
    public int getZoneIdForUid(int uid) {
        try {
            return mService.getZoneIdForUid(uid);
        } catch (RemoteException e) {
            return handleRemoteExceptionFromCarService(e, 0);
        }
    }

    /**
     * Maps the audio zone id to uid
     *
     * @param zoneId The audio zone id
     * @param uid The uid to map
     * @return true if the uid is successfully mapped
     * @hide
     */
    @RequiresPermission(Car.PERMISSION_CAR_CONTROL_AUDIO_SETTINGS)
    @AddedInOrBefore(majorVersion = 33)
    public boolean setZoneIdForUid(int zoneId, int uid) {
        try {
            return mService.setZoneIdForUid(zoneId, uid);
        } catch (RemoteException e) {
            return handleRemoteExceptionFromCarService(e, false);
        }
    }

    /**
     * Clears the current zone mapping of the uid
     *
     * @param uid The uid to clear
     * @return true if the zone was successfully cleared
     * @hide
     */
    @RequiresPermission(Car.PERMISSION_CAR_CONTROL_AUDIO_SETTINGS)
    @AddedInOrBefore(majorVersion = 33)
    public boolean clearZoneIdForUid(int uid) {
        try {
            return mService.clearZoneIdForUid(uid);
        } catch (RemoteException e) {
            return handleRemoteExceptionFromCarService(e, false);
        }
    }

    /**
     * Gets the output device for a given {@link AudioAttributes} usage in zoneId.
     *
     * <p><b>Note:</b> To be used for routing to a specific device. Most applications should
     * use the regular routing mechanism, which is to set audio attribute usage to
     * an audio track.
     *
     * @param zoneId zone id to query for device
     * @param usage usage where audio is routed
     * @return Audio device info, returns {@code null} if audio device usage fails to map to
     * an active audio device. This is different from the using an invalid value for
     * {@link AudioAttributes} usage. In the latter case the query will fail with a
     * RuntimeException indicating the issue.
     *
     * @hide
     */
    @SystemApi
    @Nullable
    @RequiresPermission(Car.PERMISSION_CAR_CONTROL_AUDIO_SETTINGS)
    @AddedInOrBefore(majorVersion = 33)
    public AudioDeviceInfo getOutputDeviceForUsage(int zoneId, @AttributeUsage int usage) {
        try {
            String deviceAddress = mService.getOutputDeviceAddressForUsage(zoneId, usage);
            if (deviceAddress == null) {
                return null;
            }
            AudioDeviceInfo[] outputDevices =
                    mAudioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
            for (AudioDeviceInfo info : outputDevices) {
                if (info.getAddress().equals(deviceAddress)) {
                    return info;
                }
            }
            return null;
        } catch (RemoteException e) {
            return handleRemoteExceptionFromCarService(e, null);
        }
    }

    /**
     * Gets the input devices for an audio zone
     *
     * @return list of input devices
     * @hide
     */
    @SystemApi
    @RequiresPermission(Car.PERMISSION_CAR_CONTROL_AUDIO_SETTINGS)
    @AddedInOrBefore(majorVersion = 33)
    public @NonNull List<AudioDeviceInfo> getInputDevicesForZoneId(int zoneId) {
        try {
            return convertInputDevicesToDeviceInfos(
                    mService.getInputDevicesForZoneId(zoneId),
                    AudioManager.GET_DEVICES_INPUTS);
        } catch (RemoteException e) {
            return handleRemoteExceptionFromCarService(e, Collections.EMPTY_LIST);
        }
    }

    /** @hide */
    @Override
    @AddedInOrBefore(majorVersion = 33)
    public void onCarDisconnected() {
        if (mService != null && !mCarVolumeCallbacks.isEmpty()) {
            unregisterVolumeCallback();
        }
    }

    /** @hide */
    public CarAudioManager(Car car, IBinder service) {
        super(car);
        mService = ICarAudio.Stub.asInterface(service);
        mAudioManager = getContext().getSystemService(AudioManager.class);
        mCarVolumeCallbacks = new CopyOnWriteArrayList<>();
        mEventHandler = new EventHandler(getEventHandler().getLooper());
    }

    /**
     * Registers a {@link CarVolumeCallback} to receive volume change callbacks
     * @param callback {@link CarVolumeCallback} instance, can not be null
     * <p>
     * Requires permission Car.PERMISSION_CAR_CONTROL_AUDIO_VOLUME
     */
    @AddedInOrBefore(majorVersion = 33)
    public void registerCarVolumeCallback(@NonNull CarVolumeCallback callback) {
        Objects.requireNonNull(callback);

        if (mCarVolumeCallbacks.isEmpty()) {
            registerVolumeCallback();
        }

        mCarVolumeCallbacks.add(callback);
    }

    /**
     * Unregisters a {@link CarVolumeCallback} from receiving volume change callbacks
     * @param callback {@link CarVolumeCallback} instance previously registered, can not be null
     * <p>
     * Requires permission Car.PERMISSION_CAR_CONTROL_AUDIO_VOLUME
     */
    @AddedInOrBefore(majorVersion = 33)
    public void unregisterCarVolumeCallback(@NonNull CarVolumeCallback callback) {
        Objects.requireNonNull(callback);
        if (mCarVolumeCallbacks.remove(callback) && mCarVolumeCallbacks.isEmpty()) {
            unregisterVolumeCallback();
        }
    }

    private void registerVolumeCallback() {
        try {
            mService.registerVolumeCallback(mCarVolumeCallbackImpl.asBinder());
        } catch (RemoteException e) {
            Log.e(CarLibLog.TAG_CAR, "registerVolumeCallback failed", e);
        }
    }

    private void unregisterVolumeCallback() {
        try {
            mService.unregisterVolumeCallback(mCarVolumeCallbackImpl.asBinder());
        } catch (RemoteException e) {
            handleRemoteExceptionFromCarService(e);
        }
    }

    /**
     * Returns the whether a volume group is muted
     *
     * <p><b>Note:<b/> If {@link #AUDIO_FEATURE_VOLUME_GROUP_MUTING} is disabled this will always
     * return {@code false} as group mute is disabled.
     *
     * @param zoneId The zone id whose volume groups is queried.
     * @param groupId The volume group id whose mute state is returned.
     * @return {@code true} if the volume group is muted, {@code false}
     * otherwise
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(Car.PERMISSION_CAR_CONTROL_AUDIO_VOLUME)
    @AddedInOrBefore(majorVersion = 33)
    public boolean isVolumeGroupMuted(int zoneId, int groupId) {
        try {
            return mService.isVolumeGroupMuted(zoneId, groupId);
        } catch (RemoteException e) {
            return handleRemoteExceptionFromCarService(e, false);
        }
    }

    /**
     * Sets a volume group mute
     *
     * <p><b>Note:<b/> If {@link #AUDIO_FEATURE_VOLUME_GROUP_MUTING} is disabled this will throw an
     * error indicating the issue.
     *
     * @param zoneId The zone id whose volume groups will be changed.
     * @param groupId The volume group id whose mute state will be changed.
     * @param mute {@code true} to mute volume group, {@code false} otherwise
     * @param flags One or more flags (e.g., {@link android.media.AudioManager#FLAG_SHOW_UI},
     * {@link android.media.AudioManager#FLAG_PLAY_SOUND})
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(Car.PERMISSION_CAR_CONTROL_AUDIO_VOLUME)
    @AddedInOrBefore(majorVersion = 33)
    public void setVolumeGroupMute(int zoneId, int groupId, boolean mute, int flags) {
        try {
            mService.setVolumeGroupMute(zoneId, groupId, mute, flags);
        } catch (RemoteException e) {
            handleRemoteExceptionFromCarService(e);
        }
    }

    private List<AudioDeviceInfo> convertInputDevicesToDeviceInfos(
            List<AudioDeviceAttributes> devices, int flag) {
        int addressesSize = devices.size();
        Set<String> deviceAddressMap = new HashSet<>(addressesSize);
        for (int i = 0; i < addressesSize; ++i) {
            AudioDeviceAttributes device = devices.get(i);
            deviceAddressMap.add(device.getAddress());
        }
        List<AudioDeviceInfo> deviceInfoList = new ArrayList<>(devices.size());
        AudioDeviceInfo[] inputDevices = mAudioManager.getDevices(flag);
        for (int i = 0; i < inputDevices.length; ++i) {
            AudioDeviceInfo info = inputDevices[i];
            if (info.isSource() && deviceAddressMap.contains(info.getAddress())) {
                deviceInfoList.add(info);
            }
        }
        return deviceInfoList;
    }

    private final class EventHandler extends Handler {
        private static final int MSG_GROUP_VOLUME_CHANGE = 1;
        private static final int MSG_GROUP_MUTE_CHANGE = 2;
        private static final int MSG_MASTER_MUTE_CHANGE = 3;

        private EventHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_GROUP_VOLUME_CHANGE:
                    VolumeGroupChangeInfo volumeInfo = (VolumeGroupChangeInfo) msg.obj;
                    handleOnGroupVolumeChanged(volumeInfo.mZoneId, volumeInfo.mGroupId,
                            volumeInfo.mFlags);
                    break;
                case MSG_GROUP_MUTE_CHANGE:
                    VolumeGroupChangeInfo muteInfo = (VolumeGroupChangeInfo) msg.obj;
                    handleOnGroupMuteChanged(muteInfo.mZoneId, muteInfo.mGroupId, muteInfo.mFlags);
                    break;
                case MSG_MASTER_MUTE_CHANGE:
                    handleOnMasterMuteChanged(msg.arg1, msg.arg2);
                    break;
                default:
                    Log.e(CarLibLog.TAG_CAR, "Unknown nessage not handled:" + msg.what);
                    break;
            }
        }

        private void dispatchOnGroupVolumeChanged(int zoneId, int groupId, int flags) {
            VolumeGroupChangeInfo volumeInfo = new VolumeGroupChangeInfo(zoneId, groupId, flags);
            sendMessage(obtainMessage(MSG_GROUP_VOLUME_CHANGE, volumeInfo));
        }

        private void dispatchOnMasterMuteChanged(int zoneId, int flags) {
            sendMessage(obtainMessage(MSG_MASTER_MUTE_CHANGE, zoneId, flags));
        }

        private void dispatchOnGroupMuteChanged(int zoneId, int groupId, int flags) {
            VolumeGroupChangeInfo volumeInfo = new VolumeGroupChangeInfo(zoneId, groupId, flags);
            sendMessage(obtainMessage(MSG_GROUP_MUTE_CHANGE, volumeInfo));
        }

        private class VolumeGroupChangeInfo {
            public int mZoneId;
            public int mGroupId;
            public int mFlags;

            VolumeGroupChangeInfo(int zoneId, int groupId, int flags) {
                mZoneId = zoneId;
                mGroupId = groupId;
                mFlags = flags;
            }
        }
    }

    private void handleOnGroupVolumeChanged(int zoneId, int groupId, int flags) {
        for (CarVolumeCallback callback : mCarVolumeCallbacks) {
            callback.onGroupVolumeChanged(zoneId, groupId, flags);
        }
    }

    private void handleOnMasterMuteChanged(int zoneId, int flags) {
        for (CarVolumeCallback callback : mCarVolumeCallbacks) {
            callback.onMasterMuteChanged(zoneId, flags);
        }
    }

    private void handleOnGroupMuteChanged(int zoneId, int groupId, int flags) {
        for (CarVolumeCallback callback : mCarVolumeCallbacks) {
            callback.onGroupMuteChanged(zoneId, groupId, flags);
        }
    }

    /**
     * Callback interface to receive volume change events in a car.
     * Extend this class and register it with {@link #registerCarVolumeCallback(CarVolumeCallback)}
     * and unregister it via {@link #unregisterCarVolumeCallback(CarVolumeCallback)}
     */
    public abstract static class CarVolumeCallback {
        /**
         * This is called whenever a group volume is changed.
         * The changed-to volume index is not included, the caller is encouraged to
         * get the current group volume index via CarAudioManager.
         *
         * @param zoneId Id of the audio zone that volume change happens
         * @param groupId Id of the volume group that volume is changed
         * @param flags see {@link android.media.AudioManager} for flag definitions
         */
        @AddedInOrBefore(majorVersion = 33)
        public void onGroupVolumeChanged(int zoneId, int groupId, int flags) {}

        /**
         * This is called whenever the global mute state is changed.
         * The changed-to global mute state is not included, the caller is encouraged to
         * get the current global mute state via AudioManager.
         *
         * <p><b>Note:<b/> If {@link CarAudioManager#AUDIO_FEATURE_VOLUME_GROUP_MUTING} is disabled
         * this will be triggered on mute changes. Otherwise, car audio mute changes will trigger
         * {@link #onGroupMuteChanged(int, int, int)}
         *
         * @param zoneId Id of the audio zone that global mute state change happens
         * @param flags see {@link android.media.AudioManager} for flag definitions
         */
        @AddedInOrBefore(majorVersion = 33)
        public void onMasterMuteChanged(int zoneId, int flags) {}

        /**
         * This is called whenever a group mute state is changed.
         * The changed-to mute state is not included, the caller is encouraged to
         * get the current group mute state via CarAudioManager.
         *
         * <p><b>Note:<b/> If {@link CarAudioManager#AUDIO_FEATURE_VOLUME_GROUP_MUTING} is enabled
         * this will be triggered on mute changes. Otherwise, car audio mute changes will trigger
         * {@link #onMasterMuteChanged(int, int)}
         *
         * @param zoneId Id of the audio zone that volume change happens
         * @param groupId Id of the volume group that volume is changed
         * @param flags see {@link android.media.AudioManager} for flag definitions
         */
        @AddedInOrBefore(majorVersion = 33)
        public void onGroupMuteChanged(int zoneId, int groupId, int flags) {}
    }
}
