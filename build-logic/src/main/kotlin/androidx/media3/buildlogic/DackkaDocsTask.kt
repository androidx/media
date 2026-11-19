/*
 * Copyright (C) 2026 The Android Open Source Project
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

import groovy.json.JsonOutput
import java.io.File
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.IgnoreEmptyDirectories
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations

/** Runs Dackka (`org.jetbrains.dokka.MainKt`) over published Media3 modules and saves the log. */
@CacheableTask
internal abstract class DackkaDocsTask : DefaultTask() {

  @get:Inject protected abstract val execOperations: ExecOperations

  /** Classpath containing `dokka-devsite-plugin` and Dokka runtime dependencies. */
  @get:Classpath abstract val dackkaClasspath: ConfigurableFileCollection

  /** Compile classpath (external dependencies and Android bootclasspath) for documented modules. */
  @get:Classpath abstract val compileClasspath: ConfigurableFileCollection

  /** Java and Kotlin source directories or files from the release variant of documented modules. */
  @get:InputFiles
  @get:PathSensitive(PathSensitivity.RELATIVE)
  @get:IgnoreEmptyDirectories
  abstract val sourceRoots: ConfigurableFileCollection

  /** Media3 root directory used only to strip machine-specific path prefixes from [logFile]. */
  @get:Internal abstract val projectRootDirectory: DirectoryProperty

  /** Output directory where generated Dackka reference documentation is written. */
  @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

  /** Normalized `WARN:` and `ERROR:` lines produced by the Dackka invocation. */
  @get:OutputFile abstract val logFile: RegularFileProperty

  @TaskAction
  fun generateDocs() {
    val outputDir = outputDirectory.get().asFile
    outputDir.deleteRecursively()
    outputDir.mkdirs()

    val devsiteConfig =
      mapOf(
        "projectPath" to "androidx",
        "javaDocsPath" to "",
        "kotlinDocsPath" to "kotlin",
        "excludedPackagesForJava" to listOf("androidx.*compose.*"),
        "hidingAnnotations" to listOf("androidx.annotation.RestrictTo"),
        "validNullabilityAnnotations" to
          listOf(
            "android.annotation.NonNull",
            "android.annotation.Nullable",
            "androidx.annotation.NonNull",
            "androidx.annotation.Nullable",
            "org.checkerframework.checker.nullness.qual.Nullable",
            "org.jspecify.annotations.NonNull",
            "org.jspecify.annotations.Nullable",
          ),
      )

    val dokkaConfig =
      mapOf(
        "moduleName" to "androidx.media3",
        "outputDir" to outputDir.absolutePath,
        "offlineMode" to true,
        "failOnWarning" to false,
        "sourceSets" to
          listOf(
            mapOf(
              "sourceSetID" to mapOf("scopeId" to "androidx.media3", "sourceSetName" to "main"),
              "sourceRoots" to sourceRoots.files.filter { it.exists() }.map { it.absolutePath },
              "classpath" to compileClasspath.files.filter { it.exists() }.map { it.absolutePath },
              "documentedVisibilities" to listOf("PUBLIC", "PROTECTED"),
            )
          ),
        "pluginsConfiguration" to
          listOf(
            mapOf(
              "fqPluginName" to "com.google.devsite.DevsitePlugin",
              "serializationFormat" to "JSON",
              "values" to JsonOutput.toJson(devsiteConfig),
            )
          ),
      )

    val configFile = File(temporaryDir, "dackka-config.json")
    configFile.writeText(JsonOutput.toJson(dokkaConfig))

    val rawLogFile = File(temporaryDir, "dackka-raw.log")
    val exitValue =
      rawLogFile.outputStream().use { fileOut ->
        execOperations
          .javaexec {
            mainClass.set("org.jetbrains.dokka.MainKt")
            classpath(dackkaClasspath)
            args(configFile.absolutePath, "-loggingLevel", "WARN")
            jvmArgs("-Xmx4g")
            standardOutput = fileOut
            errorOutput = fileOut
            isIgnoreExitValue = true
          }
          .exitValue
      }

    if (exitValue != 0) {
      rawLogFile.useLines { lines -> lines.forEach(logger::error) }
      throw GradleException(
        "Dackka failed with exit code $exitValue. Full output was logged above and saved to " +
          "${rawLogFile.absolutePath}."
      )
    }

    val logOutputFile = logFile.get().asFile
    logOutputFile.parentFile.mkdirs()
    val outputPrefixRegex = buildDirectoryPrefixRegex(outputDir)
    val rootPrefixRegex = buildDirectoryPrefixRegex(projectRootDirectory.get().asFile)
    val normalizedLines = rawLogFile.useLines { lines ->
      lines
        .map { it.trimEnd() }
        .filter { it.startsWith("WARN:") || it.startsWith("ERROR:") }
        .map { line ->
          line
            .replace('\\', '/')
            .replace(outputPrefixRegex) { "\$DOCS_DIR/" }
            .replace(rootPrefixRegex, "")
        }
        .distinct()
        .sorted()
        .toList()
    }
    logOutputFile.writeText(
      if (normalizedLines.isEmpty()) "" else normalizedLines.joinToString("\n", postfix = "\n")
    )
  }

  private fun buildDirectoryPrefixRegex(dir: File): Regex {
    val paths =
      listOf(dir.canonicalFile, dir.absoluteFile)
        .map { it.invariantSeparatorsPath.trimEnd('/') }
        .filter { it.isNotEmpty() }
        .distinct()
        .sortedByDescending { it.length }
        .joinToString("|") { Regex.escape(it) }
    return Regex("""(?<=^|\s)(?:file:/*)?(?:$paths)/""")
  }
}
