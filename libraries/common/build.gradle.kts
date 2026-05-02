// Copyright (C) 2020 The Android Open Source Project
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

import androidx.media3.buildlogic.Media3Modules

plugins {
  id("media3.android-library")
  id("media3.publish")
}

android { namespace = "androidx.media3.common" }

dependencies {
  constraints {
    // List all released targets as constraints. This ensures they are all
    // resolved to the same version.
    Media3Modules.EXTERNAL_MODULES.forEach { (gradleName, moduleInfo) ->
      if (moduleInfo.artifactId?.startsWith("media3-") == true) {
        implementation(project(":$gradleName"))
      }
    }
  }
  api(libs.guava) {
    // Exclude dependencies that are only used by Guava at compile time
    // (but declared as runtime deps) [internal b/168188131].
    exclude(group = "com.google.code.findbugs", module = "jsr305")
    exclude(group = "org.checkerframework", module = "checker-compat-qual")
    exclude(group = "org.checkerframework", module = "checker-qual")
    exclude(group = "com.google.errorprone", module = "error_prone_annotations")
    exclude(group = "com.google.j2objc", module = "j2objc-annotations")
    exclude(group = "org.codehaus.mojo", module = "animal-sniffer-annotations")
  }
  api(libs.androidx.annotation.experimental)
  implementation(libs.androidx.annotation)

  testImplementation(libs.mockito.core)
  testImplementation(libs.androidx.test.core)
  testImplementation(libs.androidx.test.ext.junit)
  testImplementation(libs.junit)
  testImplementation(libs.test.parameter.injector)
  testImplementation(libs.truth)
  testImplementation(libs.robolectric)
  testImplementation(project(":lib-exoplayer"))
  testImplementation(project(":test-utils"))

  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(project(":test-utils"))
}
