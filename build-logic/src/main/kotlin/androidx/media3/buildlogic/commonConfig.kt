/*
 * Copyright (C) 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.media3.buildlogic

import com.android.build.api.dsl.CommonExtension
import java.io.File
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.extra
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension

fun Project.configureCommonConfig(android: CommonExtension, libs: VersionCatalog) {
  android.apply {
    compileSdk = libs.findVersion("compileSdkVersion").get().requiredVersion.toInt()

    defaultConfig.apply {
      minSdk = libs.findVersion("minSdkVersion").get().requiredVersion.toInt()

      testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
      // This requires the ANDROIDX_TEST_ORCHESTRATOR and
      // androidx.test:orchestrator config below.
      // See
      // https://developer.android.com/training/testing/instrumented-tests/androidx-test-libraries/runner
      testInstrumentationRunnerArguments["clearPackageData"] = "true"
    }

    lint.checkTestSources = true

    dependencies {
      // Add the missing annotations to all compile classpaths to satisfy Javac
      // when it parses Guava and other dependencies, without bundling them in the release.
      val annotationLibs =
        listOf(
          "jsr305", // E.g. javax.annotation.meta.When.MAYBE
          "errorprone-annotations",
          "checkerframework-qual",
          "kotlin-annotations-jvm", // E.g. MigrationStatus.STRICT
          "j2objc-annotations", // E.g. j2objc.annotations.ReflectionSupport.Level.FULL
          "animal-sniffer-annotations",
        )

      for (libName in annotationLibs) {
        val lib = libs.findLibrary(libName).get()
        add("compileOnly", lib)
        add("testCompileOnly", lib)
        add("androidTestCompileOnly", lib)
      }
    }

    compileOptions.apply {
      sourceCompatibility = JavaVersion.VERSION_1_8
      targetCompatibility = JavaVersion.VERSION_1_8
    }
    tasks.withType<JavaCompile>().configureEach { options.compilerArgs.add("-Xlint:-options") }

    pluginManager.withPlugin("org.jetbrains.kotlin.android") {
      extensions.configure<KotlinAndroidProjectExtension>("kotlin") {
        compilerOptions { freeCompilerArgs.add("-Xannotation-default-target=param-property") }
      }
    }

    testOptions.apply {
      unitTests.all {
        it.jvmArgs("-Xmx2g")
        it.systemProperty("robolectric.graphicsMode", "NATIVE")
      }
      unitTests.isIncludeAndroidResources = true
      // See
      // https://developer.android.com/training/testing/instrumented-tests/androidx-test-libraries/runner
      execution = "ANDROIDX_TEST_ORCHESTRATOR"
    }
  }
}
