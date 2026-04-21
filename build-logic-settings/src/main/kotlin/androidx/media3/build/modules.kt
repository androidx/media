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

object Media3Modules {
  val libraryModules: Map<String, Media3Module> =
    mapOf(
      // go/keep-sorted start
      "lib-cast" to Media3Module("media3-cast"),
      "lib-common" to Media3Module("media3-common"),
      "lib-common-ktx" to Media3Module("media3-common-ktx"),
      "lib-container" to Media3Module("media3-container"),
      "lib-database" to Media3Module("media3-database"),
      "lib-datasource" to Media3Module("media3-datasource"),
      "lib-datasource-cronet" to Media3Module("media3-datasource-cronet"),
      "lib-datasource-ktor" to Media3Module("media3-datasource-ktor"),
      "lib-datasource-okhttp" to Media3Module("media3-datasource-okhttp"),
      "lib-datasource-rtmp" to Media3Module("media3-datasource-rtmp"),
      "lib-decoder" to Media3Module("media3-decoder"),
      "lib-decoder-av1" to Media3Module("media3-decoder-av1"),
      "lib-decoder-ffmpeg" to Media3Module("media3-decoder-ffmpeg"),
      "lib-decoder-flac" to Media3Module("media3-decoder-flac"),
      "lib-decoder-iamf" to Media3Module("media3-decoder-iamf"),
      "lib-decoder-midi" to Media3Module("media3-exoplayer-midi"),
      "lib-decoder-mpegh" to Media3Module("media3-decoder-mpegh"),
      "lib-decoder-opus" to Media3Module("media3-decoder-opus"),
      "lib-decoder-vp9" to Media3Module("media3-decoder-vp9"),
      "lib-effect" to Media3Module("media3-effect"),
      "lib-effect-lottie" to Media3Module("media3-effect-lottie"),
      "lib-effect-ndk" to Media3Module("media3-effect-ndk"),
      "lib-exoplayer" to Media3Module("media3-exoplayer"),
      "lib-exoplayer-dash" to Media3Module("media3-exoplayer-dash"),
      "lib-exoplayer-hls" to Media3Module("media3-exoplayer-hls"),
      "lib-exoplayer-ima" to Media3Module("media3-exoplayer-ima"),
      "lib-exoplayer-rtsp" to Media3Module("media3-exoplayer-rtsp"),
      "lib-exoplayer-smoothstreaming" to Media3Module("media3-exoplayer-smoothstreaming"),
      "lib-exoplayer-workmanager" to Media3Module("media3-exoplayer-workmanager"),
      "lib-extractor" to Media3Module("media3-extractor"),
      "lib-inspector" to Media3Module("media3-inspector"),
      "lib-inspector-frame" to Media3Module("media3-inspector-frame"),
      "lib-muxer" to Media3Module("media3-muxer"),
      "lib-session" to Media3Module("media3-session"),
      "lib-transformer" to Media3Module("media3-transformer"),
      "lib-ui" to Media3Module("media3-ui"),
      "lib-ui-compose" to Media3Module("media3-ui-compose"),
      "lib-ui-compose-material3" to Media3Module("media3-ui-compose-material3"),
      "lib-ui-leanback" to Media3Module("media3-ui-leanback"),
      "test-data" to Media3Module(),
      "test-utils" to Media3Module("media3-test-utils"),
      "test-utils-robolectric" to Media3Module("media3-test-utils-robolectric"),
      // go/keep-sorted end
    )
}
