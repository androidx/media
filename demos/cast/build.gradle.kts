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

plugins {
  id("media3.android-application")
  id("com.google.android.gms.strict-version-matcher-plugin")
}

android {
  namespace = "androidx.media3.demo.cast"

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

  // The demo app isn't indexed and doesn't have translations.
  lint.disable += listOf("GoogleAppIndexingWarning", "MissingTranslation")
}

dependencies {
  implementation(project(":lib-exoplayer"))
  implementation(project(":lib-exoplayer-dash"))
  implementation(project(":lib-exoplayer-hls"))
  implementation(project(":lib-exoplayer-rtsp"))
  implementation(project(":lib-exoplayer-smoothstreaming"))
  implementation(project(":lib-ui"))
  implementation(project(":lib-cast"))
  implementation(libs.androidx.appcompat)
  implementation(libs.androidx.recyclerview)
  implementation(libs.material)
}
