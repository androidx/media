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
plugins { alias(libs.plugins.android.library) }

apply(from = "${gradle.extra["androidxMediaSettingsDir"]}/common_config.gradle")

android {
  namespace = "androidx.media3.extractor"

  buildTypes { getByName("debug") { enableUnitTestCoverage = true } }

  sourceSets {
    getByName("androidTest").assets.srcDir("../test_data/src/test/assets")
    getByName("test").assets.srcDir("../test_data/src/test/assets/")
  }

  publishing { singleVariant("release") { withSourcesJar() } }
}

dependencies {
  implementation(libs.androidx.annotation)
  api(project(modulePrefix + "lib-common"))
  api(project(modulePrefix + "lib-container"))
  // TODO(b/203752187): Remove this dependency.
  implementation(project(modulePrefix + "lib-decoder"))
  compileOnly(libs.errorprone.annotations)
  compileOnly(libs.checkerframework.qual)
  compileOnly(libs.kotlin.annotations.jvm)
  testImplementation(project(modulePrefix + "lib-exoplayer"))
  testImplementation(project(modulePrefix + "test-utils"))
  testImplementation(project(modulePrefix + "test-data"))
  testImplementation(libs.test.parameter.injector)
  testImplementation(libs.robolectric)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(project(modulePrefix + "test-utils"))
  androidTestImplementation(libs.dexmaker)
}

extra["releaseArtifactId"] = "media3-extractor"
extra["releaseName"] = "Media3 Extractor module"

apply(from = "../../publish.gradle")
