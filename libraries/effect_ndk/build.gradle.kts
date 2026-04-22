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

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  id("media3.android-library")
  id("media3.publish")
  alias(libs.plugins.kotlin.android)
}

android {
  namespace = "androidx.media3.effect.ndk"

  buildTypes {
    getByName("debug") {
      enableUnitTestCoverage = true
      enableAndroidTestCoverage = true
    }
  }

  kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_1_8) } }

  defaultConfig {
    externalNativeBuild {
      cmake {
        // TODO(b/505317653): Remove flexible page sizes once AGP is upgraded to 9.0 or
        // higher (which uses NDK r28 by default where 16KB alignment is automatic).
        arguments.add("-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON")
        arguments.add("-DANDROID_WEAK_API_DEFS=ON")
        arguments.add("-Werror=unguarded-availability")
        targets.add("hardwareBufferJNI")
      }
    }
  }
  externalNativeBuild { cmake { path = file("src/main/jni/CMakeLists.txt") } }

  sourceSets {
    getByName("androidTest").assets.srcDir("../test_data/src/test/assets/")
    getByName("test").assets.srcDir("../test_data/src/test/assets/")
  }
}

dependencies {
  implementation(libs.androidx.annotation)
  api(project(":lib-common"))
  api(project(":lib-effect"))
  api(project(":lib-transformer"))
  implementation(libs.kotlinx.coroutines.guava)
  compileOnly(libs.errorprone.annotations)
  androidTestImplementation(libs.junit)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.truth)
  androidTestImplementation(project(":test-utils"))
}
