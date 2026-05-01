// Copyright 2024 The Android Open Source Project
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

plugins {
  id("media3.android-application")
  alias(libs.plugins.kotlin.compose.compiler)
}

android {
  namespace = "androidx.media3.demo.effect"

  buildTypes {
    getByName("release") {
      isShrinkResources = true
      isMinifyEnabled = true
      signingConfig = signingConfigs.getByName("debug")
    }
  }

  // The demo app isn't indexed, and doesn't have translations.
  lint.disable += listOf("GoogleAppIndexingWarning", "MissingTranslation")

  buildFeatures { compose = true }
}

dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.compose.foundation)
  implementation(libs.androidx.compose.material3)

  implementation(libs.androidx.activity.compose)
  implementation(libs.material)

  implementation(project(":lib-exoplayer"))
  implementation(project(":lib-ui"))
  implementation(project(":lib-effect"))
  implementation(project(":lib-effect-lottie"))

  // For detecting and debugging leaks only. LeakCanary is not needed for demo app to work.
  debugImplementation(libs.leakcanary.android)
}
