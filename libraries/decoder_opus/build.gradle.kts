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
import java.io.File

plugins { id("media3.android-library") }

android {
  namespace = "androidx.media3.decoder.opus"

  sourceSets { getByName("androidTest").assets.directories.add("../test_data/src/test/assets") }

  defaultConfig {
    externalNativeBuild {
      cmake {
        // TODO(b/505317653): Remove flexible page sizes once AGP is upgraded to 9.0 or
        // higher (which uses NDK r28 by default where 16KB alignment is automatic).
        arguments("-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON")
        targets.add("opusV2JNI")
      }
    }
  }
}

// Configure the native build only if libopus is present to avoid gradle sync
// failures if libopus hasn't been built according to the README instructions.
if (project.file("src/main/jni/libopus").exists()) {
  android.externalNativeBuild.cmake {
    path = file("src/main/jni/CMakeLists.txt")
    version = "3.21.0+"
    if (project.hasProperty("externalNativeBuildDir")) {
      val externalNativeBuildDirProp = project.property("externalNativeBuildDir") as String
      val externalNativeBuildDirFile =
        if (File(externalNativeBuildDirProp).isAbsolute) {
          File(externalNativeBuildDirProp)
        } else {
          File(rootDir, externalNativeBuildDirProp)
        }
      buildStagingDirectory = File(externalNativeBuildDirFile, project.name)
    }
  }
}

dependencies {
  api(project(":lib-decoder"))
  // TODO(b/203752526): Remove this dependency.
  implementation(project(":lib-exoplayer"))
  implementation(libs.androidx.annotation)
  compileOnly(libs.kotlin.annotations.jvm)
  testImplementation(project(":test-utils"))
  testImplementation(libs.robolectric)
  androidTestImplementation(project(":test-utils"))
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.ext.junit)
}
