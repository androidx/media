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
pluginManagement {
  repositories {
    google()
    mavenCentral()
    gradlePluginPortal()
  }
}

gradle.extra["androidxMediaModulePrefix"] = ""
gradle.extra["androidxMediaSettingsDir"] = settingsDir

val modulePrefix =
  ":" +
    (gradle.extra.takeIf { it.has("androidxMediaModulePrefix") }?.get("androidxMediaModulePrefix")
      as? String ?: "")

rootProject.name = "androidx.media3"

gradle.extra["rootProjectIsAndroidXMedia3"] = true
gradle.extra["androidxMediaEnableMidiModule"] = true

// All library modules should be configured in core_settings.gradle.kts. Below are
// modules that no app should depend on.

// Demo apps
include("${modulePrefix}demo")
project("${modulePrefix}demo").projectDir = file("demos/main")

include("${modulePrefix}demo-cast")
project("${modulePrefix}demo-cast").projectDir = file("demos/cast")

include("${modulePrefix}demo-compose")
project("${modulePrefix}demo-compose").projectDir = file("demos/compose")

include("${modulePrefix}demo-composition")
project("${modulePrefix}demo-composition").projectDir = file("demos/composition")

include("${modulePrefix}demo-effect")
project("${modulePrefix}demo-effect").projectDir = file("demos/effect")

include("${modulePrefix}demo-gl")
project("${modulePrefix}demo-gl").projectDir = file("demos/gl")

include("${modulePrefix}demo-session")
project("${modulePrefix}demo-session").projectDir = file("demos/session")

include("${modulePrefix}demo-session-service")
project("${modulePrefix}demo-session-service").projectDir = file("demos/session_service")

include("${modulePrefix}demo-session-automotive")
project("${modulePrefix}demo-session-automotive").projectDir = file("demos/session_automotive")

include("${modulePrefix}demo-shortform")
project("${modulePrefix}demo-shortform").projectDir = file("demos/shortform")

include("${modulePrefix}demo-surface")
project("${modulePrefix}demo-surface").projectDir = file("demos/surface")

include("${modulePrefix}demo-transformer")
project("${modulePrefix}demo-transformer").projectDir = file("demos/transformer")

// Modules that only contain tests (not utils used by other test modules)
include("${modulePrefix}test-exoplayer-playback")
project("${modulePrefix}test-exoplayer-playback").projectDir =
  file("libraries/test_exoplayer_playback")

include("${modulePrefix}test-proguard")
project("${modulePrefix}test-proguard").projectDir = file("libraries/test_proguard")

include("${modulePrefix}test-session-common")
project("${modulePrefix}test-session-common").projectDir = file("libraries/test_session_common")

include("${modulePrefix}test-session-current")
project("${modulePrefix}test-session-current").projectDir = file("libraries/test_session_current")

// MediaController test app.
include("${modulePrefix}testapp-controller")
project("${modulePrefix}testapp-controller").projectDir = file("testapps/controller")

// Documentation samples.
include("${modulePrefix}doc-samples")
project("${modulePrefix}doc-samples").projectDir = file("docsamples")

apply(from = "core_settings.gradle.kts")
