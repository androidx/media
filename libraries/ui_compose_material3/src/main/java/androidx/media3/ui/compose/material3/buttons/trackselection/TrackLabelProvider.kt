package androidx.media3.ui.compose.material3.buttons.trackselection

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.material3.R
import java.util.Locale

/**
 * Returns a localized name for an audio track, including its language and bitrate.
 *
 * @param format The [Format] of the audio track.
 * @return A string representation of the track name (e.g. "English • 128 kbps").
 */
@UnstableApi
@Composable
internal fun getAudioTrackLabel(format: Format): String {
    val language = format.language
    val locale = if (language == null || language == C.LANGUAGE_UNDETERMINED) {
        null
    } else {
        Locale.forLanguageTag(language)
    }
    val languageName = locale?.displayName ?: stringResource(R.string.track_selection_unknown)
    val label = format.label ?: languageName
    return buildString {
        append(label)
        if (format.bitrate != Format.NO_VALUE) {
            append(" • ${format.bitrate / 1000} kbps")
        }
    }
}

/**
 * Returns a localized label for a video track, including its resolution.
 *
 * @param format The [Format] of the video track.
 * @return A string representation of the track label (e.g. "1920 × 1080").
 */
@UnstableApi
@Composable
internal fun getVideoTrackLabel(format: Format): String {
    val width = format.width
    val height = format.height
    return if (width == Format.NO_VALUE || height == Format.NO_VALUE) {
        stringResource(R.string.track_selection_unknown)
    } else {
        "$width × $height"
    }
}
