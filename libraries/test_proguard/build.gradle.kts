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
plugins { alias(libs.plugins.android.application) }

apply(from = "${gradle.extra["androidxMediaSettingsDir"]}/common_config.gradle")

android {
  namespace = "androidx.media3.test.proguard"

  sourceSets {
    getByName("androidTest").assets.srcDir("../test_data/src/test/assets/")
    getByName("test").assets.srcDir("../test_data/src/test/assets/")
  }

  compileOptions.isCoreLibraryDesugaringEnabled = true

  defaultConfig {
    versionName = releaseVersion
    versionCode = releaseVersionCode
    targetSdk = libs.versions.appTargetSdkVersion.get().toInt()
  }

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
  implementation(project(modulePrefix + "lib-exoplayer"))
  implementation(project(modulePrefix + "lib-exoplayer-dash"))
  implementation(project(modulePrefix + "lib-exoplayer-hls"))
  implementation(project(modulePrefix + "lib-exoplayer-rtsp"))
  implementation(project(modulePrefix + "lib-exoplayer-smoothstreaming"))
  implementation(project(modulePrefix + "lib-transformer"))
  implementation(project(modulePrefix + "lib-ui"))
  implementation(project(modulePrefix + "lib-datasource-rtmp"))
  implementation(project(modulePrefix + "lib-decoder-vp9"))
  implementation(project(modulePrefix + "lib-decoder-opus"))
  implementation(project(modulePrefix + "lib-decoder-flac"))
  implementation(project(modulePrefix + "lib-decoder-ffmpeg"))
  implementation(project(modulePrefix + "lib-decoder-midi"))
  implementation(project(modulePrefix + "lib-decoder-av1"))
  implementation(project(modulePrefix + "lib-decoder-iamf"))
  implementation(libs.androidx.appcompat)
  implementation(libs.guava)
  compileOnly(libs.kotlin.annotations.jvm)
  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.runner)
}
