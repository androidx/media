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
apply(from = "../../constants.gradle")

plugins {
  alias(libs.plugins.android.application)
  id("com.google.android.gms.strict-version-matcher-plugin")
}

android {
  namespace = "androidx.media3.demo.main"

  compileSdk = libs.versions.compileSdkVersion.get().toInt()

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
    isCoreLibraryDesugaringEnabled = true
  }

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
      proguardFiles("proguard-rules.txt", getDefaultProguardFile("proguard-android-optimize.txt"))
      signingConfig = signingConfigs.getByName("debug")
    }
    getByName("debug") { isJniDebuggable = true }
  }

  // The demo app isn't indexed, doesn't have translations, and has a
  // banner for AndroidTV that's only in xhdpi density.
  lint.disable += listOf("GoogleAppIndexingWarning", "MissingTranslation", "IconDensities")

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
  implementation(project(modulePrefix + "lib-exoplayer"))
  implementation(project(modulePrefix + "lib-exoplayer-dash"))
  implementation(project(modulePrefix + "lib-exoplayer-hls"))
  implementation(project(modulePrefix + "lib-exoplayer-rtsp"))
  implementation(project(modulePrefix + "lib-exoplayer-smoothstreaming"))
  implementation(project(modulePrefix + "lib-ui"))
  implementation(project(modulePrefix + "lib-datasource-cronet"))
  implementation(project(modulePrefix + "lib-exoplayer-ima"))
  "withDecoderExtensionsImplementation"(project(modulePrefix + "lib-decoder-av1"))
  "withDecoderExtensionsImplementation"(project(modulePrefix + "lib-decoder-ffmpeg"))
  "withDecoderExtensionsImplementation"(project(modulePrefix + "lib-decoder-flac"))
  "withDecoderExtensionsImplementation"(project(modulePrefix + "lib-decoder-opus"))
  "withDecoderExtensionsImplementation"(project(modulePrefix + "lib-decoder-iamf"))
  "withDecoderExtensionsImplementation"(project(modulePrefix + "lib-decoder-vp9"))
  "withDecoderExtensionsImplementation"(project(modulePrefix + "lib-decoder-midi"))
  "withDecoderExtensionsImplementation"(project(modulePrefix + "lib-decoder-mpegh"))
  "withDecoderExtensionsImplementation"(project(modulePrefix + "lib-datasource-rtmp"))
}
