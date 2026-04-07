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

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.android.library)
  alias(libs.plugins.kotlin.android)
}

apply(from = "${gradle.extra["androidxMediaSettingsDir"]}/common_config.gradle")

android {
  namespace = "androidx.media3.test.utils"

  sourceSets { getByName("test").assets.srcDir("../test_data/src/test/assets/") }

  publishing { singleVariant("release") { withSourcesJar() } }
  kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_1_8) } }
}

dependencies {
  api(libs.mockito.core)
  api(libs.androidx.test.core)
  api(libs.androidx.test.ext.junit)
  api(libs.androidx.test.truth)
  api(libs.junit)
  api(libs.truth)
  api(libs.okhttp.mockwebserver)
  compileOnly(libs.checkerframework.qual)
  compileOnly(libs.kotlin.annotations.jvm)
  implementation(libs.androidx.annotation)
  implementation(libs.test.parameter.injector)
  implementation(libs.kotlinx.coroutines.test)
  implementation(project(modulePrefix + "lib-inspector"))
  api(project(modulePrefix + "lib-exoplayer"))
  api(project(modulePrefix + "lib-effect"))
  api(project(modulePrefix + "lib-transformer"))
  testImplementation(project(modulePrefix + "test-utils-robolectric"))
  testImplementation(libs.androidx.test.espresso.core)
  testImplementation(libs.robolectric)
  testImplementation(libs.guava.testlib)
}

extra["releaseArtifactId"] = "media3-test-utils"
extra["releaseName"] = "Media3 test utils module"

apply(from = "../../publish.gradle")
