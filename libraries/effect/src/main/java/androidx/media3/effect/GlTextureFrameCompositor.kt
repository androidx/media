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
package androidx.media3.effect

import android.content.Context
import androidx.annotation.RestrictTo
import androidx.media3.common.GlObjectsProvider
import androidx.media3.common.GlTextureInfo
import androidx.media3.common.VideoCompositorSettings
import androidx.media3.common.util.Size
import androidx.media3.effect.DefaultCompositorGlProgram.InputFrameInfo
import androidx.media3.effect.PacketConsumer.Packet
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.withContext

/**
 * A [PacketProcessor] implementation that composites a [List] of [GlTextureFrame]s into a
 * [GlTextureFrame].
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
class GlTextureFrameCompositor(
  context: Context,
  private val dispatcher: CoroutineDispatcher,
  private val glObjectsProvider: GlObjectsProvider,
  @Volatile var videoCompositorSettings: VideoCompositorSettings? = null,
) :
  RunnablePacketConsumer<List<GlTextureFrame>>,
  PacketProcessor<List<GlTextureFrame>, GlTextureFrame> {

  private val inputConsumer = ChannelPacketConsumer(this::consume, this::releaseFrames)
  private val glProgram = DefaultCompositorGlProgram(context)
  // TODO: b/449957627 - Handle HDR.
  private val outputTexturePool: TexturePool =
    TexturePool(/* useHighPrecisionColorComponents= */ false, /* capacity= */ 1)
  @Volatile private var outputConsumer: PacketConsumer<GlTextureFrame>? = null

  override fun tryQueuePacket(packet: Packet<List<GlTextureFrame>>): Boolean {
    return inputConsumer.tryQueuePacket(packet)
  }

  override suspend fun queuePacket(packet: Packet<List<GlTextureFrame>>) {
    return inputConsumer.queuePacket(packet)
  }

  override suspend fun run() {
    // Switch to the GL thread for the run loop.
    withContext(dispatcher) { inputConsumer.run() }
  }

  override fun setOutput(output: PacketConsumer<GlTextureFrame>) {
    this.outputConsumer = output
  }

  override suspend fun release() {
    inputConsumer.release()
    // Release the internal glProgram on the GL thread.
    withContext(dispatcher) { glProgram.release() }
  }

  private suspend fun consume(frames: List<GlTextureFrame>) {
    var compositedFrame: GlTextureFrame? = null
    try {
      // TODO: b/449957627 - Investigate whether blocking until an output is set is a safer
      // approach.
      outputConsumer?.let { outputConsumer ->
        compositedFrame = compositeFrames(frames)
        outputConsumer.queuePacket(Packet.of(compositedFrame))
      }
    } finally {
      compositedFrame?.release()
    }
  }

  private fun compositeFrames(frames: List<GlTextureFrame>): GlTextureFrame {
    val inputSizes = mutableListOf<Size>()
    val framesToComposite = mutableListOf<InputFrameInfo>()

    frames.forEachIndexed { i, frame ->
      val texture: GlTextureInfo = frame.glTextureInfo
      inputSizes.add(Size(texture.width, texture.height))
      framesToComposite.add(
        InputFrameInfo(
          texture,
          checkNotNull(videoCompositorSettings)
            .getOverlaySettings(/* inputId= */ i, frame.presentationTimeUs),
        )
      )
    }
    val outputSize = checkNotNull(videoCompositorSettings).getOutputSize(inputSizes)
    outputTexturePool.ensureConfigured(glObjectsProvider, outputSize.width, outputSize.height)

    val outputTexture = outputTexturePool.useTexture()
    glProgram.drawFrame(framesToComposite, outputTexture)
    // TODO: b/449957627 - Does this need a sync fence?
    return GlTextureFrame.Builder(outputTexture, dispatcher.asExecutor()) { c ->
        // TODO: b/459374133 - Remove dependency on GlUtil in TexturePool so this can be unit
        // tested.
        if (outputTexturePool.freeTextureCount() < outputTexturePool.capacity()) {
          outputTexturePool.freeTexture()
        }
      }
      .setMetadata(frames[0].metadata)
      .setFormat(frames[0].format)
      .setPresentationTimeUs(frames[0].presentationTimeUs)
      .setReleaseTimeNs(frames[0].releaseTimeNs)
      .build()
  }

  private fun releaseFrames(frames: List<GlTextureFrame>) {
    for (frame in frames) {
      frame.release()
    }
  }
}
