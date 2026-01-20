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
package androidx.media3.exoplayer.e2etest;

import static androidx.media3.test.utils.robolectric.TestPlayerRunHelper.advance;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.net.Uri;
import android.util.Pair;
import android.view.Surface;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.ClippingMediaSource;
import androidx.media3.exoplayer.source.ForwardingMediaSourceFactory;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.extractor.DefaultExtractorsFactory;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.ForwardingExtractor;
import androidx.media3.extractor.ForwardingExtractorOutput;
import androidx.media3.extractor.ForwardingExtractorsFactory;
import androidx.media3.extractor.ForwardingTrackOutput;
import androidx.media3.extractor.TrackOutput;
import androidx.media3.test.utils.DumpFileAsserts;
import androidx.media3.test.utils.Dumper;
import androidx.media3.test.utils.FakeClock;
import androidx.media3.test.utils.robolectric.CapturingRenderersFactory;
import androidx.media3.test.utils.robolectric.PlaybackOutput;
import androidx.media3.test.utils.robolectric.ShadowMediaCodecConfig;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.List;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/** End-to-end tests for the behavior of clipping with progressive media. */
@RunWith(AndroidJUnit4.class)
public final class ClippingProgressivePlaybackTest {

  private static final String TEST_MP4_URI = "asset:///media/mp4/sample.mp4";

  @Rule
  public ShadowMediaCodecConfig mediaCodecConfig =
      ShadowMediaCodecConfig.withAllDefaultSupportedCodecs();

  @Test
  public void playback_clipped() throws Exception {
    Pair<ExoPlayer, PlaybackOutput> setupData = setUpPlayerAndCapturingOutputForClippingTest();
    ExoPlayer player = setupData.first;
    PlaybackOutput playbackOutput = setupData.second;

    player.setMediaItem(
        new MediaItem.Builder()
            .setUri(TEST_MP4_URI)
            .setClippingConfiguration(
                new MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(100)
                    .setEndPositionMs(500)
                    .build())
            .build());
    player.prepare();
    advance(player).untilFullyBuffered();
    player.play();
    advance(player).untilState(Player.STATE_ENDED);
    player.release();

    DumpFileAsserts.assertOutput(
        ApplicationProvider.getApplicationContext(),
        playbackOutput,
        "playbackdumps/clipping/clipped.dump");
  }

  @Test
  public void playback_clippedWithSeek() throws Exception {
    Pair<ExoPlayer, PlaybackOutput> setupData = setUpPlayerAndCapturingOutputForClippingTest();
    ExoPlayer player = setupData.first;
    PlaybackOutput playbackOutput = setupData.second;

    player.setMediaItem(
        new MediaItem.Builder()
            .setUri(TEST_MP4_URI)
            .setClippingConfiguration(
                new MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(100)
                    .setEndPositionMs(500)
                    .build())
            .build());
    player.prepare();
    advance(player).untilFullyBuffered();
    player.play();
    advance(player).untilState(Player.STATE_ENDED);
    player.seekTo(300);
    advance(player).untilState(Player.STATE_ENDED);
    player.release();

    DumpFileAsserts.assertOutput(
        ApplicationProvider.getApplicationContext(),
        playbackOutput,
        "playbackdumps/clipping/clipped_seek.dump");
  }

  private Pair<ExoPlayer, PlaybackOutput> setUpPlayerAndCapturingOutputForClippingTest() {
    Context context = ApplicationProvider.getApplicationContext();
    FakeClock clock = new FakeClock(/* isAutoAdvancing= */ true);
    // Capture the extracted samples in addition to the decoded samples to capture the loading
    // pattern and verify that loading stops once all required samples are available.
    CapturingExtractorsFactory capturingExtractorsFactory = new CapturingExtractorsFactory();
    CapturingRenderersFactory capturingRenderersFactory =
        new CapturingRenderersFactory(context, clock) {
          @Override
          public void dump(Dumper dumper) {
            capturingExtractorsFactory.dump(dumper);
            super.dump(dumper);
          }
        };
    // Limit the continue loading interval to something very low so we can see the effect of
    // clipping on loading.
    MediaSource.Factory progressiveFactory =
        new ProgressiveMediaSource.Factory(
                new DefaultDataSource.Factory(context), capturingExtractorsFactory)
            .setContinueLoadingCheckIntervalBytes(10);
    ExoPlayer player =
        new ExoPlayer.Builder(context, capturingRenderersFactory)
            .setMediaSourceFactory(
                new ForwardingMediaSourceFactory(progressiveFactory) {
                  @Override
                  public MediaSource createMediaSource(MediaItem mediaItem) {
                    MediaSource progressiveSource = super.createMediaSource(mediaItem);
                    return new ClippingMediaSource.Builder(progressiveSource)
                        .setStartPositionUs(mediaItem.clippingConfiguration.startPositionUs)
                        .setEndPositionUs(mediaItem.clippingConfiguration.endPositionUs)
                        .build();
                  }
                })
            .setClock(clock)
            .build();
    Surface surface = new Surface(new SurfaceTexture(/* texName= */ 1));
    player.setVideoSurface(surface);
    PlaybackOutput playbackOutput = PlaybackOutput.register(player, capturingRenderersFactory);
    return Pair.create(player, playbackOutput);
  }

  private static final class CapturingExtractorsFactory extends ForwardingExtractorsFactory
      implements Dumper.Dumpable {

    private final ImmutableList.Builder<DumpableSampleMetadata> samples;

    private int nextSampleIndex;

    private CapturingExtractorsFactory() {
      super(new DefaultExtractorsFactory());
      samples = ImmutableList.builder();
    }

    @Override
    public Extractor[] createExtractors() {
      return wrapExtractors(super.createExtractors());
    }

    @Override
    public Extractor[] createExtractors(Uri uri, Map<String, List<String>> responseHeaders) {
      return wrapExtractors(super.createExtractors(uri, responseHeaders));
    }

    @Override
    public void dump(Dumper dumper) {
      dumper.startBlock("extracted samples");
      for (DumpableSampleMetadata sampleMetadata : samples.build()) {
        sampleMetadata.dump(dumper);
      }
      dumper.endBlock();
    }

    @CanIgnoreReturnValue
    private Extractor[] wrapExtractors(Extractor[] extractors) {
      for (int i = 0; i < extractors.length; i++) {
        extractors[i] =
            new ForwardingExtractor(extractors[i]) {
              boolean seenClippedSeek = false;

              @Override
              public void seek(long position, long timeUs) {
                seenClippedSeek |= timeUs != 0;
                super.seek(position, timeUs);
              }

              @Override
              public void init(ExtractorOutput output) {
                super.init(
                    new ForwardingExtractorOutput(output) {
                      @Override
                      public TrackOutput track(int id, @C.TrackType int type) {
                        return new ForwardingTrackOutput(super.track(id, type)) {
                          @Override
                          public void sampleMetadata(
                              long timeUs,
                              @C.BufferFlags int flags,
                              int size,
                              int offset,
                              @Nullable CryptoData cryptoData) {
                            if (seenClippedSeek) {
                              // Ignore samples from initial preparation before applying the seek to
                              // the clipped start position.
                              samples.add(
                                  new DumpableSampleMetadata(
                                      nextSampleIndex++, id, timeUs, flags, size));
                            }
                            super.sampleMetadata(timeUs, flags, size, offset, cryptoData);
                          }
                        };
                      }
                    });
              }
            };
      }
      return extractors;
    }
  }

  private static final class DumpableSampleMetadata implements Dumper.Dumpable {

    private final int sampleIndex;
    private final int trackId;
    private final long timeUs;
    private final @C.BufferFlags int flags;
    private final int size;

    private DumpableSampleMetadata(
        int sampleIndex, int trackId, long timeUs, @C.BufferFlags int flags, int size) {
      this.sampleIndex = sampleIndex;
      this.trackId = trackId;
      this.timeUs = timeUs;
      this.flags = flags;
      this.size = size;
    }

    @Override
    public void dump(Dumper dumper) {
      dumper.startBlock("sample #" + sampleIndex);
      dumper.add("track", trackId);
      dumper.addTime("timeUs", timeUs);
      if (flags != 0) {
        dumper.add("flags", flags);
      }
      dumper.add("size", size);
      dumper.endBlock();
    }
  }
}
