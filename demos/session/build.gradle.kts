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

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  id("media3.android-application")
  alias(libs.plugins.kotlin.android)
}

android {
  namespace = "androidx.media3.demo.session"

  kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_1_8) } }

  defaultConfig { targetSdk = libs.versions.appTargetSdkVersion.get().toInt() }

  buildTypes {
    getByName("release") {
      isShrinkResources = true
      isMinifyEnabled = true
      proguardFiles("proguard-rules.txt", getDefaultProguardFile("proguard-android-optimize.txt"))
      signingConfig = signingConfigs.getByName("debug")
    }
    getByName("debug") { isJniDebuggable = true }
  }

  // The demo app isn't indexed, and doesn't have translations.
  lint.disable += listOf("GoogleAppIndexingWarning", "MissingTranslation")
}

dependencies {
  // For detecting and debugging leaks only. LeakCanary is not needed for demo app to work.
  debugImplementation(libs.leakcanary.android)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.common)
  implementation(libs.androidx.lifecycle.runtime)
  implementation(libs.androidx.mediarouter)
  implementation(libs.androidx.appcompat)
  implementation(libs.material)
  implementation(libs.kotlinx.coroutines.guava)
  implementation(project(":lib-cast"))
  implementation(project(":lib-ui"))
  implementation(project(":lib-session"))
  implementation(project(":demo-session-service"))
}
