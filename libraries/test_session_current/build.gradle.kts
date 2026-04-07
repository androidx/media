// Copyright 2021 The Android Open Source Project
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
plugins { alias(libs.plugins.android.application) }

apply(from = "${gradle.extra["androidxMediaSettingsDir"]}/common_config.gradle")

android {
  namespace = "androidx.media3.test.session"

  defaultConfig {
    versionName = releaseVersion
    versionCode = releaseVersionCode
    targetSdk = libs.versions.appTargetSdkVersion.get().toInt()
  }

  lint {
    // TODO: b/353490583 - Disable this once the violations are fixed.
    ignoreTestSources = true
  }

  sourceSets {
    getByName("androidTest").assets.srcDir("../test_data/src/test/assets")
    getByName("main").assets.srcDir("../test_data/src/test/assets")
  }
}

dependencies {
  implementation(project(modulePrefix + "lib-session"))
  implementation(project(modulePrefix + "test-session-common"))
  implementation(project(modulePrefix + "test-data"))
  implementation(libs.androidx.media)
  implementation(libs.androidx.test.core)
  implementation(project(modulePrefix + "test-data"))
  androidTestImplementation(project(modulePrefix + "lib-exoplayer"))
  androidTestImplementation(project(modulePrefix + "test-utils"))
  androidTestImplementation(libs.test.parameter.injector)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.truth)
  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.rules)
  androidTestImplementation(libs.androidx.test.runner)
}
