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

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/** Validates that Dackka warnings match [baselineFile] with no unexpected or stale entries. */
@CacheableTask
internal abstract class ValidateDocsWarningsTask : DefaultTask() {

  /** Log file produced by [DackkaDocsTask]. */
  @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val logFile: RegularFileProperty

  /** Baseline regex file listing known-acceptable `WARN:` lines. */
  @get:InputFile
  @get:PathSensitive(PathSensitivity.NONE)
  abstract val baselineFile: RegularFileProperty

  /** Stamp file written on success to enable Gradle task up-to-date and caching checks. */
  @get:OutputFile abstract val stampFile: RegularFileProperty

  @TaskAction
  fun validateWarnings() {
    val baselinePatterns =
      baselineFile.get().asFile.useLines { lines ->
        lines
          .map { it.trim() }
          .filter { it.isNotEmpty() && !it.startsWith("#") }
          .map { Regex(it) }
          .toList()
      }

    val warnings = logFile.get().asFile.readLines().filter { it.isNotEmpty() }

    val unexpectedWarnings = warnings.filter { warning ->
      baselinePatterns.none { pattern -> pattern.matches(warning) }
    }
    val unusedPatterns = baselinePatterns.filter { pattern ->
      warnings.none { warning -> pattern.matches(warning) }
    }

    if (unexpectedWarnings.isNotEmpty() || unusedPatterns.isNotEmpty()) {
      throw GradleException(
        buildString {
          if (unexpectedWarnings.isNotEmpty()) {
            appendLine(
              "Found ${unexpectedWarnings.size} unexpected Dackka warning(s)/error(s). " +
                "These issues should be fixed in source if possible:"
            )
            for (warning in unexpectedWarnings) {
              appendLine("  $warning")
            }
            appendLine()
            appendLine(
              "If it is impossible to fix a warning in source (e.g. due to an upstream " +
                "Dackka/Dokka issue), add a regex pattern to gradle/dackka-warnings-baseline.txt."
            )
          }
          if (unusedPatterns.isNotEmpty()) {
            if (isNotEmpty()) {
              appendLine()
            }
            appendLine(
              "Found ${unusedPatterns.size} unused pattern(s) in " +
                "gradle/dackka-warnings-baseline.txt that matched no Dackka warnings. " +
                "Remove stale baseline entries:"
            )
            for (pattern in unusedPatterns) {
              appendLine("  ${pattern.pattern}")
            }
          }
          appendLine()
          appendLine("To reproduce locally, run: ./gradlew validateDocsWarnings")
        }
      )
    }

    val stamp = stampFile.get().asFile
    stamp.parentFile.mkdirs()
    stamp.writeText("Validated ${warnings.size} warning(s) against baseline.\n")
  }
}
