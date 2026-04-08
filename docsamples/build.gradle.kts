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
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.android.library)
  alias(libs.plugins.kotlin.android)
  alias(libs.plugins.kotlin.compose.compiler)
}

apply(from = "$projectDir/../common_config.gradle")

android {
  namespace = "androidx.media3.docsamples"

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
    isCoreLibraryDesugaringEnabled = true
  }

  kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_1_8) } }

  defaultConfig { minSdk = 23 }
}

dependencies {
  coreLibraryDesugaring(libs.desugar.jdk.libs)
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.annotation)
  implementation(libs.androidx.appcompat)
  implementation(platform(libs.androidx.compose.bom))
  implementation("androidx.compose.material:material-icons-extended")
  implementation(libs.androidx.mediarouter)
  implementation(libs.glide)
  implementation(libs.glide.concurrent.integration)
  implementation(libs.kotlinx.coroutines.guava)
  implementation(project(modulePrefix + "lib-cast"))
  implementation(project(modulePrefix + "lib-container"))
  implementation(project(modulePrefix + "lib-datasource-cronet"))
  implementation(project(modulePrefix + "lib-effect"))
  implementation(project(modulePrefix + "lib-exoplayer"))
  implementation(project(modulePrefix + "lib-exoplayer-dash"))
  implementation(project(modulePrefix + "lib-exoplayer-hls"))
  implementation(project(modulePrefix + "lib-exoplayer-ima"))
  implementation(project(modulePrefix + "lib-exoplayer-rtsp"))
  implementation(project(modulePrefix + "lib-exoplayer-smoothstreaming"))
  implementation(project(modulePrefix + "lib-inspector"))
  implementation(project(modulePrefix + "lib-inspector-frame"))
  implementation(project(modulePrefix + "lib-muxer"))
  implementation(project(modulePrefix + "lib-session"))
  implementation(project(modulePrefix + "lib-transformer"))
  implementation(project(modulePrefix + "lib-ui"))
  implementation(project(modulePrefix + "lib-ui-compose-material3"))
}
