/*
 * Copyright (C) 2020 The Android Open Source Project
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
package androidx.media3.test.utils;

import static androidx.media3.common.util.Assertions.checkNotNull;
import static androidx.media3.common.util.Assertions.checkState;

import android.content.Context;
import android.media.MediaCodec;
import android.os.Handler;
import android.os.Looper;
import android.util.SparseArray;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.Renderer;
import androidx.media3.exoplayer.RenderersFactory;
import androidx.media3.exoplayer.audio.AudioRendererEventListener;
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer;
import androidx.media3.exoplayer.image.BitmapFactoryImageDecoder;
import androidx.media3.exoplayer.image.ImageDecoder;
import androidx.media3.exoplayer.image.ImageOutput;
import androidx.media3.exoplayer.image.ImageRenderer;
import androidx.media3.exoplayer.mediacodec.ForwardingMediaCodecAdapter;
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.media3.exoplayer.metadata.MetadataOutput;
import androidx.media3.exoplayer.metadata.MetadataRenderer;
import androidx.media3.exoplayer.text.TextOutput;
import androidx.media3.exoplayer.text.TextRenderer;
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer;
import androidx.media3.exoplayer.video.VideoRendererEventListener;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedMap;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A {@link RenderersFactory} that captures interactions with the audio and video {@link
 * MediaCodecAdapter} instances and {@link ImageOutput} instances.
 *
 * <p>The captured interactions can be used in a test assertion via the {@link Dumper.Dumpable}
 * interface.
 */
@UnstableApi
public class CapturingRenderersFactory implements RenderersFactory, Dumper.Dumpable {

  private final Context context;
  private final CapturingMediaCodecAdapter.Factory mediaCodecAdapterFactory;
  private final CapturingAudioSink audioSink;

  private ImageDecoder.Factory imageDecoderFactory;
  private TextRendererFactory textRendererFactory;
  private boolean parseAv1SampleDependencies;

  /**
   * Creates an instance.
   *
   * @param context The {@link Context}.
   */
  public CapturingRenderersFactory(Context context) {
    this(context, CapturingAudioSink.create());
  }

  /**
   * Creates an instance.
   *
   * @param context The {@link Context}.
   * @param capturingAudioSink The audio sink to use for capturing audio output.
   */
  public CapturingRenderersFactory(Context context, CapturingAudioSink capturingAudioSink) {
    this.context = context;
    this.mediaCodecAdapterFactory = new CapturingMediaCodecAdapter.Factory(context);
    this.audioSink = capturingAudioSink;
    this.imageDecoderFactory = new BitmapFactoryImageDecoder.Factory(context);
    this.textRendererFactory = TextRenderer::new;
  }

  /**
   * Sets the {@link ImageDecoder.Factory} used by the {@link ImageRenderer}.
   *
   * @param imageDecoderFactory The {@link ImageDecoder.Factory}.
   * @return This factory, for convenience.
   */
  public CapturingRenderersFactory setImageDecoderFactory(
      ImageDecoder.Factory imageDecoderFactory) {
    this.imageDecoderFactory = imageDecoderFactory;
    return this;
  }

  protected final ImageDecoder.Factory getImageDecoderFactory() {
    return imageDecoderFactory;
  }

  /**
   * Sets the factory for {@link Renderer} instances that handle {@link C#TRACK_TYPE_TEXT} tracks.
   *
   * @param textRendererFactory The {@link TextRendererFactory}.
   * @return This factory, for convenience.
   */
  @CanIgnoreReturnValue
  public CapturingRenderersFactory setTextRendererFactory(TextRendererFactory textRendererFactory) {
    this.textRendererFactory = textRendererFactory;
    return this;
  }

  /**
   * Sets whether {@link MimeTypes#VIDEO_AV1} bitstream parsing for sample dependency information is
   * enabled. Knowing which input frames are not depended on can speed up seeking and reduce dropped
   * frames.
   *
   * <p>Defaults to {@code false}.
   *
   * <p>This method is experimental and will be renamed or removed in a future release.
   *
   * @param parseAv1SampleDependencies Whether bitstream parsing is enabled.
   */
  @CanIgnoreReturnValue
  public final CapturingRenderersFactory experimentalSetParseAv1SampleDependencies(
      boolean parseAv1SampleDependencies) {
    this.parseAv1SampleDependencies = parseAv1SampleDependencies;
    return this;
  }

  @Override
  public Renderer[] createRenderers(
      Handler eventHandler,
      VideoRendererEventListener videoRendererEventListener,
      AudioRendererEventListener audioRendererEventListener,
      TextOutput textRendererOutput,
      MetadataOutput metadataRendererOutput) {
    ArrayList<Renderer> renderers = new ArrayList<>();
    renderers.add(createMediaCodecVideoRenderer(eventHandler, videoRendererEventListener));
    renderers.add(
        new MediaCodecAudioRenderer(
            context,
            mediaCodecAdapterFactory,
            MediaCodecSelector.DEFAULT,
            /* enableDecoderFallback= */ false,
            eventHandler,
            audioRendererEventListener,
            audioSink));
    renderers.add(textRendererFactory.create(textRendererOutput, eventHandler.getLooper()));
    renderers.add(new MetadataRenderer(metadataRendererOutput, eventHandler.getLooper()));
    renderers.add(new ImageRenderer(imageDecoderFactory, /* imageOutput= */ null));

    return renderers.toArray(new Renderer[] {});
  }

  @Override
  public void dump(Dumper dumper) {
    mediaCodecAdapterFactory.dump(dumper);
    audioSink.dump(dumper);
  }

  /** A factory for {@link Renderer} instances that handle {@link C#TRACK_TYPE_TEXT} tracks. */
  public interface TextRendererFactory {

    /**
     * Creates a new {@link Renderer} instance for a {@link C#TRACK_TYPE_TEXT} track.
     *
     * @param textOutput A {@link TextOutput} to handle the parsed subtitles.
     * @param outputLooper The looper used to invoke {@code textOutput}.
     */
    Renderer create(TextOutput textOutput, Looper outputLooper);
  }

  /**
   * Returns new instance of a specialized {@link MediaCodecVideoRenderer} that will not drop or
   * skip buffers due to slow processing.
   *
   * @param eventHandler A handler to use when invoking event listeners and outputs.
   * @param videoRendererEventListener An event listener for video renderers.
   * @return a new instance of a specialized {@link MediaCodecVideoRenderer}.
   */
  protected MediaCodecVideoRenderer createMediaCodecVideoRenderer(
      Handler eventHandler, VideoRendererEventListener videoRendererEventListener) {
    return new CapturingMediaCodecVideoRenderer(
        context,
        mediaCodecAdapterFactory,
        MediaCodecSelector.DEFAULT,
        DefaultRenderersFactory.DEFAULT_ALLOWED_VIDEO_JOINING_TIME_MS,
        /* enableDecoderFallback= */ false,
        eventHandler,
        videoRendererEventListener,
        DefaultRenderersFactory.MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY,
        parseAv1SampleDependencies);
  }

  /**
   * Returns the {@link Context} used to instantiate the {@link Renderer renderers} like for example
   * a {@link CapturingMediaCodecVideoRenderer#CapturingMediaCodecVideoRenderer
   * CapturingMediaCodecVideoRenderer}.
   */
  protected Context getContext() {
    return context;
  }

  /**
   * Returns the {@link CapturingMediaCodecAdapter.Factory} as a {@link MediaCodecAdapter.Factory}.
   */
  protected MediaCodecAdapter.Factory getMediaCodecAdapterFactory() {
    return mediaCodecAdapterFactory;
  }

  /**
   * A {@link MediaCodecVideoRenderer} that will not skip or drop buffers due to slow processing.
   */
  protected static class CapturingMediaCodecVideoRenderer extends MediaCodecVideoRenderer {
    protected CapturingMediaCodecVideoRenderer(
        Context context,
        MediaCodecAdapter.Factory codecAdapterFactory,
        MediaCodecSelector mediaCodecSelector,
        long allowedJoiningTimeMs,
        boolean enableDecoderFallback,
        @Nullable Handler eventHandler,
        @Nullable VideoRendererEventListener eventListener,
        int maxDroppedFramesToNotify,
        boolean parseAv1SampleDependencies) {
      super(
          new Builder(context)
              .setCodecAdapterFactory(codecAdapterFactory)
              .setMediaCodecSelector(mediaCodecSelector)
              .setAllowedJoiningTimeMs(allowedJoiningTimeMs)
              .setEnableDecoderFallback(enableDecoderFallback)
              .setEventHandler(eventHandler)
              .setEventListener(eventListener)
              .setMaxDroppedFramesToNotify(maxDroppedFramesToNotify)
              .experimentalSetParseAv1SampleDependencies(parseAv1SampleDependencies));
    }

    @Override
    protected boolean shouldDropOutputBuffer(
        long earlyUs, long elapsedRealtimeUs, boolean isLastBuffer) {
      // Do not drop output buffers due to slow processing.
      return false;
    }

    @Override
    protected boolean shouldDropBuffersToKeyframe(
        long earlyUs, long elapsedRealtimeUs, boolean isLastBuffer) {
      // Do not drop output buffers due to slow processing.
      return false;
    }

    @Override
    protected boolean shouldSkipBuffersWithIdenticalReleaseTime() {
      // Do not skip buffers with identical vsync times as we can't control this from tests.
      return false;
    }
  }

  /**
   * A {@link MediaCodecAdapter} that captures interactions and exposes them for test assertions via
   * {@link Dumper.Dumpable}.
   */
  private static class CapturingMediaCodecAdapter extends ForwardingMediaCodecAdapter
      implements Dumper.Dumpable {

    private static class Factory implements MediaCodecAdapter.Factory, Dumper.Dumpable {

      private final Context context;
      private final List<CapturingMediaCodecAdapter> constructedAdapters;

      private Factory(Context context) {
        this.context = context;
        constructedAdapters = new ArrayList<>();
      }

      @Override
      public MediaCodecAdapter createAdapter(Configuration configuration) throws IOException {
        CapturingMediaCodecAdapter adapter =
            new CapturingMediaCodecAdapter(
                MediaCodecAdapter.Factory.getDefault(context).createAdapter(configuration),
                configuration.codecInfo.name);
        constructedAdapters.add(adapter);
        return adapter;
      }

      @Override
      public void dump(Dumper dumper) {
        ImmutableList<CapturingMediaCodecAdapter> sortedAdapters =
            ImmutableList.sortedCopyOf(
                (adapter1, adapter2) -> adapter1.codecName.compareTo(adapter2.codecName),
                constructedAdapters);
        for (int i = 0; i < sortedAdapters.size(); i++) {
          sortedAdapters.get(i).dump(dumper);
        }
      }
    }

    private static final String INPUT_BUFFER_INTERACTION_TYPE = "inputBuffers";
    private static final String OUTPUT_BUFFER_INTERACTION_TYPE = "outputBuffers";

    // TODO(internal b/175710547): Consider using MediaCodecInfo, but currently Robolectric (v4.5)
    // doesn't correctly implement MediaCodec#getCodecInfo() (getName() works).
    private final String codecName;

    /**
     * The client-owned buffers, keyed by the index used by {@link #dequeueInputBufferIndex()} and
     * {@link #getInputBuffer(int)}, or {@link #dequeueOutputBufferIndex} respectively.
     */
    private final SparseArray<ByteBuffer> dequeuedInputBuffers;

    private final SparseArray<MediaCodec.BufferInfo> dequeuedOutputBuffers;

    /** All interactions recorded with this adapter. */
    private final ArrayListMultimap<String, CapturedInteraction> capturedInteractions;

    private int inputBufferCount;
    private int outputBufferCount;
    private final AtomicBoolean isReleased;

    private CapturingMediaCodecAdapter(MediaCodecAdapter delegate, String codecName) {
      super(delegate);
      this.codecName = codecName;
      dequeuedInputBuffers = new SparseArray<>();
      dequeuedOutputBuffers = new SparseArray<>();
      capturedInteractions = ArrayListMultimap.create();
      isReleased = new AtomicBoolean();
    }

    @Override
    public int dequeueOutputBufferIndex(MediaCodec.BufferInfo bufferInfo) {
      int index = super.dequeueOutputBufferIndex(bufferInfo);
      if (index >= 0) {
        dequeuedOutputBuffers.put(index, bufferInfo);
      }
      return index;
    }

    @Nullable
    @Override
    public ByteBuffer getInputBuffer(int index) {
      @Nullable ByteBuffer inputBuffer = super.getInputBuffer(index);
      if (inputBuffer != null) {
        dequeuedInputBuffers.put(index, inputBuffer);
      }
      return inputBuffer;
    }

    @Override
    public void queueInputBuffer(
        int index, int offset, int size, long presentationTimeUs, int flags) {
      ByteBuffer inputBuffer = checkNotNull(dequeuedInputBuffers.get(index));
      capturedInteractions.put(
          INPUT_BUFFER_INTERACTION_TYPE,
          new CapturedInputBuffer(
              inputBufferCount++, peekBytes(inputBuffer, offset, size), presentationTimeUs, flags));

      super.queueInputBuffer(index, offset, size, presentationTimeUs, flags);
      dequeuedInputBuffers.delete(index);
    }

    @Override
    public void releaseOutputBuffer(int index, boolean render) {
      MediaCodec.BufferInfo bufferInfo = checkNotNull(dequeuedOutputBuffers.get(index));
      capturedInteractions.put(
          OUTPUT_BUFFER_INTERACTION_TYPE,
          new CapturedOutputBuffer(
              outputBufferCount++,
              bufferInfo.size,
              bufferInfo.presentationTimeUs,
              bufferInfo.flags,
              /* rendered= */ render));
      super.releaseOutputBuffer(index, render);
      dequeuedOutputBuffers.delete(index);
    }

    @Override
    public void releaseOutputBuffer(int index, long renderTimeStampNs) {
      MediaCodec.BufferInfo bufferInfo = checkNotNull(dequeuedOutputBuffers.get(index));
      capturedInteractions.put(
          OUTPUT_BUFFER_INTERACTION_TYPE,
          new CapturedOutputBuffer(
              outputBufferCount++,
              bufferInfo.size,
              bufferInfo.presentationTimeUs,
              bufferInfo.flags,
              /* rendered= */ true));
      super.releaseOutputBuffer(index, renderTimeStampNs);
      dequeuedOutputBuffers.delete(index);
    }

    @Override
    public void flush() {
      dequeuedInputBuffers.clear();
      dequeuedOutputBuffers.clear();
      super.flush();
    }

    @Override
    public void release() {
      dequeuedInputBuffers.clear();
      dequeuedOutputBuffers.clear();
      isReleased.set(true);
      super.release();
    }

    @Override
    public boolean needsReconfiguration() {
      return false;
    }

    // Dumpable implementation

    @Override
    public void dump(Dumper dumper) {
      checkState(isReleased.get());
      ImmutableSortedMap<String, Collection<CapturedInteraction>> sortedInteractions =
          ImmutableSortedMap.copyOf(capturedInteractions.asMap());

      dumper.startBlock("MediaCodecAdapter (" + codecName + ")");
      for (Map.Entry<String, Collection<CapturedInteraction>> interactionEntry :
          sortedInteractions.entrySet()) {
        String interactionType = interactionEntry.getKey();
        Collection<CapturedInteraction> interactions = interactionEntry.getValue();
        dumper.startBlock(interactionType);
        dumper.add("count", interactions.size());
        for (CapturedInteraction interaction : interactions) {
          dumper.add(interaction);
        }
        dumper.endBlock();
      }
      dumper.endBlock();
    }

    private static byte[] peekBytes(ByteBuffer buffer, int offset, int size) {
      int originalPosition = buffer.position();
      buffer.position(offset);
      byte[] bytes = new byte[size];
      buffer.get(bytes);
      buffer.position(originalPosition);
      return bytes;
    }

    /** A marker interface for different interactions with {@link CapturingMediaCodecAdapter}. */
    private interface CapturedInteraction extends Dumper.Dumpable {}

    /**
     * Records the data passed to {@link CapturingMediaCodecAdapter#queueInputBuffer(int, int, int,
     * long, int)}.
     */
    private static class CapturedInputBuffer implements CapturedInteraction {
      private final int inputBufferCounter;
      private final byte[] contents;
      private final long bufferTimeUs;
      private final int flags;

      private CapturedInputBuffer(
          int inputBufferCounter, byte[] contents, long bufferTimeUs, int flags) {
        this.inputBufferCounter = inputBufferCounter;
        this.contents = contents;
        this.bufferTimeUs = bufferTimeUs;
        this.flags = flags;
      }

      @Override
      public void dump(Dumper dumper) {
        dumper.startBlock("input buffer #" + inputBufferCounter);
        dumper.addTime("timeUs", bufferTimeUs);
        if (flags != 0) {
          dumper.add("flags", flags);
        }
        dumper.add("contents", contents);
        dumper.endBlock();
      }
    }

    /** Records the data passed to {@link CapturingMediaCodecAdapter#releaseOutputBuffer}. */
    private static class CapturedOutputBuffer implements CapturedInteraction {
      private final int outputBufferCounter;
      private final int bufferSize;
      private final long bufferTimeUs;
      private final int flags;
      private final boolean rendered;

      private CapturedOutputBuffer(
          int outputBufferCounter, int bufferSize, long bufferTimeUs, int flags, boolean rendered) {
        this.outputBufferCounter = outputBufferCounter;
        this.bufferSize = bufferSize;
        this.bufferTimeUs = bufferTimeUs;
        this.flags = flags;
        this.rendered = rendered;
      }

      @Override
      public void dump(Dumper dumper) {
        dumper.startBlock("output buffer #" + outputBufferCounter);
        dumper.addTime("timeUs", bufferTimeUs);
        if (flags != 0) {
          dumper.add("flags", flags);
        }
        dumper.add("size", bufferSize);
        dumper.add("rendered", rendered);
        dumper.endBlock();
      }
    }
  }
}
