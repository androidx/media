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
  namespace = "androidx.media3.exoplayer.ima"

  compileOptions.isCoreLibraryDesugaringEnabled = true

  sourceSets { getByName("androidTest").assets.directories.add("../test_data/src/test/assets") }
}

dependencies {
  coreLibraryDesugaring(libs.desugar.jdk.libs)
  api("com.google.ads.interactivemedia.v3:interactivemedia:3.39.0") {
    exclude(group = "androidx.media3", module = "media3-common")
  }
  api(project(":lib-exoplayer"))
  implementation(libs.androidx.annotation)
  androidTestImplementation(project(":test-utils"))
  androidTestImplementation(libs.androidx.test.rules)
  androidTestImplementation(libs.androidx.test.runner)
  testImplementation(project(":test-utils"))
  testImplementation(project(":test-utils-robolectric"))
  testImplementation(libs.robolectric)
}
