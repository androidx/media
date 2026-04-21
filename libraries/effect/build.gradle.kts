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

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.android.library)
  alias(libs.plugins.kotlin.android)
}

apply(from = "${gradle.extra["androidxMediaSettingsDir"]}/common_config.gradle")

android {
  namespace = "androidx.media3.effect"

  buildTypes { getByName("debug") { enableUnitTestCoverage = true } }

  kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_1_8) } }

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
  api(project(":lib-common"))
  implementation(project(":lib-datasource"))
  implementation(libs.kotlinx.coroutines.guava)
  compileOnly(libs.errorprone.annotations)
  compileOnly(libs.checkerframework.qual)
  compileOnly(libs.kotlin.annotations.jvm)
  testImplementation(project(":test-utils-robolectric"))
  testImplementation(project(":test-utils"))
  testImplementation(project(":test-data"))
  testImplementation(libs.robolectric)
  testImplementation(libs.truth)
  testImplementation(libs.kotlinx.coroutines.test)
  androidTestImplementation(project(":lib-exoplayer"))
  androidTestImplementation(project(":lib-effect-ndk"))
  androidTestImplementation(libs.kotlinx.coroutines.android)
  androidTestImplementation(libs.test.parameter.injector)
  androidTestImplementation(libs.junit)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.truth)
  androidTestImplementation(project(":test-utils"))
}

extra["releaseArtifactId"] = "media3-effect"

extra["releaseName"] = "Media3 Effect module"

apply(from = "../../publish.gradle")
