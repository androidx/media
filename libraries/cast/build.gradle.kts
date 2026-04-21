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
  alias(libs.plugins.kotlin.compose.compiler)
  alias(libs.plugins.kotlin.android)
}

apply(from = "${gradle.extra["androidxMediaSettingsDir"]}/common_config.gradle")

android {
  namespace = "androidx.media3.cast"

  publishing { singleVariant("release") { withSourcesJar() } }
  kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_1_8) } }
}

dependencies {
  implementation(libs.androidx.annotation)
  implementation(libs.androidx.appcompat)
  implementation(libs.androidx.mediarouter)
  api(libs.gms.play.services.cast.framework)
  api(project(":lib-common"))
  api(project(":lib-exoplayer"))
  implementation(platform(libs.androidx.compose.bom))
  // Remove the version number once b/385138624 is fixed, GMaven doesn't resolve the BOM above
  implementation(libs.androidx.compose.material3)

  compileOnly(libs.errorprone.annotations)
  compileOnly(libs.checkerframework.qual)
  compileOnly(libs.kotlin.annotations.jvm)
  testImplementation(libs.androidx.compose.ui.test)
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(project(":test-utils"))
  testImplementation(libs.robolectric)
}

extra["releaseArtifactId"] = "media3-cast"

extra["releaseName"] = "Media3 Cast module"

apply(from = "../../publish.gradle")
