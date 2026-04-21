// Copyright (C) 2017 The Android Open Source Project
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
  namespace = "androidx.media3.exoplayer.hls"

  buildTypes {
    getByName("debug") {
      enableUnitTestCoverage = true
      enableAndroidTestCoverage = true
    }
  }

  sourceSets { getByName("test").assets.srcDir("../test_data/src/test/assets/") }

  publishing { singleVariant("release") { withSourcesJar() } }
}

dependencies {
  implementation(libs.androidx.annotation)
  compileOnly(libs.errorprone.annotations)
  compileOnly(libs.checkerframework.qual)
  compileOnly(libs.kotlin.annotations.jvm)
  api(project(":lib-exoplayer"))
  testImplementation(project(":test-utils-robolectric"))
  testImplementation(project(":test-utils"))
  testImplementation(project(":test-data"))
  testImplementation(libs.test.parameter.injector)
  testImplementation(libs.okhttp.mockwebserver)
  testImplementation(libs.robolectric)
  androidTestImplementation(project(":test-utils"))
  androidTestImplementation(libs.androidx.test.runner)
}

extra["releaseArtifactId"] = "media3-exoplayer-hls"

extra["releaseName"] = "Media3 ExoPlayer HLS module"

apply(from = "../../publish.gradle")
