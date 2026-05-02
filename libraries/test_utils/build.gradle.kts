// Copyright (C) 2017 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

plugins {
  id("media3.android-library")
  id("media3.publish")
}

android {
  namespace = "androidx.media3.test.utils"

  sourceSets { getByName("test").assets.directories.add("../test_data/src/test/assets/") }
}

dependencies {
  api(libs.mockito.core)
  api(libs.androidx.test.core)
  api(libs.androidx.test.ext.junit)
  api(libs.androidx.test.truth)
  api(libs.junit)
  api(libs.truth)
  api(libs.okhttp.mockwebserver)
  implementation(libs.androidx.annotation)
  implementation(libs.test.parameter.injector)
  implementation(libs.kotlinx.coroutines.test)
  implementation(project(":lib-inspector"))
  api(project(":lib-exoplayer"))
  api(project(":lib-effect"))
  api(project(":lib-transformer"))
  testImplementation(project(":test-utils-robolectric"))
  testImplementation(libs.androidx.test.espresso.core)
  testImplementation(libs.robolectric)
  testImplementation(libs.guava.testlib)
}
