// Copyright 2023 The Android Open Source Project
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

plugins { alias(libs.plugins.android.library) }

apply(from = "${gradle.extra["androidxMediaSettingsDir"]}/common_config.gradle")

android {
  namespace = "androidx.media3.container"

  buildTypes { getByName("debug") { enableUnitTestCoverage = true } }

  sourceSets {
    getByName("androidTest").assets.srcDir("../test_data/src/test/assets/")
    getByName("test").assets.srcDir("../test_data/src/test/assets/")
  }

  publishing { singleVariant("release") { withSourcesJar() } }
}

dependencies {
  api(project(":lib-common"))
  implementation(libs.androidx.annotation)
  compileOnly(libs.errorprone.annotations)
  testImplementation(libs.androidx.test.ext.junit)
  testImplementation(libs.truth)
  testImplementation(libs.junit)
  testImplementation(libs.robolectric)
  testImplementation(project(":test-utils"))
}

extra["releaseName"] = "Media3 Container module"

apply(from = "../../publish.gradle")
