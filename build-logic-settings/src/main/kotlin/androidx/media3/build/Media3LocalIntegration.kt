// Copyright 2026 The Android Open Source Project
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

package androidx.media3.build

import java.io.File
import org.gradle.api.artifacts.component.ModuleComponentSelector
import org.gradle.api.initialization.Settings

val mavenIdToGradleName =
  Media3Modules.libraryModules.entries
    .filter { it.value.artifactId != null }
    .associate { it.value.artifactId to it.key }

fun Settings.includeMedia3(media3Dir: File) {
  includeBuild(media3Dir) {
    dependencySubstitution {
      all {
        val requested = this.requested
        if (requested is ModuleComponentSelector && requested.group == "androidx.media3") {
          mavenIdToGradleName[requested.module]?.let { gradleName ->
            useTarget(project(":$gradleName"))
          }
        }
      }
    }
  }
}
