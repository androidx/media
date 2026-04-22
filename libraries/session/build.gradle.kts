// Copyright 2019 The Android Open Source Project
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
  namespace = "androidx.media3.session"

  buildTypes {
    getByName("debug") {
      enableUnitTestCoverage = true
      enableAndroidTestCoverage = true
    }
  }
  sourceSets { getByName("test").assets.srcDir("../test_data/src/test/assets/") }
  publishing { singleVariant("release") { withSourcesJar() } }
  buildFeatures { aidl = true }
}

dependencies {
  api(project(":lib-common"))
  api(libs.androidx.lifecycle.service)
  compileOnly(libs.errorprone.annotations)
  compileOnly(libs.checkerframework.qual)
  implementation(project(":lib-datasource"))
  implementation(libs.androidx.collection)
  implementation(libs.androidx.concurrent.futures)
  implementation(libs.androidx.media)
  implementation(libs.androidx.core)
  testImplementation(libs.okhttp.mockwebserver)
  testImplementation(project(":test-data"))
  testImplementation(project(":test-utils"))
  testImplementation(project(":test-utils-robolectric"))
  testImplementation(project(":lib-exoplayer"))
  testImplementation(libs.robolectric)
  testImplementation(libs.test.parameter.injector)
}

extra["releaseName"] = "Media3 Session module"

apply(from = "../../publish.gradle")
