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
  namespace = "androidx.media3.exoplayer.dash"

  sourceSets { getByName("test").assets.directories.add("../test_data/src/test/assets/") }
}

dependencies {
  api(project(":lib-exoplayer"))
  compileOnly(libs.errorprone.annotations)
  compileOnly(libs.checkerframework.qual)
  compileOnly(libs.kotlin.annotations.jvm)
  implementation(libs.androidx.annotation)
  testImplementation(project(":lib-inspector"))
  testImplementation(project(":test-utils-robolectric"))
  testImplementation(project(":test-utils"))
  testImplementation(libs.test.parameter.injector)
  testImplementation(libs.robolectric)
}
