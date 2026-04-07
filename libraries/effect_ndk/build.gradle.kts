// Copyright 2026 The Android Open Source Project
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
  alias(libs.plugins.kotlin.android)
}

apply(from = "${gradle.extra["androidxMediaSettingsDir"]}/common_config.gradle")

android {
  namespace = "androidx.media3.effect.ndk"

  buildTypes {
    getByName("debug") {
      enableUnitTestCoverage = true
      enableAndroidTestCoverage = true
    }
  }

  kotlinOptions { jvmTarget = "1.8" }

  defaultConfig {
    minSdk = libs.versions.minSdkVersion.get().toInt()
    externalNativeBuild {
      cmake {
        arguments.add("-DANDROID_WEAK_API_DEFS=ON")
        arguments.add("-Werror=unguarded-availability")
        targets.add("hardwareBufferJNI")
      }
    }
  }
  externalNativeBuild { cmake { path = file("src/main/jni/CMakeLists.txt") } }

  sourceSets {
    getByName("androidTest").assets.srcDir("../test_data/src/test/assets/")
    getByName("test").assets.srcDir("../test_data/src/test/assets/")
  }

  publishing { singleVariant("release") { withSourcesJar() } }
}

dependencies {
  implementation(libs.androidx.annotation)
  api(project(modulePrefix + "lib-common"))
  api(project(modulePrefix + "lib-effect"))
  api(project(modulePrefix + "lib-transformer"))
  implementation(libs.kotlinx.coroutines.guava)
  compileOnly(libs.errorprone.annotations)
  androidTestImplementation(libs.junit)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.truth)
  androidTestImplementation(project(modulePrefix + "test-utils"))
}

extra["releaseArtifactId"] = "media3-effect-ndk"
extra["releaseName"] = "Media3 Effect NDK module"

apply(from = "../../publish.gradle")
