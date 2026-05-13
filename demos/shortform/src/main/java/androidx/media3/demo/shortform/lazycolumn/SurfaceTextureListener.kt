package androidx.media3.demo.shortform.lazycolumn

import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView

class SurfaceTextureListener {

    private val releasableSurfaceTextures by lazy { mutableListOf<SurfaceTexture>() }

    fun get(
        onSurfaceUpdate: (Surface) -> Unit,
    ) = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(
            surface: SurfaceTexture,
            width: Int,
            height: Int,
        ) {
            onSurfaceUpdate(Surface(surface))
        }

        override fun onSurfaceTextureSizeChanged(
            surface: SurfaceTexture,
            width: Int,
            height: Int,
        ) {
            onSurfaceUpdate(Surface(surface))
        }

        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
            releasableSurfaceTextures.add(surface)
            return false
        }

        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
    }

    fun release() {
        releasableSurfaceTextures.forEach { it.release() }
        releasableSurfaceTextures.clear()
    }
}
