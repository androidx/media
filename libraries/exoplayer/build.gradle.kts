// Copyright (C) 2016 The Android Open Source Project
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
  namespace = "androidx.media3.exoplayer"

  sourceSets {
    getByName("androidTest").assets.srcDir("../test_data/src/test/assets")
    getByName("test").assets.srcDir("../test_data/src/test/assets/")
  }
}

dependencies {
  api(project(":lib-common"))
  api(project(":lib-container"))
  // TODO(b/203754886): Revisit which modules are exported as API dependencies.
  api(project(":lib-datasource"))
  api(project(":lib-decoder"))
  api(project(":lib-extractor"))
  api(project(":lib-database"))
  implementation(libs.androidx.annotation)
  implementation(libs.androidx.exifinterface)
  compileOnly(libs.jsr305)
  compileOnly(libs.checkerframework.qual)
  compileOnly(libs.kotlin.annotations.jvm)
  compileOnly(libs.errorprone.annotations)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.dexmaker)
  androidTestImplementation(libs.dexmaker.mockito)
  androidTestImplementation(project(":test-utils"))
  androidTestImplementation(libs.okhttp.mockwebserver)
  testImplementation(libs.test.parameter.injector)
  testImplementation(libs.okhttp.mockwebserver)
  testImplementation(libs.robolectric)
  testImplementation(project(":test-utils"))
  testImplementation(project(":test-utils-robolectric"))
}
