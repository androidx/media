/*
 * Copyright 2023 The Android Open Source Project
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
package androidx.media3.transformer;

import static androidx.media3.common.C.TRACK_TYPE_AUDIO;
import static androidx.media3.common.Player.STATE_READY;
import static androidx.media3.test.utils.AssetInfo.WAV_24LE_PCM_ASSET;
import static androidx.media3.test.utils.AssetInfo.WAV_32LE_PCM_ASSET;
import static androidx.media3.test.utils.AssetInfo.WAV_ASSET;
import static androidx.media3.test.utils.TestUtil.createByteCountingAudioProcessor;
import static androidx.media3.test.utils.robolectric.TestPlayerRunHelper.advance;
import static androidx.media3.test.utils.robolectric.TestPlayerRunHelper.play;
import static androidx.media3.transformer.EditedMediaItemSequence.withAudioFrom;
import static androidx.media3.transformer.TestUtil.ASSET_URI_PREFIX;
import static androidx.media3.transformer.TestUtil.FILE_AUDIO_RAW;
import static androidx.media3.transformer.TestUtil.FILE_AUDIO_RAW_STEREO_48000KHZ;
import static androidx.media3.transformer.TestUtil.createAudioEffects;
import static androidx.media3.transformer.TestUtil.createChannelCountChangingAudioProcessor;
import static androidx.media3.transformer.TestUtil.createSampleRateChangingAudioProcessor;
import static androidx.media3.transformer.TestUtil.createTestCompositionPlayer;
import static androidx.media3.transformer.TestUtil.createVolumeScalingAudioProcessor;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.truth.Truth.assertThat;

import android.content.Context;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaItem.ClippingConfiguration;
import androidx.media3.common.Player;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.SpeedProvider;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.test.utils.CapturingAudioSink;
import androidx.media3.test.utils.DumpFileAsserts;
import androidx.media3.test.utils.FakeClock;
import androidx.media3.test.utils.PassthroughAudioProcessor;
import androidx.media3.test.utils.robolectric.TestPlayerRunHelper;
import androidx.media3.transformer.TestUtil.FormatCapturingAudioProcessor;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Audio playback unit tests for {@link CompositionPlayer}.
 *
 * <p>These tests focus on audio because the video pipeline doesn't work in Robolectric.
 */
@RunWith(AndroidJUnit4.class)
public final class CompositionPlayerAudioPlaybackTest {

  private static final String PREVIEW_DUMP_FILE_EXTENSION = "audiosinkdumps/";
  private static final SpeedProvider SPEED_PROVIDER_2X =
      new SpeedProvider() {
        @Override
        public float getSpeed(long timeUs) {
          return 2f;
        }

        @Override
        public long getNextSpeedChangeTimeUs(long timeUs) {
          return C.TIME_UNSET;
        }
      };

  private static final SpeedProvider SPEED_PROVIDER_MULTIPLE_SPEEDS =
      new SpeedProvider() {
        @Override
        public float getSpeed(long timeUs) {
          if (timeUs >= 500_000) {
            return 0.5f;
          }
          return 2f;
        }

        @Override
        public long getNextSpeedChangeTimeUs(long timeUs) {
          if (timeUs < 500_000) {
            return 500_000;
          }
          return C.TIME_UNSET;
        }
      };

  private final Context context = ApplicationProvider.getApplicationContext();
  private CapturingAudioSink capturingAudioSink;

  @Before
  public void setUp() {
    capturingAudioSink = CapturingAudioSink.createForSampleCapturing();
  }

  @Test
  public void playSingleSequence_outputsCorrectSamples() throws Exception {
    CompositionPlayer player = createCompositionPlayer(context, capturingAudioSink);
    EditedMediaItem editedMediaItem1 =
        new EditedMediaItem.Builder(MediaItem.fromUri(ASSET_URI_PREFIX + FILE_AUDIO_RAW))
            .setDurationUs(1_000_000L)
            .build();
    EditedMediaItem editedMediaItem2 =
        new EditedMediaItem.Builder(
                MediaItem.fromUri(ASSET_URI_PREFIX + FILE_AUDIO_RAW_STEREO_48000KHZ))
            .setDurationUs(348_000L)
            .build();
    EditedMediaItemSequence sequence =
        withAudioFrom(ImmutableList.of(editedMediaItem1, editedMediaItem2));
    Composition composition = new Composition.Builder(sequence).build();

    player.setComposition(composition);
    player.prepare();
    player.play();
    advance(player).untilState(Player.STATE_ENDED);
    player.release();

    DumpFileAsserts.assertOutput(
        context,
        capturingAudioSink,
        PREVIEW_DUMP_FILE_EXTENSION + FILE_AUDIO_RAW + "_then_sample_rf64.wav.dump");
  }

  @Test
  public void playSingleSequence_withItemEffects_outputsCorrectSamples() throws Exception {
    CompositionPlayer player = createCompositionPlayer(context, capturingAudioSink);
    EditedMediaItem editedMediaItem1 =
        new EditedMediaItem.Builder(MediaItem.fromUri(ASSET_URI_PREFIX + FILE_AUDIO_RAW))
            .setDurationUs(1_000_000L)
            .setEffects(createAudioEffects(createVolumeScalingAudioProcessor(0.5f)))
            .build();
    EditedMediaItem editedMediaItem2 =
        new EditedMediaItem.Builder(
                MediaItem.fromUri(ASSET_URI_PREFIX + FILE_AUDIO_RAW_STEREO_48000KHZ))
            .setDurationUs(348_000L)
            .setEffects(createAudioEffects(createVolumeScalingAudioProcessor(2f)))
            .build();
    EditedMediaItemSequence sequence =
        withAudioFrom(ImmutableList.of(editedMediaItem1, editedMediaItem2));
    Composition composition = new Composition.Builder(sequence).build();

    player.setComposition(composition);
    player.prepare();
    player.play();
    advance(player).untilState(Player.STATE_ENDED);
    player.release();

    DumpFileAsserts.assertOutput(
        context,
        capturingAudioSink,
        PREVIEW_DUMP_FILE_EXTENSION
            + FILE_AUDIO_RAW
            + "-lowVolume_then_sample_rf64.wav-highVolume.dump");
  }

  @Test
  public void playSingleItem_withItemEffects_outputsCorrectSamples() throws Exception {
    CompositionPlayer player = createCompositionPlayer(context, capturingAudioSink);
    EditedMediaItem audioEditedMediaItem =
        new EditedMediaItem.Builder(MediaItem.fromUri(ASSET_URI_PREFIX + FILE_AUDIO_RAW))
            .setRemoveVideo(true)
            .setDurationUs(1_000_000L)
            .setEffects(createAudioEffects(createVolumeScalingAudioProcessor(2f)))
            .build();
    Composition composition =
        new Composition.Builder(withAudioFrom(ImmutableList.of(audioEditedMediaItem))).build();

    player.setComposition(composition);
    player.prepare();
    player.play();
    advance(player).untilState(Player.STATE_ENDED);
    player.release();

    DumpFileAsserts.assertOutput(
        context,
        capturingAudioSink,
        PREVIEW_DUMP_FILE_EXTENSION + FILE_AUDIO_RAW + "/highVolume.dump");
  }

  @Test
  public void playSingleItem_withCompositionEffects_outputsCorrectSamples() throws Exception {
    CompositionPlayer player = createCompositionPlayer(context, capturingAudioSink);
    EditedMediaItem audioEditedMediaItem =
        new EditedMediaItem.Builder(MediaItem.fromUri(ASSET_URI_PREFIX + FILE_AUDIO_RAW))
            .setRemoveVideo(true)
            .setDurationUs(1_000_000L)
            .build();
    Composition composition =
        new Composition.Builder(withAudioFrom(ImmutableList.of(audioEditedMediaItem)))
            .setEffects(createAudioEffects(createVolumeScalingAudioProcessor(2f)))
            .build();

    player.setComposition(composition);
    player.prepare();
    player.play();
    advance(player).untilState(Player.STATE_ENDED);
    player.release();

    DumpFileAsserts.assertOutput(
        context,
        capturingAudioSink,
        PREVIEW_DUMP_FILE_EXTENSION + FILE_AUDIO_RAW + "/highVolume.dump");
  }

  @Test
  public void playSingleSequence_withClipping_outputsCorrectSamples() throws Exception {
    CompositionPlayer player = createCompositionPlayer(context, capturingAudioSink);
    MediaItem mediaItem1 =
        new MediaItem.Builder()
            .setUri(ASSET_URI_PREFIX + FILE_AUDIO_RAW)
            .setClippingConfiguration(
                new MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(300)
                    .setEndPositionMs(800)
                    .build())
            .build();
    EditedMediaItem editedMediaItem1 =
        new EditedMediaItem.Builder(mediaItem1).setDurationUs(1_000_000L).build();
    MediaItem mediaItem2 =
        new MediaItem.Builder()
            .setUri(ASSET_URI_PREFIX + FILE_AUDIO_RAW_STEREO_48000KHZ)
            .setClippingConfiguration(
                new MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(100)
                    .setEndPositionMs(300)
                    .build())
            .build();
    EditedMediaItem editedMediaItem2 =
        new EditedMediaItem.Builder(mediaItem2).setDurationUs(348_000L).build();
    Composition composition =
        new Composition.Builder(withAudioFrom(ImmutableList.of(editedMediaItem1, editedMediaItem2)))
            .build();

    player.setComposition(composition);
    player.prepare();
    player.play();
    advance(player).untilState(Player.STATE_ENDED);
    player.release();

    DumpFileAsserts.assertOutput(
        context,
        capturingAudioSink,
        PREVIEW_DUMP_FILE_EXTENSION
            + FILE_AUDIO_RAW
            + "_clipped_then_sample_rf64_clipped.wav.dump");
  }

  @Test
  public void playMultipleSequences_withClippingAndEffects_outputsCorrectSamples()
      throws Exception {
    CompositionPlayer player = createCompositionPlayer(context, capturingAudioSink);
    EditedMediaItem editedMediaItem1 =
        new EditedMediaItem.Builder(
                new MediaItem.Builder()
                    .setUri(ASSET_URI_PREFIX + FILE_AUDIO_RAW)
                    .setClippingConfiguration(
                        new MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(0)
                            .setEndPositionUs(696_000)
                            .build())
                    .build())
            .setDurationUs(1_000_000L)
            .build();
    EditedMediaItem editedMediaItem2 =
        new EditedMediaItem.Builder(
                MediaItem.fromUri(ASSET_URI_PREFIX + FILE_AUDIO_RAW_STEREO_48000KHZ))
            .setDurationUs(348_000L)
            .setEffects(
                createAudioEffects(
                    createSampleRateChangingAudioProcessor(44100),
                    createChannelCountChangingAudioProcessor(1)))
            .build();
    Composition composition =
        new Composition.Builder(
                withAudioFrom(ImmutableList.of(editedMediaItem1)),
                withAudioFrom(ImmutableList.of(editedMediaItem2, editedMediaItem2)))
            .build();
    player.setComposition(composition);
    player.prepare();
    player.play();

    TestPlayerRunHelper.runUntilPlaybackState(player, Player.STATE_ENDED);
    player.release();

    DumpFileAsserts.assertOutput(
        context,
        capturingAudioSink,
        PREVIEW_DUMP_FILE_EXTENSION + "wav/compositionOf_sample.wav-clipped__sample_rf64.wav.dump");
  }

  @Test
  public void playSingleItem_withRepeatModeEnabled_outputsCorrectSamples() throws Exception {
    CompositionPlayer player = createCompositionPlayer(context, capturingAudioSink);
    player.setRepeatMode(Player.REPEAT_MODE_ALL);
    EditedMediaItem editedMediaItem =
        new EditedMediaItem.Builder(MediaItem.fromUri(ASSET_URI_PREFIX + FILE_AUDIO_RAW))
            .setDurationUs(1_000_000L)
            .build();
    EditedMediaItemSequence sequence = withAudioFrom(ImmutableList.of(editedMediaItem));
    Composition composition = new Composition.Builder(sequence).build();
    player.setComposition(composition);
    player.prepare();
    player.play();

    TestPlayerRunHelper.runUntilPositionDiscontinuity(
        player, Player.DISCONTINUITY_REASON_AUTO_TRANSITION);
    player.setRepeatMode(Player.REPEAT_MODE_OFF);
    TestPlayerRunHelper.runUntilPlaybackState(player, Player.STATE_ENDED);
    player.release();

    DumpFileAsserts.assertOutput(
        context,
        capturingAudioSink,
        PREVIEW_DUMP_FILE_EXTENSION + FILE_AUDIO_RAW + "_repeated.dump");
  }

  @Test
  public void playMultipleSequences_withShortLoopingSequence_outputsCorrectSamples()
      throws Exception {
    CompositionPlayer player = createCompositionPlayer(context, capturingAudioSink);
    EditedMediaItemSequence primarySequence =
        withAudioFrom(
            ImmutableList.of(
                new EditedMediaItem.Builder(MediaItem.fromUri(ASSET_URI_PREFIX + FILE_AUDIO_RAW))
                    .setDurationUs(1_000_000L)
                    .build()));
    EditedMediaItemSequence loopingSequence =
        new EditedMediaItemSequence.Builder(ImmutableSet.of(TRACK_TYPE_AUDIO))
            .addItem(
                new EditedMediaItem.Builder(
                        MediaItem.fromUri(ASSET_URI_PREFIX + FILE_AUDIO_RAW_STEREO_48000KHZ))
                    .setDurationUs(348_000L)
                    .build())
            .setIsLooping(true)
            .build();
    Composition composition = new Composition.Builder(primarySequence, loopingSequence).build();
    player.setComposition(composition);
    player.prepare();
    player.play();
    TestPlayerRunHelper.runUntilPlaybackState(player, Player.STATE_ENDED);
    player.release();

    DumpFileAsserts.assertOutput(
        context,
        capturingAudioSink,
        PREVIEW_DUMP_FILE_EXTENSION
            + "wav/compositionPlayback_withShortLoopingSequence_outputsCorrectSamples.dump");
  }

  @Test
  public void playMultipleSequences_withLongLoopingSequence_outputsCorrectSamples()
      throws Exception {
    CompositionPlayer player = createCompositionPlayer(context, capturingAudioSink);
    EditedMediaItemSequence primarySequence =
        withAudioFrom(
            ImmutableList.of(
                new EditedMediaItem.Builder(
                        MediaItem.fromUri(ASSET_URI_PREFIX + FILE_AUDIO_RAW_STEREO_48000KHZ))
                    .setDurationUs(348_000L)
                    .build()));
    EditedMediaItemSequence loopingSequence =
        new EditedMediaItemSequence.Builder(ImmutableSet.of(TRACK_TYPE_AUDIO))
            .addItem(
                new EditedMediaItem.Builder(MediaItem.fromUri(ASSET_URI_PREFIX + FILE_AUDIO_RAW))
                    .setDurationUs(1_000_000L)
                    .build())
            .setIsLooping(true)
            .build();
    Composition composition = new Composition.Builder(primarySequence, loopingSequence).build();
    player.setComposition(composition);
    player.prepare();
    player.play();
    TestPlayerRunHelper.runUntilPlaybackState(player, Player.STATE_ENDED);
    player.release();

    DumpFileAsserts.assertOutput(
        context,
        capturingAudioSink,
        PREVIEW_DUMP_FILE_EXTENSION
            + "wav/compositionPlayback_withLongLoopingSequence_outputsCorrectSamples.dump");
  }

  @Test
  public void playTwoSequences_withLongLoopingSequence_hasNonLoopingSequenceDuration() {
    CompositionPlayer player = createCompositionPlayer(context, capturingAudioSink);
    EditedMediaItemSequence primarySequence =
        withAudioFrom(
            ImmutableList.of(
                new EditedMediaItem.Builder(
                        MediaItem.fromUri(ASSET_URI_PREFIX + FILE_AUDIO_RAW_STEREO_48000KHZ))
                    .setDurationUs(348_000L)
                    .build()));
    EditedMediaItemSequence loopingSequence =
        new EditedMediaItemSequence.Builder(ImmutableSet.of(TRACK_TYPE_AUDIO))
            .addItem(
                new EditedMediaItem.Builder(MediaItem.fromUri(ASSET_URI_PREFIX + FILE_AUDIO_RAW))
                    .setDurationUs(1_000_000L)
                    .build())
            .setIsLooping(true)
            .build();
    Composition composition = new Composition.Builder(primarySequence, loopingSequence).build();
    player.setComposition(composition);
    player.prepare();

    assertThat(player.getDuration()).isEqualTo(348);
  }

  @Test
  public void play_audioSequenceWithMiddleGap_outputsCorrectSamples()
      throws TimeoutException, IOException {
    CompositionPlayer player = createCompositionPlayer(context, capturingAudioSink);
    EditedMediaItem clip =
        new EditedMediaItem.Builder(MediaItem.fromUri(ASSET_URI_PREFIX + FILE_AUDIO_RAW))
            .setDurationUs(1_000_000L)
            .build();
    EditedMediaItemSequence sequence =
        new EditedMediaItemSequence.Builder(ImmutableSet.of(TRACK_TYPE_AUDIO))
            .addItem(clip)
            .addGap(500_000L)
            .addItem(clip)
            .build();
    Composition composition = new Composition.Builder(sequence).build();
    player.setComposition(composition);
    player.prepare();
    checkState(player.getDuration() == 2_500L);

    player.play();
    TestPlayerRunHelper.runUntilPlaybackState(player, Player.STATE_ENDED);
    player.release();

    DumpFileAsserts.assertOutput(
        context,
        capturingAudioSink,
        PREVIEW_DUMP_FILE_EXTENSION
            + "wav/sequencePlayback_withMiddleGap_outputsCorrectSamples.dump");
  }

  @Test
  public void play_audioSequenceWithStartGap_outputsCorrectSamples()
      throws TimeoutException, IOException {
    CompositionPlayer player = createCompositionPlayer(context, capturingAudioSink);
    EditedMediaItem clip =
        new EditedMediaItem.Builder(MediaItem.fromUri(ASSET_URI_PREFIX + FILE_AUDIO_RAW))
            .setDurationUs(1_000_000L)
            .build();
    EditedMediaItemSequence sequence =
        new EditedMediaItemSequence.Builder(ImmutableSet.of(TRACK_TYPE_AUDIO))
            .addGap(500_000L)
            .addItem(clip)
            .build();
    Composition composition = new Composition.Builder(sequence).build();
    player.setComposition(composition);
    player.prepare();
    checkState(player.getDuration() == 1_500L);

    player.play();
    TestPlayerRunHelper.runUntilPlaybackState(player, Player.STATE_ENDED);
    player.release();

    DumpFileAsserts.assertOutput(
        context,
        capturingAudioSink,
        PREVIEW_DUMP_FILE_EXTENSION
            + "wav/sequencePlayback_withStartGap_outputsCorrectSamples.dump");
  }

  @Test
  public void playSingleSequence_withRepeatModeEnabled_outputsCorrectSamples() throws Exception {
    CompositionPlayer player = createCompositionPlayer(context, capturingAudioSink);
    player.setRepeatMode(Player.REPEAT_MODE_ALL);
    EditedMediaItem editedMediaItem1 =
        new EditedMediaItem.Builder(MediaItem.fromUri(ASSET_URI_PREFIX + FILE_AUDIO_RAW))
            .setDurationUs(1_000_000L)
            .build();
    EditedMediaItem editedMediaItem2 =
        new EditedMediaItem.Builder(
                MediaItem.fromUri(ASSET_URI_PREFIX + FILE_AUDIO_RAW_STEREO_48000KHZ))
            .setDurationUs(348_000L)
            .build();
    EditedMediaItemSequence sequence =
        withAudioFrom(ImmutableList.of(editedMediaItem1, editedMediaItem2));
    Composition composition = new Composition.Builder(sequence).build();
    player.setComposition(composition);
    player.prepare();
    player.play();

    TestPlayerRunHelper.runUntilPositionDiscontinuity(
        player, Player.DISCONTINUITY_REASON_AUTO_TRANSITION);

    player.setRepeatMode(Player.REPEAT_MODE_OFF);
    TestPlayerRunHelper.runUntilPlaybackState(player, Player.STATE_ENDED);
    player.release();

    DumpFileAsserts.assertOutput(
        context,
        capturingAudioSink,
        PREVIEW_DUMP_FILE_EXTENSION + FILE_AUDIO_RAW + "_then_sample_rf64.wav_repeated.dump");
  }

  @Test
  public void playSingleSequence_withMiddleItemAudioRemoved_outputsCorrectSamples()
      throws Exception {
    CompositionPlayer player = createCompositionPlayer(context, capturingAudioSink);
    EditedMediaItem editedMediaItem =
        new EditedMediaItem.Builder(MediaItem.fromUri(ASSET_URI_PREFIX + FILE_AUDIO_RAW))
            .setDurationUs(1_000_000L)
            .build();
    EditedMediaItem audioRemovedMediaItem =
        editedMediaItem.buildUpon().setRemoveAudio(true).build();
    Composition composition =
        new Composition.Builder(
                withAudioFrom(
                    ImmutableList.of(editedMediaItem, audioRemovedMediaItem, editedMediaItem)))
            .build();
    player.setComposition(composition);
    player.prepare();
    player.play();

    TestPlayerRunHelper.runUntilPlaybackState(player, Player.STATE_ENDED);
    player.release();

    // The silence should be in between the timestamp between [1, 2] seconds.
    DumpFileAsserts.assertOutput(
        context,
        capturingAudioSink,
        PREVIEW_DUMP_FILE_EXTENSION
            + "wav/sequencePlayback_withThreeMediaAndRemovingMiddleAudio_outputsCorrectSamples.dump");
  }

  @Test
  public void playSingleSequence_withFirstAndLastItemAudioRemoved_outputsCorrectSamples()
      throws Exception {
    CompositionPlayer player = createCompositionPlayer(context, capturingAudioSink);
    EditedMediaItem editedMediaItem =
        new EditedMediaItem.Builder(MediaItem.fromUri(ASSET_URI_PREFIX + FILE_AUDIO_RAW))
            .setDurationUs(1_000_000L)
            .build();
    EditedMediaItem audioRemovedMediaItem =
        editedMediaItem.buildUpon().setRemoveAudio(true).build();
    Composition composition =
        new Composition.Builder(
                withAudioFrom(
                    ImmutableList.of(
                        audioRemovedMediaItem, editedMediaItem, audioRemovedMediaItem)))
            .build();
    player.setComposition(composition);
    player.prepare();
    player.play();

    TestPlayerRunHelper.runUntilPlaybackState(player, Player.STATE_ENDED);
    player.release();

    // The silence should be in between the timestamp between [0, 1] and [2, 3] seconds.
    DumpFileAsserts.assertOutput(
        context,
        capturingAudioSink,
        PREVIEW_DUMP_FILE_EXTENSION
            + "wav/sequencePlayback_withThreeMediaAndRemovingFirstAndThirdAudio_outputsCorrectSamples.dump");
  }

  @Test
  public void playMultipleSequences_withRepeatModeEnabled_outputsCorrectSamples() throws Exception {
    CompositionPlayer player = createCompositionPlayer(context, capturingAudioSink);
    player.setRepeatMode(Player.REPEAT_MODE_ALL);
    EditedMediaItem editedMediaItem1 =
        new EditedMediaItem.Builder(
                new MediaItem.Builder()
                    .setUri(ASSET_URI_PREFIX + FILE_AUDIO_RAW)
                    .setClippingConfiguration(
                        new MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(0)
                            .setEndPositionUs(696_000)
                            .build())
                    .build())
            .setDurationUs(1_000_000L)
            .build();
    EditedMediaItem editedMediaItem2 =
        new EditedMediaItem.Builder(
                MediaItem.fromUri(ASSET_URI_PREFIX + FILE_AUDIO_RAW_STEREO_48000KHZ))
            .setDurationUs(348_000L)
            .build();
    Composition composition =
        new Composition.Builder(
                withAudioFrom(ImmutableList.of(editedMediaItem1)),
                withAudioFrom(ImmutableList.of(editedMediaItem2, editedMediaItem2)))
            .build();
    player.setComposition(composition);
    player.prepare();
    player.play();

    TestPlayerRunHelper.runUntilPositionDiscontinuity(
        player, Player.DISCONTINUITY_REASON_AUTO_TRANSITION);

    player.setRepeatMode(Player.REPEAT_MODE_OFF);
    TestPlayerRunHelper.runUntilPlaybackState(player, Player.STATE_ENDED);
    player.release();

    DumpFileAsserts.assertOutput(
        context,
        capturingAudioSink,
        PREVIEW_DUMP_FILE_EXTENSION
            + "wav/repeatedCompositionOf_sample.wav-clipped__sample_rf64.wav.dump");
  }

  @Test
  public void play_itemsWithNon16BitPcm_inputIsConvertedTo16BitPcm() throws Exception {
    FormatCapturingAudioProcessor firstProcessor = new FormatCapturingAudioProcessor();
    FormatCapturingAudioProcessor secondProcessor = new FormatCapturingAudioProcessor();
    EditedMediaItem firstItem =
        new EditedMediaItem.Builder(MediaItem.fromUri(WAV_32LE_PCM_ASSET.uri))
            .setDurationUs(WAV_32LE_PCM_ASSET.audioDurationUs)
            .setEffects(createAudioEffects(firstProcessor))
            .build();
    EditedMediaItem secondItem =
        new EditedMediaItem.Builder(MediaItem.fromUri(WAV_24LE_PCM_ASSET.uri))
            .setDurationUs(WAV_24LE_PCM_ASSET.audioDurationUs)
            .setEffects(createAudioEffects(secondProcessor))
            .build();
    Composition composition =
        new Composition.Builder(
                EditedMediaItemSequence.withAudioFrom(ImmutableList.of(firstItem, secondItem)))
            .build();
    CompositionPlayer player = createTestCompositionPlayer();

    player.setComposition(composition);
    player.prepare();
    play(player).untilState(Player.STATE_ENDED);

    // Channel mixing happens after user-provided processors, so we can still see the original
    // sample rate and channel count of each input file.
    assertThat(firstProcessor.inputFormat.get().encoding).isEqualTo(C.ENCODING_PCM_16BIT);
    assertThat(firstProcessor.inputFormat.get().sampleRate).isEqualTo(48000);
    assertThat(firstProcessor.inputFormat.get().channelCount).isEqualTo(2);

    assertThat(secondProcessor.inputFormat.get().encoding).isEqualTo(C.ENCODING_PCM_16BIT);
    assertThat(secondProcessor.inputFormat.get().sampleRate).isEqualTo(44100);
    assertThat(secondProcessor.inputFormat.get().channelCount).isEqualTo(1);
  }

  @Test
  public void seekTo_singleSequence_outputsCorrectSamples() throws Exception {
    CompositionPlayer player = createCompositionPlayer(context, capturingAudioSink);
    EditedMediaItem editedMediaItem =
        new EditedMediaItem.Builder(MediaItem.fromUri(ASSET_URI_PREFIX + FILE_AUDIO_RAW))
            .setDurationUs(1_000_000L)
            .build();
    EditedMediaItemSequence sequence = withAudioFrom(ImmutableList.of(editedMediaItem));
    Composition composition = new Composition.Builder(sequence).build();
    player.setComposition(composition);

    player.seekTo(/* positionMs= */ 500);
    player.prepare();
    player.play();
    advance(player).untilState(Player.STATE_ENDED);
    player.release();

    DumpFileAsserts.assertOutput(
        context,
        capturingAudioSink,
        PREVIEW_DUMP_FILE_EXTENSION + FILE_AUDIO_RAW + "/seek_to_500_ms.dump");
  }

  @Test
  public void seekToNextMediaItem_singleSequence_outputsCorrectSamples() throws Exception {
    CompositionPlayer player = createCompositionPlayer(context, capturingAudioSink);
    EditedMediaItem editedMediaItem1 =
        new EditedMediaItem.Builder(MediaItem.fromUri(ASSET_URI_PREFIX + FILE_AUDIO_RAW))
            .setDurationUs(1_000_000L)
            .build();
    EditedMediaItem editedMediaItem2 =
        new EditedMediaItem.Builder(
                MediaItem.fromUri(ASSET_URI_PREFIX + FILE_AUDIO_RAW_STEREO_48000KHZ))
            .setDurationUs(348_000L)
            .build();
    EditedMediaItemSequence sequence =
        withAudioFrom(ImmutableList.of(editedMediaItem1, editedMediaItem2));
    Composition composition = new Composition.Builder(sequence).build();
    player.setComposition(composition);

    player.seekTo(/* positionMs= */ 1200);
    player.prepare();
    player.play();
    advance(player).untilState(Player.STATE_ENDED);
    player.release();

    DumpFileAsserts.assertOutput(
        context,
        capturingAudioSink,
        PREVIEW_DUMP_FILE_EXTENSION
            + FILE_AUDIO_RAW
            + "_then_sample_rf64.wav_seek_to_1200_ms.dump");
  }

  @Test
  public void seekToPreviousMediaItem_singleSequence_outputsCorrectSamples() throws Exception {
    CompositionPlayer player = createCompositionPlayer(context, capturingAudioSink);
    EditedMediaItem editedMediaItem1 =
        new EditedMediaItem.Builder(MediaItem.fromUri(ASSET_URI_PREFIX + FILE_AUDIO_RAW))
            .setDurationUs(1_000_000L)
            .build();
    EditedMediaItem editedMediaItem2 =
        new EditedMediaItem.Builder(
                MediaItem.fromUri(ASSET_URI_PREFIX + FILE_AUDIO_RAW_STEREO_48000KHZ))
            .setDurationUs(348_000L)
            .build();
    EditedMediaItemSequence sequence =
        withAudioFrom(ImmutableList.of(editedMediaItem1, editedMediaItem2));
    Composition composition = new Composition.Builder(sequence).build();
    player.setComposition(composition);

    player.seekTo(/* positionMs= */ 1200);
    player.seekTo(/* positionMs= */ 500);
    player.prepare();
    player.play();
    advance(player).untilState(Player.STATE_ENDED);
    player.release();

    DumpFileAsserts.assertOutput(
        context,
        capturingAudioSink,
        PREVIEW_DUMP_FILE_EXTENSION + FILE_AUDIO_RAW + "_then_sample_rf64.wav_seek_to_500_ms.dump");
  }

  @Test
  public void seekTo_singleSequenceWithClipping_outputsCorrectSamples() throws Exception {
    CompositionPlayer player = createCompositionPlayer(context, capturingAudioSink);
    MediaItem mediaItem1 =
        new MediaItem.Builder()
            .setUri(ASSET_URI_PREFIX + FILE_AUDIO_RAW)
            .setClippingConfiguration(
                new MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(200)
                    .setEndPositionMs(900)
                    .build())
            .build();
    EditedMediaItem editedMediaItem1 =
        new EditedMediaItem.Builder(mediaItem1).setDurationUs(1_000_000L).build();
    MediaItem mediaItem2 =
        new MediaItem.Builder()
            .setUri(ASSET_URI_PREFIX + FILE_AUDIO_RAW_STEREO_48000KHZ)
            .setClippingConfiguration(
                new MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(100)
                    .setEndPositionMs(300)
                    .build())
            .build();
    EditedMediaItem editedMediaItem2 =
        new EditedMediaItem.Builder(mediaItem2).setDurationUs(348_000L).build();
    EditedMediaItemSequence sequence =
        withAudioFrom(ImmutableList.of(editedMediaItem1, editedMediaItem2));
    Composition composition = new Composition.Builder(sequence).build();
    player.setComposition(composition);

    player.seekTo(/* positionMs= */ 800);
    player.prepare();
    player.play();
    advance(player).untilState(Player.STATE_ENDED);
    player.release();

    DumpFileAsserts.assertOutput(
        context,
        capturingAudioSink,
        PREVIEW_DUMP_FILE_EXTENSION
            + FILE_AUDIO_RAW
            + "_then_sample_rf64.wav_clipped_seek_to_800_ms.dump");
  }

  @Test
  public void playSingleSequence_replayAfterEnd_outputCorrectSamples() throws Exception {
    CompositionPlayer player = createCompositionPlayer(context, capturingAudioSink);
    EditedMediaItem editedMediaItem =
        new EditedMediaItem.Builder(MediaItem.fromUri(ASSET_URI_PREFIX + FILE_AUDIO_RAW))
            .setDurationUs(1_000_000L)
            .build();
    EditedMediaItemSequence sequence = withAudioFrom(ImmutableList.of(editedMediaItem));
    Composition composition = new Composition.Builder(sequence).build();

    player.setComposition(composition);
    player.prepare();
    // First Play
    player.play();
    advance(player).untilState(Player.STATE_ENDED);
    // Second Play
    player.seekToDefaultPosition();
    player.play();
    advance(player).untilState(Player.STATE_ENDED);
    player.release();

    DumpFileAsserts.assertOutput(
        context,
        capturingAudioSink,
        PREVIEW_DUMP_FILE_EXTENSION + FILE_AUDIO_RAW + "_playedTwice.dump");
  }

  @Test
  public void playSingleSequence_withCustomAudioMixer_mixesTheCorrectNumberOfBytes()
      throws Exception {
    AtomicLong bytesMixed = new AtomicLong();
    AudioMixer.Factory forwardingAudioMixerFactory =
        () ->
            new ForwardingAudioMixer(new DefaultAudioMixer.Factory().create()) {
              @Override
              public void queueInput(int sourceId, ByteBuffer sourceBuffer) {
                bytesMixed.addAndGet(sourceBuffer.remaining());
                super.queueInput(sourceId, sourceBuffer);
              }
            };
    CompositionPlayer player =
        new CompositionPlayer.Builder(context)
            .setClock(new FakeClock(/* isAutoAdvancing= */ true))
            .setAudioMixerFactory(forwardingAudioMixerFactory)
            .build();
    EditedMediaItem editedMediaItem =
        new EditedMediaItem.Builder(MediaItem.fromUri(ASSET_URI_PREFIX + FILE_AUDIO_RAW))
            .setDurationUs(1_000_000L)
            .build();
    EditedMediaItemSequence sequence = withAudioFrom(ImmutableList.of(editedMediaItem));
    Composition composition = new Composition.Builder(sequence).build();

    player.setComposition(composition);
    player.prepare();
    player.play();
    advance(player).untilState(Player.STATE_ENDED);
    player.release();

    // Expect 1 second of single-channel, 44_100Hz, 2 bytes per sample.
    assertThat(bytesMixed.get()).isEqualTo(88_200);
  }

  @Test
  public void playback_withRawAudioStream_signalsPositionOffsetOfZero() throws Exception {
    PositionOffsetRecorder processor = new PositionOffsetRecorder();
    EditedMediaItem item =
        new EditedMediaItem.Builder(MediaItem.fromUri(WAV_ASSET.uri))
            .setDurationUs(1_000_000)
            .setEffects(new Effects(ImmutableList.of(processor), ImmutableList.of()))
            .build();
    Composition composition =
        new Composition.Builder(EditedMediaItemSequence.withAudioFrom(ImmutableList.of(item, item)))
            .build();

    CompositionPlayer player = createTestCompositionPlayer();
    player.setComposition(composition);
    player.prepare();
    play(player).untilState(Player.STATE_ENDED);

    // The audio pipeline calls an additional flush with a position offset of 0 before it knows the
    // actual position offset.
    assertThat(processor.positionOffsetsUs).containsExactly(0L, 0L, 0L);
  }

  @Test
  public void playback_withClippedRawAudioStream_signalsPositionOffsetOfZero() throws Exception {
    PositionOffsetRecorder processor = new PositionOffsetRecorder();
    EditedMediaItem item =
        new EditedMediaItem.Builder(
                MediaItem.fromUri(WAV_ASSET.uri)
                    .buildUpon()
                    .setClippingConfiguration(
                        new ClippingConfiguration.Builder().setStartPositionMs(500).build())
                    .build())
            .setDurationUs(1_000_000)
            .setEffects(new Effects(ImmutableList.of(processor), ImmutableList.of()))
            .build();
    Composition composition =
        new Composition.Builder(EditedMediaItemSequence.withAudioFrom(ImmutableList.of(item, item)))
            .build();

    CompositionPlayer player = createTestCompositionPlayer();
    player.setComposition(composition);
    player.prepare();
    play(player).untilState(Player.STATE_ENDED);

    // The audio pipeline calls an additional flush with a position offset of 0 before it knows the
    // actual position offset.
    assertThat(processor.positionOffsetsUs).containsExactly(0L, 0L, 0L);
  }

  @Test
  public void playback_withSpeedAdjustedRawAudioStream_signalsPositionOffsetOfZero()
      throws Exception {
    PositionOffsetRecorder processor = new PositionOffsetRecorder();
    EditedMediaItem item =
        new EditedMediaItem.Builder(MediaItem.fromUri(WAV_ASSET.uri))
            .setDurationUs(1_000_000)
            .setEffects(new Effects(ImmutableList.of(processor), ImmutableList.of()))
            .setSpeed(SPEED_PROVIDER_2X)
            .build();
    Composition composition =
        new Composition.Builder(EditedMediaItemSequence.withAudioFrom(ImmutableList.of(item, item)))
            .build();

    CompositionPlayer player = createTestCompositionPlayer();
    player.setComposition(composition);
    player.prepare();
    play(player).untilState(Player.STATE_ENDED);

    // The audio pipeline calls an additional flush with a position offset of 0 before it knows the
    // actual position offset.
    assertThat(processor.positionOffsetsUs).containsExactly(0L, 0L, 0L);
  }

  @Test
  public void playback_withSpeedAdjustedAndClippedRawAudioStream_signalsPositionOffsetOfZero()
      throws Exception {
    PositionOffsetRecorder processor = new PositionOffsetRecorder();
    EditedMediaItem item =
        new EditedMediaItem.Builder(
                MediaItem.fromUri(WAV_ASSET.uri)
                    .buildUpon()
                    .setClippingConfiguration(
                        new ClippingConfiguration.Builder().setStartPositionMs(500).build())
                    .build())
            .setDurationUs(1_000_000)
            .setEffects(new Effects(ImmutableList.of(processor), ImmutableList.of()))
            .setSpeed(SPEED_PROVIDER_2X)
            .build();
    Composition composition =
        new Composition.Builder(EditedMediaItemSequence.withAudioFrom(ImmutableList.of(item, item)))
            .build();

    CompositionPlayer player = createTestCompositionPlayer();
    player.setComposition(composition);
    player.prepare();
    play(player).untilState(Player.STATE_ENDED);

    // The audio pipeline calls an additional flush with a position offset of 0 before it knows the
    // actual position offset.
    assertThat(processor.positionOffsetsUs).containsExactly(0L, 0L, 0L);
  }

  @Test
  public void seek_withRawAudioStream_signalsNextFrameAsPositionOffset() throws Exception {
    PositionOffsetRecorder processor = new PositionOffsetRecorder();
    EditedMediaItem item =
        new EditedMediaItem.Builder(MediaItem.fromUri(WAV_ASSET.uri))
            .setDurationUs(1_000_000)
            .setEffects(new Effects(ImmutableList.of(processor), ImmutableList.of()))
            .build();
    Composition composition =
        new Composition.Builder(EditedMediaItemSequence.withAudioFrom(ImmutableList.of(item, item)))
            .build();

    CompositionPlayer player = createTestCompositionPlayer();
    player.setComposition(composition);
    player.prepare();
    advance(player).untilState(STATE_READY);
    player.seekTo(250);
    play(player).untilState(Player.STATE_ENDED);

    // The audio processor receives 3 additional flushes before the position offset is known: one
    // when creating the AudioGraphInput, then when configuring the new EditedMediaItem, and finally
    // when starting the seek from PlaybackAudioGraphWrapper.
    // The wav extractor pretends that the file has frames of 100ms for seeking. The next audio
    // frame after seek of 250ms is 300ms (b/458654879).
    assertThat(processor.positionOffsetsUs).containsExactly(0L, 0L, 0L, 300_000L, 0L).inOrder();
  }

  @Test
  public void seek_withClippedRawAudioStream_signalsSeekPositionAsPositionOffset()
      throws Exception {
    PositionOffsetRecorder processor = new PositionOffsetRecorder();
    EditedMediaItem item =
        new EditedMediaItem.Builder(
                MediaItem.fromUri(WAV_ASSET.uri)
                    .buildUpon()
                    .setClippingConfiguration(
                        new ClippingConfiguration.Builder().setStartPositionMs(500).build())
                    .build())
            .setDurationUs(1_000_000)
            .setEffects(new Effects(ImmutableList.of(processor), ImmutableList.of()))
            .build();
    Composition composition =
        new Composition.Builder(EditedMediaItemSequence.withAudioFrom(ImmutableList.of(item)))
            .build();

    CompositionPlayer player = createTestCompositionPlayer();
    player.setComposition(composition);
    player.prepare();
    advance(player).untilState(STATE_READY);
    player.seekTo(250);
    play(player).untilState(Player.STATE_ENDED);

    // The audio processor receives 3 additional flushes before the position offset is known: one
    // when creating the AudioGraphInput, then when configuring the new EditedMediaItem, and finally
    // when starting the seek from PlaybackAudioGraphWrapper.
    // The wav extractor pretends that the file has frames of 100ms for seeking. The next audio
    // frame after seek of 250ms is 300ms (b/458654879).
    assertThat(processor.positionOffsetsUs).containsExactly(0L, 0L, 0L, 300000L).inOrder();
  }

  @Test
  public void seek_withSpeedAdjustedRawAudioStream_signalsSeekPositionAsPositionOffset()
      throws Exception {
    PositionOffsetRecorder processor = new PositionOffsetRecorder();
    EditedMediaItem item =
        new EditedMediaItem.Builder(MediaItem.fromUri(WAV_ASSET.uri))
            .setDurationUs(1_000_000)
            .setEffects(new Effects(ImmutableList.of(processor), ImmutableList.of()))
            .setSpeed(SPEED_PROVIDER_2X)
            .build();
    Composition composition =
        new Composition.Builder(EditedMediaItemSequence.withAudioFrom(ImmutableList.of(item, item)))
            .build();

    CompositionPlayer player = createTestCompositionPlayer();
    player.setComposition(composition);
    player.prepare();
    player.seekTo(250);
    play(player).untilState(Player.STATE_ENDED);

    // The audio pipeline calls an additional flush with a position offset of 0 before it knows the
    // actual position offset.
    assertThat(processor.positionOffsetsUs).containsExactly(0L, 250_000L, 0L).inOrder();
  }

  @Test
  public void seek_withSpeedAdjustedRawAudioStream_appliesCorrectSpeedRegion() throws Exception {
    PositionOffsetRecorder processor = new PositionOffsetRecorder();
    AtomicInteger bytesRead = new AtomicInteger();
    AudioProcessor byteCountingAudioProcessor = createByteCountingAudioProcessor(bytesRead);
    EditedMediaItem normalSpeedItem =
        new EditedMediaItem.Builder(MediaItem.fromUri(WAV_ASSET.uri))
            .setDurationUs(1_000_000)
            .build();
    EditedMediaItem item =
        normalSpeedItem
            .buildUpon()
            .setEffects(createAudioEffects(processor, byteCountingAudioProcessor))
            .setSpeed(SPEED_PROVIDER_MULTIPLE_SPEEDS)
            .build();
    Composition composition =
        new Composition.Builder(
                EditedMediaItemSequence.withAudioFrom(ImmutableList.of(normalSpeedItem, item)))
            .build();

    CompositionPlayer player = createCompositionPlayer(context, capturingAudioSink);
    player.setComposition(composition);
    player.prepare();
    player.seekTo(/* positionMs= */ 1250);
    play(player).untilState(Player.STATE_ENDED);

    // The audio pipeline calls an additional flush with a position offset of 0 before it knows the
    // actual position offset. Seek position 1250ms maps to speed adjusted position 250ms within the
    // second item.
    assertThat(processor.positionOffsetsUs).containsExactly(0L, 250_000L).inOrder();
    assertThat(bytesRead.get() / 2).isEqualTo(44100);
    DumpFileAsserts.assertOutput(
        context,
        capturingAudioSink,
        PREVIEW_DUMP_FILE_EXTENSION
            + "seek_withSpeedAdjustedRawAudioStream_appliesCorrectSpeedRegion.dump");
  }

  @Test
  public void seek_withClippedSpeedAdjustedRawAudioStream_appliesCorrectSpeedRegion()
      throws Exception {
    PositionOffsetRecorder processor = new PositionOffsetRecorder();
    AtomicInteger bytesRead = new AtomicInteger();
    AudioProcessor byteCountingAudioProcessor = createByteCountingAudioProcessor(bytesRead);
    EditedMediaItem normalSpeedItem =
        new EditedMediaItem.Builder(MediaItem.fromUri(WAV_ASSET.uri))
            .setDurationUs(1_000_000)
            .build();
    EditedMediaItem item =
        new EditedMediaItem.Builder(
                new MediaItem.Builder()
                    .setUri(WAV_ASSET.uri)
                    .setClippingConfiguration(
                        new ClippingConfiguration.Builder().setStartPositionMs(100).build())
                    .build())
            .setDurationUs(1_000_000)
            .setEffects(createAudioEffects(processor, byteCountingAudioProcessor))
            .setSpeed(SPEED_PROVIDER_MULTIPLE_SPEEDS)
            .build();
    Composition composition =
        new Composition.Builder(
                EditedMediaItemSequence.withAudioFrom(ImmutableList.of(normalSpeedItem, item)))
            .build();

    CompositionPlayer player = createCompositionPlayer(context, capturingAudioSink);
    player.setComposition(composition);
    player.prepare();
    player.seekTo(/* positionMs= */ 1100);
    play(player).untilState(Player.STATE_ENDED);

    // The audio pipeline calls an additional flush with a position offset of 0 before it knows the
    // actual position offset. Seek position 1100ms maps to speed adjusted and clipped position
    // 100ms within the second item.
    assertThat(processor.positionOffsetsUs).containsExactly(0L, 100_000L).inOrder();
    assertThat(bytesRead.get() / 2).isWithin(1).of(41895);
    DumpFileAsserts.assertOutput(
        context,
        capturingAudioSink,
        PREVIEW_DUMP_FILE_EXTENSION
            + "seek_withClippedSpeedAdjustedRawAudioStream_appliesCorrectSpeedRegion.dump");
  }

  @Test
  public void seek_withSpeedAdjustedAndClippedRawAudioStream_signalsSeekPositionAsPositionOffset()
      throws Exception {
    PositionOffsetRecorder processor = new PositionOffsetRecorder();
    EditedMediaItem item =
        new EditedMediaItem.Builder(
                MediaItem.fromUri(WAV_ASSET.uri)
                    .buildUpon()
                    .setClippingConfiguration(
                        new ClippingConfiguration.Builder().setStartPositionMs(500).build())
                    .build())
            .setDurationUs(1_000_000)
            .setEffects(new Effects(ImmutableList.of(processor), ImmutableList.of()))
            .setSpeed(SPEED_PROVIDER_2X)
            .build();
    Composition composition =
        new Composition.Builder(EditedMediaItemSequence.withAudioFrom(ImmutableList.of(item, item)))
            .build();

    CompositionPlayer player = createTestCompositionPlayer();
    player.setComposition(composition);
    player.prepare();
    player.seekTo(100);
    play(player).untilState(Player.STATE_ENDED);

    // The audio pipeline calls an additional flush with a position offset of 0 before it knows the
    // actual position offset.
    assertThat(processor.positionOffsetsUs).containsExactly(0L, 100_000L, 0L).inOrder();
  }

  @Test
  public void playUntilEnd_finalSinkIsEnded() throws Exception {
    EditedMediaItem item =
        new EditedMediaItem.Builder(MediaItem.fromUri(WAV_ASSET.uri))
            .setDurationUs(1_000_000)
            .build();
    Composition composition =
        new Composition.Builder(EditedMediaItemSequence.withAudioFrom(ImmutableList.of(item)))
            .build();
    CompositionPlayer player = createCompositionPlayer(context, capturingAudioSink);
    player.setComposition(composition);
    player.prepare();
    player.play();
    advance(player).untilState(Player.STATE_ENDED);
    assertThat(capturingAudioSink.isEnded()).isTrue();
  }

  private static class ForwardingAudioMixer implements AudioMixer {

    private final AudioMixer wrappedAudioMixer;

    public ForwardingAudioMixer(AudioMixer audioMixer) {
      wrappedAudioMixer = audioMixer;
    }

    @Override
    public void configure(
        AudioProcessor.AudioFormat outputAudioFormat, int bufferSizeMs, long startTimeUs)
        throws AudioProcessor.UnhandledAudioFormatException {
      wrappedAudioMixer.configure(outputAudioFormat, bufferSizeMs, startTimeUs);
    }

    @Override
    public void setEndTimeUs(long endTimeUs) {
      wrappedAudioMixer.setEndTimeUs(endTimeUs);
    }

    @Override
    public boolean supportsSourceAudioFormat(AudioProcessor.AudioFormat sourceFormat) {
      return wrappedAudioMixer.supportsSourceAudioFormat(sourceFormat);
    }

    @Override
    public int addSource(AudioProcessor.AudioFormat sourceFormat, long startTimeUs)
        throws AudioProcessor.UnhandledAudioFormatException {
      return wrappedAudioMixer.addSource(sourceFormat, startTimeUs);
    }

    @Override
    public boolean hasSource(int sourceId) {
      return wrappedAudioMixer.hasSource(sourceId);
    }

    @Override
    public void setSourceVolume(int sourceId, float volume) {
      wrappedAudioMixer.setSourceVolume(sourceId, volume);
    }

    @Override
    public void removeSource(int sourceId) {
      wrappedAudioMixer.removeSource(sourceId);
    }

    @Override
    public void queueInput(int sourceId, ByteBuffer sourceBuffer) {
      wrappedAudioMixer.queueInput(sourceId, sourceBuffer);
    }

    @Override
    public ByteBuffer getOutput() {
      return wrappedAudioMixer.getOutput();
    }

    @Override
    public boolean isEnded() {
      return wrappedAudioMixer.isEnded();
    }

    @Override
    public void reset() {
      wrappedAudioMixer.reset();
    }
  }

  private static CompositionPlayer createCompositionPlayer(Context context, AudioSink audioSink) {
    return new CompositionPlayer.Builder(context)
        .setClock(new FakeClock(/* isAutoAdvancing= */ true))
        .setAudioSink(audioSink)
        .build();
  }

  private static class PositionOffsetRecorder extends PassthroughAudioProcessor {
    private final List<Long> positionOffsetsUs = new CopyOnWriteArrayList<>();

    @Override
    protected void onFlush(StreamMetadata streamMetadata) {
      positionOffsetsUs.add(streamMetadata.positionOffsetUs);
    }
  }
}
