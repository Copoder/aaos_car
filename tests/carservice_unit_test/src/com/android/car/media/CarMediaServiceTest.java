/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.car.media;

import static android.car.media.CarMediaManager.MEDIA_SOURCE_MODE_BROWSE;
import static android.car.media.CarMediaManager.MEDIA_SOURCE_MODE_PLAYBACK;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.notNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.ActivityManager;
import android.car.Car;
import android.car.media.ICarMediaSourceListener;
import android.car.test.mocks.AbstractExtendedMockitoTestCase;
import android.car.test.mocks.AndroidMockitoHelper;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.res.Resources;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.IBinder;
import android.os.UserHandle;
import android.os.UserManager;

import com.android.car.CarLocalServices;
import com.android.car.CarLog;
import com.android.car.CarMediaService;
import com.android.car.R;
import com.android.car.power.CarPowerManagementService;
import com.android.car.systeminterface.SystemInterface;
import com.android.car.user.CarUserService;
import com.android.car.user.UserHandleHelper;
import com.android.internal.app.IVoiceInteractionManagerService;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import java.util.ArrayList;
import java.util.List;

public final class CarMediaServiceTest extends AbstractExtendedMockitoTestCase {

    private static final String MEDIA_PACKAGE = "test.package";
    private static final String MEDIA_PACKAGE2 = "test.package2";
    private static final String MEDIA_CLASS = "test_class";
    private static final String MEDIA_CLASS2 = "test_class2";

    private static final int TEST_USER_ID = 100;

    private static final ComponentName MEDIA_COMPONENT =
            new ComponentName(MEDIA_PACKAGE, MEDIA_CLASS);
    private static final ComponentName MEDIA_COMPONENT2 =
            new ComponentName(MEDIA_PACKAGE2, MEDIA_CLASS2);

    @Mock private Context mContext;
    @Mock private Resources mResources;
    @Mock private CarUserService mUserService;
    @Mock private UserManager mUserManager;
    @Mock private PackageManager mPackageManager;
    @Mock private MediaSessionManager mMediaSessionManager;
    @Mock private SystemInterface mMockSystemInterface;
    @Mock private IVoiceInteractionManagerService mMockVoiceService;
    @Mock private CarPowerManagementService mMockCarPowerManagementService;
    @Mock private UserHandleHelper mUserHandleHelper;
    @Mock private SharedPreferences mMockSharedPreferences;
    @Mock private SharedPreferences.Editor mMockSharedPreferencesEditor;

    private CarMediaService mCarMediaService;

    public CarMediaServiceTest() {
        super(CarLog.TAG_MEDIA);
    }

    @Override
    protected void onSessionBuilder(CustomMockitoSessionBuilder builder) {
        builder.spyStatic(ActivityManager.class);
    }

    @Before
    public void setUp() {
        when(mContext.checkCallingOrSelfPermission(anyString()))
                .thenReturn(PackageManager.PERMISSION_GRANTED);
        when(mContext.getPackageManager()).thenReturn(mPackageManager);
        when(mContext.createContextAsUser(any(), anyInt())).thenReturn(mContext);
        when(mContext.getSharedPreferences(anyString(), anyInt()))
                .thenReturn(mMockSharedPreferences);
        when(mMockSharedPreferences.edit()).thenReturn(mMockSharedPreferencesEditor);
        when(mMockSharedPreferencesEditor.putInt(anyString(), anyInt()))
                .thenReturn(mMockSharedPreferencesEditor);
        when(mMockSharedPreferencesEditor.putLong(anyString(), anyLong()))
                .thenReturn(mMockSharedPreferencesEditor);
        when(mMockSharedPreferencesEditor.putString(anyString(), anyString()))
                .thenReturn(mMockSharedPreferencesEditor);

        doReturn(mResources).when(mContext).getResources();
        doReturn(mUserManager).when(mContext).getSystemService(UserManager.class);
        UserHandle user = UserHandle.of(TEST_USER_ID);
        AndroidMockitoHelper.mockAmGetCurrentUser(TEST_USER_ID);
        when(mUserHandleHelper.isEphemeralUser(UserHandle.of(TEST_USER_ID))).thenReturn(false);
        doReturn(mMediaSessionManager).when(mContext).getSystemService(MediaSessionManager.class);

        mCarMediaService = new CarMediaService(mContext, mUserService, mUserHandleHelper);
        CarLocalServices.addService(CarPowerManagementService.class,
                mMockCarPowerManagementService);
    }

    @After
    public void tearDown() {
        CarLocalServices.removeServiceForTest(CarPowerManagementService.class);
    }

    @Test
    public void testSetMediaSource_ModePlaybackIndependent() {
        mCarMediaService.setIndependentPlaybackConfig(true);
        initMediaService();

        mCarMediaService.setMediaSource(MEDIA_COMPONENT, MEDIA_SOURCE_MODE_PLAYBACK);

        assertThat(mCarMediaService.getMediaSource(MEDIA_SOURCE_MODE_PLAYBACK))
                .isEqualTo(MEDIA_COMPONENT);
        assertThat(mCarMediaService.getMediaSource(MEDIA_SOURCE_MODE_BROWSE))
                .isNotEqualTo(MEDIA_COMPONENT);
    }

    @Test
    public void testSetMediaSource_ModeBrowseIndependent() {
        mCarMediaService.setIndependentPlaybackConfig(true);
        initMediaService();

        mCarMediaService.setMediaSource(MEDIA_COMPONENT, MEDIA_SOURCE_MODE_BROWSE);

        assertThat(mCarMediaService.getMediaSource(MEDIA_SOURCE_MODE_BROWSE))
                .isEqualTo(MEDIA_COMPONENT);
        assertThat(mCarMediaService.getMediaSource(MEDIA_SOURCE_MODE_PLAYBACK))
                .isNotEqualTo(MEDIA_COMPONENT);
    }

    @Test
    public void testSetMediaSource_ModePlaybackAndBrowseIndependent() {
        mCarMediaService.setIndependentPlaybackConfig(true);
        initMediaService();

        mCarMediaService.setMediaSource(MEDIA_COMPONENT, MEDIA_SOURCE_MODE_BROWSE);
        mCarMediaService.setMediaSource(MEDIA_COMPONENT2, MEDIA_SOURCE_MODE_PLAYBACK);

        assertThat(mCarMediaService.getMediaSource(MEDIA_SOURCE_MODE_BROWSE))
                .isEqualTo(MEDIA_COMPONENT);
        assertThat(mCarMediaService.getMediaSource(MEDIA_SOURCE_MODE_PLAYBACK))
                .isEqualTo(MEDIA_COMPONENT2);
    }

    @Test
    public void testSetMediaSource_Dependent() {
        mCarMediaService.setIndependentPlaybackConfig(false);
        initMediaService();

        mCarMediaService.setMediaSource(MEDIA_COMPONENT, MEDIA_SOURCE_MODE_PLAYBACK);

        assertThat(mCarMediaService.getMediaSource(MEDIA_SOURCE_MODE_BROWSE))
                .isEqualTo(MEDIA_COMPONENT);
        assertThat(mCarMediaService.getMediaSource(MEDIA_SOURCE_MODE_PLAYBACK))
                .isEqualTo(MEDIA_COMPONENT);

        mCarMediaService.setMediaSource(MEDIA_COMPONENT2, MEDIA_SOURCE_MODE_BROWSE);

        assertThat(mCarMediaService.getMediaSource(MEDIA_SOURCE_MODE_BROWSE))
                .isEqualTo(MEDIA_COMPONENT2);
        assertThat(mCarMediaService.getMediaSource(MEDIA_SOURCE_MODE_PLAYBACK))
                .isEqualTo(MEDIA_COMPONENT2);
    }

    @Test
    public void testMediaSourceListener_Independent() throws Exception {
        mCarMediaService.setIndependentPlaybackConfig(true);
        initMediaService();
        ICarMediaSourceListener listenerPlayback = mockMediaSourceListener();
        ICarMediaSourceListener listenerBrowse = mockMediaSourceListener();

        mCarMediaService.registerMediaSourceListener(listenerPlayback, MEDIA_SOURCE_MODE_PLAYBACK);
        mCarMediaService.registerMediaSourceListener(listenerBrowse, MEDIA_SOURCE_MODE_BROWSE);
        mCarMediaService.setMediaSource(MEDIA_COMPONENT, MEDIA_SOURCE_MODE_PLAYBACK);

        verify(listenerPlayback).onMediaSourceChanged(MEDIA_COMPONENT);
        verify(listenerBrowse, never()).onMediaSourceChanged(any());
    }

    @Test
    public void testMediaSourceListener_IndependentBrowse() throws Exception {
        mCarMediaService.setIndependentPlaybackConfig(true);
        initMediaService();
        ICarMediaSourceListener listenerPlayback = mockMediaSourceListener();
        ICarMediaSourceListener listenerBrowse = mockMediaSourceListener();

        mCarMediaService.registerMediaSourceListener(listenerPlayback, MEDIA_SOURCE_MODE_PLAYBACK);
        mCarMediaService.registerMediaSourceListener(listenerBrowse, MEDIA_SOURCE_MODE_BROWSE);
        mCarMediaService.setMediaSource(MEDIA_COMPONENT, MEDIA_SOURCE_MODE_BROWSE);

        verify(listenerBrowse).onMediaSourceChanged(MEDIA_COMPONENT);
        verify(listenerPlayback, never()).onMediaSourceChanged(any());
    }

    @Test
    public void testMediaSourceListener_Dependent() throws Exception {
        mCarMediaService.setIndependentPlaybackConfig(false);
        initMediaService();
        ICarMediaSourceListener listenerPlayback = mockMediaSourceListener();
        ICarMediaSourceListener listenerBrowse = mockMediaSourceListener();

        mCarMediaService.registerMediaSourceListener(listenerPlayback, MEDIA_SOURCE_MODE_PLAYBACK);
        mCarMediaService.registerMediaSourceListener(listenerBrowse, MEDIA_SOURCE_MODE_BROWSE);
        mCarMediaService.setMediaSource(MEDIA_COMPONENT, MEDIA_SOURCE_MODE_PLAYBACK);

        verify(listenerPlayback).onMediaSourceChanged(MEDIA_COMPONENT);
        verify(listenerBrowse).onMediaSourceChanged(MEDIA_COMPONENT);

        mCarMediaService.setMediaSource(MEDIA_COMPONENT, MEDIA_SOURCE_MODE_BROWSE);

        verify(listenerPlayback).onMediaSourceChanged(MEDIA_COMPONENT);
        verify(listenerBrowse).onMediaSourceChanged(MEDIA_COMPONENT);
    }

    @Test
    public void testMediaSourceListener_Unregister() throws Exception {
        initMediaService();
        ICarMediaSourceListener listener = mockMediaSourceListener();

        mCarMediaService.registerMediaSourceListener(listener, MEDIA_SOURCE_MODE_PLAYBACK);
        mCarMediaService.unregisterMediaSourceListener(listener, MEDIA_SOURCE_MODE_PLAYBACK);
        mCarMediaService.setMediaSource(MEDIA_COMPONENT, MEDIA_SOURCE_MODE_PLAYBACK);

        verify(listener, never()).onMediaSourceChanged(MEDIA_COMPONENT);
    }

    @Test
    public void testDefaultMediaSource() {
        initMediaService(MEDIA_CLASS);

        assertThat(mCarMediaService.getMediaSource(MEDIA_SOURCE_MODE_PLAYBACK))
                .isEqualTo(MEDIA_COMPONENT);
    }

    @Test
    public void testUnresolvedMediaPackage() {
        initializeMockPackageManager();

        assertThat(mCarMediaService.isMediaService(MEDIA_COMPONENT)).isFalse();
    }

    /**
     * Tests that PlaybackState changing to PlaybackState#isActive
     * will result the media source changing
     */
    @Test
    public void testActiveSessionListener_StateActiveChangesSource() {
        mockPlaybackStateChange(createPlaybackState(PlaybackState.STATE_BUFFERING));

        initMediaService(MEDIA_CLASS, MEDIA_CLASS2);

        assertThat(mCarMediaService.getMediaSource(MEDIA_SOURCE_MODE_PLAYBACK))
                .isEqualTo(MEDIA_COMPONENT2);
        verify(mContext, times(2)).startForegroundService(any());
    }

    // Tests that PlaybackState changing to STATE_PLAYING will result the media source changing
    @Test
    public void testActiveSessionListener_StatePlayingChangesSource() {
        mockPlaybackStateChange(createPlaybackState(PlaybackState.STATE_PLAYING));

        initMediaService(MEDIA_CLASS, MEDIA_CLASS2);

        assertThat(mCarMediaService.getMediaSource(MEDIA_SOURCE_MODE_PLAYBACK))
                .isEqualTo(MEDIA_COMPONENT2);
        verify(mContext, times(2)).startForegroundService(any());
    }

    @Test
    public void testActiveSessionListener_StatePlayingNonMediaAppDoesntChangesSource() {
        mockPlaybackStateChange(createPlaybackState(PlaybackState.STATE_PLAYING));

        // setup media source info only for MEDIA Component
        // second one will stay null
        initMediaService(MEDIA_CLASS);

        // New Media source should be null
        assertThat(mCarMediaService.getMediaSource(MEDIA_SOURCE_MODE_PLAYBACK)).isNull();
        // service start should happen on init but not on media source change
        verify(mContext, times(1)).startForegroundService(any());
    }

    @Test
    public void testActiveSessionListener_IndependentBrowseUnchanged() {
        mCarMediaService.setIndependentPlaybackConfig(true);
        mockPlaybackStateChange(createPlaybackState(PlaybackState.STATE_PLAYING));

        initMediaService(MEDIA_CLASS, MEDIA_CLASS2);

        assertThat(mCarMediaService.getMediaSource(MEDIA_SOURCE_MODE_PLAYBACK))
                .isEqualTo(MEDIA_COMPONENT2);
        assertThat(mCarMediaService.getMediaSource(MEDIA_SOURCE_MODE_BROWSE))
                .isEqualTo(MEDIA_COMPONENT);
        verify(mContext, times(2)).startForegroundService(any());
    }

    @Test
    public void testActiveSessionListener_DependentBrowseChanged() {
        mCarMediaService.setIndependentPlaybackConfig(false);
        mockPlaybackStateChange(createPlaybackState(PlaybackState.STATE_PLAYING));

        initMediaService(MEDIA_CLASS, MEDIA_CLASS2);

        assertThat(mCarMediaService.getMediaSource(MEDIA_SOURCE_MODE_PLAYBACK))
                .isEqualTo(MEDIA_COMPONENT2);
        assertThat(mCarMediaService.getMediaSource(MEDIA_SOURCE_MODE_BROWSE))
                .isEqualTo(MEDIA_COMPONENT2);
        verify(mContext, times(2)).startForegroundService(any());
    }

    @Test
    public void testActiveSessionListener_StatePaused() {
        mockPlaybackStateChange(createPlaybackState(PlaybackState.STATE_PAUSED));

        initMediaService(MEDIA_CLASS, MEDIA_CLASS2);

        assertThat(mCarMediaService.getMediaSource(MEDIA_SOURCE_MODE_PLAYBACK))
                .isEqualTo(MEDIA_COMPONENT);
        verify(mContext, times(1)).startForegroundService(any());
    }

    private void initMediaService(String... classesToResolve) {
        initializeMockPackageManager(classesToResolve);
        mockUserUnlocked(true);

        mCarMediaService.init();
    }

    private void mockUserUnlocked(boolean unlocked) {
        when(mUserManager.isUserUnlocked(any())).thenReturn(unlocked);
    }

    private ICarMediaSourceListener mockMediaSourceListener() {
        ICarMediaSourceListener listener = mock(ICarMediaSourceListener.class);
        when(listener.asBinder()).thenReturn(mock(IBinder.class));
        return listener;
    }

    // This method invokes a playback state changed callback on a mock MediaController
    private void mockPlaybackStateChange(PlaybackState newState) {
        List<MediaController> controllers = new ArrayList<>();
        MediaController mockController = mock(MediaController.class);
        when(mockController.getPackageName()).thenReturn(MEDIA_PACKAGE2);
        when(mockController.getSessionToken()).thenReturn(mock(MediaSession.Token.class));
        Bundle sessionExtras = new Bundle();
        sessionExtras.putString(Car.CAR_EXTRA_BROWSE_SERVICE_FOR_SESSION, MEDIA_CLASS2);
        when(mockController.getExtras()).thenReturn(sessionExtras);

        doAnswer(invocation -> {
            MediaController.Callback callback = invocation.getArgument(0);
            callback.onPlaybackStateChanged(newState);
            return null;
        }).when(mockController).registerCallback(notNull());
        controllers.add(mockController);

        doAnswer(invocation -> {
            MediaSessionManager.OnActiveSessionsChangedListener callback =
                    invocation.getArgument(3);
            callback.onActiveSessionsChanged(controllers);
            return null;
        }).when(mMediaSessionManager).addOnActiveSessionsChangedListener(any(), any(), any(),
                any(MediaSessionManager.OnActiveSessionsChangedListener.class));
    }

    // This method sets up PackageManager queries to return mocked media components if specified
    private void initializeMockPackageManager(String... classesToResolve) {
        when(mContext.getString(R.string.config_defaultMediaSource))
                .thenReturn(MEDIA_COMPONENT.flattenToShortString());
        List<ResolveInfo> packageList = new ArrayList();
        for (String className : classesToResolve) {
            ResolveInfo info = new ResolveInfo();
            ServiceInfo serviceInfo = new ServiceInfo();
            serviceInfo.name = className;
            info.serviceInfo = serviceInfo;
            packageList.add(info);
        }
        when(mPackageManager.queryIntentServicesAsUser(any(), anyInt(), any()))
                .thenReturn(packageList);
    }

    private PlaybackState createPlaybackState(@PlaybackState.State int state) {
        return new PlaybackState.Builder().setState(state, 0, 0).build();
    }
}
