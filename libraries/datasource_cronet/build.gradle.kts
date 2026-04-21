// Copyright (C) 2014 The Android Open Source Project
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
  namespace = "androidx.media3.datasource.cronet"

  publishing { singleVariant("release") { withSourcesJar() } }
}

dependencies {
  runtimeOnly(libs.play.services.cronet)
  api(libs.cronet.api)
  api(project(":lib-common"))
  api(project(":lib-datasource"))
  implementation(libs.androidx.annotation)
  compileOnly(libs.errorprone.annotations)
  compileOnly(libs.checkerframework.qual)
  compileOnly(libs.kotlin.annotations.jvm)
  androidTestImplementation(libs.androidx.test.rules)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.dexmaker.mockito)
  // Instrumentation tests assume that an app-packaged version of cronet is available.
  androidTestImplementation(libs.cronet.embedded)
  androidTestImplementation(project(":test-utils"))
  testImplementation(project(":test-utils"))
  testImplementation(libs.okhttp.mockwebserver)
  testImplementation(libs.robolectric)
}

extra["releaseName"] = "Media3 Cronet DataSource module"

apply(from = "../../publish.gradle")
