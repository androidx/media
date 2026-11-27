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

import androidx.media3.buildlogic.DackkaDocsTask
import androidx.media3.buildlogic.Media3Modules
import androidx.media3.buildlogic.ValidateDocsWarningsTask
import com.android.build.api.variant.LibraryAndroidComponentsExtension
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.type.ArtifactTypeDefinition

val libs: VersionCatalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
val dackkaBuildId = libs.findVersion("dackkaBuildId").get().requiredVersion

repositories {
  exclusiveContent {
    forRepository {
      maven {
        url = uri("https://androidx.dev/dackka/builds/$dackkaBuildId/artifacts/repo/repository")
      }
    }
    filter { includeGroup("com.google.devsite") }
  }
  maven {
    url = uri("https://packages.jetbrains.team/maven/p/kt/dokka-dev")
    content { includeGroup("org.jetbrains.dokka") }
  }
}

val dackkaClasspathConfig =
  configurations.create("dackkaClasspath") {
    isCanBeConsumed = false
    isCanBeResolved = true
  }

private val mediaRoot = project(":lib-common").projectDir.parentFile.parentFile

private val docsTask =
  tasks.register<DackkaDocsTask>("docs") {
    group = "documentation"
    description = "Generates reference documentation for published Media3 modules using Dackka."
    dackkaClasspath.from(dackkaClasspathConfig)
    projectRootDirectory.set(mediaRoot)
    outputDirectory.set(layout.buildDirectory.dir("docs/reference"))
    logFile.set(layout.buildDirectory.file("docs/dackka.log"))
  }

subprojects {
  if (Media3Modules.EXTERNAL_MODULES[name]?.publishedToGmaven == true) {
    plugins.withId("media3.android-library") {
      val androidComponents = extensions.getByType<LibraryAndroidComponentsExtension>()
      docsTask.configure { compileClasspath.from(androidComponents.sdkComponents.bootClasspath) }
      androidComponents.onVariants(androidComponents.selector().withName("release")) { variant ->
        docsTask.configure {
          variant.sources.java?.all?.let { sourceRoots.from(it) }
          variant.sources.kotlin?.all?.let { sourceRoots.from(it) }
          compileClasspath.from(
            variant.compileConfiguration.incoming
              .artifactView {
                attributes {
                  attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, "android-classes-jar")
                }
                componentFilter { it is ModuleComponentIdentifier }
              }
              .files
          )
        }
      }
      afterEvaluate {
        if ("generateReleaseRFile" in tasks.names) {
          val rTask = tasks.named("generateReleaseRFile")
          docsTask.configure {
            compileClasspath.from(rTask.get().outputs.files.filter { it.extension == "jar" })
          }
        }
      }
    }
  }
}

tasks.register<ValidateDocsWarningsTask>("validateDocsWarnings") {
  group = "verification"
  description = "Validates that Dackka emitted no unexpected warnings."
  logFile.set(docsTask.flatMap { it.logFile })
  baselineFile.set(mediaRoot.resolve("gradle/dackka-warnings-baseline.txt"))
  stampFile.set(layout.buildDirectory.file("docs/validate-warnings.stamp"))
}

dependencies { dackkaClasspathConfig(libs.findLibrary("dokka-devsite-plugin").get()) }
