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

plugins { alias(libs.plugins.android.library) }

apply(from = "${gradle.extra["androidxMediaSettingsDir"]}/common_config.gradle")

android {
  namespace = "androidx.media3.decoder.flac"

  sourceSets { getByName("androidTest").assets.srcDir("../test_data/src/test/assets") }

  defaultConfig {
    externalNativeBuild {
      cmake {
        arguments("-DWITH_OGG=OFF")
        arguments("-DINSTALL_MANPAGES=OFF")
        targets("flacJNI")
      }
    }
  }
}

// Configure the native build only if libflac is present to avoid gradle sync
// failures if libflac hasn't been built according to the README instructions.
if (project.file("src/main/jni/libflac").exists()) {
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
  api(project(modulePrefix + "lib-decoder"))
  // TODO(b/203752526): Remove this dependency.
  implementation(project(modulePrefix + "lib-exoplayer"))
  implementation(libs.androidx.annotation)
  compileOnly(libs.checkerframework.qual)
  compileOnly(libs.kotlin.annotations.jvm)
  androidTestImplementation(project(modulePrefix + "test-utils"))
  androidTestImplementation(libs.androidx.test.runner)
  testImplementation(libs.androidx.test.core)
  testImplementation(libs.androidx.test.ext.junit)
  testImplementation(project(modulePrefix + "test-utils"))
  testImplementation(project(modulePrefix + "test-data"))
  testImplementation(libs.robolectric)
}
