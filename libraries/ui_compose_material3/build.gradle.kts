// Copyright (C) 2025 The Android Open Source Project
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

plugins {
  id("media3.android-library")
  id("media3.publish")
  alias(libs.plugins.kotlin.compose.compiler)
}

android {
  namespace = "androidx.media3.ui.compose.material3"

  lint { baseline = file("lint-baseline.xml") }

  buildFeatures { compose = true }
}

dependencies {
  api(project(":lib-common"))
  api(project(":lib-common-ktx"))
  api(project(":lib-ui-compose"))

  api(platform(libs.androidx.compose.bom))
  api(libs.androidx.compose.foundation)
  api(libs.androidx.compose.material3)

  // TODO: b/509786666 - This dependency is added as part of the Artwork implementation in
  // MiniController. This might need updating once the actual implementation is done.
  implementation(libs.kotlinx.coroutines.guava)

  testImplementation(libs.androidx.compose.ui.test)
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(project(":test-utils"))
  testImplementation(libs.robolectric)
}
