// Copyright (C) 2026 The Android Open Source Project
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
import androidx.media3.build.configureCommonConfig

plugins { id("com.android.library") }

group = "androidx.media3"

val libs: VersionCatalog = extensions.getByType<VersionCatalogsExtension>().named("libs")

android {
  configureCommonConfig(android = this, libs)

  buildTypes {
    getByName("debug") {
      enableUnitTestCoverage = true
      enableAndroidTestCoverage = true
    }
  }

  defaultConfig {
    consumerProguardFiles("proguard-rules.txt")
    aarMetadata {
      minCompileSdk = libs.findVersion("compileSdkVersion").get().requiredVersion.toInt()
    }
  }

  testOptions.targetSdk = libs.findVersion("libTestTargetSdkVersion").get().requiredVersion.toInt()
}

dependencies { "androidTestUtil"(libs.findLibrary("androidx-test-orchestrator").get()) }
