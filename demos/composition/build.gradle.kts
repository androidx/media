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
apply(from = "../../constants.gradle")

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.android)
  alias(libs.plugins.kotlin.compose.compiler)
}

android {
  namespace = "androidx.media3.demo.composition"

  compileSdk = libs.versions.compileSdkVersion.get().toInt()

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
  }

  kotlinOptions { jvmTarget = "1.8" }

  defaultConfig {
    versionName = releaseVersion
    versionCode = releaseVersionCode
    minSdk = libs.versions.minSdkVersion.get().toInt()
    targetSdk = libs.versions.appTargetSdkVersion.get().toInt()
  }

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
  implementation(project(modulePrefix + "lib-effect"))
  implementation(project(modulePrefix + "lib-effect-ndk"))
  implementation(project(modulePrefix + "lib-exoplayer"))
  implementation(project(modulePrefix + "lib-exoplayer-dash"))
  implementation(project(modulePrefix + "lib-muxer"))
  implementation(project(modulePrefix + "lib-transformer"))
  implementation(project(modulePrefix + "lib-inspector"))
  implementation(project(modulePrefix + "lib-ui"))
  implementation(project(modulePrefix + "lib-ui-compose"))
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
