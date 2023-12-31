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
package com.android.car;

import static com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport.DUMP_INFO;

import android.car.Car;
import android.car.builtin.util.Slogf;
import android.car.test.ICarTest;
import android.content.Context;
import android.os.IBinder;
import android.os.RemoteException;

import com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport;
import com.android.car.internal.util.IndentingPrintWriter;
import com.android.internal.annotations.GuardedBy;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Service to allow testing / mocking vehicle HAL.
 * This service uses Vehicle HAL APIs directly (one exception) as vehicle HAL mocking anyway
 * requires accessing that level directly.
 */
class CarTestService extends ICarTest.Stub implements CarServiceBase {

    private static final String TAG = CarLog.tagFor(CarTestService.class);

    private final Context mContext;
    private final ICarImpl mICarImpl;

    private final Object mLock = new Object();

    @GuardedBy("mLock")
    private final Map<IBinder, TokenDeathRecipient> mTokens = new HashMap<>();

    CarTestService(Context context, ICarImpl carImpl) {
        mContext = context;
        mICarImpl = carImpl;
    }

    @Override
    public void init() {
        // nothing to do.
        // This service should not reset anything for init / release to maintain mocking.
    }

    @Override
    public void release() {
        // nothing to do
        // This service should not reset anything for init / release to maintain mocking.
    }

    @Override
    @ExcludeFromCodeCoverageGeneratedReport(reason = DUMP_INFO)
    public void dump(IndentingPrintWriter writer) {
        writer.println("*CarTestService*");
        synchronized (mLock) {
            writer.println(" mTokens:" + Arrays.toString(mTokens.entrySet().toArray()));
        }
    }

    @Override
    public void stopCarService(IBinder token) throws RemoteException {
        Slogf.d(TAG, "stopCarService, token: " + token);
        CarServiceUtils.assertPermission(mContext, Car.PERMISSION_CAR_TEST_SERVICE);

        synchronized (mLock) {
            if (mTokens.containsKey(token)) {
                Slogf.w(TAG, "Calling stopCarService twice with the same token.");
                return;
            }

            TokenDeathRecipient deathRecipient = new TokenDeathRecipient(token);
            mTokens.put(token, deathRecipient);
            token.linkToDeath(deathRecipient, 0);

            if (mTokens.size() == 1) {
                CarServiceUtils.runOnMainSync(mICarImpl::release);
            }
        }
    }

    @Override
    public void startCarService(IBinder token) throws RemoteException {
        Slogf.d(TAG, "startCarService, token: " + token);
        CarServiceUtils.assertPermission(mContext, Car.PERMISSION_CAR_TEST_SERVICE);
        releaseToken(token);
    }

    @Override
    public String getOemServiceName() {
        return mICarImpl.getOemServiceName();
    }

    private void releaseToken(IBinder token) {
        Slogf.d(TAG, "releaseToken, token: " + token);
        synchronized (mLock) {
            DeathRecipient deathRecipient = mTokens.remove(token);
            if (deathRecipient != null) {
                token.unlinkToDeath(deathRecipient, 0);
            }

            if (mTokens.size() == 0) {
                CarServiceUtils.runOnMainSync(mICarImpl::init);
            }
        }
    }

    private class TokenDeathRecipient implements DeathRecipient {
        private final IBinder mToken;

        TokenDeathRecipient(IBinder token) throws RemoteException {
            mToken = token;
        }

        @Override
        public void binderDied() {
            releaseToken(mToken);
        }
    }
}
