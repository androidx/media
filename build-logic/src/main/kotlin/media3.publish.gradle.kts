// Copyright (C) 2017 The Android Open Source Project
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
import androidx.media3.build.addMissingAarTypeToXml
import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication

plugins { id("maven-publish") }

val libs = project.extensions.getByType<VersionCatalogsExtension>().named("libs")

plugins.withId("com.android.library") {
  configure<LibraryExtension> { publishing { singleVariant("release") { withSourcesJar() } } }
}

afterEvaluate {
  // Check if this is the root standalone build
  if (gradle.parent == null) {
    configure<PublishingExtension> {
      repositories {
        maven {
          val mavenRepo = findProperty("mavenRepo") as String?
          url = uri(mavenRepo ?: layout.buildDirectory.dir("repo").get())
        }
      }

      publications {
        register<MavenPublication>("release") {
          from(components["release"])
          groupId = "androidx.media3"
          artifactId = Media3Modules.externalModules[project.name]?.artifactId ?: ""
          version = libs.findVersion("releaseVersion").get().requiredVersion

          pom {
            name.set(Media3Modules.externalModules[project.name]?.name ?: "")

            licenses {
              license {
                name.set("The Apache Software License, Version 2.0")
                url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("repo")
              }
            }
            developers { developer { name.set("The Android Open Source Project") } }
            scm {
              connection.set("scm:git:https://github.com/androidx/media.git")
              url.set("https://github.com/androidx/media")
            }
            withXml { addMissingAarTypeToXml(this) }
          }
        }
      }
    }
  }
}
