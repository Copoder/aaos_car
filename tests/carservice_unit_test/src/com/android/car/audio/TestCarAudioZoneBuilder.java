/*
 * Copyright (C) 2021 The Android Open Source Project
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

import java.util.ArrayList;
import java.util.List;

public final class TestCarAudioZoneBuilder {

    private final int mAudioZoneId;
    private final List<CarVolumeGroup> mCarVolumeGroups = new ArrayList<>();
    private final String mAudioZoneName;
    private CarAudioContext mCarAudioContext =
            new CarAudioContext(CarAudioContext.getAllContextsInfo());

    public TestCarAudioZoneBuilder(String audioZoneName, int audioZoneId) {
        mAudioZoneId = audioZoneId;
        mAudioZoneName = audioZoneName;
    }

    TestCarAudioZoneBuilder addVolumeGroup(CarVolumeGroup group) {
        mCarVolumeGroups.add(group);
        return this;
    }

    TestCarAudioZoneBuilder setCarAudioContexts(CarAudioContext carAudioContext) {
        mCarAudioContext = carAudioContext;
        return this;
    }

    CarAudioZone build() {
        return mCarVolumeGroups.stream().collect(
                ()->new CarAudioZone(mCarAudioContext, mAudioZoneName, mAudioZoneId),
                (x, y) -> x.addVolumeGroup(y), (a, b) -> {
                    for (CarVolumeGroup group: b.getVolumeGroups()) {
                    a.addVolumeGroup(group);
                }});
    }
}
