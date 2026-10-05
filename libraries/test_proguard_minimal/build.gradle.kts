// Copyright 2026 The Android Open Source Project
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
  namespace = "androidx.media3.test.proguard.minimal"

  compileOptions.isCoreLibraryDesugaringEnabled = true

  buildTypes {
    // Run R8 for all build types to discover potential proguard problems.
    configureEach {
      isDebuggable = false
      isShrinkResources = true
      isMinifyEnabled = true
      proguardFiles(
        "src/androidTest/proguard-rules.pgcfg",
        "src/androidTest/proguard-checkdiscard-rules.pgcfg",
        getDefaultProguardFile("proguard-android-optimize.txt"),
      )
      testProguardFile("src/androidTest/proguard-test-rules.pgcfg")
    }
  }
}

dependencies {
  coreLibraryDesugaring(libs.desugar.jdk.libs)
  compileOnly(libs.androidx.annotation)
  implementation(project(":lib-exoplayer"))
  // Explicit dependency on lib-effect so R8 verifies that unused effect classes
  // are discarded at build time.
  implementation(project(":lib-effect"))
  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.runner)
}
