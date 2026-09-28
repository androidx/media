/*
 * Copyright 2026 The Android Open Source Project
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

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Copies GLSL shaders to the `raw` subdirectory of a resource directory, with comments removed to
 * reduce APK size.
 *
 * Line breaks are kept, so line numbers in shader compilation errors match the source files.
 */
@CacheableTask
abstract class StripGlslCommentsTask : DefaultTask() {

  /** The GLSL files to copy. */
  @get:InputFiles
  @get:PathSensitive(PathSensitivity.NAME_ONLY)
  abstract val shaders: ConfigurableFileCollection

  /** The resource directory to write the shaders to. */
  @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

  @TaskAction
  fun stripComments() {
    val resourceDirectory = outputDirectory.get().asFile
    resourceDirectory.deleteRecursively()
    val rawDirectory = resourceDirectory.resolve("raw").apply { mkdirs() }
    for (shader in shaders) {
      rawDirectory.resolve(shader.name).writeText(stripGlslComments(shader.readText()))
    }
  }
}

// GLSL has no string literals, so a regular expression is enough to find the comments.
private val GLSL_COMMENT = Regex("""//[^\n]*|/\*[\s\S]*?\*/""")

/**
 * Returns [glsl] with its comments removed.
 *
 * Each comment is replaced with the line breaks it contains, or with a space if it has none. Then
 * trailing whitespace is removed from each line.
 */
internal fun stripGlslComments(glsl: String): String =
  glsl
    .replace(GLSL_COMMENT) { comment ->
      "\n".repeat(comment.value.count { it == '\n' }).ifEmpty { " " }
    }
    .lines()
    .joinToString("\n") { it.trimEnd() }
