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
  namespace = "androidx.media3.exoplayer.smoothstreaming"

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
  api(project(modulePrefix + "lib-exoplayer"))
  compileOnly(libs.errorprone.annotations)
  compileOnly(libs.checkerframework.qual)
  compileOnly(libs.kotlin.annotations.jvm)
  implementation(libs.androidx.annotation)
  testImplementation(project(modulePrefix + "test-utils-robolectric"))
  testImplementation(project(modulePrefix + "test-utils"))
  testImplementation(libs.robolectric)
}

extra["releaseArtifactId"] = "media3-exoplayer-smoothstreaming"

extra["releaseName"] = "Media3 ExoPlayer SmoothStreaming module"

apply(from = "../../publish.gradle")
