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
      "lib-cast" to Media3Module("libraries/cast", "media3-cast"),
      "lib-common" to Media3Module("libraries/common", "media3-common"),
      "lib-common-ktx" to Media3Module("libraries/common_ktx", "media3-common-ktx"),
      "lib-container" to Media3Module("libraries/container", "media3-container"),
      "lib-database" to Media3Module("libraries/database", "media3-database"),
      "lib-datasource" to Media3Module("libraries/datasource", "media3-datasource"),
      "lib-datasource-cronet" to
        Media3Module("libraries/datasource_cronet", "media3-datasource-cronet"),
      "lib-datasource-ktor" to Media3Module("libraries/datasource_ktor", "media3-datasource-ktor"),
      "lib-datasource-okhttp" to
        Media3Module("libraries/datasource_okhttp", "media3-datasource-okhttp"),
      "lib-datasource-rtmp" to Media3Module("libraries/datasource_rtmp", "media3-datasource-rtmp"),
      "lib-decoder" to Media3Module("libraries/decoder", "media3-decoder"),
      "lib-decoder-av1" to Media3Module("libraries/decoder_av1", "media3-decoder-av1"),
      "lib-decoder-ffmpeg" to Media3Module("libraries/decoder_ffmpeg", "media3-decoder-ffmpeg"),
      "lib-decoder-flac" to Media3Module("libraries/decoder_flac", "media3-decoder-flac"),
      "lib-decoder-iamf" to Media3Module("libraries/decoder_iamf", "media3-decoder-iamf"),
      "lib-decoder-midi" to
        Media3Module(
          "libraries/decoder_midi",
          "media3-exoplayer-midi",
          includeInCompositeBuild = false,
        ),
      "lib-decoder-mpegh" to Media3Module("libraries/decoder_mpegh", "media3-decoder-mpegh"),
      "lib-decoder-opus" to Media3Module("libraries/decoder_opus", "media3-decoder-opus"),
      "lib-decoder-vp9" to Media3Module("libraries/decoder_vp9", "media3-decoder-vp9"),
      "lib-effect" to Media3Module("libraries/effect", "media3-effect"),
      "lib-effect-lottie" to Media3Module("libraries/effect_lottie", "media3-effect-lottie"),
      "lib-effect-ndk" to Media3Module("libraries/effect_ndk", "media3-effect-ndk"),
      "lib-exoplayer" to Media3Module("libraries/exoplayer", "media3-exoplayer"),
      "lib-exoplayer-dash" to Media3Module("libraries/exoplayer_dash", "media3-exoplayer-dash"),
      "lib-exoplayer-hls" to Media3Module("libraries/exoplayer_hls", "media3-exoplayer-hls"),
      "lib-exoplayer-ima" to Media3Module("libraries/exoplayer_ima", "media3-exoplayer-ima"),
      "lib-exoplayer-rtsp" to Media3Module("libraries/exoplayer_rtsp", "media3-exoplayer-rtsp"),
      "lib-exoplayer-smoothstreaming" to
        Media3Module("libraries/exoplayer_smoothstreaming", "media3-exoplayer-smoothstreaming"),
      "lib-exoplayer-workmanager" to
        Media3Module("libraries/exoplayer_workmanager", "media3-exoplayer-workmanager"),
      "lib-extractor" to Media3Module("libraries/extractor", "media3-extractor"),
      "lib-inspector" to Media3Module("libraries/inspector", "media3-inspector"),
      "lib-inspector-frame" to Media3Module("libraries/inspector_frame", "media3-inspector-frame"),
      "lib-muxer" to Media3Module("libraries/muxer", "media3-muxer"),
      "lib-session" to Media3Module("libraries/session", "media3-session"),
      "lib-transformer" to Media3Module("libraries/transformer", "media3-transformer"),
      "lib-ui" to Media3Module("libraries/ui", "media3-ui"),
      "lib-ui-compose" to Media3Module("libraries/ui_compose", "media3-ui-compose"),
      "lib-ui-compose-material3" to
        Media3Module("libraries/ui_compose_material3", "media3-ui-compose-material3"),
      "lib-ui-leanback" to Media3Module("libraries/ui_leanback", "media3-ui-leanback"),
      "test-data" to Media3Module("libraries/test_data", null),
      "test-utils" to Media3Module("libraries/test_utils", "media3-test-utils"),
      "test-utils-robolectric" to
        Media3Module("libraries/test_utils_robolectric", "media3-test-utils-robolectric"),
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
