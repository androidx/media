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
plugins {
  alias(libs.plugins.android.library)
}

apply(from = "${gradle.extra["androidxMediaSettingsDir"]}/common_config.gradle")

android {
  namespace = "androidx.media3.datasource"

  buildTypes { getByName("debug") { enableUnitTestCoverage = true } }

  sourceSets {
    getByName("androidTest") { assets.srcDir("../test_data/src/test/assets") }
    getByName("test") { assets.srcDir("../test_data/src/test/assets") }
  }

  publishing { singleVariant("release") { withSourcesJar() } }
}

dependencies {
  api(project(modulePrefix + "lib-common"))
  api(project(modulePrefix + "lib-database"))
  implementation(libs.androidx.annotation)
  implementation(libs.androidx.exifinterface)
  compileOnly(libs.jsr305)
  compileOnly(libs.errorprone.annotations)
  compileOnly(libs.checkerframework.qual)
  compileOnly(libs.kotlin.annotations.jvm)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.dexmaker)
  androidTestImplementation(libs.dexmaker.mockito)
  androidTestImplementation(libs.okhttp.mockwebserver)
  androidTestImplementation(project(modulePrefix + "test-utils"))
  testImplementation(libs.mockito.core)
  testImplementation(libs.androidx.test.core)
  testImplementation(libs.androidx.test.ext.junit)
  testImplementation(libs.truth)
  testImplementation(libs.okhttp.mockwebserver)
  testImplementation(libs.robolectric)
  testImplementation(project(modulePrefix + "test-utils"))
}

extra["releaseArtifactId"] = "media3-datasource"
extra["releaseName"] = "Media3 DataSource module"

apply(from = "../../publish.gradle")
