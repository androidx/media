// Copyright 2026 The Android Open Source Project
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
}

apply(from = "${gradle.extra["androidxMediaSettingsDir"]}/common_config.gradle")

android {
  namespace = "androidx.media3.datasource.ktor"

  publishing { singleVariant("release") { withSourcesJar() } }
  kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_1_8) } }
}

dependencies {
  api(project(modulePrefix + "lib-common"))
  api(project(modulePrefix + "lib-datasource"))
  implementation(libs.androidx.annotation)
  compileOnly(libs.errorprone.annotations)
  compileOnly(libs.checkerframework.qual)
  compileOnly(libs.kotlin.annotations.jvm)
  implementation(libs.kotlinx.coroutines.core)
  androidTestImplementation(project(modulePrefix + "test-utils"))
  androidTestImplementation(libs.okhttp.mockwebserver)
  androidTestImplementation(libs.ktor.client.okhttp)
  api(libs.ktor.client.core)
}

extra["releaseArtifactId"] = "media3-datasource-ktor"
extra["releaseName"] = "Media3 Ktor DataSource module"

apply(from = "../../publish.gradle")
