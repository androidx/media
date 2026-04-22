// Copyright 2022 The Android Open Source Project
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

plugins { id("media3.android-library") }

android {
  namespace = "androidx.media3.muxer"

  buildTypes {
    getByName("debug") {
      enableUnitTestCoverage = true
      enableAndroidTestCoverage = true
    }
  }

  sourceSets {
    getByName("androidTest").assets.srcDir("../test_data/src/test/assets/")
    getByName("test").assets.srcDir("../test_data/src/test/assets/")
  }

  publishing { singleVariant("release") { withSourcesJar() } }
}

dependencies {
  api(project(":lib-common"))
  implementation(project(":lib-container"))
  implementation(libs.androidx.annotation)
  compileOnly(libs.errorprone.annotations)
  compileOnly(libs.checkerframework.qual)
  testImplementation(project(":lib-extractor"))
  testImplementation(project(":lib-inspector"))
  testImplementation(libs.test.parameter.injector)
  testImplementation(project(":test-utils-robolectric"))
  testImplementation(project(":test-utils"))
  testImplementation(project(":test-data"))
  testImplementation(libs.robolectric)
  testImplementation(libs.truth)
  testImplementation(project(":lib-exoplayer"))

  androidTestImplementation(libs.junit)
  androidTestImplementation(libs.test.parameter.injector)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.truth)
  androidTestImplementation(project(":test-utils"))
  androidTestImplementation(project(":lib-extractor"))
  androidTestImplementation(project(":lib-inspector"))
}

extra["releaseName"] = "Media3 Muxer module"

apply(from = "../../publish.gradle")
