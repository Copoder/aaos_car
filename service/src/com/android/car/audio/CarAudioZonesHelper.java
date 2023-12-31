/*
 * Copyright (C) 2018 The Android Open Source Project
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
package com.android.car.audio;

import static android.car.media.CarAudioManager.PRIMARY_AUDIO_ZONE;

import static com.android.car.audio.CarAudioService.CAR_DEFAULT_AUDIO_ATTRIBUTE;
import static com.android.car.audio.CarAudioUtils.isMicrophoneInputDevice;

import static java.util.Locale.ROOT;

import android.annotation.NonNull;
import android.media.AudioDeviceAttributes;
import android.media.AudioDeviceInfo;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.SparseArray;
import android.util.SparseIntArray;
import android.util.Xml;

import com.android.car.audio.CarAudioContext.AudioContext;
import com.android.internal.util.Preconditions;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A helper class loads all audio zones from the configuration XML file.
 */
/* package */ class CarAudioZonesHelper {
    private static final String NAMESPACE = null;
    private static final String TAG_ROOT = "carAudioConfiguration";
    private static final String TAG_AUDIO_ZONES = "zones";
    private static final String TAG_AUDIO_ZONE = "zone";
    private static final String TAG_VOLUME_GROUPS = "volumeGroups";
    private static final String TAG_VOLUME_GROUP = "group";
    private static final String TAG_AUDIO_DEVICE = "device";
    private static final String TAG_CONTEXT = "context";
    private static final String ATTR_VERSION = "version";
    private static final String ATTR_IS_PRIMARY = "isPrimary";
    private static final String ATTR_ZONE_NAME = "name";
    private static final String ATTR_DEVICE_ADDRESS = "address";
    private static final String ATTR_CONTEXT_NAME = "context";
    private static final String ATTR_ZONE_ID = "audioZoneId";
    private static final String ATTR_OCCUPANT_ZONE_ID = "occupantZoneId";
    private static final String TAG_INPUT_DEVICES = "inputDevices";
    private static final String TAG_INPUT_DEVICE = "inputDevice";
    private static final int INVALID_VERSION = -1;
    private static final int SUPPORTED_VERSION_1 = 1;
    private static final int SUPPORTED_VERSION_2 = 2;
    private static final SparseIntArray SUPPORTED_VERSIONS;

    static {
        SUPPORTED_VERSIONS = new SparseIntArray(2);
        SUPPORTED_VERSIONS.put(SUPPORTED_VERSION_1, SUPPORTED_VERSION_1);
        SUPPORTED_VERSIONS.put(SUPPORTED_VERSION_2, SUPPORTED_VERSION_2);
    }

    static void setNonLegacyContexts(CarVolumeGroup.Builder groupBuilder,
            CarAudioDeviceInfo info) {
        List<Integer> nonCarSystemContexts = CarAudioContext.getCarSystemContextIds();
        for (int index = 0; index < nonCarSystemContexts.size(); index++) {
            @AudioContext int audioContext = nonCarSystemContexts.get(index);
            groupBuilder.setDeviceInfoForContext(audioContext, info);
        }
    }

    private final CarAudioSettings mCarAudioSettings;
    private final List<CarAudioContextInfo> mCarAudioContextInfos;
    private final Map<String, CarAudioDeviceInfo> mAddressToCarAudioDeviceInfo;
    private final Map<String, AudioDeviceInfo> mAddressToInputAudioDeviceInfoForAllInputDevices;
    private final InputStream mInputStream;
    private final SparseIntArray mZoneIdToOccupantZoneIdMapping;
    private final Set<Integer> mAudioZoneIds;
    private final Set<String> mAssignedInputAudioDevices;
    private final boolean mUseCarVolumeGroupMute;

    private final ArrayMap<String, Integer> mContextNameToId = new ArrayMap<>();
    private CarAudioContext mCarAudioContext;
    private int mNextSecondaryZoneId;
    private int mCurrentVersion;

    /**
     * <p><b>Note: <b/> CarAudioZonesHelper is expected to be used from a single thread. This
     * should be the same thread that originally called new CarAudioZonesHelper.
     */
    CarAudioZonesHelper(@NonNull CarAudioSettings carAudioSettings,
            @NonNull InputStream inputStream,
            @NonNull List<CarAudioDeviceInfo> carAudioDeviceInfos,
            @NonNull AudioDeviceInfo[] inputDeviceInfo, boolean useCarVolumeGroupMute) {
        mCarAudioContextInfos = CarAudioContext.getAllContextsInfo();
        mCarAudioSettings = Objects.requireNonNull(carAudioSettings);
        mInputStream = Objects.requireNonNull(inputStream);
        Objects.requireNonNull(carAudioDeviceInfos);
        Objects.requireNonNull(inputDeviceInfo);
        mAddressToCarAudioDeviceInfo = CarAudioZonesHelper.generateAddressToInfoMap(
                carAudioDeviceInfos);
        mAddressToInputAudioDeviceInfoForAllInputDevices =
                CarAudioZonesHelper.generateAddressToInputAudioDeviceInfoMap(inputDeviceInfo);
        mNextSecondaryZoneId = PRIMARY_AUDIO_ZONE + 1;
        mZoneIdToOccupantZoneIdMapping = new SparseIntArray();
        mAudioZoneIds = new ArraySet<>();
        mAssignedInputAudioDevices = new ArraySet<>();
        mUseCarVolumeGroupMute = useCarVolumeGroupMute;
    }

    SparseIntArray getCarAudioZoneIdToOccupantZoneIdMapping() {
        return mZoneIdToOccupantZoneIdMapping;
    }

    SparseArray<CarAudioZone> loadAudioZones() throws IOException, XmlPullParserException {
        return parseCarAudioZones(mInputStream);
    }

    private static Map<String, CarAudioDeviceInfo> generateAddressToInfoMap(
            List<CarAudioDeviceInfo> carAudioDeviceInfos) {
        return carAudioDeviceInfos.stream()
                .filter(info -> !TextUtils.isEmpty(info.getAddress()))
                .collect(Collectors.toMap(CarAudioDeviceInfo::getAddress, info -> info));
    }

    private static Map<String, AudioDeviceInfo> generateAddressToInputAudioDeviceInfoMap(
            @NonNull AudioDeviceInfo[] inputAudioDeviceInfos) {
        Map<String, AudioDeviceInfo> deviceAddressToInputDeviceMap =
                new ArrayMap<>(inputAudioDeviceInfos.length);
        for (int i = 0; i < inputAudioDeviceInfos.length; ++i) {
            AudioDeviceInfo device = inputAudioDeviceInfos[i];
            if (device.isSource()) {
                deviceAddressToInputDeviceMap.put(device.getAddress(), device);
            }
        }
        return deviceAddressToInputDeviceMap;
    }

    private SparseArray<CarAudioZone> parseCarAudioZones(InputStream stream)
            throws XmlPullParserException, IOException {
        XmlPullParser parser = Xml.newPullParser();
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, NAMESPACE != null);
        parser.setInput(stream, null);

        // Ensure <carAudioConfiguration> is the root
        parser.nextTag();
        parser.require(XmlPullParser.START_TAG, NAMESPACE, TAG_ROOT);

        // Version check
        final int versionNumber = Integer.parseInt(
                parser.getAttributeValue(NAMESPACE, ATTR_VERSION));

        if (SUPPORTED_VERSIONS.get(versionNumber, INVALID_VERSION) == INVALID_VERSION) {
            throw new IllegalArgumentException("Latest Supported version:"
                    + SUPPORTED_VERSION_2 + " , got version:" + versionNumber);
        }

        mCurrentVersion = versionNumber;

        loadCarAudioContexts();

        // Get all zones configured under <zones> tag
        while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.getEventType() != XmlPullParser.START_TAG) continue;
            if (TAG_AUDIO_ZONES.equals(parser.getName())) {
                return parseAudioZones(parser);
            } else {
                skip(parser);
            }
        }
        throw new RuntimeException(TAG_AUDIO_ZONES + " is missing from configuration");
    }

    private void loadCarAudioContexts() {
        for (int index = 0; index < mCarAudioContextInfos.size(); index++) {
            CarAudioContextInfo info = mCarAudioContextInfos.get(index);
            mContextNameToId.put(info.getName().toLowerCase(ROOT), info.getId());
        }
        mCarAudioContext = new CarAudioContext(mCarAudioContextInfos);
    }

    private SparseArray<CarAudioZone> parseAudioZones(XmlPullParser parser)
            throws XmlPullParserException, IOException {
        SparseArray<CarAudioZone> carAudioZones = new SparseArray<>();

        while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.getEventType() != XmlPullParser.START_TAG) continue;
            if (TAG_AUDIO_ZONE.equals(parser.getName())) {
                CarAudioZone zone = parseAudioZone(parser);
                verifyOnlyOnePrimaryZone(zone, carAudioZones);
                carAudioZones.put(zone.getId(), zone);
            } else {
                skip(parser);
            }
        }

        verifyPrimaryZonePresent(carAudioZones);
        addRemainingMicrophonesToPrimaryZone(carAudioZones);
        return carAudioZones;
    }

    private void addRemainingMicrophonesToPrimaryZone(SparseArray<CarAudioZone> carAudioZones) {
        CarAudioZone primaryAudioZone = carAudioZones.get(PRIMARY_AUDIO_ZONE);
        for (AudioDeviceInfo info : mAddressToInputAudioDeviceInfoForAllInputDevices.values()) {
            if (!mAssignedInputAudioDevices.contains(info.getAddress())
                    && isMicrophoneInputDevice(info)) {
                primaryAudioZone.addInputAudioDevice(new AudioDeviceAttributes(info));
            }
        }
    }

    private void verifyOnlyOnePrimaryZone(CarAudioZone newZone, SparseArray<CarAudioZone> zones) {
        if (newZone.getId() == PRIMARY_AUDIO_ZONE && zones.contains(PRIMARY_AUDIO_ZONE)) {
            throw new RuntimeException("More than one zone parsed with primary audio zone ID: "
                            + PRIMARY_AUDIO_ZONE);
        }
    }

    private void verifyPrimaryZonePresent(SparseArray<CarAudioZone> zones) {
        if (!zones.contains(PRIMARY_AUDIO_ZONE)) {
            throw new RuntimeException("Primary audio zone is required");
        }
    }

    private CarAudioZone parseAudioZone(XmlPullParser parser)
            throws XmlPullParserException, IOException {
        final boolean isPrimary = Boolean.parseBoolean(
                parser.getAttributeValue(NAMESPACE, ATTR_IS_PRIMARY));
        final String zoneName = parser.getAttributeValue(NAMESPACE, ATTR_ZONE_NAME);
        final int audioZoneId = getZoneId(isPrimary, parser);
        parseOccupantZoneId(audioZoneId, parser);
        final CarAudioZone zone = new CarAudioZone(mCarAudioContext, zoneName, audioZoneId);
        while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.getEventType() != XmlPullParser.START_TAG) continue;
            // Expect one <volumeGroups> in one audio zone
            if (TAG_VOLUME_GROUPS.equals(parser.getName())) {
                parseVolumeGroups(parser, zone);
            } else if (TAG_INPUT_DEVICES.equals(parser.getName())) {
                parseInputAudioDevices(parser, zone);
            } else {
                skip(parser);
            }
        }
        return zone;
    }

    private int getZoneId(boolean isPrimary, XmlPullParser parser) {
        String audioZoneIdString = parser.getAttributeValue(NAMESPACE, ATTR_ZONE_ID);
        if (isVersionOne()) {
            Preconditions.checkArgument(audioZoneIdString == null,
                    "Invalid audio attribute %s"
                            + ", Please update car audio configurations file "
                            + "to version to 2 to use it.", ATTR_ZONE_ID);
            return isPrimary ? PRIMARY_AUDIO_ZONE
                    : getNextSecondaryZoneId();
        }
        // Primary zone does not need to define it
        if (isPrimary && audioZoneIdString == null) {
            return PRIMARY_AUDIO_ZONE;
        }
        Objects.requireNonNull(audioZoneIdString, () ->
                "Requires " + ATTR_ZONE_ID + " for all audio zones.");
        int zoneId = parsePositiveIntAttribute(ATTR_ZONE_ID, audioZoneIdString);
        //Verify that primary zone id is PRIMARY_AUDIO_ZONE
        if (isPrimary) {
            Preconditions.checkArgument(zoneId == PRIMARY_AUDIO_ZONE,
                    "Primary zone %s must be %d or it can be left empty.",
                    ATTR_ZONE_ID, PRIMARY_AUDIO_ZONE);
        } else {
            Preconditions.checkArgument(zoneId != PRIMARY_AUDIO_ZONE,
                    "%s can only be %d for primary zone.",
                    ATTR_ZONE_ID, PRIMARY_AUDIO_ZONE);
        }
        validateAudioZoneIdIsUnique(zoneId);
        return zoneId;
    }

    private void parseOccupantZoneId(int audioZoneId, XmlPullParser parser) {
        String occupantZoneIdString = parser.getAttributeValue(NAMESPACE, ATTR_OCCUPANT_ZONE_ID);
        if (isVersionOne()) {
            Preconditions.checkArgument(occupantZoneIdString == null,
                    "Invalid audio attribute %s"
                            + ", Please update car audio configurations file "
                            + "to version to 2 to use it.", ATTR_OCCUPANT_ZONE_ID);
            return;
        }
        //Occupant id not required for all zones
        if (occupantZoneIdString == null) {
            return;
        }
        int occupantZoneId = parsePositiveIntAttribute(ATTR_OCCUPANT_ZONE_ID, occupantZoneIdString);
        validateOccupantZoneIdIsUnique(occupantZoneId);
        mZoneIdToOccupantZoneIdMapping.put(audioZoneId, occupantZoneId);
    }

    private int parsePositiveIntAttribute(String attribute, String integerString) {
        try {
            return Integer.parseUnsignedInt(integerString);
        } catch (NumberFormatException | IndexOutOfBoundsException e) {
            throw new IllegalArgumentException(attribute + " must be a positive integer, but was \""
                    + integerString + "\" instead.", e);
        }
    }

    private void parseInputAudioDevices(XmlPullParser parser, CarAudioZone zone)
            throws IOException, XmlPullParserException {
        if (isVersionOne()) {
            throw new IllegalStateException(
                    TAG_INPUT_DEVICES + " are not supported in car_audio_configuration.xml version "
                            + SUPPORTED_VERSION_1);
        }
        while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.getEventType() != XmlPullParser.START_TAG) continue;
            if (TAG_INPUT_DEVICE.equals(parser.getName())) {
                String audioDeviceAddress =
                        parser.getAttributeValue(NAMESPACE, ATTR_DEVICE_ADDRESS);
                validateInputAudioDeviceAddress(audioDeviceAddress);
                AudioDeviceInfo info =
                        mAddressToInputAudioDeviceInfoForAllInputDevices.get(audioDeviceAddress);
                Preconditions.checkArgument(info != null,
                        "%s %s of %s does not exist, add input device to"
                                + " audio_policy_configuration.xml.",
                        ATTR_DEVICE_ADDRESS, audioDeviceAddress, TAG_INPUT_DEVICE);
                zone.addInputAudioDevice(new AudioDeviceAttributes(info));
            }
            skip(parser);
        }
    }

    private void validateInputAudioDeviceAddress(String audioDeviceAddress) {
        Objects.requireNonNull(audioDeviceAddress, () ->
                TAG_INPUT_DEVICE + " " + ATTR_DEVICE_ADDRESS + " attribute must be present.");
        Preconditions.checkArgument(!audioDeviceAddress.isEmpty(),
                "%s %s attribute can not be empty.",
                TAG_INPUT_DEVICE, ATTR_DEVICE_ADDRESS);
        if (mAssignedInputAudioDevices.contains(audioDeviceAddress)) {
            throw new IllegalArgumentException(TAG_INPUT_DEVICE + " " + audioDeviceAddress
                    + " repeats, " + TAG_INPUT_DEVICES + " can not repeat.");
        }
        mAssignedInputAudioDevices.add(audioDeviceAddress);
    }

    private void validateOccupantZoneIdIsUnique(int occupantZoneId) {
        if (mZoneIdToOccupantZoneIdMapping.indexOfValue(occupantZoneId) > -1) {
            throw new IllegalArgumentException(ATTR_OCCUPANT_ZONE_ID + " " + occupantZoneId
                    + " is already associated with a zone");
        }
    }

    private void validateAudioZoneIdIsUnique(int audioZoneId) {
        if (mAudioZoneIds.contains(audioZoneId)) {
            throw new IllegalArgumentException(ATTR_ZONE_ID + " " + audioZoneId
                    + " is already associated with a zone");
        }
        mAudioZoneIds.add(audioZoneId);
    }

    private void parseVolumeGroups(XmlPullParser parser, CarAudioZone zone)
            throws XmlPullParserException, IOException {
        int groupId = 0;
        while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.getEventType() != XmlPullParser.START_TAG) continue;
            if (TAG_VOLUME_GROUP.equals(parser.getName())) {
                zone.addVolumeGroup(parseVolumeGroup(parser, zone.getId(), groupId));
                groupId++;
            } else {
                skip(parser);
            }
        }
    }

    private CarVolumeGroup parseVolumeGroup(XmlPullParser parser, int zoneId, int groupId)
            throws XmlPullParserException, IOException {
        CarVolumeGroup.Builder groupBuilder =
                new CarVolumeGroup.Builder(mCarAudioSettings, mCarAudioContext,
                        zoneId, groupId, mUseCarVolumeGroupMute);
        while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.getEventType() != XmlPullParser.START_TAG) continue;
            if (TAG_AUDIO_DEVICE.equals(parser.getName())) {
                String address = parser.getAttributeValue(NAMESPACE, ATTR_DEVICE_ADDRESS);
                validateOutputDeviceExist(address);
                parseVolumeGroupContexts(parser, groupBuilder, address);
            } else {
                skip(parser);
            }
        }
        return groupBuilder.build();
    }

    private void validateOutputDeviceExist(String address) {
        if (!mAddressToCarAudioDeviceInfo.containsKey(address)) {
            throw new IllegalStateException(String.format(
                    "Output device address %s does not belong to any configured output device.",
                    address));
        }
    }

    private void parseVolumeGroupContexts(
            XmlPullParser parser, CarVolumeGroup.Builder groupBuilder, String address)
            throws XmlPullParserException, IOException {
        while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.getEventType() != XmlPullParser.START_TAG) continue;
            if (TAG_CONTEXT.equals(parser.getName())) {
                @AudioContext int carAudioContextId = parseCarAudioContextId(
                        parser.getAttributeValue(NAMESPACE, ATTR_CONTEXT_NAME));
                validateCarAudioContextSupport(carAudioContextId);
                CarAudioDeviceInfo info = mAddressToCarAudioDeviceInfo.get(address);
                groupBuilder.setDeviceInfoForContext(carAudioContextId, info);

                // If V1, default new contexts to same device as DEFAULT_AUDIO_USAGE
                if (isVersionOne() && carAudioContextId == mCarAudioContext
                        .getContextForAudioAttribute(CAR_DEFAULT_AUDIO_ATTRIBUTE)) {
                    setNonLegacyContexts(groupBuilder, info);
                }
            }
            // Always skip to upper level since we're at the lowest.
            skip(parser);
        }
    }

    private boolean isVersionOne() {
        return mCurrentVersion == SUPPORTED_VERSION_1;
    }

    private void skip(XmlPullParser parser) throws XmlPullParserException, IOException {
        if (parser.getEventType() != XmlPullParser.START_TAG) {
            throw new IllegalStateException();
        }
        int depth = 1;
        while (depth != 0) {
            switch (parser.next()) {
                case XmlPullParser.END_TAG:
                    depth--;
                    break;
                case XmlPullParser.START_TAG:
                    depth++;
                    break;
            }
        }
    }

    private @AudioContext int parseCarAudioContextId(String context) {
        return mContextNameToId.getOrDefault(context.toLowerCase(ROOT),
                CarAudioContext.getInvalidContext());
    }

    private void validateCarAudioContextSupport(@AudioContext int audioContext) {
        if (isVersionOne() && CarAudioContext.getCarSystemContextIds().contains(audioContext)) {
            throw new IllegalArgumentException(String.format(
                    "Non-legacy audio contexts such as %s are not supported in "
                            + "car_audio_configuration.xml version %d",
                    mCarAudioContext.toString(audioContext), SUPPORTED_VERSION_1));
        }
    }

    private int getNextSecondaryZoneId() {
        int zoneId = mNextSecondaryZoneId;
        mNextSecondaryZoneId += 1;
        return zoneId;
    }

    public CarAudioContext getCarAudioContext() {
        return mCarAudioContext;
    }
}
