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

// Make sure this project is evaluated after all other libraries. This ensures
// the Gradle properties of each library are populated and we can automatically
// check if a 'releaseArtifactId' exists.
rootProject.allprojects.forEach {
  if (
    (it.name.startsWith(modulePrefix.replace(":", "") + "lib-") ||
      it.name.startsWith(modulePrefix.replace(":", "") + "test-")) && !it.name.endsWith("-common")
  ) {
    evaluationDependsOn(":" + it.name)
  }
}

android {
  namespace = "androidx.media3.common"

  buildTypes { getByName("debug") { enableUnitTestCoverage = true } }
  publishing { singleVariant("release") { withSourcesJar() } }
}

dependencies {
  constraints {
    // List all released targets as constraints. This ensures they are all
    // resolved to the same version.
    rootProject.allprojects.forEach {
      if (
        it.extra.has("releaseArtifactId") &&
          (it.extra["releaseArtifactId"] as String).startsWith("media3-")
      ) {
        implementation(project(":" + it.name))
      }
    }
  }
  api(libs.guava) {
    // Exclude dependencies that are only used by Guava at compile time
    // (but declared as runtime deps) [internal b/168188131].
    exclude(group = "com.google.code.findbugs", module = "jsr305")
    exclude(group = "org.checkerframework", module = "checker-compat-qual")
    exclude(group = "org.checkerframework", module = "checker-qual")
    exclude(group = "com.google.errorprone", module = "error_prone_annotations")
    exclude(group = "com.google.j2objc", module = "j2objc-annotations")
    exclude(group = "org.codehaus.mojo", module = "animal-sniffer-annotations")
  }
  api(libs.androidx.annotation.experimental)
  implementation(libs.androidx.annotation)
  // Workaround for 'duplicate class' error caused by incomplete version
  // metadata in Kotlin std lib (https://issuetracker.google.com/278545487).
  // This can be removed when one of the other deps here (probably
  // androidx.annotation) depends on kotlin-stdlib:1.9.20.
  implementation(platform(libs.kotlin.bom))
  compileOnly(libs.jsr305)
  compileOnly(libs.errorprone.annotations)
  compileOnly(libs.checkerframework.qual)
  compileOnly(libs.kotlin.annotations.jvm)

  testImplementation(libs.mockito.core)
  testImplementation(libs.androidx.test.core)
  testImplementation(libs.androidx.test.ext.junit)
  testImplementation(libs.junit)
  testImplementation(libs.test.parameter.injector)
  testImplementation(libs.truth)
  testImplementation(libs.robolectric)
  testImplementation(project(modulePrefix + "lib-exoplayer"))
  testImplementation(project(modulePrefix + "test-utils"))

  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(project(modulePrefix + "test-utils"))
}

extra["releaseArtifactId"] = "media3-common"

extra["releaseName"] = "Media3 common module"

apply(from = "../../publish.gradle")
