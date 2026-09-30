// Copyright 2022 The Android Open Source Project
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

import androidx.media3.buildlogic.StripGlslCommentsTask

plugins {
  id("media3.android-library")
  id("media3.publish")
}

android {
  namespace = "androidx.media3.effect"

  defaultConfig {
    externalNativeBuild {
      cmake {
        arguments.add("-DANDROID_WEAK_API_DEFS=ON")
        arguments.add("-Werror=unguarded-availability")
        targets.add("media3EffectJNI")
      }
    }
  }
  externalNativeBuild { cmake { path = file("src/main/jni/CMakeLists.txt") } }

  sourceSets {
    getByName("androidTest").assets.directories.add("../test_data/src/test/assets/")
    getByName("test").assets.directories.add("../test_data/src/test/assets/")
  }
}

// Packages the GLSL shaders in src/main/res/raw without comments, to reduce APK size.
androidComponents {
  onVariants { variant ->
    val stripGlslComments =
      tasks.register<StripGlslCommentsTask>(
        "strip${variant.name.replaceFirstChar { it.uppercase() }}GlslComments"
      ) {
        shaders.from(fileTree("src/main/res/raw") { include("*.glsl") })
      }
    // Generated resources override the resources with the same name in src/main/res.
    variant.sources.res?.addGeneratedSourceDirectory(
      stripGlslComments,
      StripGlslCommentsTask::outputDirectory,
    )
  }
}

dependencies {
  implementation(libs.androidx.annotation)
  implementation(libs.androidx.concurrent.futures)
  api(project(":lib-common"))
  implementation(project(":lib-datasource"))
  implementation(libs.kotlinx.coroutines.guava)
  testImplementation(project(":test-utils-robolectric"))
  testImplementation(project(":test-utils"))
  testImplementation(project(":test-data"))
  testImplementation(libs.robolectric)
  testImplementation(libs.truth)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.androidx.runner)
  androidTestImplementation(project(":lib-exoplayer"))
  androidTestImplementation(project(":lib-effect-ndk"))
  androidTestImplementation(libs.kotlinx.coroutines.android)
  androidTestImplementation(libs.test.parameter.injector)
  androidTestImplementation(libs.junit)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.truth)
  androidTestImplementation(project(":test-utils"))
}
