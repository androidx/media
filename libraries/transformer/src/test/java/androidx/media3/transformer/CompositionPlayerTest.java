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
import static androidx.media3.test.utils.TestUtil.createByteCountingAudioProcessor;
import static androidx.media3.test.utils.TestUtil.getCommandsAsList;
import static androidx.media3.test.utils.robolectric.RobolectricUtil.runMainLooperUntil;
import static androidx.media3.test.utils.robolectric.TestPlayerRunHelper.advance;
import static androidx.media3.test.utils.robolectric.TestPlayerRunHelper.play;
import static androidx.media3.transformer.EditedMediaItemSequence.withAudioFrom;
import static androidx.media3.transformer.TestUtil.ASSET_URI_PREFIX;
import static androidx.media3.transformer.TestUtil.FILE_AUDIO_RAW;
import static androidx.media3.transformer.TestUtil.FILE_AUDIO_RAW_STEREO_48000KHZ;
import static androidx.media3.transformer.TestUtil.createTestCompositionPlayer;
import static androidx.media3.transformer.TestUtil.createTestCompositionPlayerBuilder;
import static androidx.test.core.app.ApplicationProvider.getApplicationContext;
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
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.Timeline;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.SpeedChangingAudioProcessor;
import androidx.media3.common.audio.SpeedProvider;
import androidx.media3.common.util.ConditionVariable;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.NullableType;
import androidx.media3.effect.AlphaScale;
import androidx.media3.effect.SpeedChangeEffect;
import androidx.media3.effect.TimestampAdjustment;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.analytics.PlayerId;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.DefaultAudioSink;
import androidx.media3.exoplayer.audio.ForwardingAudioSink;
import androidx.media3.exoplayer.audio.TrimmingAudioProcessor;
import androidx.media3.test.utils.TestSpeedProvider;
import androidx.media3.test.utils.robolectric.TestPlayerRunHelper;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.robolectric.shadows.ShadowLooper;

/** Unit tests for {@link CompositionPlayer}. */
@RunWith(AndroidJUnit4.class)
public class CompositionPlayerTest {
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

  @Test
  public void builder_buildCalledTwice_throws() {
    CompositionPlayer.Builder builder = new CompositionPlayer.Builder(getApplicationContext());

    CompositionPlayer player = builder.build();

    assertThrows(IllegalStateException.class, builder::build);

    player.release();
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
    CompositionPlayer player = createTestCompositionPlayer();
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
    CompositionPlayer player = createTestCompositionPlayer();

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
    CompositionPlayer player = createTestCompositionPlayerBuilder().setAudioSink(audioSink).build();
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
    CompositionPlayer player = createTestCompositionPlayer();

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

    player.release();
  }

  @Test
  public void prepare_withoutCompositionSet_throws() {
    CompositionPlayer player = createTestCompositionPlayer();

    assertThrows(NullPointerException.class, player::prepare);

    player.release();
  }

  @Test
  public void prepare_playbackStateIsBuffering() {
    CompositionPlayer player = createTestCompositionPlayer();
    player.setComposition(buildComposition());

    player.prepare();

    assertThat(player.getPlaybackState()).isEqualTo(STATE_BUFFERING);
    player.release();
  }

  @Test
  public void seekTo_playbackStateIsBuffering() throws Exception {
    CompositionPlayer player = createTestCompositionPlayer();
    player.setComposition(buildComposition());
    player.prepare();
    advance(player).untilState(STATE_READY);

    player.seekTo(100);

    assertThat(player.getPlaybackState()).isEqualTo(STATE_BUFFERING);
    player.release();
  }

  @Test
  public void stop_playbackStateIsIdle() throws Exception {
    CompositionPlayer player = createTestCompositionPlayer();
    player.setComposition(buildComposition());
    player.prepare();
    advance(player).untilState(STATE_READY);

    player.stop();

    assertThat(player.getPlaybackState()).isEqualTo(STATE_IDLE);
    player.release();
  }

  @Test
  public void setComposition_playbackStateIsUpdated() throws Exception {
    CompositionPlayer player = createTestCompositionPlayer();
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
    player.release();
  }

  @Test
  public void playWhenReady_calledBeforePrepare_startsPlayingAfterPrepareCalled() throws Exception {
    CompositionPlayer player = createTestCompositionPlayer();

    player.setPlayWhenReady(true);
    player.setComposition(buildComposition());
    player.prepare();

    TestPlayerRunHelper.runUntilPlaybackState(player, STATE_ENDED);
    player.release();
  }

  @Test
  public void playWhenReady_triggersPlayWhenReadyCallbackWithReason() throws Exception {
    CompositionPlayer player = createTestCompositionPlayer();
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
    CompositionPlayer player = createTestCompositionPlayer();

    assertThrows(
        UnsupportedOperationException.class,
        () -> player.setVideoTextureView(new TextureView(context)));

    player.release();
  }

  @Test
  public void setVideoSurface_withNonNullSurface_throws() {
    CompositionPlayer player = createTestCompositionPlayer();
    Surface surface = new Surface(new SurfaceTexture(/* texName= */ 0));

    assertThrows(UnsupportedOperationException.class, () -> player.setVideoSurface(surface));

    player.release();
    surface.release();
  }

  @Test
  public void clearVideoSurface_specifiedSurfaceNotPreviouslySet_throws() {
    CompositionPlayer player = createTestCompositionPlayer();

    assertThrows(
        IllegalArgumentException.class,
        () -> player.clearVideoSurface(new Surface(new SurfaceTexture(/* texName= */ 0))));

    player.release();
  }

  @Test
  public void getTotalBufferedDuration_playerStillIdle_returnsZero() {
    CompositionPlayer player = createTestCompositionPlayer();

    assertThat(player.getTotalBufferedDuration()).isEqualTo(0);

    player.release();
  }

  @Test
  public void getTotalBufferedDuration_setCompositionButNotPrepare_returnsZero() {
    CompositionPlayer player = createTestCompositionPlayer();

    player.setComposition(buildComposition());

    assertThat(player.getTotalBufferedDuration()).isEqualTo(0);

    player.release();
  }

  @Test
  public void getTotalBufferedDuration_playerReady_returnsNonZero() throws Exception {
    CompositionPlayer player = createTestCompositionPlayer();

    player.setComposition(buildComposition());
    player.prepare();
    TestPlayerRunHelper.runUntilPlaybackState(player, STATE_READY);

    assertThat(player.getTotalBufferedDuration()).isGreaterThan(0);

    player.release();
  }

  @Test
  public void getDuration_withoutComposition_returnsTimeUnset() {
    CompositionPlayer player = createTestCompositionPlayer();

    assertThat(player.getDuration()).isEqualTo(C.TIME_UNSET);

    player.release();
  }

  @Test
  public void getDuration_withComposition_returnsDuration() throws Exception {
    CompositionPlayer player = createTestCompositionPlayer();
    Composition composition = buildComposition();

    player.setComposition(composition);
    player.prepare();
    TestPlayerRunHelper.runUntilPlaybackState(player, STATE_READY);

    // Refer to the durations in buildComposition().
    assertThat(player.getDuration()).isEqualTo(1_348);

    player.release();
  }

  @Test
  public void getDuration_withClippedStart_returnsCorrectDuration() throws Exception {
    CompositionPlayer player = createTestCompositionPlayer();
    MediaItem mediaItem =
        new MediaItem.Builder()
            .setUri(ASSET_URI_PREFIX + FILE_AUDIO_RAW)
            .setClippingConfiguration(
                new MediaItem.ClippingConfiguration.Builder().setStartPositionUs(200_000).build())
            .build();
    EditedMediaItem editedMediaItem1 =
        new EditedMediaItem.Builder(mediaItem).setDurationUs(1_000_000L).build();
    EditedMediaItemSequence sequence = withAudioFrom(ImmutableList.of(editedMediaItem1));
    Composition composition = new Composition.Builder(sequence).build();

    player.setComposition(composition);
    player.prepare();
    TestPlayerRunHelper.runUntilPlaybackState(player, STATE_READY);

    assertThat(player.getDuration()).isEqualTo(800);

    player.release();
  }

  @Test
  public void getDuration_withClippedStartGreaterThanHalfDuration_returnsCorrectDuration()
      throws Exception {
    // This test covers cases where the clipped duration exceeds half the original duration.
    // It is needed to make sure no problems arise from clipping in this case. This would catch
    // removing ClippingConfiguration wrapping SilenceMediaSource, because the problem only occurs
    // when the clippedDuration exceeds half the original duration.
    CompositionPlayer player = createTestCompositionPlayer();
    MediaItem mediaItem =
        new MediaItem.Builder()
            .setUri(ASSET_URI_PREFIX + FILE_AUDIO_RAW)
            .setClippingConfiguration(
                new MediaItem.ClippingConfiguration.Builder().setStartPositionUs(600_000).build())
            .build();
    EditedMediaItem editedMediaItem1 =
        new EditedMediaItem.Builder(mediaItem).setDurationUs(1_000_000L).build();
    EditedMediaItemSequence sequence = withAudioFrom(ImmutableList.of(editedMediaItem1));
    Composition composition = new Composition.Builder(sequence).build();

    player.setComposition(composition);
    player.prepare();
    TestPlayerRunHelper.runUntilPlaybackState(player, STATE_READY);

    assertThat(player.getDuration()).isEqualTo(400);

    player.release();
  }

  @Test
  public void getDuration_withClippedEnd_returnsCorrectDuration() throws Exception {
    CompositionPlayer player = createTestCompositionPlayer();
    MediaItem mediaItem =
        new MediaItem.Builder()
            .setUri(ASSET_URI_PREFIX + FILE_AUDIO_RAW)
            .setClippingConfiguration(
                new MediaItem.ClippingConfiguration.Builder().setEndPositionUs(600_000).build())
            .build();
    EditedMediaItem editedMediaItem1 =
        new EditedMediaItem.Builder(mediaItem).setDurationUs(1_000_000L).build();
    EditedMediaItemSequence sequence = withAudioFrom(ImmutableList.of(editedMediaItem1));
    Composition composition = new Composition.Builder(sequence).build();

    player.setComposition(composition);
    player.prepare();
    TestPlayerRunHelper.runUntilPlaybackState(player, STATE_READY);

    assertThat(player.getDuration()).isEqualTo(600);

    player.release();
  }

  @Test
  public void getDuration_withClippedStartEnd_returnsCorrectDuration() throws Exception {
    CompositionPlayer player = createTestCompositionPlayer();
    MediaItem mediaItem =
        new MediaItem.Builder()
            .setUri(ASSET_URI_PREFIX + FILE_AUDIO_RAW)
            .setClippingConfiguration(
                new MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionUs(100_000)
                    .setEndPositionUs(550_000)
                    .build())
            .build();
    EditedMediaItem editedMediaItem1 =
        new EditedMediaItem.Builder(mediaItem).setDurationUs(1_000_000L).build();
    EditedMediaItemSequence sequence = withAudioFrom(ImmutableList.of(editedMediaItem1));
    Composition composition = new Composition.Builder(sequence).build();

    player.setComposition(composition);
    player.prepare();
    TestPlayerRunHelper.runUntilPlaybackState(player, STATE_READY);

    assertThat(player.getDuration()).isEqualTo(450);

    player.release();
  }

  @Test
  public void getDuration_withDurationAdjustingEffectsAndClippedStart_returnsCorrectDuration()
      throws Exception {
    CompositionPlayer player = createTestCompositionPlayer();
    ImmutableList<AudioProcessor> audioProcessors =
        ImmutableList.of(
            new SpeedChangingAudioProcessor(
                TestSpeedProvider.createWithStartTimes(new long[] {0L}, new float[] {2f})));
    MediaItem mediaItem =
        new MediaItem.Builder()
            .setUri(ASSET_URI_PREFIX + FILE_AUDIO_RAW)
            .setClippingConfiguration(
                new MediaItem.ClippingConfiguration.Builder().setStartPositionUs(200_000).build())
            .build();
    // Video must be removed because Composition presentation time assumes there is audio and video.
    EditedMediaItem editedMediaItem1 =
        new EditedMediaItem.Builder(mediaItem)
            .setRemoveVideo(true)
            .setDurationUs(1_000_000L)
            .setEffects(new Effects(audioProcessors, /* videoEffects= */ ImmutableList.of()))
            .build();
    EditedMediaItemSequence sequence = withAudioFrom(ImmutableList.of(editedMediaItem1));
    Composition composition = new Composition.Builder(sequence).build();

    player.setComposition(composition);
    player.prepare();
    TestPlayerRunHelper.runUntilPlaybackState(player, STATE_READY);

    assertThat(player.getDuration()).isEqualTo(400);

    player.release();
  }

  @Test
  public void getDuration_withDurationAdjustingEffectsAndClippedEnd_returnsCorrectDuration()
      throws Exception {
    CompositionPlayer player = createTestCompositionPlayer();
    ImmutableList<AudioProcessor> audioProcessors =
        ImmutableList.of(
            new SpeedChangingAudioProcessor(
                TestSpeedProvider.createWithStartTimes(new long[] {0L}, new float[] {2f})));
    MediaItem mediaItem =
        new MediaItem.Builder()
            .setUri(ASSET_URI_PREFIX + FILE_AUDIO_RAW)
            .setClippingConfiguration(
                new MediaItem.ClippingConfiguration.Builder().setEndPositionUs(600_000).build())
            .build();
    // Video must be removed because Composition presentation time assumes there is audio and video.
    EditedMediaItem editedMediaItem1 =
        new EditedMediaItem.Builder(mediaItem)
            .setRemoveVideo(true)
            .setDurationUs(1_000_000L)
            .setEffects(new Effects(audioProcessors, /* videoEffects= */ ImmutableList.of()))
            .build();
    EditedMediaItemSequence sequence = withAudioFrom(ImmutableList.of(editedMediaItem1));
    Composition composition = new Composition.Builder(sequence).build();

    player.setComposition(composition);
    player.prepare();
    TestPlayerRunHelper.runUntilPlaybackState(player, STATE_READY);

    assertThat(player.getDuration()).isEqualTo(300);

    player.release();
  }

  @Test
  public void getDuration_withDurationAdjustingEffectsAndClippedStartEnd_returnsCorrectDuration()
      throws Exception {
    CompositionPlayer player = createTestCompositionPlayer();
    ImmutableList<AudioProcessor> audioProcessors =
        ImmutableList.of(
            new SpeedChangingAudioProcessor(
                TestSpeedProvider.createWithStartTimes(new long[] {0L}, new float[] {0.5f})));
    MediaItem mediaItem =
        new MediaItem.Builder()
            .setUri(ASSET_URI_PREFIX + FILE_AUDIO_RAW)
            .setClippingConfiguration(
                new MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionUs(100_000)
                    .setEndPositionUs(550_000)
                    .build())
            .build();
    EditedMediaItem editedMediaItem1 =
        new EditedMediaItem.Builder(mediaItem)
            .setDurationUs(1_000_000L)
            .setEffects(new Effects(audioProcessors, /* videoEffects= */ ImmutableList.of()))
            .build();
    EditedMediaItemSequence sequence = withAudioFrom(ImmutableList.of(editedMediaItem1));
    Composition composition = new Composition.Builder(sequence).build();

    player.setComposition(composition);
    player.prepare();
    TestPlayerRunHelper.runUntilPlaybackState(player, STATE_READY);

    assertThat(player.getDuration()).isEqualTo(900);

    player.release();
  }

  @Test
  public void addListener_callsSupportedCallbacks() throws Exception {
    CompositionPlayer player = createTestCompositionPlayer();
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
    CompositionPlayer player = createTestCompositionPlayer();
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
    CompositionPlayer player = createTestCompositionPlayer();
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
    CompositionPlayer player =
        createTestCompositionPlayerBuilder().setAudioSink(mockAudioSink).build();
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

    player.release();
  }

  @Test
  public void playSequence_withRepeatModeOff_doesNotReportRepeatMediaItemTransition()
      throws Exception {
    CompositionPlayer player = createTestCompositionPlayer();
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
    CompositionPlayer player = createTestCompositionPlayer();
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
    CompositionPlayer player = createTestCompositionPlayer();
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
  public void playComposition_withRepeatModeAll_reportsRepeatReasonForMediaItemTransition()
      throws Exception {
    CompositionPlayer player = createTestCompositionPlayer();
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
    CompositionPlayer player = createTestCompositionPlayer();
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
    player.release();
  }

  @Test
  public void seekPastDuration_withClippedStart_ends() throws Exception {
    CompositionPlayer player = createTestCompositionPlayer();
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
    player.release();
  }

  @Test
  public void seekPastDuration_withClippedEnd_ends() throws Exception {
    CompositionPlayer player = createTestCompositionPlayer();
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
    player.release();
  }

  @Test
  public void isScrubbingModeEnabled_defaultsFalse() {
    CompositionPlayer player = createTestCompositionPlayer();

    assertThat(player.isScrubbingModeEnabled()).isFalse();
  }

  @Test
  public void setScrubbingModeEnabled_updatesIsScrubbingModeEnabled() {
    CompositionPlayer player = createTestCompositionPlayer();

    player.setScrubbingModeEnabled(true);

    assertThat(player.isScrubbingModeEnabled()).isTrue();
  }

  @Test
  public void prepare_withCustomLoadControl_preparesTheLoadControl() throws Exception {
    CustomLoadControl customLoadControl = new CustomLoadControl();
    CompositionPlayer.Builder playerBuilder = createTestCompositionPlayerBuilder();
    playerBuilder.setLoadControl(customLoadControl);
    CompositionPlayer player = playerBuilder.build();

    player.setComposition(buildComposition());
    player.prepare();
    TestPlayerRunHelper.runUntilPlaybackState(player, STATE_READY);

    assertThat(customLoadControl.prepared).isTrue();

    player.release();
  }

  @Test
  public void setComposition_withCompositionLevelSpeedChangingAudioProcessor_throws() {
    CompositionPlayer player = createTestCompositionPlayer();
    Effects effects =
        new Effects(
            ImmutableList.of(new SpeedChangingAudioProcessor(TEST_SPEED_PROVIDER)),
            ImmutableList.of());
    Composition composition = buildComposition().buildUpon().setEffects(effects).build();

    assertThrows(IllegalArgumentException.class, () -> player.setComposition(composition));
  }

  @Test
  public void setComposition_withCompositionLevelTimestampAdjustment_throws() {
    CompositionPlayer player = createTestCompositionPlayer();
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
  public void setComposition_withNonFirstSpeedChangingAudioProcessor_throws() {
    CompositionPlayer player = createTestCompositionPlayer();
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
    CompositionPlayer player = createTestCompositionPlayer();
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
    CompositionPlayer player = createTestCompositionPlayer();
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
  public void setSpeed_onSecondarySequence_outputsExpectedNumberOfSamples() throws Exception {
    AtomicInteger bytes = new AtomicInteger();
    AudioProcessor processor = createByteCountingAudioProcessor(bytes);
    CompositionPlayer player = createTestCompositionPlayer();
    EditedMediaItem item =
        new EditedMediaItem.Builder(MediaItem.fromUri(ASSET_URI_PREFIX + FILE_AUDIO_RAW))
            .setDurationUs(1_000_000L)
            .build();
    EditedMediaItem speedAdjustedItem =
        item.buildUpon()
            .setSpeed(TestSpeedProvider.createWithStartTimes(new long[] {0L}, new float[] {0.5f}))
            .build();
    EditedMediaItemSequence primarySequence = new EditedMediaItemSequence.Builder(item).build();
    EditedMediaItemSequence secondarySequence =
        new EditedMediaItemSequence.Builder(speedAdjustedItem).build();
    player.setComposition(
        new Composition.Builder(primarySequence, secondarySequence)
            .setEffects(new Effects(ImmutableList.of(processor), ImmutableList.of()))
            .build());
    player.prepare();

    play(player).untilState(STATE_ENDED);

    assertThat(bytes.get() / 2).isEqualTo(88200);
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

  private static final class CustomLoadControl extends DefaultLoadControl {
    public boolean prepared;

    @Override
    public void onPrepared(PlayerId playerId) {
      prepared = true;
      super.onPrepared(playerId);
    }
  }
}
