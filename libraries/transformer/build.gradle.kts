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
  implementation(project(":lib-datasource"))
  implementation(project(":lib-container"))
  api(project(":lib-exoplayer"))
  api(project(":lib-effect"))
  api(project(":lib-muxer"))
  compileOnly(libs.errorprone.annotations)
  compileOnly(libs.checkerframework.qual)
  compileOnly(libs.kotlin.annotations.jvm)
  testImplementation(project(":lib-exoplayer-dash"))
  testImplementation(project(":test-utils-robolectric"))
  testImplementation(project(":test-utils"))
  testImplementation(project(":test-data"))
  testImplementation(libs.robolectric)
  testImplementation(libs.test.parameter.injector)
  testImplementation(libs.truth)
  androidTestImplementation(libs.junit)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.uiautomator)
  androidTestImplementation(libs.androidx.window)
  androidTestImplementation(libs.truth)
  androidTestImplementation(libs.test.parameter.injector)
  androidTestImplementation(project(":lib-inspector"))
  androidTestImplementation(project(":lib-inspector-frame"))
  androidTestImplementation(project(":lib-effect-ndk"))
  androidTestImplementation(project(":test-utils"))
}

extra["releaseArtifactId"] = "media3-transformer"

extra["releaseName"] = "Media3 Transformer module"

apply(from = "../../publish.gradle")
