// Copyright 2024 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
import java.io.File

plugins { id("media3.android-library") }

android {
  namespace = "androidx.media3.decoder.mpegh"

  sourceSets { getByName("androidTest").assets.directories.add("../test_data/src/test/assets") }

  defaultConfig { externalNativeBuild { cmake { targets.add("mpeghJNI") } } }
}

// Configure the native build only if libmpegh is present to avoid gradle sync
// failures if libmpegh hasn't been built according to the README instructions.
if (project.file("src/main/jni/libmpegh").exists()) {
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
  compileOnly(libs.checkerframework.qual)
  compileOnly(libs.kotlin.annotations.jvm)
  testImplementation(project(":test-utils"))
  testImplementation(libs.robolectric)
  androidTestImplementation(project(":test-utils"))
  androidTestImplementation(libs.androidx.test.runner)
}
