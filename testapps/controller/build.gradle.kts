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

plugins { id("media3.android-application") }

android {
  namespace = "androidx.media3.testapp.controller"

  defaultConfig { vectorDrawables.useSupportLibrary = true }

  buildTypes {
    getByName("release") {
      isShrinkResources = true
      isMinifyEnabled = true
      proguardFiles("proguard-rules.txt", getDefaultProguardFile("proguard-android-optimize.txt"))
      signingConfig = signingConfigs.getByName("debug")
    }
  }

  lint {
    // The test app isn't indexed, and doesn't have translations.
    disable.add("GoogleAppIndexingWarning")
    disable.add("MissingTranslation")
    // TODO: b/507008072 - Disable this once the violations (NewApi lint wrong location) are fixed.
    ignoreTestSources = true
  }
}

dependencies {
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.appcompat)
  implementation(libs.material)
  implementation(libs.androidx.media)
  implementation(project(":lib-session"))
  implementation(project(":lib-datasource"))

  testImplementation(project(":test-utils"))
  testImplementation(libs.robolectric)
}
