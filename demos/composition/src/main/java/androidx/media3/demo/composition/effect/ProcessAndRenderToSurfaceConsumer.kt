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
import android.os.Build.VERSION.SDK_INT
import android.view.SurfaceHolder
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.media3.common.GlObjectsProvider
import androidx.media3.common.SurfaceInfo
import androidx.media3.common.util.Consumer
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.GlTextureFrameRenderer
import androidx.media3.effect.HardwareBufferFrame
import androidx.media3.effect.PacketConsumer
import androidx.media3.effect.PacketConsumer.Packet
import androidx.media3.effect.ndk.HardwareBufferToGlTextureFrameProcessor
import com.google.common.util.concurrent.MoreExecutors.listeningDecorator
import java.util.concurrent.ExecutorService

// TODO: b/449957627 - Remove once pipeline has been migrated to PacketConsumer interface.
/**
 * A [PacketConsumer] that renders a [Packet] of [HardwareBufferFrame]s to a [SurfaceHolder] by
 * using a [HardwareBufferToGlTextureFrameProcessor] and [GlTextureFrameRenderer].
 */
@RequiresApi(26)
@OptIn(UnstableApi::class, ExperimentalApi::class)
internal class ProcessAndRenderToSurfaceConsumer
private constructor(
  context: Context,
  glExecutorService: ExecutorService,
  glObjectsProvider: GlObjectsProvider,
  output: SurfaceHolder?,
) : PacketConsumer<List<HardwareBufferFrame>>, SurfaceHolder.Callback {

  private val glTextureFrameRenderer: GlTextureFrameRenderer
  private val hardwareBufferToGlTextureFrameProcessor: HardwareBufferToGlTextureFrameProcessor

  init {
    val errorListener = Consumer<Exception> { t -> throw IllegalArgumentException(t) }
    glTextureFrameRenderer =
      GlTextureFrameRenderer.create(
        context,
        listeningDecorator(glExecutorService),
        glObjectsProvider,
        { vfpException -> errorListener.accept(vfpException) },
        GlTextureFrameRenderer.Listener.NO_OP,
      )
    hardwareBufferToGlTextureFrameProcessor =
      HardwareBufferToGlTextureFrameProcessor(
        context,
        glExecutorService,
        glObjectsProvider,
        errorListener,
      )
    hardwareBufferToGlTextureFrameProcessor.setOutput(glTextureFrameRenderer)
    output?.addCallback(this)
  }

  /** [PacketConsumer.Factory] for creating [ProcessAndRenderToSurfaceConsumer] instances. */
  class Factory(
    private val context: Context,
    private val glExecutorService: ExecutorService,
    private val glObjectsProvider: GlObjectsProvider,
    private val errorListener: Consumer<Exception>,
  ) : PacketConsumer.Factory<List<HardwareBufferFrame>> {
    private var output: SurfaceHolder? = null

    override fun create(): PacketConsumer<List<HardwareBufferFrame>> {
      return ProcessAndRenderToSurfaceConsumer(
        context,
        glExecutorService,
        glObjectsProvider,
        output,
      )
    }

    fun setOutput(output: SurfaceHolder?) {
      this.output = output
    }
  }

  // PacketConsumer implementation.

  override suspend fun queuePacket(packet: Packet<List<HardwareBufferFrame>>) {
    if ((SDK_INT >= 29) && (packet is Packet.Payload)) {
      // TODO: b/474075198 - Add multi-sequence support.
      hardwareBufferToGlTextureFrameProcessor.queuePacket(Packet.of(packet.payload[0]))
      for (i in 1..<packet.payload.size) {
        packet.payload[i].release()
      }
    }
  }

  override suspend fun release() {
    hardwareBufferToGlTextureFrameProcessor.release()
    glTextureFrameRenderer.release()
  }

  override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
    glTextureFrameRenderer.setOutputSurfaceInfo(SurfaceInfo(holder.surface, width, height))
  }

  override fun surfaceCreated(holder: SurfaceHolder) {}

  override fun surfaceDestroyed(holder: SurfaceHolder) {
    glTextureFrameRenderer.setOutputSurfaceInfo(null)
  }
}
