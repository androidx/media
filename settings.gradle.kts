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

gradle.extra["androidxMediaSettingsDir"] = settingsDir

rootProject.name = "androidx.media3"

gradle.extra["androidxMediaEnableMidiModule"] = true

// All library modules should be configured in core_settings.gradle.kts. Below are
// modules that no app should depend on.
// Demo apps
include(":demo")

project(":demo").projectDir = file("demos/main")

include(":demo-cast")

project(":demo-cast").projectDir = file("demos/cast")

include(":demo-compose")

project(":demo-compose").projectDir = file("demos/compose")

include(":demo-composition")

project(":demo-composition").projectDir = file("demos/composition")

include(":demo-effect")

project(":demo-effect").projectDir = file("demos/effect")

include(":demo-gl")

project(":demo-gl").projectDir = file("demos/gl")

include(":demo-session")

project(":demo-session").projectDir = file("demos/session")

include(":demo-session-service")

project(":demo-session-service").projectDir = file("demos/session_service")

include(":demo-session-automotive")

project(":demo-session-automotive").projectDir = file("demos/session_automotive")

include(":demo-shortform")

project(":demo-shortform").projectDir = file("demos/shortform")

include(":demo-surface")

project(":demo-surface").projectDir = file("demos/surface")

include(":demo-transformer")

project(":demo-transformer").projectDir = file("demos/transformer")

// Modules that only contain tests (not utils used by other test modules)
include(":test-exoplayer-playback")

project(":test-exoplayer-playback").projectDir = file("libraries/test_exoplayer_playback")

include(":test-proguard")

project(":test-proguard").projectDir = file("libraries/test_proguard")

include(":test-session-common")

project(":test-session-common").projectDir = file("libraries/test_session_common")

include(":test-session-current")

project(":test-session-current").projectDir = file("libraries/test_session_current")

// MediaController test app.
include(":testapp-controller")

project(":testapp-controller").projectDir = file("testapps/controller")

// Documentation samples.
include(":doc-samples")

project(":doc-samples").projectDir = file("docsamples")

apply(
  from = "core_settings.gradle.kts"
)
