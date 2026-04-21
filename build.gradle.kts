// Copyright (C) 2016 The Android Open Source Project
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
import androidx.media3.build.Media3Modules
import java.io.File

buildscript {
  repositories {
    google()
    mavenCentral()
  }
  dependencies {
    // Use the legacy 'buildscript' classpath mechanism instead of the modern 'plugins' block
    // because version 1.2.4 of the strict-version-matcher-plugin is currently only available
    // as a Maven artifact (com.google.android.gms:strict-version-matcher-plugin) and is not
    // yet published to the Gradle Plugin Portal in a compatible form (only 1.2.2 so far).
    // If version 1.2.4 (or newer) becomes available on the Plugin Portal in the future,
    // this can be safely migrated to a standard 'id(...) alias(...)' declaration.
    // Naming mismatch reference:
    // https://maven.google.com/web/index.html#com.google.android.gms:strict-version-matcher-plugin
    // https://maven.google.com/web/index.html#com.google.android.gms.strict-version-matcher-plugin
    classpath(libs.strict.version.matcher.plugin)
  }
}

plugins {
  alias(libs.plugins.android.application) apply false
  alias(libs.plugins.android.library) apply false
  alias(libs.plugins.kotlin.android) apply false
  alias(libs.plugins.kotlin.compose.compiler) apply false
  id("gradlebuild.media3-build-logic")
}

allprojects {
  repositories {
    google()
    mavenCentral()
    maven {
      url = uri("https://jitpack.io")
      content { includeGroup("com.github.philburk") }
    }
  }
  if (project.hasProperty("externalBuildDir")) {
    val externalBuildDirProp = project.property("externalBuildDir") as String
    val externalBuildDirFile =
      if (File(externalBuildDirProp).isAbsolute) {
        File(externalBuildDirProp)
      } else {
        File(rootDir, externalBuildDirProp)
      }
    layout.buildDirectory.set(File(externalBuildDirFile, project.name))
  }
  group = "androidx.media3"
}

subprojects {
  // If the map has a Maven ID for this module, dynamically inject it
  Media3Modules.externalModules[project.name]?.artifactId?.let { extra["releaseArtifactId"] = it }
}

tasks.register("printReleaseArtifactIds") {
  description = "Prints the releaseArtifactId of modules configured for publishing."
  doLast {
    subprojects {
      // Check if the project is intended to be published by looking for a task
      // added by the media/publish.gradle script.
      if (!tasks.names.contains("generatePomFileForReleasePublication")) {
        return@subprojects
      }

      if (extra.has("releaseArtifactId")) {
        println(extra["releaseArtifactId"])
      } else {
        logger.warn("WARN: Project ${path} has publish task but no releaseArtifactId.")
      }
    }
  }
}
