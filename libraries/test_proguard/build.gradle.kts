// Copyright 2025 The Android Open Source Project
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
  namespace = "androidx.media3.test.proguard"

  sourceSets {
    getByName("androidTest").assets.srcDir("../test_data/src/test/assets/")
    getByName("test").assets.srcDir("../test_data/src/test/assets/")
  }

  compileOptions.isCoreLibraryDesugaringEnabled = true

  defaultConfig { targetSdk = libs.versions.appTargetSdkVersion.get().toInt() }

  buildTypes {
    // Run R8 for all build types to discover potential proguard problems.
    configureEach {
      isShrinkResources = true
      isMinifyEnabled = true
      proguardFiles("proguard-rules.txt", getDefaultProguardFile("proguard-android-optimize.txt"))
      testProguardFile("proguard-test-rules.txt")
    }
  }
}

dependencies {
  coreLibraryDesugaring(libs.desugar.jdk.libs)
  implementation(project(":lib-exoplayer"))
  implementation(project(":lib-exoplayer-dash"))
  implementation(project(":lib-exoplayer-hls"))
  implementation(project(":lib-exoplayer-rtsp"))
  implementation(project(":lib-exoplayer-smoothstreaming"))
  implementation(project(":lib-transformer"))
  implementation(project(":lib-ui"))
  implementation(project(":lib-datasource-rtmp"))
  implementation(project(":lib-decoder-vp9"))
  implementation(project(":lib-decoder-opus"))
  implementation(project(":lib-decoder-flac"))
  implementation(project(":lib-decoder-ffmpeg"))
  implementation(project(":lib-decoder-midi"))
  implementation(project(":lib-decoder-av1"))
  implementation(project(":lib-decoder-iamf"))
  implementation(libs.androidx.appcompat)
  implementation(libs.guava)
  compileOnly(libs.kotlin.annotations.jvm)
  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.runner)
}
