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
plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.android)
}


apply(from = "../../constants.gradle")

android {
  namespace = "androidx.media3.testapp.controller"

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
    vectorDrawables.useSupportLibrary = true
  }

  buildTypes {
    getByName("release") {
      isShrinkResources = true
      isMinifyEnabled = true
      proguardFiles("proguard-rules.txt", getDefaultProguardFile("proguard-android-optimize.txt"))
      signingConfig = signingConfigs.getByName("debug")
    }
    getByName("debug") { isJniDebuggable = true }
  }

  lint {
    // The test app isn't indexed, and doesn't have translations.
    disable.add("GoogleAppIndexingWarning")
    disable.add("MissingTranslation")
  }
}

dependencies {
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.appcompat)
  implementation(libs.material)
  implementation(libs.androidx.media)
  implementation(project(modulePrefix + "lib-session"))
  implementation(project(modulePrefix + "lib-datasource"))

  testImplementation(project(modulePrefix + "test-utils"))
  testImplementation(libs.robolectric)
}
