/*
 * Copyright 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.demo.composition

import android.content.Context
import android.os.Build.VERSION.SDK_INT
import androidx.annotation.GuardedBy
import androidx.annotation.RequiresApi
import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.ColorInfo.SDR_BT709_LIMITED
import androidx.media3.common.Format
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.util.Log
import androidx.media3.common.video.AsyncFrame
import androidx.media3.common.video.Frame
import androidx.media3.common.video.FrameProcessor
import androidx.media3.common.video.FrameWriter
import androidx.media3.common.video.HardwareBufferFrame
import androidx.media3.common.video.SyncFenceWrapper
import androidx.webgpu.BlendFactor
import androidx.webgpu.BlendOperation
import androidx.webgpu.ColorSpacePrimariesDawn
import androidx.webgpu.ColorSpaceTransferDawn
import androidx.webgpu.ColorSpaceYCbCrMatrixDawn
import androidx.webgpu.ColorSpaceYCbCrRangeDawn
import androidx.webgpu.DeviceLostCallback
import androidx.webgpu.DeviceLostReason
import androidx.webgpu.ExperimentalWebGpuApi
import androidx.webgpu.ExternalTextureRotation
import androidx.webgpu.FeatureName
import androidx.webgpu.GPUBindGroupDescriptor
import androidx.webgpu.GPUBindGroupEntry
import androidx.webgpu.GPUBindGroupLayoutDescriptor
import androidx.webgpu.GPUBindGroupLayoutEntry
import androidx.webgpu.GPUBlendComponent
import androidx.webgpu.GPUBlendState
import androidx.webgpu.GPUColor
import androidx.webgpu.GPUColorSpaceDawn
import androidx.webgpu.GPUColorTargetState
import androidx.webgpu.GPUDawnTogglesDescriptor
import androidx.webgpu.GPUDevice
import androidx.webgpu.GPUDeviceDescriptor
import androidx.webgpu.GPUExtent2D
import androidx.webgpu.GPUExternalTextureBindingEntry
import androidx.webgpu.GPUExternalTextureBindingLayout
import androidx.webgpu.GPUExternalTextureDescriptor
import androidx.webgpu.GPUFragmentState
import androidx.webgpu.GPUHardwareBufferExternalTexture
import androidx.webgpu.GPUInstanceDescriptor
import androidx.webgpu.GPUPipelineLayoutDescriptor
import androidx.webgpu.GPUPrimitiveState
import androidx.webgpu.GPURenderPassColorAttachment
import androidx.webgpu.GPURenderPassDescriptor
import androidx.webgpu.GPURenderPipeline
import androidx.webgpu.GPURenderPipelineDescriptor
import androidx.webgpu.GPUSampler
import androidx.webgpu.GPUSamplerBindingLayout
import androidx.webgpu.GPUShaderModuleDescriptor
import androidx.webgpu.GPUShaderSourceWGSL
import androidx.webgpu.GPUSyncFence
import androidx.webgpu.GPUVertexState
import androidx.webgpu.LoadOp
import androidx.webgpu.PredefinedColorSpace
import androidx.webgpu.PrimitiveTopology
import androidx.webgpu.ShaderStage
import androidx.webgpu.StoreOp
import androidx.webgpu.TextureFormat
import androidx.webgpu.TextureUsage
import androidx.webgpu.UncapturedErrorCallback
import androidx.webgpu.helper.GPUAndroidHardwareBufferUtil
import androidx.webgpu.helper.WebGpu
import androidx.webgpu.helper.createWebGpu
import androidx.webgpu.helper.toSyncFence
import com.google.common.util.concurrent.MoreExecutors.directExecutor
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * A [FrameProcessor] that composites the [HardwareBufferFrame]s from the input packet onto the
 * output [HardwareBufferFrame] using WebGPU.
 */
@RequiresApi(28)
@androidx.annotation.OptIn(ExperimentalApi::class)
@OptIn(ExperimentalWebGpuApi::class)
class WebGPUHardwareBufferEffectsPipeline
private constructor(
  private val context: Context,
  private val webGpu: WebGpu,
  private val forwardSyncFences: Boolean,
  private val output: FrameWriter,
  private val listenerExecutor: Executor,
  private val listener: FrameProcessor.Listener,
) : FrameProcessor {

  class Factory
  @JvmOverloads
  constructor(private val context: Context, private val forwardSyncFences: Boolean = true) :
    FrameProcessor.Factory {
    override fun create(
      output: FrameWriter,
      listenerExecutor: Executor,
      listener: FrameProcessor.Listener,
    ): FrameProcessor {
      val webGpu = createDefaultWebGpu()
      try {
        return WebGPUHardwareBufferEffectsPipeline(
          context,
          webGpu,
          forwardSyncFences,
          output,
          listenerExecutor,
          listener,
        )
      } catch (t: Throwable) {
        webGpu.close()
        throw t
      }
    }
  }

  private val device: GPUDevice = webGpu.device

  /** Coroutine scope bound to WebGPU thread, used for all internal operations. */
  private val pipelineScope = CoroutineScope(webGpu.dispatcher + Job())

  /**
   * Helper wrapper method to run asynchronous WebGPU tasks on the pipeline scope with centralized
   * try/catch exception-handling logic.
   */
  private fun launchWithExceptionHandling(block: suspend () -> Unit) {
    pipelineScope.launch {
      try {
        block()
      } catch (t: Throwable) {
        val exception =
          t as? VideoFrameProcessingException
            ?: VideoFrameProcessingException("Error occurred in WebGPU processing pipeline", t)

        // Forward the exception to the media framework listener
        listenerExecutor.execute { listener.onError(exception) }
      }
    }
  }

  private val isReleased = AtomicBoolean(false)

  private val lock = Any()
  @GuardedBy("lock") private var needsWakeup = false
  @GuardedBy("lock") private var hasPendingTask = false
  private var endOfStreamSignaled = false
  private var targetFormat: Format? = null

  private val pipeline: GPURenderPipeline
  private val sampler: GPUSampler

  init {
    val shaderModule =
      device.createShaderModule(
        GPUShaderModuleDescriptor(
          shaderSourceWGSL =
            GPUShaderSourceWGSL(
              code =
                """
                struct VertexOutput {
                    @builtin(position) position: vec4<f32>,
                    @location(0) texCoord: vec2<f32>,
                }

                @vertex
                fn vertexMain(@builtin(vertex_index) vertexIndex: u32) -> VertexOutput {
                    var pos = array<vec2<f32>, 4>(
                        vec2<f32>(-1.0,  1.0),
                        vec2<f32>(-1.0, -1.0),
                        vec2<f32>( 1.0,  1.0),
                        vec2<f32>( 1.0, -1.0)
                    );
                    var tex = array<vec2<f32>, 4>(
                        vec2<f32>(0.0, 0.0),
                        vec2<f32>(0.0, 1.0),
                        vec2<f32>(1.0, 0.0),
                        vec2<f32>(1.0, 1.0)
                    );
                    var output: VertexOutput;
                    output.position = vec4<f32>(pos[vertexIndex], 0.0, 1.0);
                    output.texCoord = tex[vertexIndex];
                    return output;
                }

                @group(0) @binding(0) var mySampler: sampler;
                @group(0) @binding(1) var myTexture: texture_external;

                fn linearToBt709(c: vec3<f32>) -> vec3<f32> {
                    return select(4.5 * c, 1.099 * pow(max(c, vec3<f32>(0.0)), vec3<f32>(0.45)) - 0.099, c >= vec3<f32>(0.018));
                }

                @fragment
                fn fragmentMain(@location(0) texCoord: vec2<f32>) -> @location(0) vec4<f32> {
                    let linearColor = textureSampleBaseClampToEdge(myTexture, mySampler, texCoord);
                    return vec4<f32>(linearToBt709(linearColor.rgb), linearColor.a);
                }
                """
                  .trimIndent()
            )
        )
      )

    val bindGroupLayout =
      device.createBindGroupLayout(
        GPUBindGroupLayoutDescriptor(
          entries =
            arrayOf(
              GPUBindGroupLayoutEntry(
                binding = 0,
                visibility = ShaderStage.Fragment,
                sampler = GPUSamplerBindingLayout(),
              ),
              GPUBindGroupLayoutEntry(
                binding = 1,
                visibility = ShaderStage.Fragment,
                externalTextureBindingLayout = GPUExternalTextureBindingLayout(),
              ),
            )
        )
      )

    val pipelineLayout =
      device.createPipelineLayout(
        GPUPipelineLayoutDescriptor(bindGroupLayouts = arrayOf(bindGroupLayout))
      )

    pipeline =
      device.createRenderPipeline(
        GPURenderPipelineDescriptor(
          layout = pipelineLayout,
          vertex = GPUVertexState(module = shaderModule, entryPoint = "vertexMain"),
          fragment =
            GPUFragmentState(
              module = shaderModule,
              entryPoint = "fragmentMain",
              targets =
                arrayOf(
                  GPUColorTargetState(
                    format = TextureFormat.RGBA8Unorm,
                    blend =
                      GPUBlendState(
                        color =
                          GPUBlendComponent(
                            srcFactor = BlendFactor.SrcAlpha,
                            dstFactor = BlendFactor.OneMinusSrcAlpha,
                            operation = BlendOperation.Add,
                          ),
                        alpha =
                          GPUBlendComponent(
                            srcFactor = BlendFactor.One,
                            dstFactor = BlendFactor.OneMinusSrcAlpha,
                            operation = BlendOperation.Add,
                          ),
                      ),
                  )
                ),
            ),
          primitive = GPUPrimitiveState(topology = PrimitiveTopology.TriangleStrip),
        )
      )

    sampler = device.createSampler()
    val unused = pipelineScope.launch { webGpu.processEventsLoop() }
  }

  override fun queue(frames: List<AsyncFrame>): Boolean {
    check(!isReleased.get())

    // While the internal thread is still processing the last task, record that the wakeup listener
    // should be triggered and synchronously return false.
    synchronized(lock) {
      if (hasPendingTask) {
        needsWakeup = true
        return false
      }
      hasPendingTask = true
    }

    // Execute the pipeline on the internal thread, handling failures.
    launchWithExceptionHandling { queueInternal(frames) }
    return true
  }

  override fun signalEndOfStream() {
    if (isReleased.get()) {
      return
    }
    // Execute the pipeline on the internal thread, handling failures.
    launchWithExceptionHandling {
      webGpu.execute {
        synchronized(lock) {
          if (!hasPendingTask) {
            output.signalEndOfStream()
          } else {
            endOfStreamSignaled = true
          }
        }
      }
    }
  }

  /** Processes the input frames and queues them downstream. */
  private suspend fun queueInternal(inputFrames: List<AsyncFrame>): Unit = webGpu.execute {
    // Step 1: Check format support and configure the output.
    val firstInputFrame = inputFrames[0].frame
    if (targetFormat == null) {
      val inputFormat = inputFrames.first().frame.format
      // HDR support is not yet implemented.
      targetFormat =
        Format.Builder()
          .setWidth(inputFormat.width)
          .setHeight(inputFormat.height)
          .setColorInfo(SDR_BT709_LIMITED)
          .build()
      output.configure(targetFormat!!, USAGE)
    }

    // Step 2: Attempt to dequeue output frame.
    val outputFrame =
      output.dequeueInputFrame(
        /* wakeupExecutor= */ directExecutor(),
        /* wakeupListener= */ { launchWithExceptionHandling { queueInternal(inputFrames) } },
      )
    if (outputFrame == null) {
      return@execute
    }

    var inputReleaseFences: List<SyncFenceWrapper?> = emptyList()
    try {
      // Step 3: Composite the input frames onto the output frame.
      val result = processFrames(inputFrames, outputFrame)
      val drawingCompletionFence = result.drawCompleteFence
      inputReleaseFences = result.readCompleteFences

      // Step 4: Update the output frame with the new content time and metadata.
      val updatedOutputFrame =
        (outputFrame.frame as HardwareBufferFrame)
          .buildUpon()
          .setContentTimeUs(firstInputFrame.contentTimeUs)
          .setMetadata(firstInputFrame.metadata)
          .build()

      // Step 5: Send the output frame downstream.
      output.queueInputFrame(updatedOutputFrame, drawingCompletionFence)

      // Step 6: Send any pending EOS signal downstream if needed.
      if (endOfStreamSignaled) {
        output.signalEndOfStream()
        endOfStreamSignaled = false
      }
    } finally {
      // Step 7: Always mark the input frames as complete.
      listenerExecutor.execute {
        for (i in inputFrames.indices) {
          val asyncFrame = inputFrames[i]
          val releaseFence = if (i < inputReleaseFences.size) inputReleaseFences[i] else null
          listener.onFrameProcessed(asyncFrame.frame, releaseFence)
        }
      }

      // Step 8: Allow the next frame to be queued.
      synchronized(lock) { hasPendingTask = false }

      // Step 9: Notify the wakeup listener if it is set.
      maybeTriggerWakeup()
    }
  }

  private fun maybeTriggerWakeup() {
    var needsWakeup = false
    synchronized(lock) {
      needsWakeup = this.needsWakeup
      this.needsWakeup = false
    }
    if (needsWakeup) {
      listenerExecutor.execute { listener.onWakeup() }
    }
  }

  /** Composites the input frames onto the output frame. */
  private suspend fun processFrames(
    inputAsyncFrames: List<AsyncFrame>,
    outputAsyncFrame: AsyncFrame,
  ): ProcessedFrameResult = webGpu.execute {
    val outputFrame = outputAsyncFrame.frame as HardwareBufferFrame
    val outputAhb = checkNotNull(outputFrame.hardwareBuffer)
    Log.d(
      "WebGPUPipeline",
      "Processing ${inputAsyncFrames.size} frames. Output: ${outputAhb.width}x${outputAhb.height} format=${outputAhb.format} (0x${outputAhb.format.toString(16)}) usage=0x${outputAhb.usage.toString(16)}",
    )
    val outputWrapper =
      GPUAndroidHardwareBufferUtil.createTexture(
        device,
        outputAhb,
        TextureUsage.RenderAttachment or TextureUsage.CopySrc,
      )
    val inputWrappers = mutableListOf<GPUHardwareBufferExternalTexture>()
    var drawCompleteFence: SyncFenceWrapper? = null
    val readCompleteFences = mutableListOf<SyncFenceWrapper?>()

    var outputAccessBegun = false
    val inputAccessBegun = BooleanArray(inputAsyncFrames.size) { false }

    try {
      // Wait on the output fence, to ensure it is ready to be written to.
      val outputFences =
        if (SDK_INT >= 33) {
          outputAsyncFrame.acquireFence?.let {
            arrayOf(GPUSyncFence.fromSyncFence(it.asSyncFence()))
          }
        } else {
          null
        }
      outputWrapper.beginAccess(outputFences)
      outputAccessBegun = true

      device.createCommandEncoder().use { commandEncoder ->
        outputWrapper.texture.createView().use { outputView ->
          for (i in inputAsyncFrames.indices) {
            val inputAsyncFrame = inputAsyncFrames[i]
            val inputFrame = inputAsyncFrame.frame as HardwareBufferFrame
            val inputAhb = checkNotNull(inputFrame.hardwareBuffer)
            Log.d(
              "WebGPUPipeline",
              "Input frame $i: ${inputAhb.width}x${inputAhb.height} format=${inputAhb.format} (0x${inputAhb.format.toString(16)}) usage=0x${inputAhb.usage.toString(16)}",
            )
            val descriptor =
              GPUExternalTextureDescriptor(
                cropSize = GPUExtent2D(inputFrame.format.width, inputFrame.format.height),
                srcColorSpace = mapColorInfoToSrcColorSpace(inputFrame.format.colorInfo),
                dstColorSpace = PredefinedColorSpace.SRGBLinear,
                rotation = mapRotation(inputFrame.format.rotationDegrees),
              )
            val inputWrapper =
              GPUAndroidHardwareBufferUtil.createExternalTexture(device, inputAhb, descriptor)
            inputWrappers.add(inputWrapper)

            // Wait on the input fences, to ensure the input frames are ready to be read from.
            val inputFences =
              if (SDK_INT >= 33) {
                inputAsyncFrame.acquireFence?.let {
                  arrayOf(GPUSyncFence.fromSyncFence(it.asSyncFence()))
                }
              } else {
                null
              }
            inputWrapper.beginAccess(inputFences)
            inputAccessBegun[i] = true

            // Draw the input textures into the output texture.
            val renderPassDesc =
              GPURenderPassDescriptor(
                colorAttachments =
                  arrayOf(
                    GPURenderPassColorAttachment(
                      view = outputView,
                      loadOp = if (i == 0) LoadOp.Clear else LoadOp.Load,
                      storeOp = StoreOp.Store,
                      clearValue = GPUColor(0.0, 0.0, 0.0, 1.0),
                    )
                  )
              )
            commandEncoder.beginRenderPass(renderPassDesc).use { renderPass ->
              renderPass.setPipeline(pipeline)

              val bindGroupLayout = pipeline.getBindGroupLayout(0)
              val bindGroup =
                device.createBindGroup(
                  GPUBindGroupDescriptor(
                    layout = bindGroupLayout,
                    entries =
                      arrayOf(
                        GPUBindGroupEntry(binding = 0, sampler = sampler),
                        GPUBindGroupEntry(
                          binding = 1,
                          externalTextureBindingEntry =
                            GPUExternalTextureBindingEntry(inputWrapper.externalTexture),
                        ),
                      ),
                  )
                )

              try {
                renderPass.setBindGroup(0, bindGroup)
                renderPass.draw(4)
              } finally {
                bindGroup.close()
                bindGroupLayout.close()
              }

              renderPass.end()
            }
          }

          Log.d("WebGPUPipeline", "Submitting commands to queue")
          val commandBuffer = commandEncoder.finish()
          try {
            device.queue.submit(arrayOf(commandBuffer))
          } finally {
            commandBuffer.close()
          }
        }
      }

      Log.d("WebGPUPipeline", "GPU work done")

      val inputReleaseFences = inputWrappers.mapIndexed { i, wrapper ->
        val fences = wrapper.endAccess()
        inputAccessBegun[i] = false
        if (SDK_INT >= 33) {
          fences.toSyncFence()?.let { syncFence ->
            if (!forwardSyncFences) {
              syncFence.awaitForever()
              null
            } else {
              SyncFenceWrapper.of(syncFence)
            }
          }
        } else {
          null
        }
      }
      readCompleteFences.addAll(inputReleaseFences)

      val outputReleaseFences = outputWrapper.endAccess()
      outputAccessBegun = false
      val outputReleaseFence =
        if (SDK_INT >= 33) {
          outputReleaseFences.toSyncFence()?.let { syncFence ->
            if (!forwardSyncFences) {
              syncFence.awaitForever()
              null
            } else {
              SyncFenceWrapper.of(syncFence)
            }
          }
        } else {
          null
        }
      drawCompleteFence = outputReleaseFence

      return@execute ProcessedFrameResult(drawCompleteFence, readCompleteFences)
    } catch (e: Exception) {
      drawCompleteFence?.close()
      readCompleteFences.forEach { it?.close() }
      throw e
    } finally {
      if (outputAccessBegun) {
        try {
          outputWrapper.endAccess()?.close()
        } catch (t: Throwable) {
          Log.e("WebGPUPipeline", "Failed to end output access on error", t)
        }
      }
      for (i in inputWrappers.indices) {
        if (i < inputAccessBegun.size && inputAccessBegun[i]) {
          try {
            inputWrappers[i].endAccess()?.close()
          } catch (t: Throwable) {
            Log.e("WebGPUPipeline", "Failed to end input $i access on error", t)
          }
        }
      }
      inputWrappers.forEach { it.close() }
      outputWrapper.close()
    }
  }

  private fun mapColorInfoToSrcColorSpace(colorInfo: ColorInfo?): GPUColorSpaceDawn {
    val info = colorInfo ?: SDR_BT709_LIMITED
    return GPUColorSpaceDawn(
      primaries =
        when (info.colorSpace) {
          C.COLOR_SPACE_BT709 -> ColorSpacePrimariesDawn.Rec709
          C.COLOR_SPACE_BT601 -> ColorSpacePrimariesDawn.Rec601
          C.COLOR_SPACE_BT2020 -> ColorSpacePrimariesDawn.Rec2020
          else -> ColorSpacePrimariesDawn.Rec709
        },
      transfer =
        when (info.colorTransfer) {
          C.COLOR_TRANSFER_SRGB -> ColorSpaceTransferDawn.SRGB
          C.COLOR_TRANSFER_SDR -> ColorSpaceTransferDawn.SMPTE_170M
          C.COLOR_TRANSFER_HLG -> ColorSpaceTransferDawn.HLG
          C.COLOR_TRANSFER_ST2084 -> ColorSpaceTransferDawn.PQ
          C.COLOR_TRANSFER_LINEAR -> ColorSpaceTransferDawn.Identity
          else -> ColorSpaceTransferDawn.SMPTE_170M
        },
      yCbCrRange =
        when (info.colorRange) {
          C.COLOR_RANGE_LIMITED -> ColorSpaceYCbCrRangeDawn.Narrow
          C.COLOR_RANGE_FULL -> ColorSpaceYCbCrRangeDawn.Full
          else -> ColorSpaceYCbCrRangeDawn.Narrow
        },
      yCbCrMatrix =
        when (info.colorSpace) {
          C.COLOR_SPACE_BT709 -> ColorSpaceYCbCrMatrixDawn.Rec709
          C.COLOR_SPACE_BT601 -> ColorSpaceYCbCrMatrixDawn.Rec601
          C.COLOR_SPACE_BT2020 -> ColorSpaceYCbCrMatrixDawn.Rec2020
          else -> ColorSpaceYCbCrMatrixDawn.Rec709
        },
    )
  }

  private fun mapRotation(rotationDegrees: Int): Int {
    return when (rotationDegrees) {
      0 -> ExternalTextureRotation.Rotate0Degrees
      90 -> ExternalTextureRotation.Rotate90Degrees
      180 -> ExternalTextureRotation.Rotate180Degrees
      270 -> ExternalTextureRotation.Rotate270Degrees
      else -> ExternalTextureRotation.Rotate0Degrees
    }
  }

  override fun close() {
    if (!isReleased.getAndSet(true)) {
      // Cancel the manually managed scope when the class's lifecycle ends
      pipelineScope.cancel()

      sampler.close()
      pipeline.close()
      webGpu.close()
    }
  }

  private data class ProcessedFrameResult(
    val drawCompleteFence: SyncFenceWrapper?,
    val readCompleteFences: List<SyncFenceWrapper?>,
  )

  companion object {
    private const val TAG = "WebGPUPipeline"
    private const val USAGE = Frame.USAGE_GPU_COLOR_OUTPUT or Frame.USAGE_GPU_SAMPLED_IMAGE

    private fun createDefaultWebGpu(): WebGpu = runBlocking {
      createWebGpu(
        instanceDescriptor =
          GPUInstanceDescriptor(
            dawnTogglesDescriptor =
              GPUDawnTogglesDescriptor(enabledToggles = arrayOf("allow_unsafe_apis"))
          ),
        deviceDescriptor =
          GPUDeviceDescriptor(
            deviceLostCallback =
              DeviceLostCallback { _, reason, message ->
                if (reason == DeviceLostReason.Destroyed) {
                  Log.d(TAG, "WebGPU device destroyed: $message")
                } else {
                  Log.e(TAG, "WebGPU device lost (reason=$reason): $message")
                }
              },
            deviceLostCallbackExecutor = directExecutor(),
            uncapturedErrorCallback =
              UncapturedErrorCallback { _, type, message ->
                Log.e(TAG, "WebGPU uncaptured error (type=$type): $message")
              },
            uncapturedErrorCallbackExecutor = directExecutor(),
            dawnTogglesDescriptor =
              GPUDawnTogglesDescriptor(enabledToggles = arrayOf("allow_unsafe_apis")),
            requiredFeatures =
              intArrayOf(
                FeatureName.SharedTextureMemoryAHardwareBuffer,
                FeatureName.SharedFenceSyncFD,
                FeatureName.YCbCrVulkanSamplers,
                FeatureName.OpaqueYCbCrAndroidForExternalTexture,
              ),
          ),
      )
    }
  }
}
