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
  val externalModules: Map<String, Media3Module> =
    mapOf(
      // go/keep-sorted start
      "lib-cast" to Media3Module("libraries/cast", "media3-cast", "Media3 Cast module"),
      "lib-common" to Media3Module("libraries/common", "media3-common", "Media3 common module"),
      "lib-common-ktx" to
        Media3Module("libraries/common_ktx", "media3-common-ktx", "Media3 common KTX module"),
      "lib-container" to
        Media3Module("libraries/container", "media3-container", "Media3 Container module"),
      "lib-database" to
        Media3Module("libraries/database", "media3-database", "Media3 database module"),
      "lib-datasource" to
        Media3Module("libraries/datasource", "media3-datasource", "Media3 DataSource module"),
      "lib-datasource-cronet" to
        Media3Module(
          "libraries/datasource_cronet",
          "media3-datasource-cronet",
          "Media3 Cronet DataSource module",
        ),
      "lib-datasource-ktor" to
        Media3Module(
          "libraries/datasource_ktor",
          "media3-datasource-ktor",
          "Media3 Ktor DataSource module",
        ),
      "lib-datasource-okhttp" to
        Media3Module(
          "libraries/datasource_okhttp",
          "media3-datasource-okhttp",
          "Media3 OkHttp DataSource module",
        ),
      "lib-datasource-rtmp" to
        Media3Module(
          "libraries/datasource_rtmp",
          "media3-datasource-rtmp",
          "Media3 RTMP DataSource module",
        ),
      "lib-decoder" to Media3Module("libraries/decoder", "media3-decoder", "Media3 decoder module"),
      "lib-decoder-av1" to
        Media3Module("libraries/decoder_av1", "media3-decoder-av1", "Media3 AV1 decoder module"),
      "lib-decoder-ffmpeg" to
        Media3Module(
          "libraries/decoder_ffmpeg",
          "media3-decoder-ffmpeg",
          "Media3 FFmpeg decoder module",
        ),
      "lib-decoder-flac" to
        Media3Module("libraries/decoder_flac", "media3-decoder-flac", "Media3 FLAC decoder module"),
      "lib-decoder-iamf" to
        Media3Module("libraries/decoder_iamf", "media3-decoder-iamf", "Media3 IAMF decoder module"),
      "lib-decoder-midi" to
        Media3Module(
          "libraries/decoder_midi",
          "media3-exoplayer-midi",
          "Media3 MIDI decoder module",
          includeInCompositeBuild = false,
        ),
      "lib-decoder-mpegh" to
        Media3Module(
          "libraries/decoder_mpegh",
          "media3-decoder-mpegh",
          "Media3 MPEG-H decoder module",
        ),
      "lib-decoder-opus" to
        Media3Module("libraries/decoder_opus", "media3-decoder-opus", "Media3 Opus decoder module"),
      "lib-decoder-vp9" to
        Media3Module("libraries/decoder_vp9", "media3-decoder-vp9", "Media3 VP9 decoder module"),
      "lib-effect" to Media3Module("libraries/effect", "media3-effect", "Media3 Effect module"),
      "lib-effect-lottie" to
        Media3Module(
          "libraries/effect_lottie",
          "media3-effect-lottie",
          "Media3 Effect Lottie module",
        ),
      "lib-effect-ndk" to
        Media3Module("libraries/effect_ndk", "media3-effect-ndk", "Media3 Effect NDK module"),
      "lib-exoplayer" to
        Media3Module("libraries/exoplayer", "media3-exoplayer", "Media3 ExoPlayer module"),
      "lib-exoplayer-dash" to
        Media3Module(
          "libraries/exoplayer_dash",
          "media3-exoplayer-dash",
          "Media3 ExoPlayer DASH module",
        ),
      "lib-exoplayer-hls" to
        Media3Module(
          "libraries/exoplayer_hls",
          "media3-exoplayer-hls",
          "Media3 ExoPlayer HLS module",
        ),
      "lib-exoplayer-ima" to
        Media3Module(
          "libraries/exoplayer_ima",
          "media3-exoplayer-ima",
          "Media3 ExoPlayer IMA module",
        ),
      "lib-exoplayer-rtsp" to
        Media3Module(
          "libraries/exoplayer_rtsp",
          "media3-exoplayer-rtsp",
          "Media3 ExoPlayer RTSP module",
        ),
      "lib-exoplayer-smoothstreaming" to
        Media3Module(
          "libraries/exoplayer_smoothstreaming",
          "media3-exoplayer-smoothstreaming",
          "Media3 ExoPlayer SmoothStreaming module",
        ),
      "lib-exoplayer-workmanager" to
        Media3Module(
          "libraries/exoplayer_workmanager",
          "media3-exoplayer-workmanager",
          "Media3 ExoPlayer WorkManager module",
        ),
      "lib-extractor" to
        Media3Module("libraries/extractor", "media3-extractor", "Media3 Extractor module"),
      "lib-inspector" to
        Media3Module("libraries/inspector", "media3-inspector", "Media3 Inspector module"),
      "lib-inspector-frame" to
        Media3Module(
          "libraries/inspector_frame",
          "media3-inspector-frame",
          "Media3 Inspector Frame module",
        ),
      "lib-muxer" to Media3Module("libraries/muxer", "media3-muxer", "Media3 Muxer module"),
      "lib-session" to Media3Module("libraries/session", "media3-session", "Media3 Session module"),
      "lib-transformer" to
        Media3Module("libraries/transformer", "media3-transformer", "Media3 Transformer module"),
      "lib-ui" to Media3Module("libraries/ui", "media3-ui", "Media3 UI module"),
      "lib-ui-compose" to
        Media3Module("libraries/ui_compose", "media3-ui-compose", "Media3 UI Compose module"),
      "lib-ui-compose-material3" to
        Media3Module(
          "libraries/ui_compose_material3",
          "media3-ui-compose-material3",
          "Media3 UI Compose Material3 module",
        ),
      "lib-ui-leanback" to
        Media3Module("libraries/ui_leanback", "media3-ui-leanback", "Media3 Leanback UI module"),
      "test-data" to Media3Module("libraries/test_data", null, null),
      "test-utils" to
        Media3Module("libraries/test_utils", "media3-test-utils", "Media3 test utils module"),
      "test-utils-robolectric" to
        Media3Module(
          "libraries/test_utils_robolectric",
          "media3-test-utils-robolectric",
          "Media3 robolectric test utils module",
        ),
      // go/keep-sorted end
      // go/keep-sorted start
      "demo" to Media3Module("demos/main", includeInCompositeBuild = false),
      "demo-cast" to Media3Module("demos/cast", includeInCompositeBuild = false),
      "demo-compose" to Media3Module("demos/compose", includeInCompositeBuild = false),
      "demo-composition" to Media3Module("demos/composition", includeInCompositeBuild = false),
      "demo-effect" to Media3Module("demos/effect", includeInCompositeBuild = false),
      "demo-gl" to Media3Module("demos/gl", includeInCompositeBuild = false),
      "demo-session" to Media3Module("demos/session", includeInCompositeBuild = false),
      "demo-session-automotive" to
        Media3Module("demos/session_automotive", includeInCompositeBuild = false),
      "demo-session-service" to
        Media3Module("demos/session_service", includeInCompositeBuild = false),
      "demo-shortform" to Media3Module("demos/shortform", includeInCompositeBuild = false),
      "demo-surface" to Media3Module("demos/surface", includeInCompositeBuild = false),
      "demo-transformer" to Media3Module("demos/transformer", includeInCompositeBuild = false),
      "doc-samples" to Media3Module("docsamples", includeInCompositeBuild = false),
      "test-exoplayer-playback" to
        Media3Module("libraries/test_exoplayer_playback", includeInCompositeBuild = false),
      "test-proguard" to Media3Module("libraries/test_proguard", includeInCompositeBuild = false),
      "test-session-common" to
        Media3Module("libraries/test_session_common", includeInCompositeBuild = false),
      "test-session-current" to
        Media3Module("libraries/test_session_current", includeInCompositeBuild = false),
      "testapp-controller" to Media3Module("testapps/controller", includeInCompositeBuild = false),
      // go/keep-sorted end
    )
}
