// Copyright 2020 The Android Open Source Project
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
  namespace = "androidx.media3.transformer"

  buildTypes { getByName("debug") { enableUnitTestCoverage = true } }

  sourceSets {
    getByName("androidTest").assets.srcDir("../test_data/src/test/assets/")
    getByName("test").assets.srcDir("../test_data/src/test/assets/")
  }

  lint {
    // TODO: b/353490583 - Disable this once the violations are fixed.
    ignoreTestSources = true
  }

  publishing { singleVariant("release") { withSourcesJar() } }
}

dependencies {
  implementation(libs.androidx.annotation)
  implementation(libs.androidx.concurrent.futures)
  implementation(project(modulePrefix + "lib-datasource"))
  implementation(project(modulePrefix + "lib-container"))
  api(project(modulePrefix + "lib-exoplayer"))
  api(project(modulePrefix + "lib-effect"))
  api(project(modulePrefix + "lib-muxer"))
  compileOnly(libs.errorprone.annotations)
  compileOnly(libs.checkerframework.qual)
  compileOnly(libs.kotlin.annotations.jvm)
  testImplementation(project(modulePrefix + "lib-exoplayer-dash"))
  testImplementation(project(modulePrefix + "test-utils-robolectric"))
  testImplementation(project(modulePrefix + "test-utils"))
  testImplementation(project(modulePrefix + "test-data"))
  testImplementation(libs.robolectric)
  testImplementation(libs.test.parameter.injector)
  testImplementation(libs.truth)
  androidTestImplementation(libs.junit)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.uiautomator)
  androidTestImplementation(libs.androidx.window)
  androidTestImplementation(libs.truth)
  androidTestImplementation(libs.test.parameter.injector)
  androidTestImplementation(project(modulePrefix + "lib-inspector"))
  androidTestImplementation(project(modulePrefix + "lib-inspector-frame"))
  androidTestImplementation(project(modulePrefix + "lib-effect-ndk"))
  androidTestImplementation(project(modulePrefix + "test-utils"))
}

extra["releaseArtifactId"] = "media3-transformer"

extra["releaseName"] = "Media3 Transformer module"

apply(from = "../../publish.gradle")
