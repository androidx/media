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
plugins { id("media3.android-library") }

android {
  namespace = "androidx.media3.decoder.ffmpeg"

  sourceSets { getByName("androidTest").assets.srcDir("../test_data/src/test/assets") }
  defaultConfig {
    externalNativeBuild {
      cmake {
        // TODO(b/505317653): Remove flexible page sizes once AGP is upgraded to 9.0 or
        // higher (which uses NDK r28 by default where 16KB alignment is automatic).
        arguments("-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON")
      }
    }
  }
}

// Configure the native build only if ffmpeg is present to avoid gradle sync
// failures if ffmpeg hasn't been built according to the README instructions.
if (project.file("src/main/jni/ffmpeg").exists()) {
  android.externalNativeBuild.cmake.path = file("src/main/jni/CMakeLists.txt")
  // LINT.IfChange
  // Should match cmake_minimum_required.
  android.externalNativeBuild.cmake.version = "3.21.0+"
  // LINT.ThenChange(src/main/jni/CMakeLists.txt)
}

dependencies {
  api(project(":lib-decoder"))
  // TODO(b/203752526): Remove this dependency.
  implementation(project(":lib-exoplayer"))
  implementation(libs.androidx.annotation)
  compileOnly(libs.checkerframework.qual)
  compileOnly(libs.kotlin.annotations.jvm)
  testImplementation(project(":test-utils"))
  testImplementation(libs.robolectric)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.truth)
}
