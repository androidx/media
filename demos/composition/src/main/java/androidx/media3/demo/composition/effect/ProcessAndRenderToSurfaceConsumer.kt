/*
 * Copyright 2025 The Android Open Source Project
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
package androidx.media3.demo.composition.effect

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build.VERSION.SDK_INT
import android.view.SurfaceHolder
import androidx.media3.common.GlObjectsProvider
import androidx.media3.common.util.Consumer
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.HardwareBufferFrame
import androidx.media3.effect.PacketConsumer
import androidx.media3.effect.PacketConsumer.Packet
import java.util.concurrent.ExecutorService

// TODO: b/449957627 - Remove once pipeline has been migrated to PacketConsumer interface.
/**
 * A [PacketConsumer] that renders a [Packet] of [HardwareBufferFrame]s to a [SurfaceHolder] by
 * tiling them horizontally on the [output canvas][SurfaceHolder.lockHardwareCanvas].
 */
@ExperimentalApi
@UnstableApi
internal class ProcessAndRenderToSurfaceConsumer
private constructor(private val output: SurfaceHolder?) :
  PacketConsumer<List<HardwareBufferFrame>> {

  /** [PacketConsumer.Factory] for creating [ProcessAndRenderToSurfaceConsumer] instances. */
  class Factory(
    private val context: Context,
    private val glExecutorService: ExecutorService,
    private val glObjectsProvider: GlObjectsProvider,
    private val errorListener: Consumer<Exception>,
  ) : PacketConsumer.Factory<List<HardwareBufferFrame>> {
    private var output: SurfaceHolder? = null

    override fun create(): PacketConsumer<List<HardwareBufferFrame>> {
      return ProcessAndRenderToSurfaceConsumer(output)
    }

    fun setOutput(output: SurfaceHolder?) {
      this.output = output
    }
  }

  var last: List<HardwareBufferFrame>? = null

  // PacketConsumer implementation.

  override suspend fun queuePacket(packet: Packet<List<HardwareBufferFrame>>) {
    if ((SDK_INT >= 29) && (packet is Packet.Payload)) {
      output?.lockHardwareCanvas()?.let { canvas ->
        packet.payload.forEachIndexed { index, frame ->
          Bitmap.wrapHardwareBuffer(frame.hardwareBuffer!!, null)?.let { bitmap ->
            val width = canvas.clipBounds.width() / packet.payload.size
            val left = index * width
            val right = (index + 1) * width
            val dst = Rect(left, canvas.clipBounds.top, right, canvas.clipBounds.bottom)
            canvas.save()
            if (frame.format.rotationDegrees != 0) {
              canvas.rotate(
                frame.format.rotationDegrees.toFloat(),
                dst.exactCenterX(),
                dst.exactCenterY(),
              )
            }
            canvas.drawBitmap(bitmap, null, dst, Paint())
            canvas.restore()
          }
        }
        output.unlockCanvasAndPost(canvas)
      }
    }
    // Release the previous packet to double buffer and prevent the decoder from overriding the
    // screen contents.
    last?.forEach { it.release() }
    last = (packet as? Packet.Payload)?.payload
  }

  override suspend fun release() {
    last?.forEach { it.release() }
  }
}
