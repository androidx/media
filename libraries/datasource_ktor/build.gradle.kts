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
  id("media3.android-library")
  id("media3.publish")
  alias(libs.plugins.kotlin.android)
}

android {
  namespace = "androidx.media3.datasource.ktor"

  kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_1_8) } }
}

dependencies {
  api(libs.ktor.client.android)
  api(libs.ktor.client.core)
  api(project(":lib-common"))
  api(project(":lib-datasource"))
  implementation(libs.androidx.annotation)
  compileOnly(libs.errorprone.annotations)
  compileOnly(libs.checkerframework.qual)
  compileOnly(libs.kotlin.annotations.jvm)
  implementation(libs.kotlinx.coroutines.core)
  androidTestImplementation(project(":test-utils"))
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.dexmaker.mockito)
  androidTestImplementation(libs.ktor.client.okhttp)
  androidTestImplementation(libs.okhttp.mockwebserver)
  androidTestImplementation(libs.test.parameter.injector)
}
