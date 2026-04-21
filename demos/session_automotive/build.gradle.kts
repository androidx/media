// Copyright 2023 The Android Open Source Project
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

apply(from = "../../constants.gradle")

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.android)
}

android {
  namespace = "androidx.media3.demo.session.automotive"

  compileSdk = libs.versions.compileSdkVersion.get().toInt()

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
  }

  kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_1_8) } }

  defaultConfig {
    versionName = releaseVersion
    versionCode = releaseVersionCode
    minSdk = libs.versions.automotiveMinSdkVersion.get().toInt()
    targetSdk = libs.versions.appTargetSdkVersion.get().toInt()
  }

  buildTypes {
    getByName("release") {
      isShrinkResources = true
      isMinifyEnabled = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
      signingConfig = signingConfigs.getByName("debug")
    }
    getByName("debug") { isJniDebuggable = true }
  }

  // The demo app isn't indexed, and doesn't have translations.
  lint.disable += listOf("GoogleAppIndexingWarning", "MissingTranslation")
}

dependencies {
  implementation(libs.androidx.annotation.experimental)
  implementation(project(":lib-session"))
  implementation(project(":demo-session-service"))
  implementation(project(":lib-exoplayer"))
}
