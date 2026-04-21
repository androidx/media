// Copyright (C) 2024 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      https://www.apache.org/licenses/LICENSE-2.0
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
  alias(libs.plugins.kotlin.compose.compiler)
}

apply(from = "${gradle.extra["androidxMediaSettingsDir"]}/common_config.gradle")

android {
  namespace = "androidx.media3.ui.compose"

  buildTypes { getByName("debug") { enableUnitTestCoverage = true } }
  buildFeatures { compose = true }
  publishing { singleVariant("release") { withSourcesJar() } }
  testOptions { unitTests { isIncludeAndroidResources = true } }
  kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_1_8) } }
}

dependencies {
  api(project(":lib-common"))
  api(project(":lib-common-ktx"))

  api(platform(libs.androidx.compose.bom))
  // Remove the version number once b/385138624 is fixed, GMaven doesn't resolve the BOM above
  api(libs.androidx.compose.foundation)

  testImplementation(libs.androidx.compose.ui.test)
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(project(":test-utils"))
  testImplementation(project(":test-utils-robolectric"))
  testImplementation(libs.robolectric)
}

extra["releaseName"] = "Media3 UI Compose module"

apply(from = "../../publish.gradle")
