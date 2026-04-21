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

import org.gradle.api.Project
import org.gradle.kotlin.dsl.extra

/**
 * Extension property to allow all build.gradle.kts files to access the 'releaseVersion' extra
 * property directly.
 */
val Project.releaseVersion: String
  get() = extra["releaseVersion"] as String

/**
 * Extension property to allow all build.gradle.kts files to access the 'releaseVersionCode' extra
 * property directly.
 */
val Project.releaseVersionCode: Int
  get() = extra["releaseVersionCode"] as Int
