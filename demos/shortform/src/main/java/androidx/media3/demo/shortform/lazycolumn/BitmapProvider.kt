package androidx.media3.demo.shortform.lazycolumn

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build

class BitmapProvider {
    companion object {
        private const val THUMB_EXTRACT_TIME = 1 * 1000L * 1000L
    }

    fun getBitmap(
        ids: List<String?>?,
        metadataRetriever: MediaMetadataRetriever,
    ): Bitmap? {
        if (ids.isNullOrEmpty()) return null
        if (ids.size > 1) {
            val customDataSource = ConcatMediaDataSource(ids)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                metadataRetriever.setDataSource(customDataSource)
            }
        } else {
            metadataRetriever.setDataSource(ids.getOrNull(0))
        }
        return metadataRetriever.getFrameAtTime(0) ?: metadataRetriever.getFrameAtTime(
            THUMB_EXTRACT_TIME
        )
    }
}
