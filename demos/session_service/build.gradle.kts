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
  alias(libs.plugins.android.library)
  alias(libs.plugins.kotlin.android)
  alias(libs.plugins.protobuf)
}

apply(from = "${gradle.extra["androidxMediaSettingsDir"]}/common_config.gradle")

android {
  namespace = "androidx.media3.demo.session.service"

  compileSdk = libs.versions.compileSdkVersion.get().toInt()

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
  }

  kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_1_8) } }

  defaultConfig {
    minSdk = libs.versions.minSdkVersion.get().toInt()
    targetSdk = libs.versions.appTargetSdkVersion.get().toInt()
  }

  buildTypes {
    getByName("release") { signingConfig = signingConfigs.getByName("debug") }
    getByName("debug") { isJniDebuggable = true }
  }

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
  implementation(project(modulePrefix + "lib-cast"))
  implementation(project(modulePrefix + "lib-common-ktx"))
  implementation(project(modulePrefix + "lib-exoplayer"))
  implementation(project(modulePrefix + "lib-exoplayer-dash"))
  implementation(project(modulePrefix + "lib-exoplayer-hls"))
  implementation(project(modulePrefix + "lib-ui"))
  implementation(project(modulePrefix + "lib-session"))
}
