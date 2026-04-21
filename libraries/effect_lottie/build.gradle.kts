// Copyright 2026 The Android Open Source Project
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
  namespace = "androidx.media3.effect.lottie"

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
  implementation(libs.androidx.annotation)
  api(project(":lib-common"))
  api(project(":lib-effect"))
  api(libs.lottie)
  compileOnly(libs.errorprone.annotations)
  androidTestImplementation(libs.junit)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.truth)
  androidTestImplementation(project(":test-utils"))
}

extra["releaseName"] = "Media3 Effect Lottie module"

apply(from = "../../publish.gradle")
