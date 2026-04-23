/*
 * Copyright 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  id("media3.android-application")
  alias(libs.plugins.kotlin.android)
  alias(libs.plugins.kotlin.compose.compiler)
}

android {
  namespace = "androidx.media3.demo.composition"

  kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_1_8) } }

  buildTypes {
    getByName("release") {
      isShrinkResources = true
      isMinifyEnabled = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.txt")
      signingConfig = signingConfigs.getByName("debug")
    }
  }

  // The demo app isn't indexed, and doesn't have translations.
  lint.disable += listOf("GoogleAppIndexingWarning", "MissingTranslation")

  buildFeatures { compose = true }
}

dependencies {
  implementation(libs.androidx.appcompat)
  implementation(libs.material)
  implementation(project(":lib-effect"))
  implementation(project(":lib-effect-ndk"))
  implementation(project(":lib-exoplayer"))
  implementation(project(":lib-exoplayer-dash"))
  implementation(project(":lib-muxer"))
  implementation(project(":lib-transformer"))
  implementation(project(":lib-inspector"))
  implementation(project(":lib-ui"))
  implementation(project(":lib-ui-compose"))
  implementation(libs.androidx.annotation)
  implementation(libs.androidx.lifecycle.viewmodel)
  implementation(libs.androidx.activity.compose)
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.material3.adaptive)
  implementation(libs.androidx.compose.material3.adaptive.layout)
  implementation(libs.androidx.compose.material3.adaptive.navigation)
  implementation(libs.kotlinx.coroutines.guava)
  compileOnly(libs.checkerframework.qual)
  debugImplementation(libs.androidx.compose.ui.tooling)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
}
