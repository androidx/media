// Copyright 2023 The Android Open Source Project
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

import org.gradle.kotlin.dsl.implementation
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  id("media3.android-library")
  alias(libs.plugins.kotlin.android)
  alias(libs.plugins.protobuf)
}

android {
  namespace = "androidx.media3.demo.session.service"

  kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_1_8) } }

  buildTypes { getByName("release") { signingConfig = signingConfigs.getByName("debug") } }

  defaultConfig { targetSdk = libs.versions.appTargetSdkVersion.get().toInt() }

  // The demo service module isn't indexed, and doesn't have translations.
  lint.disable += listOf("GoogleAppIndexingWarning", "MissingTranslation")
}

protobuf {
  protoc { artifact = libs.protobuf.protoc.get().toString() }
  generateProtoTasks { all().forEach { task -> task.plugins { create("java") } } }
}

dependencies {
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.datastore)
  implementation(libs.kotlinx.coroutines.guava)
  implementation(libs.protobuf.java)
  implementation(project(":lib-cast"))
  implementation(project(":lib-common-ktx"))
  implementation(project(":lib-exoplayer"))
  implementation(project(":lib-exoplayer-dash"))
  implementation(project(":lib-exoplayer-hls"))
  implementation(project(":lib-ui"))
  implementation(project(":lib-session"))
}
