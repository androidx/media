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
plugins { alias(libs.plugins.android.library) }

apply(from = "${gradle.extra["androidxMediaSettingsDir"]}/common_config.gradle")

android {
  namespace = "androidx.media3.decoder.vp9"

  sourceSets {
    getByName("main") {
      jniLibs.srcDir("src/main/libs")
      jni.setSrcDirs(listOf<String>()) // Disable the automatic ndk-build call by Android Studio.
    }
    getByName("androidTest").assets.srcDir("../test_data/src/test/assets")
  }
}

dependencies {
  api(project(modulePrefix + "lib-decoder"))
  // TODO(b/203752526): Remove this dependency.
  implementation(project(modulePrefix + "lib-exoplayer"))
  implementation(libs.androidx.annotation)
  compileOnly(libs.kotlin.annotations.jvm)
  testImplementation(project(modulePrefix + "test-utils"))
  testImplementation(libs.robolectric)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.truth)
}
