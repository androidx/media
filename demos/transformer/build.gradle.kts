/*
 * Copyright 2021 The Android Open Source Project
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

plugins { alias(libs.plugins.android.application) }

android {
  namespace = "androidx.media3.demo.transformer"

  compileSdk = libs.versions.compileSdkVersion.get().toInt()

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
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
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
      signingConfig = signingConfigs.getByName("debug")
    }
  }

  // The demo module isn't indexed, and doesn't have translations.
  lint.disable += listOf("GoogleAppIndexingWarning", "MissingTranslation")

  flavorDimensions.add("mediaPipe")

  productFlavors {
    create("noMediaPipe") { dimension = "mediaPipe" }
    create("withMediaPipe") { dimension = "mediaPipe" }
  }
}

androidComponents {
  // Ignore the withMediaPipe variant if the MediaPipe AAR is not present.
  if (!project.file("libs/edge_detector_mediapipe_aar.aar").exists()) {
    beforeVariants { variantBuilder ->
      if (variantBuilder.productFlavors.contains("mediaPipe" to "withMediaPipe")) {
        variantBuilder.enable = false
      }
    }
  }
}

dependencies {
  implementation(libs.androidx.core)
  compileOnly(libs.checkerframework.qual)
  implementation(libs.androidx.annotation)
  implementation(libs.androidx.appcompat)
  implementation(libs.androidx.constraintlayout)
  implementation(libs.androidx.window)
  implementation(libs.material)
  implementation(project(modulePrefix + "lib-effect"))
  implementation(project(modulePrefix + "lib-effect-ndk"))
  implementation(project(modulePrefix + "lib-exoplayer"))
  implementation(project(modulePrefix + "lib-exoplayer-dash"))
  implementation(project(modulePrefix + "lib-transformer"))
  implementation(project(modulePrefix + "lib-muxer"))
  implementation(project(modulePrefix + "lib-ui"))

  // For MediaPipe and its dependencies:
  "withMediaPipeImplementation"(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar"))))
  "withMediaPipeImplementation"("com.google.flogger:flogger:latest.release")
  "withMediaPipeImplementation"("com.google.flogger:flogger-system-backend:latest.release")
  "withMediaPipeImplementation"(libs.jsr305)
  "withMediaPipeImplementation"("com.google.protobuf:protobuf-javalite:3.19.1")
}
