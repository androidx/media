// Copyright (C) 2017 The Android Open Source Project
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
if (!gradle.extra.has("androidxMediaSettingsDir")) {
  gradle.extra["androidxMediaSettingsDir"] = rootDir.canonicalPath
}

val modulePrefix =
  ":" +
    (gradle.extra.takeIf { it.has("androidxMediaModulePrefix") }?.get("androidxMediaModulePrefix")
      as? String ?: "")

include("${modulePrefix}lib-common")
project("${modulePrefix}lib-common").projectDir = file("libraries/common")

include("${modulePrefix}lib-common-ktx")
project("${modulePrefix}lib-common-ktx").projectDir = file("libraries/common_ktx")

include("${modulePrefix}lib-container")
project("${modulePrefix}lib-container").projectDir = file("libraries/container")

include("${modulePrefix}lib-session")
project("${modulePrefix}lib-session").projectDir = file("libraries/session")

include("${modulePrefix}lib-exoplayer")
project("${modulePrefix}lib-exoplayer").projectDir = file("libraries/exoplayer")
include("${modulePrefix}lib-exoplayer-dash")
project("${modulePrefix}lib-exoplayer-dash").projectDir = file("libraries/exoplayer_dash")
include("${modulePrefix}lib-exoplayer-hls")
project("${modulePrefix}lib-exoplayer-hls").projectDir = file("libraries/exoplayer_hls")
include("${modulePrefix}lib-exoplayer-rtsp")
project("${modulePrefix}lib-exoplayer-rtsp").projectDir = file("libraries/exoplayer_rtsp")
include("${modulePrefix}lib-exoplayer-smoothstreaming")
project("${modulePrefix}lib-exoplayer-smoothstreaming").projectDir =
  file("libraries/exoplayer_smoothstreaming")
include("${modulePrefix}lib-exoplayer-ima")
project("${modulePrefix}lib-exoplayer-ima").projectDir = file("libraries/exoplayer_ima")
include("${modulePrefix}lib-exoplayer-workmanager")
project("${modulePrefix}lib-exoplayer-workmanager").projectDir =
  file("libraries/exoplayer_workmanager")

include("${modulePrefix}lib-ui")
project("${modulePrefix}lib-ui").projectDir = file("libraries/ui")
include("${modulePrefix}lib-ui-leanback")
project("${modulePrefix}lib-ui-leanback").projectDir = file("libraries/ui_leanback")
include("${modulePrefix}lib-ui-compose")
project("${modulePrefix}lib-ui-compose").projectDir = file("libraries/ui_compose")
include("${modulePrefix}lib-ui-compose-material3")
project("${modulePrefix}lib-ui-compose-material3").projectDir =
  file("libraries/ui_compose_material3")

include("${modulePrefix}lib-database")
project("${modulePrefix}lib-database").projectDir = file("libraries/database")

include("${modulePrefix}lib-datasource")
project("${modulePrefix}lib-datasource").projectDir = file("libraries/datasource")
include("${modulePrefix}lib-datasource-cronet")
project("${modulePrefix}lib-datasource-cronet").projectDir = file("libraries/datasource_cronet")
include("${modulePrefix}lib-datasource-rtmp")
project("${modulePrefix}lib-datasource-rtmp").projectDir = file("libraries/datasource_rtmp")
include("${modulePrefix}lib-datasource-okhttp")
project("${modulePrefix}lib-datasource-okhttp").projectDir = file("libraries/datasource_okhttp")

include("${modulePrefix}lib-decoder")
project("${modulePrefix}lib-decoder").projectDir = file("libraries/decoder")
include("${modulePrefix}lib-decoder-av1")
project("${modulePrefix}lib-decoder-av1").projectDir = file("libraries/decoder_av1")
include("${modulePrefix}lib-decoder-ffmpeg")
project("${modulePrefix}lib-decoder-ffmpeg").projectDir = file("libraries/decoder_ffmpeg")
include("${modulePrefix}lib-decoder-flac")
project("${modulePrefix}lib-decoder-flac").projectDir = file("libraries/decoder_flac")
include("${modulePrefix}lib-decoder-iamf")
project("${modulePrefix}lib-decoder-iamf").projectDir = file("libraries/decoder_iamf")
if (
  gradle.extra.has("androidxMediaEnableMidiModule") &&
    gradle.extra["androidxMediaEnableMidiModule"] as Boolean
) {
  include("${modulePrefix}lib-decoder-midi")
  project("${modulePrefix}lib-decoder-midi").projectDir = file("libraries/decoder_midi")
}
include("${modulePrefix}lib-decoder-mpegh")
project("${modulePrefix}lib-decoder-mpegh").projectDir = file("libraries/decoder_mpegh")
include("${modulePrefix}lib-decoder-opus")
project("${modulePrefix}lib-decoder-opus").projectDir = file("libraries/decoder_opus")
include("${modulePrefix}lib-decoder-vp9")
project("${modulePrefix}lib-decoder-vp9").projectDir = file("libraries/decoder_vp9")

include("${modulePrefix}lib-extractor")
project("${modulePrefix}lib-extractor").projectDir = file("libraries/extractor")
include("${modulePrefix}lib-cast")
project("${modulePrefix}lib-cast").projectDir = file("libraries/cast")

include("${modulePrefix}lib-effect")
project("${modulePrefix}lib-effect").projectDir = file("libraries/effect")

include("${modulePrefix}lib-effect-lottie")
project("${modulePrefix}lib-effect-lottie").projectDir = file("libraries/effect_lottie")

include("${modulePrefix}lib-effect-ndk")
project("${modulePrefix}lib-effect-ndk").projectDir = file("libraries/effect_ndk")

include("${modulePrefix}lib-inspector")
project("${modulePrefix}lib-inspector").projectDir = file("libraries/inspector")

include("${modulePrefix}lib-inspector-frame")
project("${modulePrefix}lib-inspector-frame").projectDir = file("libraries/inspector_frame")

include("${modulePrefix}lib-muxer")
project("${modulePrefix}lib-muxer").projectDir = file("libraries/muxer")

include("${modulePrefix}lib-transformer")
project("${modulePrefix}lib-transformer").projectDir = file("libraries/transformer")

include("${modulePrefix}test-utils-robolectric")
project("${modulePrefix}test-utils-robolectric").projectDir =
  file("libraries/test_utils_robolectric")

include("${modulePrefix}test-data")
project("${modulePrefix}test-data").projectDir = file("libraries/test_data")

include("${modulePrefix}test-utils")
project("${modulePrefix}test-utils").projectDir =
  file(
    "libraries/test_utils"
  )
