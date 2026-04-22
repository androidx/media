// Copyright (C) 2016 The Android Open Source Project
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

// LINT.IfChange

plugins {
  id("media3.android-application")
  id("com.google.android.gms.strict-version-matcher-plugin")
}

android {
  namespace = "androidx.media3.demo.main"

  compileOptions { isCoreLibraryDesugaringEnabled = true }

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

  // The demo app isn't indexed, doesn't have translations, and has a
  // banner for AndroidTV that's only in xhdpi density.
  lint.disable +=
    listOf(
      "GoogleAppIndexingWarning",
      "MissingTranslation",
      "IconDensities",
      "ExpiredTargetSdkVersion",
    )

  flavorDimensions += "decoderExtensions"

  buildFeatures { buildConfig = true }

  productFlavors {
    create("noDecoderExtensions") {
      dimension = "decoderExtensions"
      buildConfigField("boolean", "USE_DECODER_EXTENSIONS", "false")
    }
    create("withDecoderExtensions") {
      dimension = "decoderExtensions"
      buildConfigField("boolean", "USE_DECODER_EXTENSIONS", "true")
    }
  }
}

dependencies {
  coreLibraryDesugaring(libs.desugar.jdk.libs)
  compileOnly(libs.checkerframework.qual)
  implementation(libs.androidx.annotation)
  implementation(libs.androidx.appcompat)
  implementation(libs.material)
  implementation(project(":lib-exoplayer"))
  implementation(project(":lib-exoplayer-dash"))
  implementation(project(":lib-exoplayer-hls"))
  implementation(project(":lib-exoplayer-rtsp"))
  implementation(project(":lib-exoplayer-smoothstreaming"))
  implementation(project(":lib-ui"))
  implementation(project(":lib-datasource-cronet"))
  implementation(project(":lib-exoplayer-ima"))
  "withDecoderExtensionsImplementation"(project(":lib-decoder-av1"))
  "withDecoderExtensionsImplementation"(project(":lib-decoder-ffmpeg"))
  "withDecoderExtensionsImplementation"(project(":lib-decoder-flac"))
  "withDecoderExtensionsImplementation"(project(":lib-decoder-opus"))
  "withDecoderExtensionsImplementation"(project(":lib-decoder-iamf"))
  "withDecoderExtensionsImplementation"(project(":lib-decoder-vp9"))
  "withDecoderExtensionsImplementation"(project(":lib-decoder-midi"))
  "withDecoderExtensionsImplementation"(project(":lib-decoder-mpegh"))
  "withDecoderExtensionsImplementation"(project(":lib-datasource-rtmp"))
}
