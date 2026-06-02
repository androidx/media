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

import static androidx.media3.common.Player.STATE_BUFFERING;
import static androidx.media3.common.Player.STATE_ENDED;
import static androidx.media3.common.Player.STATE_IDLE;
import static androidx.media3.common.Player.STATE_READY;
import static androidx.media3.common.util.Util.usToMs;
import static androidx.media3.test.utils.AssetInfo.MP4_12_5FPS;
import static androidx.media3.test.utils.AssetInfo.MP4_15FPS;
import static androidx.media3.test.utils.AssetInfo.MP4_SIMPLE_ASSET;
import static androidx.media3.test.utils.AssetInfo.WAV_ASSET;
import static androidx.media3.test.utils.TestUtil.createByteCountingAudioProcessor;
import static androidx.media3.test.utils.TestUtil.getCommandsAsList;
import static androidx.media3.test.utils.robolectric.RobolectricUtil.runMainLooperUntil;
import static androidx.media3.test.utils.robolectric.ShadowMediaCodecConfig.CODEC_INFO_AVC;
import static androidx.media3.test.utils.robolectric.ShadowMediaCodecConfig.CODEC_INFO_RAW;
import static androidx.media3.test.utils.robolectric.TestPlayerRunHelper.advance;
import static androidx.media3.test.utils.robolectric.TestPlayerRunHelper.play;
import static androidx.media3.transformer.EditedMediaItemSequence.withAudioFrom;
import static androidx.media3.transformer.TestUtil.ASSET_URI_PREFIX;
import static androidx.media3.transformer.TestUtil.FILE_AUDIO_RAW;
import static androidx.media3.transformer.TestUtil.FILE_AUDIO_RAW_STEREO_48000KHZ;
import static androidx.media3.transformer.TestUtil.FILE_PNG;
import static androidx.media3.transformer.TestUtil.createTestCompositionPlayer;
import static androidx.media3.transformer.TestUtil.createTestCompositionPlayerBuilder;
import static androidx.media3.transformer.TestUtil.createTestHardwareBufferCompositionPlayerBuilder;
import static androidx.test.core.app.ApplicationProvider.getApplicationContext;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.view.Surface;
import android.view.TextureView;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.Timeline;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.SpeedChangingAudioProcessor;
import androidx.media3.common.audio.SpeedProvider;
import androidx.media3.common.util.ConditionVariable;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.NullableType;
import androidx.media3.common.video.AsyncFrame;
import androidx.media3.common.video.Frame;
import androidx.media3.common.video.FrameProcessor;
import androidx.media3.common.video.FrameWriter;
import androidx.media3.common.video.SyncFenceWrapper;
import androidx.media3.effect.AlphaScale;
import androidx.media3.effect.DebugTraceUtil;
import androidx.media3.effect.SpeedChangeEffect;
import androidx.media3.effect.TimestampAdjustment;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.analytics.PlayerId;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.DefaultAudioSink;
import androidx.media3.exoplayer.audio.ForwardingAudioSink;
import androidx.media3.exoplayer.audio.TrimmingAudioProcessor;
import androidx.media3.test.utils.FakeFrameProcessor;
import androidx.media3.test.utils.TestSpeedProvider;
import androidx.media3.test.utils.robolectric.ShadowMediaCodecConfig;
import androidx.media3.test.utils.robolectric.TestPlayerRunHelper;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterValuesProvider;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.robolectric.RobolectricTestParameterInjector;
import org.robolectric.shadows.ShadowLooper;

/** Unit tests for {@link CompositionPlayer}. */
@RunWith(RobolectricTestParameterInjector.class)
public class CompositionPlayerTest {
  @Rule
  public ShadowMediaCodecConfig shadowMediaCodecConfig =
      ShadowMediaCodecConfig.withCodecs(
          /* decoders= */ ImmutableList.of(CODEC_INFO_AVC),
          /* encoders= */ ImmutableList.of(CODEC_INFO_RAW));

  private static final long TEST_TIMEOUT_MS = 1_000;

  private static final SpeedProvider TEST_SPEED_PROVIDER =
      new SpeedProvider() {
        @Override
        public float getSpeed(long timeUs) {
          return 1f;
        }

        @Override
        public long getNextSpeedChangeTimeUs(long timeUs) {
          return C.TIME_UNSET;
        }
      };
  private static final SpeedProvider DOUBLE_SPEED_PROVIDER =
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

  private static final SpeedProvider HALF_SPEED_PROVIDER =
      new SpeedProvider() {
        @Override
        public float getSpeed(long timeUs) {
          return 0.5f;
        }

        @Override
        public long getNextSpeedChangeTimeUs(long timeUs) {
          return C.TIME_UNSET;
        }
      };
  private static final Input TWO_AUDIO_ITEMS =
      new Input(
          CompositionPlayerTest::buildComposition,
          /* expectedDurationMs= */ 1_348,
          "TWO_AUDIO_ITEMS");
  private static final Input TWO_AUDIO_ITEMS_WITH_SET_SPEED =
      new Input(
          () -> {
            EditedMediaItem item1 =
                new EditedMediaItem.Builder(MediaItem.fromUri(ASSET_URI_PREFIX + FILE_AUDIO_RAW))
                    .setDurationUs(1_000_000L)
                    .setSpeed(HALF_SPEED_PROVIDER)
                    .build();
            EditedMediaItem item2 =
                new EditedMediaItem.Builder(
                        MediaItem.fromUri(ASSET_URI_PREFIX + FILE_AUDIO_RAW_STEREO_48000KHZ))
                    .setDurationUs(348_000L)
                    .setSpeed(DOUBLE_SPEED_PROVIDER)
                    .build();
            return new Composition.Builder(withAudioFrom(ImmutableList.of(item1, item2))).build();
          },
          /* expectedDurationMs= */ 2_174,
          "TWO_AUDIO_ITEMS_WITH_SET_SPEED");
  private static final Input AUDIO_ITEM_WITH_SET_DOUBLE_SPEED =
      new Input(
          () -> {
            EditedMediaItem item =
                new EditedMediaItem.Builder(MediaItem.fromUri(ASSET_URI_PREFIX + FILE_AUDIO_RAW))
                    .setDurationUs(1_000_000L)
                    .setSpeed(DOUBLE_SPEED_PROVIDER)
                    .build();
            return new Composition.Builder(withAudioFrom(ImmutableList.of(item))).build();
          },
          /* expectedDurationMs= */ 500,
          "AUDIO_ITEM_WITH_SET_DOUBLE_SPEED");
  private static final Input AUDIO_ITEM_WITH_CLIPPED_START =
      new Input(
          () -> {
            MediaItem mediaItem =
                new MediaItem.Builder()
                    .setUri(ASSET_URI_PREFIX + FILE_AUDIO_RAW)
                    .setClippingConfiguration(
                        new MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionUs(200_000)
                            .build())
                    .build();
            EditedMediaItem editedMediaItem =
                new EditedMediaItem.Builder(mediaItem).setDurationUs(1_000_000L).build();
            return new Composition.Builder(withAudioFrom(ImmutableList.of(editedMediaItem)))
                .build();
          },
          /* expectedDurationMs= */ 800,
          "AUDIO_ITEM_WITH_CLIPPED_START");
  private static final Input AUDIO_ITEM_WITH_CLIPPED_START_GREATER_THAN_HALF_DURATION =
      new Input(
          () -> {
            // This test covers cases where the clipped duration exceeds half the original duration.
            // It is needed to make sure no problems arise from clipping in this case. This would
            // catch removing ClippingConfiguration wrapping SilenceMediaSource, because the problem
            // only occurs when the clippedDuration exceeds half the original duration.
            MediaItem mediaItem =
                new MediaItem.Builder()
                    .setUri(ASSET_URI_PREFIX + FILE_AUDIO_RAW)
                    .setClippingConfiguration(
                        new MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionUs(600_000)
                            .build())
                    .build();
            EditedMediaItem editedMediaItem =
                new EditedMediaItem.Builder(mediaItem).setDurationUs(1_000_000L).build();
            return new Composition.Builder(withAudioFrom(ImmutableList.of(editedMediaItem)))
                .build();
          },
          /* expectedDurationMs= */ 400,
          "AUDIO_ITEM_WITH_CLIPPED_START_GREATER_THAN_HALF_DURATION");
  private static final Input AUDIO_ITEM_WITH_CLIPPED_END =
      new Input(
          () -> {
            MediaItem mediaItem =
                new MediaItem.Builder()
                    .setUri(ASSET_URI_PREFIX + FILE_AUDIO_RAW)
                    .setClippingConfiguration(
                        new MediaItem.ClippingConfiguration.Builder()
                            .setEndPositionUs(600_000)
                            .build())
                    .build();
            EditedMediaItem editedMediaItem =
                new EditedMediaItem.Builder(mediaItem).setDurationUs(1_000_000L).build();
            return new Composition.Builder(withAudioFrom(ImmutableList.of(editedMediaItem)))
                .build();
          },
          /* expectedDurationMs= */ 600,
          "AUDIO_ITEM_WITH_CLIPPED_END");
  private static final Input AUDIO_ITEM_WITH_CLIPPED_START_AND_END =
      new Input(
          () -> {
            MediaItem mediaItem =
                new MediaItem.Builder()
                    .setUri(ASSET_URI_PREFIX + FILE_AUDIO_RAW)
                    .setClippingConfiguration(
                        new MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionUs(100_000)
                            .setEndPositionUs(550_000)
                            .build())
                    .build();
            EditedMediaItem editedMediaItem =
                new EditedMediaItem.Builder(mediaItem).setDurationUs(1_000_000L).build();
            return new Composition.Builder(withAudioFrom(ImmutableList.of(editedMediaItem)))
                .build();
          },
          /* expectedDurationMs= */ 450,
          "AUDIO_ITEM_WITH_CLIPPED_START_AND_END");
  private static final Input AUDIO_ITEM_WITH_CLIPPED_START_AND_DOUBLE_SPEED_EFFECT =
      new Input(
          () -> {
            MediaItem mediaItem =
                new MediaItem.Builder()
                    .setUri(ASSET_URI_PREFIX + FILE_AUDIO_RAW)
                    .setClippingConfiguration(
                        new MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionUs(200_000)
                            .build())
                    .build();
            // Video must be removed because Composition presentation time assumes there is audio
            // and video.
            // TODO: b/469706967 - Remove setRemoveVideo() call when EditedMediaItems consider
            // sequence types.
            EditedMediaItem editedMediaItem =
                new EditedMediaItem.Builder(mediaItem)
                    .setRemoveVideo(true)
                    .setDurationUs(1_000_000L)
                    .setEffects(toEffects(new SpeedChangingAudioProcessor(DOUBLE_SPEED_PROVIDER)))
                    .build();
            return new Composition.Builder(withAudioFrom(ImmutableList.of(editedMediaItem)))
                .build();
          },
          /* expectedDurationMs= */ 400,
          "AUDIO_ITEM_WITH_CLIPPED_START_AND_DOUBLE_SPEED_EFFECT");
  private static final Input AUDIO_ITEM_WITH_CLIPPED_START_AND_SET_DOUBLE_SPEED =
      new Input(
          () -> {
            MediaItem mediaItem =
                new MediaItem.Builder()
                    .setUri(ASSET_URI_PREFIX + FILE_AUDIO_RAW)
                    .setClippingConfiguration(
                        new MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionUs(200_000)
                            .build())
                    .build();
            EditedMediaItem editedMediaItem =
                new EditedMediaItem.Builder(mediaItem)
                    .setDurationUs(1_000_000L)
                    .setSpeed(DOUBLE_SPEED_PROVIDER)
                    .build();
            return new Composition.Builder(withAudioFrom(ImmutableList.of(editedMediaItem)))
                .build();
          },
          /* expectedDurationMs= */ 400,
          "AUDIO_ITEM_WITH_CLIPPED_START_AND_SET_DOUBLE_SPEED");
  private static final Input AUDIO_ITEM_WITH_CLIPPED_END_AND_DOUBLE_SPEED_EFFECT =
      new Input(
          () -> {
            MediaItem mediaItem =
                new MediaItem.Builder()
                    .setUri(ASSET_URI_PREFIX + FILE_AUDIO_RAW)
                    .setClippingConfiguration(
                        new MediaItem.ClippingConfiguration.Builder()
                            .setEndPositionUs(600_000)
                            .build())
                    .build();
            // Video must be removed because Composition presentation time assumes there is audio
            // and video.
            // TODO: b/469706967 - Remove setRemoveVideo() call when EditedMediaItems consider
            // sequence types.
            EditedMediaItem editedMediaItem =
                new EditedMediaItem.Builder(mediaItem)
                    .setRemoveVideo(true)
                    .setDurationUs(1_000_000L)
                    .setEffects(toEffects(new SpeedChangingAudioProcessor(DOUBLE_SPEED_PROVIDER)))
                    .build();
            return new Composition.Builder(withAudioFrom(ImmutableList.of(editedMediaItem)))
                .build();
          },
          /* expectedDurationMs= */ 300,
          "AUDIO_ITEM_WITH_CLIPPED_END_AND_DOUBLE_SPEED_EFFECT");
  private static final Input AUDIO_ITEM_WITH_CLIPPED_END_AND_SET_DOUBLE_SPEED =
      new Input(
          () -> {
            MediaItem mediaItem =
                new MediaItem.Builder()
                    .setUri(ASSET_URI_PREFIX + FILE_AUDIO_RAW)
                    .setClippingConfiguration(
                        new MediaItem.ClippingConfiguration.Builder()
                            .setEndPositionUs(600_000)
                            .build())
                    .build();
            EditedMediaItem editedMediaItem =
                new EditedMediaItem.Builder(mediaItem)
                    .setDurationUs(1_000_000L)
                    .setSpeed(DOUBLE_SPEED_PROVIDER)
                    .build();
            return new Composition.Builder(withAudioFrom(ImmutableList.of(editedMediaItem)))
                .build();
          },
          /* expectedDurationMs= */ 300,
          "AUDIO_ITEM_WITH_CLIPPED_END_AND_SET_DOUBLE_SPEED");
  private static final Input AUDIO_ITEM_WITH_CLIPPED_START_AND_END_WITH_HALF_SPEED_EFFECT =
      new Input(
          () -> {
            MediaItem mediaItem =
                new MediaItem.Builder()
                    .setUri(ASSET_URI_PREFIX + FILE_AUDIO_RAW)
                    .setClippingConfiguration(
                        new MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionUs(100_000)
                            .setEndPositionUs(550_000)
                            .build())
                    .build();
            EditedMediaItem editedMediaItem =
                new EditedMediaItem.Builder(mediaItem)
                    .setDurationUs(1_000_000L)
                    .setEffects(toEffects(new SpeedChangingAudioProcessor(HALF_SPEED_PROVIDER)))
                    .build();
            EditedMediaItemSequence sequence = withAudioFrom(ImmutableList.of(editedMediaItem));
            return new Composition.Builder(sequence).build();
          },
          /* expectedDurationMs= */ 900,
          "AUDIO_ITEM_WITH_CLIPPED_START_AND_END_WITH_HALF_SPEED_EFFECT");
  private static final Input AUDIO_ITEM_WITH_CLIPPED_START_AND_END_WITH_SET_HALF_SPEED =
      new Input(
          () -> {
            MediaItem mediaItem =
                new MediaItem.Builder()
                    .setUri(ASSET_URI_PREFIX + FILE_AUDIO_RAW)
                    .setClippingConfiguration(
                        new MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionUs(100_000)
                            .setEndPositionUs(550_000)
                            .build())
                    .build();
            EditedMediaItem editedMediaItem =
                new EditedMediaItem.Builder(mediaItem)
                    .setDurationUs(1_000_000L)
                    .setSpeed(HALF_SPEED_PROVIDER)
                    .build();
            EditedMediaItemSequence sequence = withAudioFrom(ImmutableList.of(editedMediaItem));
            return new Composition.Builder(sequence).build();
          },
          /* expectedDurationMs= */ 900,
          "AUDIO_ITEM_WITH_CLIPPED_START_AND_END_WITH_SET_HALF_SPEED");

  @MonotonicNonNull CompositionPlayer player;
  private FakeFrameProcessor.Factory frameProcessorFactory;

  @Before
  public void setUp() {
    frameProcessorFactory =
        new FakeFrameProcessor.Factory(/* shouldCompleteIncomingFrames= */ true);
  }

  @After
  public void tearDown() {
    if (player != null) {
      player.release();
    }
  }

  @Test
  public void builder_buildCalledTwice_throws() {
    CompositionPlayer.Builder builder = new CompositionPlayer.Builder(getApplicationContext());

    player = builder.build();

    assertThrows(IllegalStateException.class, builder::build);
  }

  @Test
  public void builder_buildCalledOnNonHandlerThread_throws() throws InterruptedException {
    AtomicReference<@NullableType Exception> exception = new AtomicReference<>();
    ConditionVariable conditionVariable = new ConditionVariable();

    Thread thread =
        new Thread(
            () -> {
              try {
                new Composition.Builder(getApplicationContext()).build();
              } catch (Exception e) {
                exception.set(e);
              } finally {
                conditionVariable.open();
              }
            });
    thread.start();

    conditionVariable.block();
    thread.join();

    assertThat(exception.get()).isNotNull();
  }

  @Test
  public void instance_accessedByWrongThread_throws() throws InterruptedException {
    player = createTestCompositionPlayer();
    AtomicReference<@NullableType RuntimeException> exception = new AtomicReference<>();
    ConditionVariable conditionVariable = new ConditionVariable();
    HandlerThread handlerThread = new HandlerThread("test");
    handlerThread.start();

    new Handler(handlerThread.getLooper())
        .post(
            () -> {
              try {
                player.setComposition(buildComposition());
              } catch (RuntimeException e) {
                exception.set(e);
              } finally {
                conditionVariable.open();
              }
            });
    conditionVariable.block();
    player.release();
    handlerThread.quit();
    handlerThread.join();

    assertThat(exception.get()).isInstanceOf(IllegalStateException.class);
    assertThat(exception.get()).hasMessageThat().contains("Player is accessed on the wrong thread");
  }

  @Test
  public void instance_withSpecifiedApplicationLooper_callbacksDispatchedOnSpecifiedThread()
      throws Exception {
    HandlerThread applicationHandlerThread = new HandlerThread("app-thread");
    applicationHandlerThread.start();
    Looper applicationLooper = applicationHandlerThread.getLooper();
    Handler applicationThreadHandler = new Handler(applicationLooper);
    AtomicReference<Thread> callbackThread = new AtomicReference<>();
    ConditionVariable eventsArrived = new ConditionVariable();
    // Do not use the shared player to avoid it being released in tearDown on the wrong thread.
    CompositionPlayer player =
        createTestCompositionPlayerBuilder().setLooper(applicationLooper).build();
    // Listeners can be added by any thread.
    player.addListener(
        new Player.Listener() {
          @Override
          public void onEvents(Player player, Player.Events events) {
            callbackThread.set(Thread.currentThread());
            eventsArrived.open();
          }
        });

    applicationThreadHandler.post(
        () -> {
          player.setComposition(buildComposition());
          player.prepare();
        });
    if (!eventsArrived.block(TEST_TIMEOUT_MS)) {
      throw new TimeoutException();
    }
    // Use a separate condition variable to releasing the player to avoid race conditions
    // with the condition variable used for the callback.
    ConditionVariable released = new ConditionVariable();
    applicationThreadHandler.post(
        () -> {
          player.release();
          released.open();
        });
    if (!released.block(TEST_TIMEOUT_MS)) {
      throw new TimeoutException();
    }
    applicationHandlerThread.quit();
    applicationHandlerThread.join();

    assertThat(eventsArrived.isOpen()).isTrue();
    assertThat(callbackThread.get()).isEqualTo(applicationLooper.getThread());
  }

  @Test
  public void release_onNewlyCreateInstance() {
    player = createTestCompositionPlayer();

    player.release();
  }

  @Test
  public void release_audioFailsDuringRelease_onlyLogsError() throws Exception {
    Log.Logger logger = mock(Log.Logger.class);
    Log.setLogger(logger);
    AudioSink audioSink =
        new ForwardingAudioSink(new DefaultAudioSink.Builder(getApplicationContext()).build()) {
          @Override
          public void release() {
            throw new RuntimeException("AudioSink release error");
          }
        };
    player = createTestCompositionPlayerBuilder().setAudioSink(audioSink).build();
    Player.Listener listener = mock(Player.Listener.class);
    player.addListener(listener);

    player.setComposition(buildComposition());
    player.prepare();
    advance(player).untilState(STATE_READY);

    player.release();

    verify(listener, never()).onPlayerError(any());
    verify(logger)
        .e(
            eq("CompPlayerInternal"),
            eq("error while releasing the player"),
            argThat(
                throwable ->
                    throwable instanceof RuntimeException
                        && throwable.getMessage().contains("AudioSink release error")));
  }

  @Test
  public void getAvailableCommands_returnsSpecificCommands() {
    player = createTestCompositionPlayer();

    assertThat(getCommandsAsList(player.getAvailableCommands()))
        .containsExactly(
            Player.COMMAND_PLAY_PAUSE,
            Player.COMMAND_PREPARE,
            Player.COMMAND_STOP,
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
            Player.COMMAND_SEEK_BACK,
            Player.COMMAND_SEEK_FORWARD,
            Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
            Player.COMMAND_GET_TIMELINE,
            Player.COMMAND_SET_VIDEO_SURFACE,
            Player.COMMAND_SET_REPEAT_MODE,
            Player.COMMAND_GET_VOLUME,
            Player.COMMAND_SET_VOLUME,
            Player.COMMAND_RELEASE,
            Player.COMMAND_SET_AUDIO_ATTRIBUTES);
  }

  @Test
  public void prepare_withoutCompositionSet_throws() {
    player = createTestCompositionPlayer();

    assertThrows(NullPointerException.class, player::prepare);
  }

  @Test
  public void prepare_playbackStateIsBuffering() {
    player = createTestCompositionPlayer();
    player.setComposition(buildComposition());

    player.prepare();

    assertThat(player.getPlaybackState()).isEqualTo(STATE_BUFFERING);
  }

  @Test
  public void seekTo_playbackStateIsBuffering() throws Exception {
    player = createTestCompositionPlayer();
    player.setComposition(buildComposition());
    player.prepare();
    advance(player).untilState(STATE_READY);

    player.seekTo(100);

    assertThat(player.getPlaybackState()).isEqualTo(STATE_BUFFERING);
  }

  @Test
  public void stop_playbackStateIsIdle() throws Exception {
    player = createTestCompositionPlayer();
    player.setComposition(buildComposition());
    player.prepare();
    advance(player).untilState(STATE_READY);

    player.stop();

    assertThat(player.getPlaybackState()).isEqualTo(STATE_IDLE);
  }

  @Test
  public void setComposition_playbackStateIsUpdated() throws Exception {
    player = createTestCompositionPlayer();
    player.setComposition(buildComposition());
    assertThat(player.getPlaybackState()).isEqualTo(STATE_IDLE);
    player.prepare();
    advance(player).untilState(STATE_READY);
    player.setComposition(
        new Composition.Builder(
                withAudioFrom(
                    ImmutableList.of(
                        new EditedMediaItem.Builder(
                                MediaItem.fromUri(ASSET_URI_PREFIX + FILE_AUDIO_RAW))
                            .setDurationUs(1_000_000L)
                            .build())))
            .build());
    assertThat(player.getPlaybackState()).isEqualTo(STATE_BUFFERING);
    player.stop();
    assertThat(player.getPlaybackState()).isEqualTo(STATE_IDLE);
  }

  @Test
  public void playWhenReady_calledBeforePrepare_startsPlayingAfterPrepareCalled() throws Exception {
    player = createTestCompositionPlayer();

    player.setPlayWhenReady(true);
    player.setComposition(buildComposition());
    player.prepare();

    TestPlayerRunHelper.runUntilPlaybackState(player, STATE_ENDED);
  }

  @Test
  public void playWhenReady_triggersPlayWhenReadyCallbackWithReason() throws Exception {
    player = createTestCompositionPlayer();
    AtomicInteger playWhenReadyReason = new AtomicInteger(-1);
    player.addListener(
        new Player.Listener() {
          @Override
          public void onPlayWhenReadyChanged(
              boolean playWhenReady, @Player.PlayWhenReadyChangeReason int reason) {
            playWhenReadyReason.set(reason);
          }
        });

    player.setPlayWhenReady(true);
    runMainLooperUntil(() -> playWhenReadyReason.get() != -1);

    assertThat(playWhenReadyReason.get())
        .isEqualTo(Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST);
  }

  @Test
  public void setVideoTextureView_throws() {
    Context context = getApplicationContext();
    player = createTestCompositionPlayer();

    assertThrows(
        UnsupportedOperationException.class,
        () -> player.setVideoTextureView(new TextureView(context)));
  }

  @Test
  public void setVideoSurface_withNonNullSurface_throws() {
    player = createTestCompositionPlayer();
    SurfaceTexture surfaceTexture = new SurfaceTexture(/* texName= */ 0);
    Surface surface = new Surface(surfaceTexture);

    assertThrows(UnsupportedOperationException.class, () -> player.setVideoSurface(surface));

    surface.release();
    surfaceTexture.release();
  }

  @Test
  public void clearVideoSurface_specifiedSurfaceNotPreviouslySet_throws() {
    player = createTestCompositionPlayer();
    SurfaceTexture surfaceTexture = new SurfaceTexture(/* texName= */ 0);
    Surface surface = new Surface(surfaceTexture);

    assertThrows(IllegalArgumentException.class, () -> player.clearVideoSurface(surface));

    player.release();
    surface.release();
    surfaceTexture.release();
  }

  @Test
  public void getTotalBufferedDuration_playerStillIdle_returnsZero() {
    player = createTestCompositionPlayer();

    assertThat(player.getTotalBufferedDuration()).isEqualTo(0);
  }

  @Test
  public void getTotalBufferedDuration_setCompositionButNotPrepare_returnsZero() {
    player = createTestCompositionPlayer();

    player.setComposition(buildComposition());

    assertThat(player.getTotalBufferedDuration()).isEqualTo(0);
  }

  @Test
  public void getTotalBufferedDuration_playerReady_returnsNonZero() throws Exception {
    player = createTestCompositionPlayer();

    player.setComposition(buildComposition());
    player.prepare();
    TestPlayerRunHelper.runUntilPlaybackState(player, STATE_READY);

    assertThat(player.getTotalBufferedDuration()).isGreaterThan(0);
  }

  @Test
  public void getDuration_withoutComposition_returnsTimeUnset() {
    player = createTestCompositionPlayer();

    assertThat(player.getDuration()).isEqualTo(C.TIME_UNSET);
  }

  @Test
  public void getDuration_returnsExpectedDuration(
      @TestParameter(valuesProvider = DurationTestCasesProvider.class) Input input)
      throws Exception {
    player = createTestCompositionPlayer();
    player.setComposition(input.composition.get());
    player.prepare();
    advance(player).untilState(STATE_READY);

    assertThat(player.getDuration()).isEqualTo(input.expectedDurationMs);
  }

  @Test
  public void addListener_callsSupportedCallbacks() throws Exception {
    player = createTestCompositionPlayer();
    Composition composition = buildComposition();
    Player.Listener listener = mock(Player.Listener.class);
    InOrder inOrder = Mockito.inOrder(listener);

    player.setComposition(composition);
    player.addListener(listener);
    player.prepare();
    TestPlayerRunHelper.runUntilPlaybackState(player, STATE_READY);

    inOrder
        .verify(listener)
        .onTimelineChanged(any(), eq(Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED));
    inOrder.verify(listener).onPlaybackStateChanged(STATE_BUFFERING);
    inOrder.verify(listener).onPlaybackStateChanged(STATE_READY);

    player.setPlayWhenReady(true);

    inOrder
        .verify(listener)
        .onPlayWhenReadyChanged(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST);
    inOrder.verify(listener).onIsPlayingChanged(true);

    TestPlayerRunHelper.runUntilPlaybackState(player, STATE_ENDED);
    inOrder.verify(listener).onPlaybackStateChanged(STATE_ENDED);

    player.stop();
    TestPlayerRunHelper.runUntilPlaybackState(player, STATE_IDLE);
    inOrder.verify(listener).onPlaybackStateChanged(STATE_IDLE);
    player.release();

    ArgumentCaptor<Integer> playbackStateCaptor = ArgumentCaptor.forClass(Integer.class);
    verify(listener, atLeastOnce()).onPlaybackStateChanged(playbackStateCaptor.capture());
    assertThat(playbackStateCaptor.getAllValues())
        .containsExactly(STATE_BUFFERING, STATE_READY, STATE_ENDED, STATE_IDLE)
        .inOrder();
  }

  @Test
  public void addListener_callsOnEventsWithSupportedEvents() throws Exception {
    player = createTestCompositionPlayer();
    Composition composition = buildComposition();
    Player.Listener mockListener = mock(Player.Listener.class);
    ArgumentCaptor<Player.Events> eventsCaptor = ArgumentCaptor.forClass(Player.Events.class);
    ImmutableSet<Integer> supportedEvents =
        ImmutableSet.of(
            Player.EVENT_TIMELINE_CHANGED,
            Player.EVENT_MEDIA_ITEM_TRANSITION,
            Player.EVENT_PLAYBACK_STATE_CHANGED,
            Player.EVENT_PLAY_WHEN_READY_CHANGED,
            Player.EVENT_IS_PLAYING_CHANGED);

    player.setComposition(composition);
    player.addListener(mockListener);
    player.prepare();
    player.play();
    TestPlayerRunHelper.runUntilPlaybackState(player, STATE_ENDED);
    player.release();

    verify(mockListener, atLeastOnce()).onEvents(any(), eventsCaptor.capture());
    List<Player.Events> eventsList = eventsCaptor.getAllValues();
    for (Player.Events events : eventsList) {
      assertThat(events.size()).isNotEqualTo(0);
      for (int j = 0; j < events.size(); j++) {
        assertThat(supportedEvents).contains(events.get(j));
      }
    }
  }

  @Test
  public void play_withCorrectTimelineUpdated() throws Exception {
    player = createTestCompositionPlayer();
    Composition composition = buildComposition();
    Player.Listener mockListener = mock(Player.Listener.class);
    ArgumentCaptor<Timeline> timelineCaptor = ArgumentCaptor.forClass(Timeline.class);
    ArgumentCaptor<Integer> timelineChangeReasonCaptor = ArgumentCaptor.forClass(Integer.class);
    player.setComposition(composition);
    player.addListener(mockListener);
    player.prepare();
    player.play();
    TestPlayerRunHelper.runUntilPlaybackState(player, STATE_ENDED);
    player.release();

    verify(mockListener)
        .onTimelineChanged(timelineCaptor.capture(), timelineChangeReasonCaptor.capture());
    assertThat(timelineCaptor.getAllValues()).hasSize(1);
    assertThat(timelineChangeReasonCaptor.getAllValues()).hasSize(1);
    Timeline timeline = timelineCaptor.getValue();
    assertThat(timeline.getWindowCount()).isEqualTo(1);
    assertThat(timeline.getPeriodCount()).isEqualTo(1);
    // Refer to the durations in buildComposition().
    assertThat(timeline.getWindow(/* windowIndex= */ 0, new Timeline.Window()).durationUs)
        .isEqualTo(1_348_000L);
    assertThat(timelineChangeReasonCaptor.getValue())
        .isEqualTo(Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED);
  }

  @Test
  public void play_audioSinkPlayNotCalledUntilReady() throws Exception {
    AudioSink mockAudioSink = mock(AudioSink.class);
    player = createTestCompositionPlayerBuilder().setAudioSink(mockAudioSink).build();
    player.setComposition(buildComposition());
    player.prepare();
    player.play();

    play(player).untilPendingCommandsAreFullyHandled();

    // AudioSink.play() should not be called before the player is ready.
    verify(mockAudioSink, never()).play();

    advance(player).untilState(STATE_READY);
    ShadowLooper.idleMainLooper();
    advance(player).untilPendingCommandsAreFullyHandled();

    verify(mockAudioSink).play();
  }

  @Test
  public void playSequence_withRepeatModeOff_doesNotReportRepeatMediaItemTransition()
      throws Exception {
    player = createTestCompositionPlayer();
    Player.Listener mockListener = mock(Player.Listener.class);
    player.addListener(mockListener);
    player.setComposition(buildComposition());
    player.prepare();
    player.play();

    TestPlayerRunHelper.runUntilPlaybackState(player, STATE_ENDED);

    verify(mockListener, never())
        .onMediaItemTransition(any(), eq(Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT));
    verify(mockListener, never())
        .onPositionDiscontinuity(any(), any(), eq(Player.DISCONTINUITY_REASON_SEEK));
  }

  @Test
  public void playSequence_withRepeatModeAll_reportsRepeatReasonForMediaItemTransition()
      throws Exception {
    player = createTestCompositionPlayer();
    Player.Listener mockListener = mock(Player.Listener.class);
    player.addListener(mockListener);
    player.setRepeatMode(Player.REPEAT_MODE_ALL);
    player.setComposition(buildComposition());
    player.prepare();
    player.play();

    TestPlayerRunHelper.runUntilPositionDiscontinuity(
        player, Player.DISCONTINUITY_REASON_AUTO_TRANSITION);
    TestPlayerRunHelper.runUntilPositionDiscontinuity(
        player, Player.DISCONTINUITY_REASON_AUTO_TRANSITION);
    TestPlayerRunHelper.runUntilPositionDiscontinuity(
        player, Player.DISCONTINUITY_REASON_AUTO_TRANSITION);
    player.setRepeatMode(Player.REPEAT_MODE_OFF);
    TestPlayerRunHelper.runUntilPlaybackState(player, STATE_ENDED);

    verify(mockListener, times(3))
        .onMediaItemTransition(any(), eq(Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT));
    verify(mockListener, never())
        .onPositionDiscontinuity(any(), any(), eq(Player.DISCONTINUITY_REASON_SEEK));
  }

  @Test
  public void playComposition_withRepeatModeOff_doesNotReportRepeatMediaItemTransition()
      throws Exception {
    player = createTestCompositionPlayer();
    Player.Listener mockListener = mock(Player.Listener.class);
    player.addListener(mockListener);
    player.setRepeatMode(Player.REPEAT_MODE_OFF);
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

    TestPlayerRunHelper.runUntilPlaybackState(player, STATE_ENDED);

    verify(mockListener, never())
        .onMediaItemTransition(any(), eq(Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT));
    verify(mockListener, never())
        .onPositionDiscontinuity(any(), any(), eq(Player.DISCONTINUITY_REASON_SEEK));
  }

  @Test
  public void scrub_pastEndOfSecondarySequence_doesNotHang() throws Exception {
    MediaItem item = MediaItem.fromUri(ASSET_URI_PREFIX + FILE_AUDIO_RAW);
    EditedMediaItem editedMediaItem =
        new EditedMediaItem.Builder(item).setDurationUs(1_000_000L).build();

    Composition composition =
        new Composition.Builder(
                withAudioFrom(ImmutableList.of(editedMediaItem, editedMediaItem)),
                withAudioFrom(ImmutableList.of(editedMediaItem)))
            .build();
    player = createTestCompositionPlayer();
    player.setComposition(composition);
    player.prepare();
    player.play();

    advance(player).untilState(STATE_READY);

    player.setScrubbingModeEnabled(true);
    player.seekTo(/* positionMs= */ 1500);
    player.setScrubbingModeEnabled(false);

    advance(player).untilState(STATE_ENDED);
  }

  @Test
  public void playComposition_withRepeatModeAll_reportsRepeatReasonForMediaItemTransition()
      throws Exception {
    player = createTestCompositionPlayer();
    Player.Listener mockListener = mock(Player.Listener.class);
    player.addListener(mockListener);
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
    TestPlayerRunHelper.runUntilPositionDiscontinuity(
        player, Player.DISCONTINUITY_REASON_AUTO_TRANSITION);
    TestPlayerRunHelper.runUntilPositionDiscontinuity(
        player, Player.DISCONTINUITY_REASON_AUTO_TRANSITION);
    player.setRepeatMode(Player.REPEAT_MODE_OFF);
    TestPlayerRunHelper.runUntilPlaybackState(player, STATE_ENDED);

    verify(mockListener, times(3))
        .onMediaItemTransition(any(), eq(Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT));
    verify(mockListener, never())
        .onPositionDiscontinuity(any(), any(), eq(Player.DISCONTINUITY_REASON_SEEK));
  }

  @Test
  public void seekPastDuration_ends() throws Exception {
    player = createTestCompositionPlayer();
    Player.Listener mockListener = mock(Player.Listener.class);
    player.addListener(mockListener);
    EditedMediaItem editedMediaItem =
        new EditedMediaItem.Builder(MediaItem.fromUri(ASSET_URI_PREFIX + FILE_AUDIO_RAW))
            .setDurationUs(1_000_000L)
            .build();
    EditedMediaItemSequence sequence = withAudioFrom(ImmutableList.of(editedMediaItem));
    Composition composition = new Composition.Builder(sequence).build();
    player.setComposition(composition);
    player.prepare();
    player.play();

    advance(player).untilState(STATE_READY);
    player.seekTo(/* positionMs= */ 1100);
    assertThat(player.getPlaybackState()).isEqualTo(STATE_BUFFERING);
    advance(player).untilState(STATE_ENDED);
  }

  @Test
  public void seekPastDuration_withClippedStart_ends() throws Exception {
    player = createTestCompositionPlayer();
    EditedMediaItem editedMediaItem =
        new EditedMediaItem.Builder(
                MediaItem.fromUri(ASSET_URI_PREFIX + FILE_AUDIO_RAW)
                    .buildUpon()
                    .setClippingConfiguration(
                        new MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionUs(200_000L)
                            .build())
                    .build())
            .setDurationUs(1_000_000L)
            .build();
    EditedMediaItemSequence sequence = withAudioFrom(ImmutableList.of(editedMediaItem));
    Composition composition = new Composition.Builder(sequence).build();
    player.setComposition(composition);
    player.seekTo(/* positionMs= */ 900);
    player.prepare();
    player.play();

    advance(player).untilState(STATE_ENDED);
  }

  @Test
  public void seekPastDuration_withClippedEnd_ends() throws Exception {
    player = createTestCompositionPlayer();
    EditedMediaItem editedMediaItem =
        new EditedMediaItem.Builder(
                MediaItem.fromUri(ASSET_URI_PREFIX + FILE_AUDIO_RAW)
                    .buildUpon()
                    .setClippingConfiguration(
                        new MediaItem.ClippingConfiguration.Builder()
                            .setEndPositionUs(800_000)
                            .build())
                    .build())
            .setDurationUs(1_000_000L)
            .build();
    EditedMediaItemSequence sequence = withAudioFrom(ImmutableList.of(editedMediaItem));
    Composition composition = new Composition.Builder(sequence).build();
    player.setComposition(composition);
    player.seekTo(/* positionMs= */ 900);
    player.prepare();
    player.play();

    advance(player).untilState(STATE_ENDED);
  }

  @Test
  public void isScrubbingModeEnabled_defaultsFalse() {
    player = createTestCompositionPlayer();

    assertThat(player.isScrubbingModeEnabled()).isFalse();
  }

  @Test
  public void setScrubbingModeEnabled_updatesIsScrubbingModeEnabled() {
    player = createTestCompositionPlayer();

    player.setScrubbingModeEnabled(true);

    assertThat(player.isScrubbingModeEnabled()).isTrue();
  }

  @Test
  public void prepare_withCustomLoadControl_preparesTheLoadControl() throws Exception {
    CustomLoadControl customLoadControl = new CustomLoadControl();
    CompositionPlayer.Builder playerBuilder = createTestCompositionPlayerBuilder();
    playerBuilder.setLoadControl(customLoadControl);
    player = playerBuilder.build();

    player.setComposition(buildComposition());
    player.prepare();
    TestPlayerRunHelper.runUntilPlaybackState(player, STATE_READY);

    assertThat(customLoadControl.prepared).isTrue();
  }

  @Test
  public void setComposition_withCompositionLevelSpeedChangingAudioProcessor_throws() {
    player = createTestCompositionPlayer();
    Effects effects =
        new Effects(
            ImmutableList.of(new SpeedChangingAudioProcessor(TEST_SPEED_PROVIDER)),
            ImmutableList.of());
    Composition composition = buildComposition().buildUpon().setEffects(effects).build();

    assertThrows(IllegalArgumentException.class, () -> player.setComposition(composition));
  }

  @Test
  public void setComposition_withCompositionLevelTimestampAdjustment_throws() {
    player = createTestCompositionPlayer();
    Effects effects =
        new Effects(
            ImmutableList.of(),
            ImmutableList.of(
                new TimestampAdjustment(
                    (inputTimeUs, outputTimeConsumer) -> {}, TEST_SPEED_PROVIDER)));
    Composition composition = buildComposition().buildUpon().setEffects(effects).build();

    assertThrows(IllegalArgumentException.class, () -> player.setComposition(composition));
  }

  @Test
  public void scrubbing_seekPastOneSequenceDuration_transitionsToReady() throws Exception {
    player = createTestCompositionPlayer();
    player.setScrubbingModeEnabled(true);
    EditedMediaItemSequence normalSequence =
        withAudioFrom(
            ImmutableList.of(
                new EditedMediaItem.Builder(MediaItem.fromUri(ASSET_URI_PREFIX + FILE_AUDIO_RAW))
                    .setDurationUs(1_000_000L)
                    .build()));
    MediaItem clippedMediaItem =
        MediaItem.fromUri(ASSET_URI_PREFIX + FILE_AUDIO_RAW)
            .buildUpon()
            .setClippingConfiguration(
                new MediaItem.ClippingConfiguration.Builder().setEndPositionUs(500_000L).build())
            .build();
    EditedMediaItemSequence clippedSequence =
        withAudioFrom(
            ImmutableList.of(
                new EditedMediaItem.Builder(clippedMediaItem).setDurationUs(1_000_000L).build()));
    player.setComposition(new Composition.Builder(normalSequence, clippedSequence).build());

    player.prepare();
    player.seekTo(800);
    advance(player).untilState(STATE_READY);

    assertThat(player.getPlaybackState()).isEqualTo(STATE_READY);
  }

  @Test
  public void setComposition_withNonFirstSpeedChangingAudioProcessor_throws() {
    player = createTestCompositionPlayer();
    Effects effects =
        new Effects(
            ImmutableList.of(
                new TrimmingAudioProcessor(), new SpeedChangingAudioProcessor(TEST_SPEED_PROVIDER)),
            ImmutableList.of());
    EditedMediaItem item =
        new EditedMediaItem.Builder(MediaItem.EMPTY)
            .setDurationUs(1_000_000L)
            .setEffects(effects)
            .build();
    Composition composition =
        new Composition.Builder(withAudioFrom(ImmutableList.of(item))).build();

    assertThrows(IllegalArgumentException.class, () -> player.setComposition(composition));
  }

  @Test
  public void setComposition_withNonFirstTimestampAdjustment_throws() {
    player = createTestCompositionPlayer();
    Effects effects =
        new Effects(
            ImmutableList.of(),
            ImmutableList.of(
                new AlphaScale(1f),
                new TimestampAdjustment(
                    (inputTimeUs, outputTimeConsumer) -> {}, TEST_SPEED_PROVIDER)));
    EditedMediaItem item =
        new EditedMediaItem.Builder(MediaItem.EMPTY)
            .setDurationUs(1_000_000L)
            .setEffects(effects)
            .build();
    Composition composition =
        new Composition.Builder(withAudioFrom(ImmutableList.of(item))).build();

    assertThrows(IllegalArgumentException.class, () -> player.setComposition(composition));
  }

  @Test
  public void setComposition_withSpeedChangeEffectInFirstPosition_throws() {
    player = createTestCompositionPlayer();
    Effects effects = new Effects(ImmutableList.of(), ImmutableList.of(new SpeedChangeEffect(1f)));
    EditedMediaItem item =
        new EditedMediaItem.Builder(MediaItem.EMPTY)
            .setDurationUs(1_000_000L)
            .setEffects(effects)
            .build();
    Composition composition =
        new Composition.Builder(withAudioFrom(ImmutableList.of(item))).build();

    assertThrows(IllegalArgumentException.class, () -> player.setComposition(composition));
  }

  @Test
  public void setComposition_withPlaceholderInMediaItemUri_doesNotThrowWhenLoggingTrace() {
    player = createTestCompositionPlayer();
    EditedMediaItem item =
        new EditedMediaItem.Builder(MediaItem.fromUri("asset:///media.%D"))
            .setDurationUs(1_000_000L)
            .build();
    Composition composition =
        new Composition.Builder(withAudioFrom(ImmutableList.of(item))).build();
    boolean oldEnableDebugTrace = DebugTraceUtil.enableTracing;
    DebugTraceUtil.enableTracing = true;

    try {
      player.setComposition(composition);
    } finally {
      DebugTraceUtil.enableTracing = oldEnableDebugTrace;
    }
  }

  @Test
  public void setSpeed_onSecondarySequence_outputsExpectedNumberOfSamples() throws Exception {
    AtomicInteger bytes = new AtomicInteger();
    AudioProcessor processor = createByteCountingAudioProcessor(bytes);
    player = createTestCompositionPlayer();
    EditedMediaItem item =
        new EditedMediaItem.Builder(MediaItem.fromUri(ASSET_URI_PREFIX + FILE_AUDIO_RAW))
            .setDurationUs(1_000_000L)
            .build();
    EditedMediaItem speedAdjustedItem =
        item.buildUpon()
            .setSpeed(TestSpeedProvider.createWithStartTimes(new long[] {0L}, new float[] {0.5f}))
            .build();
    EditedMediaItemSequence primarySequence = withAudioFrom(ImmutableList.of(item));
    EditedMediaItemSequence secondarySequence = withAudioFrom(ImmutableList.of(speedAdjustedItem));
    player.setComposition(
        new Composition.Builder(primarySequence, secondarySequence)
            .setEffects(new Effects(ImmutableList.of(processor), ImmutableList.of()))
            .build());
    player.prepare();

    play(player).untilState(STATE_ENDED);

    assertThat(bytes.get() / 2).isEqualTo(88200);
  }

  @Test
  public void frameProcessor_setComposition_rendersFirstFrameBeforeStarted()
      throws PlaybackException, TimeoutException {
    Composition composition1 =
        new Composition.Builder(
                EditedMediaItemSequence.withVideoFrom(ImmutableList.of(getImageItem())),
                EditedMediaItemSequence.withAudioAndVideoFrom(
                    ImmutableList.of(
                        new EditedMediaItem.Builder(MediaItem.fromUri(MP4_SIMPLE_ASSET.uri))
                            .setDurationUs(1_090_000L)
                            .build())))
            .build();
    Composition composition2 =
        new Composition.Builder(
                EditedMediaItemSequence.withVideoFrom(ImmutableList.of(getImageItem())),
                EditedMediaItemSequence.withVideoFrom(ImmutableList.of(getImageItem())),
                EditedMediaItemSequence.withAudioAndVideoFrom(
                    ImmutableList.of(
                        new EditedMediaItem.Builder(MediaItem.fromUri(MP4_SIMPLE_ASSET.uri))
                            .setDurationUs(1_090_000L)
                            .build())))
            .build();
    Composition composition3 =
        new Composition.Builder(
                EditedMediaItemSequence.withAudioAndVideoFrom(
                    ImmutableList.of(
                        new EditedMediaItem.Builder(MediaItem.fromUri(MP4_SIMPLE_ASSET.uri))
                            .setDurationUs(1_090_000L)
                            .build())))
            .build();
    player = createTestHardwareBufferCompositionPlayerBuilder(frameProcessorFactory).build();

    player.setComposition(composition1);
    player.prepare();
    advance(player).untilState(STATE_READY);

    FakeFrameProcessor frameProcessor = frameProcessorFactory.createdProcessor;
    assertThat(frameProcessor).isNotNull();
    assertThat(frameProcessor.getQueuedContentTimesUs()).containsExactly(ImmutableList.of(0L, 0L));

    player.setComposition(composition2);
    advance(player).untilState(STATE_READY);
    runMainLooperUntilContentTimesUs(frameProcessor, ImmutableList.of(0L, 0L, 0L));

    assertThat(frameProcessor.getQueuedContentTimesUs())
        .containsExactly(ImmutableList.of(0L, 0L), ImmutableList.of(0L, 0L, 0L))
        .inOrder();

    player.setComposition(composition3);
    advance(player).untilState(STATE_READY);
    runMainLooperUntilContentTimesUs(frameProcessor, ImmutableList.of(0L));

    assertThat(frameProcessor.getQueuedContentTimesUs())
        .containsExactly(
            ImmutableList.of(0L, 0L), ImmutableList.of(0L, 0L, 0L), ImmutableList.of(0L))
        .inOrder();
  }

  @Test
  public void frameProcessor_setCompositionAfterSeek_rendersFrameAtSeekPositionBeforeStarted()
      throws PlaybackException, TimeoutException {
    Composition composition1 =
        new Composition.Builder(
                EditedMediaItemSequence.withVideoFrom(ImmutableList.of(getImageItem())),
                EditedMediaItemSequence.withAudioAndVideoFrom(
                    ImmutableList.of(
                        new EditedMediaItem.Builder(MediaItem.fromUri(MP4_SIMPLE_ASSET.uri))
                            .setDurationUs(1_090_000L)
                            .build())))
            .build();
    Composition composition2 =
        new Composition.Builder(
                EditedMediaItemSequence.withVideoFrom(ImmutableList.of(getImageItem())),
                EditedMediaItemSequence.withVideoFrom(ImmutableList.of(getImageItem())),
                EditedMediaItemSequence.withAudioAndVideoFrom(
                    ImmutableList.of(
                        new EditedMediaItem.Builder(MediaItem.fromUri(MP4_SIMPLE_ASSET.uri))
                            .setDurationUs(1_090_000L)
                            .build())))
            .build();
    player = createTestHardwareBufferCompositionPlayerBuilder(frameProcessorFactory).build();

    player.setComposition(composition1);
    player.prepare();
    player.seekTo(/* positionMs= */ 500);
    advance(player).untilState(STATE_READY);

    FakeFrameProcessor frameProcessor = frameProcessorFactory.createdProcessor;
    assertThat(frameProcessor).isNotNull();
    assertThat(frameProcessor.getQueuedContentTimesUs())
        .containsExactly(ImmutableList.of(500_000L, 500_500L));

    player.setComposition(composition2, player.getCurrentPosition());
    advance(player).untilState(STATE_READY);
    runMainLooperUntilContentTimesUs(
        frameProcessor, ImmutableList.of(500_000L, 500_000L, 500_500L));

    assertThat(frameProcessor.getQueuedContentTimesUs())
        .containsExactly(
            ImmutableList.of(500_000L, 500_500L), ImmutableList.of(500_000L, 500_000L, 500_500L))
        .inOrder();
  }

  @Test
  public void frameProcessor_setCompositionWithNewPosition_rendersFrameAtSeekPositionBeforeStarted()
      throws PlaybackException, TimeoutException {
    Composition composition1 =
        new Composition.Builder(
                EditedMediaItemSequence.withVideoFrom(ImmutableList.of(getImageItem())),
                EditedMediaItemSequence.withAudioAndVideoFrom(
                    ImmutableList.of(
                        new EditedMediaItem.Builder(MediaItem.fromUri(MP4_SIMPLE_ASSET.uri))
                            .setDurationUs(1_090_000L)
                            .build())))
            .build();
    Composition composition2 =
        new Composition.Builder(
                EditedMediaItemSequence.withAudioAndVideoFrom(
                    ImmutableList.of(
                        new EditedMediaItem.Builder(MediaItem.fromUri(MP4_SIMPLE_ASSET.uri))
                            .setDurationUs(1_090_000L)
                            .build(),
                        new EditedMediaItem.Builder(MediaItem.fromUri(MP4_SIMPLE_ASSET.uri))
                            .setDurationUs(1_090_000L)
                            .build())),
                EditedMediaItemSequence.withVideoFrom(ImmutableList.of(getImageItem())))
            .build();
    player = createTestHardwareBufferCompositionPlayerBuilder(frameProcessorFactory).build();

    player.setComposition(composition1);
    player.prepare();
    player.seekTo(/* positionMs= */ 500);
    advance(player).untilState(STATE_READY);

    FakeFrameProcessor frameProcessor = frameProcessorFactory.createdProcessor;
    assertThat(frameProcessor).isNotNull();
    assertThat(frameProcessor.getQueuedContentTimesUs())
        .containsExactly(ImmutableList.of(500_000L, 500_500L));

    player.setComposition(composition2, /* startPositionMs= */ 1_200);
    advance(player).untilState(STATE_READY);
    runMainLooperUntilContentTimesUs(frameProcessor, ImmutableList.of(1_223_466L));

    assertThat(frameProcessor.getQueuedContentTimesUs())
        .containsExactly(ImmutableList.of(500_000L, 500_500L), ImmutableList.of(1_223_466L))
        .inOrder();
  }

  @Test
  public void frameProcessor_playback_audio_endsSuccessfully() throws Exception {
    player = createTestHardwareBufferCompositionPlayerBuilder(frameProcessorFactory).build();
    EditedMediaItem audioItem =
        new EditedMediaItem.Builder(MediaItem.fromUri(WAV_ASSET.uri))
            .setDurationUs(1_000_000)
            .build();
    Composition composition =
        new Composition.Builder(withAudioFrom(ImmutableList.of(audioItem))).build();
    player.setComposition(composition);
    player.prepare();

    play(player).untilState(STATE_ENDED);

    FakeFrameProcessor frameProcessor = frameProcessorFactory.createdProcessor;
    assertThat(frameProcessor).isNotNull();
    assertThat(frameProcessor.getQueuedEvents()).hasSize(1);
    assertThat(frameProcessor.isEnded()).isTrue();
  }

  @Test
  public void frameProcessor_playback_image_endsWithExpectedNumberOfFrames() throws Exception {
    Composition composition =
        new Composition.Builder(
                EditedMediaItemSequence.withVideoFrom(ImmutableList.of(getImageItem())))
            .build();
    // The image sequence is 1_000_000 us (1 sec) long at 30 fps, so it should generate exactly 30
    // frames.
    ImmutableList.Builder<ImmutableList<Long>> expectedTimestampsUs = new ImmutableList.Builder<>();
    for (int i = 0; i < 30; i++) {
      expectedTimestampsUs.add(ImmutableList.of(Math.round(i * 1_000_000.0 / 30)));
    }
    expectedTimestampsUs.add(ImmutableList.of(C.TIME_UNSET));
    player = createTestHardwareBufferCompositionPlayerBuilder(frameProcessorFactory).build();

    player.setComposition(composition);
    player.prepare();

    play(player).untilState(STATE_ENDED);

    FakeFrameProcessor frameProcessor = frameProcessorFactory.createdProcessor;
    assertThat(frameProcessor).isNotNull();
    assertThat(frameProcessor.getQueuedContentTimesUs())
        .containsExactlyElementsIn(expectedTimestampsUs.build())
        .inOrder();
  }

  @Test
  public void frameProcessor_playback_imageAndAudio_endsWithExpectedNumberOfFrames()
      throws Exception {
    Composition composition =
        new Composition.Builder(
                EditedMediaItemSequence.withVideoFrom(ImmutableList.of(getImageItem())),
                EditedMediaItemSequence.withAudioFrom(
                    ImmutableList.of(
                        new EditedMediaItem.Builder(MediaItem.fromUri(WAV_ASSET.uri))
                            .setDurationUs(1_000_000)
                            .build())))
            .build();
    // The image sequence is 1_000_000 us (1 sec) long at 30 fps, so it should generate exactly 30
    // frames.
    ImmutableList.Builder<ImmutableList<Long>> expectedTimestampsUs = new ImmutableList.Builder<>();
    for (int i = 0; i < 30; i++) {
      expectedTimestampsUs.add(ImmutableList.of(Math.round(i * 1_000_000.0 / 30)));
    }
    expectedTimestampsUs.add(ImmutableList.of(C.TIME_UNSET));
    player = createTestHardwareBufferCompositionPlayerBuilder(frameProcessorFactory).build();

    player.setComposition(composition);
    player.prepare();

    play(player).untilState(STATE_ENDED);

    FakeFrameProcessor frameProcessor = frameProcessorFactory.createdProcessor;
    assertThat(frameProcessor).isNotNull();
    assertThat(frameProcessor.getQueuedContentTimesUs())
        .containsExactlyElementsIn(expectedTimestampsUs.build())
        .inOrder();
  }

  @Test
  public void frameProcessor_playback_videoAndAudio_endsWithExpectedTimestamps() throws Exception {
    Composition composition =
        new Composition.Builder(
                EditedMediaItemSequence.withAudioAndVideoFrom(
                    ImmutableList.of(
                        new EditedMediaItem.Builder(MediaItem.fromUri(MP4_SIMPLE_ASSET.uri))
                            .setDurationUs(1_090_000L)
                            .build())))
            .build();
    ImmutableList<ImmutableList<Long>> expectedTimestamps =
        new ImmutableList.Builder<ImmutableList<Long>>()
            .addAll(
                MP4_SIMPLE_ASSET.videoTimestampsUs.stream()
                    .map(ImmutableList::of)
                    .collect(toImmutableList()))
            .add(ImmutableList.of(C.TIME_UNSET))
            .build();
    player = createTestHardwareBufferCompositionPlayerBuilder(frameProcessorFactory).build();

    player.setComposition(composition);
    player.prepare();

    play(player).untilState(STATE_ENDED);

    FakeFrameProcessor frameProcessor = frameProcessorFactory.createdProcessor;
    assertThat(frameProcessor).isNotNull();
    assertThat(frameProcessor.getQueuedContentTimesUs())
        .containsExactlyElementsIn(expectedTimestamps)
        .inOrder();
  }

  @Test
  public void frameProcessor_imagePlayback_seekToMiddle_completesWithExpectedNumberOfFrames()
      throws Exception {
    Composition composition =
        new Composition.Builder(
                EditedMediaItemSequence.withVideoFrom(ImmutableList.of(getImageItem())))
            .build();
    player = createTestHardwareBufferCompositionPlayerBuilder(frameProcessorFactory).build();
    player.setComposition(composition);
    player.prepare();
    player.setPlayWhenReady(false);
    advance(player).untilState(STATE_READY);

    player.seekTo(/* positionMs= */ 500);
    play(player).untilState(STATE_ENDED);

    FakeFrameProcessor frameProcessor = frameProcessorFactory.createdProcessor;
    assertThat(frameProcessor).isNotNull();
    // 1 initial frame + 15 frames from 500ms + 1 EOS.
    assertThat(frameProcessor.getQueuedEvents()).hasSize(1 + 15 + 1);
  }

  @Test
  public void frameProcessor_oneImageSequence_seekForwardsAndBackwards_outputsCorrectFrames()
      throws Exception {
    Composition composition =
        new Composition.Builder(
                EditedMediaItemSequence.withVideoFrom(ImmutableList.of(getImageItem())))
            .build();
    player = createTestHardwareBufferCompositionPlayerBuilder(frameProcessorFactory).build();
    player.setComposition(composition);
    player.prepare();

    advance(player).untilState(STATE_READY);

    FakeFrameProcessor frameProcessor = frameProcessorFactory.createdProcessor;
    assertThat(frameProcessor).isNotNull();

    // Seek forwards
    player.setScrubbingModeEnabled(true);
    player.seekTo(/* positionMs= */ 500);
    player.setScrubbingModeEnabled(false);
    advance(player).untilState(STATE_READY);

    assertThat(player.getPlaybackState()).isEqualTo(STATE_READY);
    assertThat(player.getPlayWhenReady()).isFalse();
    assertThat(frameProcessor.getQueuedContentTimesUs())
        .containsExactly(ImmutableList.of(0L), ImmutableList.of(500_000L));

    // Seek backwards
    player.setScrubbingModeEnabled(true);
    player.seekTo(/* positionMs= */ 200);
    player.setScrubbingModeEnabled(false);
    advance(player).untilState(STATE_READY);

    assertThat(player.getPlaybackState()).isEqualTo(STATE_READY);
    assertThat(player.getPlayWhenReady()).isFalse();
    assertThat(frameProcessor.getQueuedContentTimesUs())
        .containsExactly(
            ImmutableList.of(0L), ImmutableList.of(500_000L), ImmutableList.of(200_000L));

    // Seek forwards
    player.setScrubbingModeEnabled(true);
    player.seekTo(/* positionMs= */ 750);
    player.setScrubbingModeEnabled(false);
    advance(player).untilState(STATE_READY);

    assertThat(player.getPlaybackState()).isEqualTo(STATE_READY);
    assertThat(player.getPlayWhenReady()).isFalse();
    assertThat(frameProcessor.getQueuedContentTimesUs())
        .containsExactly(
            ImmutableList.of(0L),
            ImmutableList.of(500_000L),
            ImmutableList.of(200_000L),
            ImmutableList.of(750_000L));
  }

  @Test
  public void frameProcessor_twoImageSequences_seekForwardsAndBackwards_outputsCorrectFrames()
      throws Exception {
    Composition composition =
        new Composition.Builder(
                EditedMediaItemSequence.withVideoFrom(ImmutableList.of(getImageItem())),
                EditedMediaItemSequence.withVideoFrom(ImmutableList.of(getImageItem())))
            .build();
    player = createTestHardwareBufferCompositionPlayerBuilder(frameProcessorFactory).build();
    player.setComposition(composition);
    player.prepare();

    advance(player).untilState(STATE_READY);

    FakeFrameProcessor frameProcessor = frameProcessorFactory.createdProcessor;
    assertThat(frameProcessor).isNotNull();

    // Seek forwards
    player.setScrubbingModeEnabled(true);
    player.seekTo(/* positionMs= */ 500);
    player.setScrubbingModeEnabled(false);
    advance(player).untilState(STATE_READY);

    assertThat(player.getPlaybackState()).isEqualTo(STATE_READY);
    assertThat(player.getPlayWhenReady()).isFalse();
    assertThat(frameProcessor.getQueuedContentTimesUs())
        .containsExactly(ImmutableList.of(0L, 0L), ImmutableList.of(500_000L, 500_000L));

    // Seek backwards
    player.setScrubbingModeEnabled(true);
    player.seekTo(/* positionMs= */ 200);
    player.setScrubbingModeEnabled(false);
    advance(player).untilState(STATE_READY);

    assertThat(player.getPlaybackState()).isEqualTo(STATE_READY);
    assertThat(player.getPlayWhenReady()).isFalse();
    assertThat(frameProcessor.getQueuedContentTimesUs())
        .containsExactly(
            ImmutableList.of(0L, 0L),
            ImmutableList.of(500_000L, 500_000L),
            ImmutableList.of(200_000L, 200_000L));

    // Seek forwards
    player.setScrubbingModeEnabled(true);
    player.seekTo(/* positionMs= */ 750);
    player.setScrubbingModeEnabled(false);
    advance(player).untilState(STATE_READY);

    assertThat(player.getPlaybackState()).isEqualTo(STATE_READY);
    assertThat(player.getPlayWhenReady()).isFalse();
    assertThat(frameProcessor.getQueuedContentTimesUs())
        .containsExactly(
            ImmutableList.of(0L, 0L),
            ImmutableList.of(500_000L, 500_000L),
            ImmutableList.of(200_000L, 200_000L),
            ImmutableList.of(750_000L, 750_000L));
  }

  @Test
  public void frameProcessor_oneImageSequence_rapidSeeking_debouncesSeeks() throws Exception {
    Composition composition =
        new Composition.Builder(
                EditedMediaItemSequence.withVideoFrom(ImmutableList.of(getImageItem())))
            .build();
    player = createTestHardwareBufferCompositionPlayerBuilder(frameProcessorFactory).build();
    player.setComposition(composition);
    player.prepare();

    advance(player).untilState(STATE_READY);

    FakeFrameProcessor frameProcessor = frameProcessorFactory.createdProcessor;
    assertThat(frameProcessor).isNotNull();

    // Seek forwards
    player.seekTo(/* positionMs= */ 100);
    player.seekTo(/* positionMs= */ 150);
    player.seekTo(/* positionMs= */ 200);
    player.seekTo(/* positionMs= */ 300);
    player.seekTo(/* positionMs= */ 400);
    player.seekTo(/* positionMs= */ 500);
    advance(player).untilState(STATE_READY);

    assertThat(player.getPlaybackState()).isEqualTo(STATE_READY);
    assertThat(player.getPlayWhenReady()).isFalse();
    assertThat(frameProcessor.getQueuedContentTimesUs())
        .containsExactly(
            ImmutableList.of(0L), ImmutableList.of(100_000L), ImmutableList.of(500_000L));

    // Seek backwards
    player.seekTo(/* positionMs= */ 400);
    player.seekTo(/* positionMs= */ 300);
    player.seekTo(/* positionMs= */ 200);
    player.seekTo(/* positionMs= */ 150);
    player.seekTo(/* positionMs= */ 100);
    advance(player).untilState(STATE_READY);

    assertThat(player.getPlaybackState()).isEqualTo(STATE_READY);
    assertThat(player.getPlayWhenReady()).isFalse();
    assertThat(frameProcessor.getQueuedContentTimesUs())
        .containsExactly(
            ImmutableList.of(0L),
            ImmutableList.of(100_000L),
            ImmutableList.of(500_000L),
            ImmutableList.of(400_000L),
            ImmutableList.of(100_000L));
  }

  @Test
  public void frameProcessor_twoImageSequences_rapidSeeking_debouncesSeeks() throws Exception {
    Composition composition =
        new Composition.Builder(
                EditedMediaItemSequence.withVideoFrom(ImmutableList.of(getImageItem())),
                EditedMediaItemSequence.withVideoFrom(ImmutableList.of(getImageItem())))
            .build();
    player = createTestHardwareBufferCompositionPlayerBuilder(frameProcessorFactory).build();
    player.setComposition(composition);
    player.prepare();

    advance(player).untilState(STATE_READY);

    FakeFrameProcessor frameProcessor = frameProcessorFactory.createdProcessor;
    assertThat(frameProcessor).isNotNull();

    // Seek forwards
    player.seekTo(/* positionMs= */ 100);
    player.seekTo(/* positionMs= */ 150);
    player.seekTo(/* positionMs= */ 200);
    player.seekTo(/* positionMs= */ 300);
    player.seekTo(/* positionMs= */ 400);
    player.seekTo(/* positionMs= */ 500);
    advance(player).untilState(STATE_READY);

    assertThat(player.getPlaybackState()).isEqualTo(STATE_READY);
    assertThat(player.getPlayWhenReady()).isFalse();
    assertThat(frameProcessor.getQueuedContentTimesUs())
        .containsExactly(
            ImmutableList.of(0L, 0L),
            ImmutableList.of(100_000L, 100_000L),
            ImmutableList.of(500_000L, 500_000L));

    // Seek backwards
    player.seekTo(/* positionMs= */ 400);
    player.seekTo(/* positionMs= */ 300);
    player.seekTo(/* positionMs= */ 200);
    player.seekTo(/* positionMs= */ 150);
    player.seekTo(/* positionMs= */ 100);
    advance(player).untilState(STATE_READY);

    assertThat(player.getPlaybackState()).isEqualTo(STATE_READY);
    assertThat(player.getPlayWhenReady()).isFalse();
    assertThat(frameProcessor.getQueuedContentTimesUs())
        .containsExactly(
            ImmutableList.of(0L, 0L),
            ImmutableList.of(100_000L, 100_000L),
            ImmutableList.of(500_000L, 500_000L),
            ImmutableList.of(400_000L, 400_000L),
            ImmutableList.of(100_000L, 100_000L));
  }

  @Test
  public void frameProcessor_oneImageSequence_scrubbing_debouncesSeeks() throws Exception {
    Composition composition =
        new Composition.Builder(
                EditedMediaItemSequence.withVideoFrom(ImmutableList.of(getImageItem())))
            .build();
    player = createTestHardwareBufferCompositionPlayerBuilder(frameProcessorFactory).build();
    player.setComposition(composition);
    player.prepare();

    advance(player).untilState(STATE_READY);

    FakeFrameProcessor frameProcessor = frameProcessorFactory.createdProcessor;
    assertThat(frameProcessor).isNotNull();

    // Seek forwards
    player.setScrubbingModeEnabled(true);
    player.seekTo(/* positionMs= */ 100);
    player.seekTo(/* positionMs= */ 150);
    player.seekTo(/* positionMs= */ 200);
    player.seekTo(/* positionMs= */ 300);
    player.seekTo(/* positionMs= */ 400);
    player.seekTo(/* positionMs= */ 500);
    player.setScrubbingModeEnabled(false);
    advance(player).untilState(STATE_READY);

    assertThat(player.getPlaybackState()).isEqualTo(STATE_READY);
    assertThat(player.getPlayWhenReady()).isFalse();
    assertThat(frameProcessor.getQueuedContentTimesUs())
        .containsExactly(ImmutableList.of(0L), ImmutableList.of(500_000L));

    // Seek backwards
    player.setScrubbingModeEnabled(true);
    player.seekTo(/* positionMs= */ 400);
    player.seekTo(/* positionMs= */ 300);
    player.seekTo(/* positionMs= */ 200);
    player.seekTo(/* positionMs= */ 150);
    player.seekTo(/* positionMs= */ 100);
    player.setScrubbingModeEnabled(false);
    advance(player).untilState(STATE_READY);

    assertThat(player.getPlaybackState()).isEqualTo(STATE_READY);
    assertThat(player.getPlayWhenReady()).isFalse();
    assertThat(frameProcessor.getQueuedContentTimesUs())
        .containsExactly(
            ImmutableList.of(0L), ImmutableList.of(500_000L), ImmutableList.of(100_000L));
  }

  @Test
  public void frameProcessor_twoImageSequences_scrubbing_debouncesSeeks() throws Exception {
    Composition composition =
        new Composition.Builder(
                EditedMediaItemSequence.withVideoFrom(ImmutableList.of(getImageItem())),
                EditedMediaItemSequence.withVideoFrom(ImmutableList.of(getImageItem())))
            .build();
    player = createTestHardwareBufferCompositionPlayerBuilder(frameProcessorFactory).build();
    player.setComposition(composition);
    player.prepare();

    advance(player).untilState(STATE_READY);

    FakeFrameProcessor frameProcessor = frameProcessorFactory.createdProcessor;
    assertThat(frameProcessor).isNotNull();

    // Seek forwards
    player.setScrubbingModeEnabled(true);
    player.seekTo(/* positionMs= */ 100);
    player.seekTo(/* positionMs= */ 150);
    player.seekTo(/* positionMs= */ 200);
    player.seekTo(/* positionMs= */ 300);
    player.seekTo(/* positionMs= */ 400);
    player.seekTo(/* positionMs= */ 500);
    player.setScrubbingModeEnabled(false);
    advance(player).untilState(STATE_READY);

    assertThat(player.getPlaybackState()).isEqualTo(STATE_READY);
    assertThat(player.getPlayWhenReady()).isFalse();
    assertThat(frameProcessor.getQueuedContentTimesUs())
        .containsExactly(ImmutableList.of(0L, 0L), ImmutableList.of(500_000L, 500_000L));

    // Seek backwards
    player.setScrubbingModeEnabled(true);
    player.seekTo(/* positionMs= */ 400);
    player.seekTo(/* positionMs= */ 300);
    player.seekTo(/* positionMs= */ 200);
    player.seekTo(/* positionMs= */ 150);
    player.seekTo(/* positionMs= */ 100);
    player.setScrubbingModeEnabled(false);
    advance(player).untilState(STATE_READY);

    assertThat(player.getPlaybackState()).isEqualTo(STATE_READY);
    assertThat(player.getPlayWhenReady()).isFalse();
    assertThat(frameProcessor.getQueuedContentTimesUs())
        .containsExactly(
            ImmutableList.of(0L, 0L),
            ImmutableList.of(500_000L, 500_000L),
            ImmutableList.of(100_000L, 100_000L));
  }

  @Test
  public void frameProcessor_oneImageSequence_scrubThenWait_debouncesSeeks() throws Exception {
    Composition composition =
        new Composition.Builder(
                EditedMediaItemSequence.withVideoFrom(ImmutableList.of(getImageItem())))
            .build();
    player = createTestHardwareBufferCompositionPlayerBuilder(frameProcessorFactory).build();
    player.setComposition(composition);
    player.prepare();

    advance(player).untilState(STATE_READY);

    FakeFrameProcessor frameProcessor = frameProcessorFactory.createdProcessor;
    assertThat(frameProcessor).isNotNull();

    // Seek forwards
    player.setScrubbingModeEnabled(true);
    player.seekTo(/* positionMs= */ 100);
    player.seekTo(/* positionMs= */ 150);
    player.seekTo(/* positionMs= */ 200);
    player.seekTo(/* positionMs= */ 300);
    player.seekTo(/* positionMs= */ 400);
    player.seekTo(/* positionMs= */ 500);
    advance(player).untilState(STATE_READY);

    assertThat(player.getPlaybackState()).isEqualTo(STATE_READY);
    assertThat(player.getPlayWhenReady()).isFalse();
    assertThat(frameProcessor.getQueuedContentTimesUs())
        .containsExactly(
            ImmutableList.of(0L), ImmutableList.of(100_000L), ImmutableList.of(500_000L));

    // Seek backwards
    player.seekTo(/* positionMs= */ 400);
    player.seekTo(/* positionMs= */ 300);
    player.seekTo(/* positionMs= */ 200);
    player.seekTo(/* positionMs= */ 150);
    player.seekTo(/* positionMs= */ 100);
    player.setScrubbingModeEnabled(false);
    advance(player).untilState(STATE_READY);

    assertThat(player.getPlaybackState()).isEqualTo(STATE_READY);
    assertThat(player.getPlayWhenReady()).isFalse();
    assertThat(frameProcessor.getQueuedContentTimesUs())
        .containsExactly(
            ImmutableList.of(0L),
            ImmutableList.of(100_000L),
            ImmutableList.of(500_000L),
            ImmutableList.of(100_000L));
  }

  @Test
  public void frameProcessor_twoImageSequences_scrubThenWait_debouncesSeeks() throws Exception {
    Composition composition =
        new Composition.Builder(
                EditedMediaItemSequence.withVideoFrom(ImmutableList.of(getImageItem())),
                EditedMediaItemSequence.withVideoFrom(ImmutableList.of(getImageItem())))
            .build();
    player = createTestHardwareBufferCompositionPlayerBuilder(frameProcessorFactory).build();
    player.setComposition(composition);
    player.prepare();

    advance(player).untilState(STATE_READY);

    FakeFrameProcessor frameProcessor = frameProcessorFactory.createdProcessor;
    assertThat(frameProcessor).isNotNull();

    // Seek forwards
    player.setScrubbingModeEnabled(true);
    player.seekTo(/* positionMs= */ 100);
    player.seekTo(/* positionMs= */ 150);
    player.seekTo(/* positionMs= */ 200);
    player.seekTo(/* positionMs= */ 300);
    player.seekTo(/* positionMs= */ 400);
    player.seekTo(/* positionMs= */ 500);
    advance(player).untilState(STATE_READY);

    assertThat(player.getPlaybackState()).isEqualTo(STATE_READY);
    assertThat(player.getPlayWhenReady()).isFalse();
    assertThat(frameProcessor.getQueuedContentTimesUs())
        .containsExactly(
            ImmutableList.of(0L, 0L),
            ImmutableList.of(100_000L, 100_000L),
            ImmutableList.of(500_000L, 500_000L));

    // Seek backwards
    player.seekTo(/* positionMs= */ 400);
    player.seekTo(/* positionMs= */ 300);
    player.seekTo(/* positionMs= */ 200);
    player.seekTo(/* positionMs= */ 150);
    player.seekTo(/* positionMs= */ 100);
    player.setScrubbingModeEnabled(false);
    advance(player).untilState(STATE_READY);

    assertThat(player.getPlaybackState()).isEqualTo(STATE_READY);
    assertThat(player.getPlayWhenReady()).isFalse();
    assertThat(frameProcessor.getQueuedContentTimesUs())
        .containsExactly(
            ImmutableList.of(0L, 0L),
            ImmutableList.of(100_000L, 100_000L),
            ImmutableList.of(500_000L, 500_000L),
            ImmutableList.of(100_000L, 100_000L));
  }

  @Test
  public void frameProcessor_oneImageSequence_seekThenPlay_outputsPacketAndEnds() throws Exception {
    Composition composition =
        new Composition.Builder(
                EditedMediaItemSequence.withVideoFrom(ImmutableList.of(getImageItem())))
            .build();
    player = createTestHardwareBufferCompositionPlayerBuilder(frameProcessorFactory).build();
    player.setComposition(composition);
    player.prepare();

    advance(player).untilState(STATE_READY);

    FakeFrameProcessor frameProcessor = frameProcessorFactory.createdProcessor;
    assertThat(frameProcessor).isNotNull();

    player.setScrubbingModeEnabled(true);
    player.seekTo(/* positionMs= */ 750);
    player.setScrubbingModeEnabled(false);
    advance(player).untilState(STATE_READY);

    assertThat(player.getPlaybackState()).isEqualTo(STATE_READY);
    assertThat(player.getPlayWhenReady()).isFalse();
    assertThat(frameProcessor.getQueuedContentTimesUs())
        .containsExactly(ImmutableList.of(0L), ImmutableList.of(750_000L));

    play(player).untilState(STATE_ENDED);

    // 2 seeked frames + 7 played frames + 1 EOS.
    assertThat(frameProcessor.getQueuedEvents()).hasSize(2 + 7 + 1);
    assertThat(frameProcessor.isEnded()).isTrue();
  }

  @Test
  public void frameProcessor_twoImageSequences_seekThenPlay_outputsPacketAndEnds()
      throws Exception {
    Composition composition =
        new Composition.Builder(
                EditedMediaItemSequence.withVideoFrom(ImmutableList.of(getImageItem())),
                EditedMediaItemSequence.withVideoFrom(ImmutableList.of(getImageItem())))
            .build();
    player = createTestHardwareBufferCompositionPlayerBuilder(frameProcessorFactory).build();
    player.setComposition(composition);
    player.prepare();

    advance(player).untilState(STATE_READY);

    FakeFrameProcessor frameProcessor = frameProcessorFactory.createdProcessor;
    assertThat(frameProcessor).isNotNull();

    player.setScrubbingModeEnabled(true);
    player.seekTo(/* positionMs= */ 750);
    player.setScrubbingModeEnabled(false);
    advance(player).untilState(STATE_READY);

    assertThat(player.getPlaybackState()).isEqualTo(STATE_READY);
    assertThat(player.getPlayWhenReady()).isFalse();
    assertThat(frameProcessor.getQueuedContentTimesUs())
        .containsExactly(ImmutableList.of(0L, 0L), ImmutableList.of(750_000L, 750_000L));

    play(player).untilState(STATE_ENDED);

    // 2 seeked frames + 7 played frames + 1 EOS.
    assertThat(frameProcessor.getQueuedEvents()).hasSize(2 + 7 + 1);
    assertThat(frameProcessor.isEnded()).isTrue();
  }

  @Test
  public void frameProcessor_oneVideoSequence_seekForwardsAndBackwards_outputsCorrectFrames()
      throws Exception {
    Composition composition =
        new Composition.Builder(
                EditedMediaItemSequence.withAudioAndVideoFrom(
                    ImmutableList.of(
                        new EditedMediaItem.Builder(MediaItem.fromUri(MP4_SIMPLE_ASSET.uri))
                            .setDurationUs(MP4_SIMPLE_ASSET.videoDurationUs)
                            .build())))
            .build();
    player = createTestHardwareBufferCompositionPlayerBuilder(frameProcessorFactory).build();
    player.setComposition(composition);
    player.prepare();

    advance(player).untilState(STATE_READY);

    FakeFrameProcessor frameProcessor = frameProcessorFactory.createdProcessor;
    assertThat(frameProcessor).isNotNull();

    // Seek forwards
    player.setScrubbingModeEnabled(true);
    player.seekTo(/* positionMs= */ 500);
    player.setScrubbingModeEnabled(false);
    advance(player).untilState(STATE_READY);

    // Seek backwards
    player.setScrubbingModeEnabled(true);
    player.seekTo(/* positionMs= */ 200);
    player.setScrubbingModeEnabled(false);
    advance(player).untilState(STATE_READY);

    // Seek forwards
    player.setScrubbingModeEnabled(true);
    player.seekTo(/* positionMs= */ 750);
    player.setScrubbingModeEnabled(false);
    advance(player).untilState(STATE_READY);

    assertThat(frameProcessor.getQueuedContentTimesUs())
        .containsExactly(
            ImmutableList.of(0L),
            ImmutableList.of(500_500L),
            ImmutableList.of(200_200L),
            ImmutableList.of(767_433L))
        .inOrder();
  }

  @Test
  public void frameProcessor_twoVideoSequences_seekForwardsAndBackwards_outputsCorrectFrames()
      throws Exception {
    Composition composition =
        new Composition.Builder(
                EditedMediaItemSequence.withAudioAndVideoFrom(
                    ImmutableList.of(
                        new EditedMediaItem.Builder(MediaItem.fromUri(MP4_SIMPLE_ASSET.uri))
                            .setDurationUs(MP4_SIMPLE_ASSET.videoDurationUs)
                            .build())),
                EditedMediaItemSequence.withAudioAndVideoFrom(
                    ImmutableList.of(
                        new EditedMediaItem.Builder(MediaItem.fromUri(MP4_SIMPLE_ASSET.uri))
                            .setDurationUs(MP4_SIMPLE_ASSET.videoDurationUs)
                            .build())))
            .build();
    player = createTestHardwareBufferCompositionPlayerBuilder(frameProcessorFactory).build();
    player.setComposition(composition);
    player.prepare();

    advance(player).untilState(STATE_READY);

    FakeFrameProcessor frameProcessor = frameProcessorFactory.createdProcessor;
    assertThat(frameProcessor).isNotNull();

    // Seek forwards
    player.setScrubbingModeEnabled(true);
    player.seekTo(/* positionMs= */ 500);
    player.setScrubbingModeEnabled(false);
    advance(player).untilState(STATE_READY);

    // Seek backwards
    player.setScrubbingModeEnabled(true);
    player.seekTo(/* positionMs= */ 200);
    player.setScrubbingModeEnabled(false);
    advance(player).untilState(STATE_READY);

    // Seek forwards
    player.setScrubbingModeEnabled(true);
    player.seekTo(/* positionMs= */ 750);
    player.setScrubbingModeEnabled(false);
    advance(player).untilState(STATE_READY);

    assertThat(frameProcessor.getQueuedContentTimesUs())
        .containsExactly(
            ImmutableList.of(0L, 0L),
            ImmutableList.of(500_500L, 500_500L),
            ImmutableList.of(200_200L, 200_200L),
            ImmutableList.of(767_433L, 767_433L))
        .inOrder();
  }

  @Test
  public void frameProcessor_oneVideoSequence_scrubForwardsAndBackwards_debouncesFrames()
      throws Exception {
    Composition composition =
        new Composition.Builder(
                EditedMediaItemSequence.withAudioAndVideoFrom(
                    ImmutableList.of(
                        new EditedMediaItem.Builder(MediaItem.fromUri(MP4_SIMPLE_ASSET.uri))
                            .setDurationUs(MP4_SIMPLE_ASSET.videoDurationUs)
                            .build())))
            .build();
    player = createTestHardwareBufferCompositionPlayerBuilder(frameProcessorFactory).build();
    player.setComposition(composition);
    player.prepare();

    advance(player).untilState(STATE_READY);

    FakeFrameProcessor frameProcessor = frameProcessorFactory.createdProcessor;
    assertThat(frameProcessor).isNotNull();

    // Seek forwards
    player.setScrubbingModeEnabled(true);
    player.seekTo(/* positionMs= */ 100);
    player.seekTo(/* positionMs= */ 150);
    player.seekTo(/* positionMs= */ 200);
    player.seekTo(/* positionMs= */ 300);
    player.seekTo(/* positionMs= */ 500);
    player.setScrubbingModeEnabled(false);
    advance(player).untilState(STATE_READY);

    // Seek backwards
    player.setScrubbingModeEnabled(true);
    player.seekTo(/* positionMs= */ 400);
    player.seekTo(/* positionMs= */ 300);
    player.seekTo(/* positionMs= */ 200);
    player.seekTo(/* positionMs= */ 150);
    player.seekTo(/* positionMs= */ 100);
    player.setScrubbingModeEnabled(false);
    advance(player).untilState(STATE_READY);

    assertThat(frameProcessor.getQueuedContentTimesUs())
        .containsExactly(
            ImmutableList.of(0L), ImmutableList.of(500_500L), ImmutableList.of(100_100L))
        .inOrder();
  }

  @Test
  public void frameProcessor_twoVideoSequences_scrubForwardsAndBackwards_debouncesFrames()
      throws Exception {
    Composition composition =
        new Composition.Builder(
                EditedMediaItemSequence.withAudioAndVideoFrom(
                    ImmutableList.of(
                        new EditedMediaItem.Builder(MediaItem.fromUri(MP4_SIMPLE_ASSET.uri))
                            .setDurationUs(MP4_SIMPLE_ASSET.videoDurationUs)
                            .build())),
                EditedMediaItemSequence.withAudioAndVideoFrom(
                    ImmutableList.of(
                        new EditedMediaItem.Builder(MediaItem.fromUri(MP4_SIMPLE_ASSET.uri))
                            .setDurationUs(MP4_SIMPLE_ASSET.videoDurationUs)
                            .build())))
            .build();
    player = createTestHardwareBufferCompositionPlayerBuilder(frameProcessorFactory).build();
    player.setComposition(composition);
    player.prepare();

    advance(player).untilState(STATE_READY);

    FakeFrameProcessor frameProcessor = frameProcessorFactory.createdProcessor;
    assertThat(frameProcessor).isNotNull();

    // Seek forwards
    player.setScrubbingModeEnabled(true);
    player.seekTo(/* positionMs= */ 100);
    player.seekTo(/* positionMs= */ 150);
    player.seekTo(/* positionMs= */ 200);
    player.seekTo(/* positionMs= */ 300);
    player.seekTo(/* positionMs= */ 500);
    player.setScrubbingModeEnabled(false);
    advance(player).untilState(STATE_READY);

    // Seek backwards
    player.setScrubbingModeEnabled(true);
    player.seekTo(/* positionMs= */ 400);
    player.seekTo(/* positionMs= */ 300);
    player.seekTo(/* positionMs= */ 200);
    player.seekTo(/* positionMs= */ 150);
    player.seekTo(/* positionMs= */ 100);
    player.setScrubbingModeEnabled(false);
    advance(player).untilState(STATE_READY);

    assertThat(frameProcessor.getQueuedContentTimesUs())
        .containsExactly(
            ImmutableList.of(0L, 0L),
            ImmutableList.of(500_500L, 500_500L),
            ImmutableList.of(100_100L, 100_100L))
        .inOrder();
  }

  @Test
  public void frameProcessor_oneVideoSequence_seekThenPlay_outputsPacketAndEnds() throws Exception {
    Composition composition =
        new Composition.Builder(
                EditedMediaItemSequence.withAudioAndVideoFrom(
                    ImmutableList.of(
                        new EditedMediaItem.Builder(MediaItem.fromUri(MP4_SIMPLE_ASSET.uri))
                            .setDurationUs(MP4_SIMPLE_ASSET.videoDurationUs)
                            .build())))
            .build();
    player = createTestHardwareBufferCompositionPlayerBuilder(frameProcessorFactory).build();
    player.setComposition(composition);
    player.prepare();

    advance(player).untilState(STATE_READY);
    player.setScrubbingModeEnabled(true);
    player.seekTo(/* positionMs= */ 750);
    player.setScrubbingModeEnabled(false);
    advance(player).untilState(STATE_READY);

    FakeFrameProcessor frameProcessor = frameProcessorFactory.createdProcessor;
    assertThat(frameProcessor).isNotNull();

    player.play();
    advance(player).untilState(STATE_ENDED);

    assertThat(frameProcessor.getQueuedContentTimesUs())
        .containsExactly(
            ImmutableList.of(0L),
            ImmutableList.of(767_433L),
            ImmutableList.of(800_800L),
            ImmutableList.of(834_166L),
            ImmutableList.of(867_533L),
            ImmutableList.of(900_900L),
            ImmutableList.of(934_266L),
            ImmutableList.of(967_633L),
            ImmutableList.of(C.TIME_UNSET))
        .inOrder();
  }

  @Test
  public void frameProcessor_twoVideoSequences_seekThenPlay_outputsPacketAndEnds()
      throws Exception {
    Composition composition =
        new Composition.Builder(
                EditedMediaItemSequence.withAudioAndVideoFrom(
                    ImmutableList.of(
                        new EditedMediaItem.Builder(MediaItem.fromUri(MP4_SIMPLE_ASSET.uri))
                            .setDurationUs(MP4_SIMPLE_ASSET.videoDurationUs)
                            .build())),
                EditedMediaItemSequence.withAudioAndVideoFrom(
                    ImmutableList.of(
                        new EditedMediaItem.Builder(MediaItem.fromUri(MP4_SIMPLE_ASSET.uri))
                            .setDurationUs(MP4_SIMPLE_ASSET.videoDurationUs)
                            .build())))
            .build();
    player = createTestHardwareBufferCompositionPlayerBuilder(frameProcessorFactory).build();
    player.setComposition(composition);
    player.prepare();

    advance(player).untilState(STATE_READY);
    player.setScrubbingModeEnabled(true);
    player.seekTo(/* positionMs= */ 750);
    player.setScrubbingModeEnabled(false);
    advance(player).untilState(STATE_READY);

    FakeFrameProcessor frameProcessor = frameProcessorFactory.createdProcessor;
    assertThat(frameProcessor).isNotNull();

    player.play();
    advance(player).untilState(STATE_ENDED);

    assertThat(frameProcessor.getQueuedContentTimesUs())
        .containsExactly(
            ImmutableList.of(0L, 0L),
            ImmutableList.of(767_433L, 767_433L),
            ImmutableList.of(800_800L, 800_800L),
            ImmutableList.of(834_166L, 834_166L),
            ImmutableList.of(867_533L, 867_533L),
            ImmutableList.of(900_900L, 900_900L),
            ImmutableList.of(934_266L, 934_266L),
            ImmutableList.of(967_633L, 967_633L),
            ImmutableList.of(C.TIME_UNSET))
        .inOrder();
  }

  @Test
  public void
      frameProcessor_twoVideoSequencesMismatchedFrameRates_seek_decodesExtraSecondaryFrames()
          throws Exception {

    Composition composition =
        new Composition.Builder(
                EditedMediaItemSequence.withVideoFrom(
                    ImmutableList.of(
                        new EditedMediaItem.Builder(MediaItem.fromUri(MP4_12_5FPS.uri))
                            .setDurationUs(MP4_12_5FPS.videoDurationUs)
                            .build())),
                EditedMediaItemSequence.withVideoFrom(
                    ImmutableList.of(
                        new EditedMediaItem.Builder(MediaItem.fromUri(MP4_15FPS.uri))
                            .setDurationUs(MP4_15FPS.videoDurationUs)
                            .build())))
            .build();

    player = createTestHardwareBufferCompositionPlayerBuilder(frameProcessorFactory).build();
    player.setComposition(composition);
    player.prepare();
    advance(player).untilState(STATE_READY);

    FakeFrameProcessor frameProcessor = frameProcessorFactory.createdProcessor;
    assertThat(frameProcessor).isNotNull();

    // Seek to 466ms, primary will resolve to 480_000, secondary will resolve to 466_667, then
    // decode an extra frame and output 533_333.
    player.setScrubbingModeEnabled(true);
    player.seekTo(466);
    player.setScrubbingModeEnabled(false);

    // Because extra frames need to be decoded, advancing until the player is ready is not enough,
    // we need to wait until the FrameProcessor receives the new frames.
    advance(player).untilState(STATE_READY);
    runMainLooperUntilContentTimesUs(frameProcessor, ImmutableList.of(480_000L, 533_333L));

    assertThat(frameProcessor.getQueuedContentTimesUs())
        .containsExactly(ImmutableList.of(0L, 0L), ImmutableList.of(480_000L, 533_333L))
        .inOrder();
  }

  @Test
  public void frameProcessor_interactedWithOnPlaybackThreadExclusively() throws Exception {
    AtomicReference<Looper> playbackLooper = new AtomicReference<>();
    AtomicInteger threadViolations = new AtomicInteger();
    AtomicInteger createCalls = new AtomicInteger();
    AtomicInteger queueCalls = new AtomicInteger();
    AtomicInteger signalEndOfStreamCalls = new AtomicInteger();
    AtomicInteger closeCalls = new AtomicInteger();
    AtomicInteger onFrameProcessedCalls = new AtomicInteger();
    AtomicInteger verifyThreadCalls = new AtomicInteger();

    FrameProcessor.Factory threadVerifyingFactory =
        new FrameProcessor.Factory() {
          @Override
          public FrameProcessor create(
              FrameWriter output, Executor listenerExecutor, FrameProcessor.Listener listener) {
            createCalls.incrementAndGet();
            return new ThreadVerifyingFrameProcessor(
                output,
                listenerExecutor,
                listener,
                playbackLooper,
                threadViolations,
                queueCalls,
                signalEndOfStreamCalls,
                closeCalls,
                onFrameProcessedCalls,
                verifyThreadCalls);
          }
        };

    player = createTestHardwareBufferCompositionPlayerBuilder(threadVerifyingFactory).build();
    playbackLooper.set(player.getPlaybackLooper());
    assertThat(playbackLooper.get()).isNotNull();

    Composition composition =
        new Composition.Builder(
                EditedMediaItemSequence.withVideoFrom(ImmutableList.of(getImageItem())))
            .build();

    player.setComposition(composition);
    player.prepare();
    play(player).untilState(STATE_ENDED);

    Thread playbackThread = player.getPlaybackLooper().getThread();
    player.release();
    playbackThread.join(/* millis= */ 10_000);

    assertThat(threadViolations.get()).isEqualTo(0);
    assertThat(createCalls.get()).isGreaterThan(0);
    assertThat(queueCalls.get()).isGreaterThan(0);
    assertThat(signalEndOfStreamCalls.get()).isGreaterThan(0);
    assertThat(closeCalls.get()).isGreaterThan(0);
    assertThat(onFrameProcessedCalls.get()).isGreaterThan(0);
    assertThat(verifyThreadCalls.get()).isGreaterThan(0);
  }

  private static EditedMediaItem getImageItem() {
    return new EditedMediaItem.Builder(
            new MediaItem.Builder()
                .setUri(ASSET_URI_PREFIX + FILE_PNG)
                .setImageDurationMs(usToMs(/* timeUs= */ 1_000_000L))
                .build())
        .setDurationUs(1_000_000L)
        .setFrameRate(30)
        .build();
  }

  private static Composition buildComposition() {
    // Use raw audio-only assets which can be played in robolectric tests.
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
    return new Composition.Builder(sequence).build();
  }

  /**
   * Runs the main looper until frames with the given content times are received by the {@code
   * frameProcessor}.
   */
  private static void runMainLooperUntilContentTimesUs(
      FakeFrameProcessor frameProcessor, ImmutableList<Long> timestamps) throws TimeoutException {
    runMainLooperUntil(
        () -> {
          List<AsyncFrame> lastFrames = frameProcessor.lastFrames;
          if (lastFrames == null || lastFrames.size() != timestamps.size()) {
            return false;
          }
          for (int i = 0; i < timestamps.size(); i++) {
            if (lastFrames.get(i).frame.getContentTimeUs() != timestamps.get(i)) {
              return false;
            }
          }
          return true;
        });
  }

  private static final class CustomLoadControl extends DefaultLoadControl {
    public boolean prepared;

    @Override
    public void onPrepared(PlayerId playerId) {
      prepared = true;
      super.onPrepared(playerId);
    }
  }

  private static Effects toEffects(AudioProcessor processor) {
    return new Effects(ImmutableList.of(processor), /* videoEffects= */ ImmutableList.of());
  }

  private static final class DurationTestCasesProvider extends TestParameterValuesProvider {
    @Override
    protected List<Input> provideValues(TestParameterValuesProvider.Context context) {
      return ImmutableList.of(
          TWO_AUDIO_ITEMS,
          TWO_AUDIO_ITEMS_WITH_SET_SPEED,
          AUDIO_ITEM_WITH_SET_DOUBLE_SPEED,
          AUDIO_ITEM_WITH_CLIPPED_START,
          AUDIO_ITEM_WITH_CLIPPED_START_GREATER_THAN_HALF_DURATION,
          AUDIO_ITEM_WITH_CLIPPED_END,
          AUDIO_ITEM_WITH_CLIPPED_START_AND_END,
          AUDIO_ITEM_WITH_CLIPPED_START_AND_DOUBLE_SPEED_EFFECT,
          AUDIO_ITEM_WITH_CLIPPED_START_AND_SET_DOUBLE_SPEED,
          AUDIO_ITEM_WITH_CLIPPED_END_AND_DOUBLE_SPEED_EFFECT,
          AUDIO_ITEM_WITH_CLIPPED_END_AND_SET_DOUBLE_SPEED,
          AUDIO_ITEM_WITH_CLIPPED_START_AND_END_WITH_HALF_SPEED_EFFECT,
          AUDIO_ITEM_WITH_CLIPPED_START_AND_END_WITH_SET_HALF_SPEED);
    }
  }

  private static final class Input {
    // Use a Supplier because Robolectric mocking is not configured at this point and will make
    // Android API calls crash.
    private final Supplier<Composition> composition;
    private final int expectedDurationMs;
    private final String name;

    private Input(Supplier<Composition> composition, int expectedDurationMs, String name) {
      this.composition = composition;
      this.expectedDurationMs = expectedDurationMs;
      this.name = name;
    }

    @Override
    public String toString() {
      return name;
    }
  }

  private static final class ThreadVerifyingFrameProcessor implements FrameProcessor {
    private final FrameProcessor.Listener listener;
    private final FrameWriter output;
    private final Executor listenerExecutor;
    private final AtomicReference<Looper> playbackLooper;
    private final AtomicInteger threadViolations;
    private final AtomicInteger queueCalls;
    private final AtomicInteger signalEndOfStreamCalls;
    private final AtomicInteger closeCalls;
    private final AtomicInteger onFrameProcessedCalls;
    private final AtomicInteger verifyThreadCalls;

    ThreadVerifyingFrameProcessor(
        FrameWriter output,
        Executor listenerExecutor,
        FrameProcessor.Listener listener,
        AtomicReference<Looper> playbackLooper,
        AtomicInteger threadViolations,
        AtomicInteger queueCalls,
        AtomicInteger signalEndOfStreamCalls,
        AtomicInteger closeCalls,
        AtomicInteger onFrameProcessedCalls,
        AtomicInteger verifyThreadCalls) {
      this.output = output;
      this.listenerExecutor = listenerExecutor;
      this.playbackLooper = playbackLooper;
      this.threadViolations = threadViolations;
      this.queueCalls = queueCalls;
      this.signalEndOfStreamCalls = signalEndOfStreamCalls;
      this.closeCalls = closeCalls;
      this.onFrameProcessedCalls = onFrameProcessedCalls;
      this.verifyThreadCalls = verifyThreadCalls;

      this.listener =
          new FrameProcessor.Listener() {
            @Override
            public void onWakeup() {
              verifyThread();
              listener.onWakeup();
            }

            @Override
            public void onError(VideoFrameProcessingException exception) {
              verifyThread();
              listener.onError(exception);
            }

            @Override
            public void onFrameProcessed(Frame frame, @Nullable SyncFenceWrapper onCompleteFence) {
              verifyThread();
              ThreadVerifyingFrameProcessor.this.onFrameProcessedCalls.incrementAndGet();
              listener.onFrameProcessed(frame, onCompleteFence);
            }

            private void verifyThread() {
              ThreadVerifyingFrameProcessor.this.verifyThreadCalls.incrementAndGet();
              if (Looper.myLooper() != ThreadVerifyingFrameProcessor.this.playbackLooper.get()) {
                ThreadVerifyingFrameProcessor.this.threadViolations.incrementAndGet();
              }
            }
          };
    }

    @Override
    public boolean queue(List<AsyncFrame> frames) {
      verifyThread();
      queueCalls.incrementAndGet();
      listenerExecutor.execute(
          () -> {
            for (AsyncFrame asyncFrame : frames) {
              listener.onFrameProcessed(asyncFrame.frame, /* onCompleteFence= */ null);
            }
          });
      return true;
    }

    @Override
    public void signalEndOfStream() {
      verifyThread();
      signalEndOfStreamCalls.incrementAndGet();
      output.signalEndOfStream();
    }

    @Override
    public void close() {
      // close() is currently called on the application thread (TODO: b/498547782).
      closeCalls.incrementAndGet();
    }

    private void verifyThread() {
      verifyThreadCalls.incrementAndGet();
      if (Looper.myLooper() != playbackLooper.get()) {
        threadViolations.incrementAndGet();
      }
    }
  }
}
