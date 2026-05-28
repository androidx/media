/*
 * Copyright 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.media3.ui.compose.material3.text

import android.content.Context
import android.util.Pair
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.ErrorMessageProvider
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.material3.R

@UnstableApi
class DefaultErrorMessageProvider(private val context: Context) :
  ErrorMessageProvider<PlaybackException> {

  override fun getErrorMessage(throwable: PlaybackException): Pair<Int, String> {
    val errorString =
      when (throwable.errorCode) {
        // Policy errors (-1 to -999)
        PlaybackException.ERROR_CODE_INVALID_STATE ->
          context.getString(R.string.media3_error_invalid_state)
        PlaybackException.ERROR_CODE_BAD_VALUE -> context.getString(R.string.media3_error_bad_value)
        PlaybackException.ERROR_CODE_PERMISSION_DENIED ->
          context.getString(R.string.media3_error_permission_denied)
        PlaybackException.ERROR_CODE_NOT_SUPPORTED ->
          context.getString(R.string.media3_error_not_supported)
        PlaybackException.ERROR_CODE_DISCONNECTED ->
          context.getString(R.string.media3_error_disconnected)
        PlaybackException.ERROR_CODE_AUTHENTICATION_EXPIRED ->
          context.getString(R.string.media3_error_authentication_expired)
        PlaybackException.ERROR_CODE_PREMIUM_ACCOUNT_REQUIRED ->
          context.getString(R.string.media3_error_premium_account_required)
        PlaybackException.ERROR_CODE_CONCURRENT_STREAM_LIMIT ->
          context.getString(R.string.media3_error_concurrent_stream_limit)
        PlaybackException.ERROR_CODE_PARENTAL_CONTROL_RESTRICTED ->
          context.getString(R.string.media3_error_parental_control_restricted)
        PlaybackException.ERROR_CODE_NOT_AVAILABLE_IN_REGION ->
          context.getString(R.string.media3_error_not_available_in_region)
        PlaybackException.ERROR_CODE_SKIP_LIMIT_REACHED ->
          context.getString(R.string.media3_error_skip_limit_reached)
        PlaybackException.ERROR_CODE_SETUP_REQUIRED ->
          context.getString(R.string.media3_error_setup_required)
        PlaybackException.ERROR_CODE_END_OF_PLAYLIST ->
          context.getString(R.string.media3_error_end_of_playlist)
        PlaybackException.ERROR_CODE_CONTENT_ALREADY_PLAYING ->
          context.getString(R.string.media3_error_content_already_playing)

        // Miscellaneous errors (1xxx)
        PlaybackException.ERROR_CODE_UNSPECIFIED ->
          context.getString(R.string.media3_error_unspecified)
        PlaybackException.ERROR_CODE_REMOTE_ERROR ->
          context.getString(R.string.media3_error_remote_error)
        PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW ->
          context.getString(R.string.media3_error_behind_live_window)
        PlaybackException.ERROR_CODE_TIMEOUT -> context.getString(R.string.media3_error_timeout)
        PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK ->
          context.getString(R.string.media3_error_failed_runtime_check)

        // Input/Output errors (2xxx)
        PlaybackException.ERROR_CODE_IO_UNSPECIFIED ->
          context.getString(R.string.media3_error_io_unspecified)
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ->
          context.getString(R.string.media3_error_io_network_connection_failed)
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
          context.getString(R.string.media3_error_io_network_connection_timeout)
        PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE ->
          context.getString(R.string.media3_error_io_invalid_http_content_type)
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
          context.getString(R.string.media3_error_io_bad_http_status)
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ->
          context.getString(R.string.media3_error_io_file_not_found)
        PlaybackException.ERROR_CODE_IO_NO_PERMISSION ->
          context.getString(R.string.media3_error_io_no_permission)
        PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED ->
          context.getString(R.string.media3_error_io_cleartext_not_permitted)
        PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE ->
          context.getString(R.string.media3_error_io_read_position_out_of_range)

        // Content parsing errors (3xxx)
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ->
          context.getString(R.string.media3_error_parsing_container_malformed)
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED ->
          context.getString(R.string.media3_error_parsing_manifest_malformed)
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ->
          context.getString(R.string.media3_error_parsing_container_unsupported)
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED ->
          context.getString(R.string.media3_error_parsing_manifest_unsupported)

        // Decoding errors (4xxx)
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ->
          context.getString(R.string.media3_error_decoder_init_failed)
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED ->
          context.getString(R.string.media3_error_decoder_query_failed)
        PlaybackException.ERROR_CODE_DECODING_FAILED ->
          context.getString(R.string.media3_error_decoding_failed)
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES ->
          context.getString(R.string.media3_error_decoding_format_exceeds_capabilities)
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ->
          context.getString(R.string.media3_error_decoding_format_unsupported)
        PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED ->
          context.getString(R.string.media3_error_decoding_resources_reclaimed)

        // AudioTrack errors (5xxx)
        PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED ->
          context.getString(R.string.media3_error_audio_track_init_failed)
        PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED ->
          context.getString(R.string.media3_error_audio_track_write_failed)
        PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_WRITE_FAILED ->
          context.getString(R.string.media3_error_audio_track_offload_write_failed)
        PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_INIT_FAILED ->
          context.getString(R.string.media3_error_audio_track_offload_init_failed)

        // DRM errors (6xxx)
        PlaybackException.ERROR_CODE_DRM_UNSPECIFIED ->
          context.getString(R.string.media3_error_drm_unspecified)
        PlaybackException.ERROR_CODE_DRM_SCHEME_UNSUPPORTED ->
          context.getString(R.string.media3_error_drm_scheme_unsupported)
        PlaybackException.ERROR_CODE_DRM_PROVISIONING_FAILED ->
          context.getString(R.string.media3_error_drm_provisioning_failed)
        PlaybackException.ERROR_CODE_DRM_CONTENT_ERROR ->
          context.getString(R.string.media3_error_drm_content_error)
        PlaybackException.ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED ->
          context.getString(R.string.media3_error_drm_license_acquisition_failed)
        PlaybackException.ERROR_CODE_DRM_DISALLOWED_OPERATION ->
          context.getString(R.string.media3_error_drm_disallowed_operation)
        PlaybackException.ERROR_CODE_DRM_SYSTEM_ERROR ->
          context.getString(R.string.media3_error_drm_system_error)
        PlaybackException.ERROR_CODE_DRM_DEVICE_REVOKED ->
          context.getString(R.string.media3_error_drm_device_revoked)
        PlaybackException.ERROR_CODE_DRM_LICENSE_EXPIRED ->
          context.getString(R.string.media3_error_drm_license_expired)

        // Frame processing errors (7xxx)
        PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSOR_INIT_FAILED ->
          context.getString(R.string.media3_error_video_frame_processor_init_failed)
        PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED ->
          context.getString(R.string.media3_error_video_frame_processing_failed)

        // Fallback for custom or newly added error codes
        else -> context.getString(R.string.media3_error_unspecified)
      }

    return Pair(0, errorString)
  }
}

@UnstableApi
@Composable
fun rememberDefaultErrorMessageProvider(): ErrorMessageProvider<PlaybackException> {
  val context = LocalContext.current
  return remember(context) { DefaultErrorMessageProvider(context) }
}
