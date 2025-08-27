/*
 * Copyright 2025 The Android Open Source Project
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
package androidx.media3.cast;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.openMocks;

import androidx.media3.common.C;
import androidx.media3.common.DeviceInfo;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.Player;
import androidx.media3.test.utils.TestSimpleBasePlayer;
import androidx.test.core.app.ApplicationProvider;
import com.google.android.gms.cast.MediaInfo;
import com.google.android.gms.cast.MediaLoadRequestData;
import com.google.android.gms.cast.MediaQueueData;
import com.google.android.gms.cast.MediaQueueItem;
import com.google.android.gms.cast.MediaStatus;
import com.google.android.gms.cast.framework.CastContext;
import com.google.android.gms.cast.framework.CastSession;
import com.google.android.gms.cast.framework.SessionManager;
import com.google.android.gms.cast.framework.SessionManagerListener;
import com.google.android.gms.cast.framework.media.MediaQueue;
import com.google.android.gms.cast.framework.media.RemoteMediaClient;
import com.google.android.gms.common.api.PendingResult;
import com.google.common.collect.ImmutableList;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public final class CastPlayerTest {

  private static final DeviceInfo DEVICE_INFO_LOCAL =
      new DeviceInfo.Builder(DeviceInfo.PLAYBACK_TYPE_LOCAL).build();

  private AutoCloseable mock;
  private CastPlayer.Builder castPlayerBuilder;
  private CastPlayer castPlayer;
  private RemoteCastPlayer remoteCastPlayer;
  private SessionManagerListener<CastSession> castSessionListener;
  private TestSimpleBasePlayer localPlayer;
  @Mock private RemoteMediaClient mockRemoteMediaClient;
  @Mock private PendingResult<RemoteMediaClient.MediaChannelResult> mockPendingResult;
  @Mock private MediaStatus mockMediaStatus;
  @Mock private MediaQueue mockMediaQueue;
  @Mock private CastContext mockCastContext;
  @Mock private SessionManager mockSessionManager;
  @Mock private CastSession mockCastSession;
  @Mock private Player.Listener mockListener;
  @Captor private ArgumentCaptor<SessionManagerListener<CastSession>> sessionManagerListenerCaptor;

  @Before
  public void setUp() {
    mock = openMocks(this);
    localPlayer = new TestSimpleBasePlayer();
    when(mockCastContext.getSessionManager()).thenReturn(mockSessionManager);
    when(mockCastSession.getRemoteMediaClient()).thenReturn(mockRemoteMediaClient);
    when(mockRemoteMediaClient.getMediaStatus()).thenReturn(mockMediaStatus);
    when(mockRemoteMediaClient.getMediaQueue()).thenReturn(mockMediaQueue);
    when(mockRemoteMediaClient.play()).thenReturn(mockPendingResult);
    when(mockRemoteMediaClient.pause()).thenReturn(mockPendingResult);
    when(mockRemoteMediaClient.queueSetRepeatMode(anyInt(), any())).thenReturn(mockPendingResult);
    when(mockRemoteMediaClient.load((MediaLoadRequestData) any())).thenReturn(mockPendingResult);
    when(mockRemoteMediaClient.setPlaybackRate(anyDouble(), any())).thenReturn(mockPendingResult);
    when(mockMediaStatus.getMediaInfo()).thenReturn(new MediaInfo.Builder("contentId").build());
    when(mockMediaQueue.getItemIds()).thenReturn(new int[0]);
    // Make the remote media client present the same default values as ExoPlayer:
    when(mockRemoteMediaClient.isPaused()).thenReturn(true);
    when(mockMediaStatus.getQueueRepeatMode()).thenReturn(MediaStatus.REPEAT_MODE_REPEAT_OFF);
    when(mockMediaStatus.getStreamVolume()).thenReturn(1.0);
    when(mockMediaStatus.getPlaybackRate()).thenReturn(1.0d);
    // We cannot pass a non-null context here because it's used to create a MediaRouter2, which is
    // not supported in Robolectric tests. See b/372731599.
    remoteCastPlayer =
        new RemoteCastPlayer(
            /* context= */ null,
            mockCastContext,
            new DefaultMediaItemConverter(),
            C.DEFAULT_SEEK_BACK_INCREMENT_MS,
            C.DEFAULT_SEEK_FORWARD_INCREMENT_MS,
            C.DEFAULT_MAX_SEEK_TO_PREVIOUS_POSITION_MS);
    when(mockSessionManager.getCurrentCastSession()).thenReturn(mockCastSession);
    verify(mockSessionManager)
        .addSessionManagerListener(sessionManagerListenerCaptor.capture(), eq(CastSession.class));
    castSessionListener = sessionManagerListenerCaptor.getValue();
    // We defer the player creation to tests so we can control whether a cast session exists at the
    // time of creating the CastPlayer.
    castPlayerBuilder =
        new CastPlayer.Builder(ApplicationProvider.getApplicationContext())
            .setLocalPlayer(localPlayer)
            .setRemotePlayer(remoteCastPlayer);
  }

  @After
  public void tearDown() throws Exception {
    castPlayer.release();
    mock.close();
  }

  @Test
  public void getDeviceInfo_whenCastSessionBecomesUnavailable_goesFromRemoteToLocal() {
    castSessionListener.onSessionStarted(mockCastSession, /* sessionId= */ "ignored");
    castPlayer = castPlayerBuilder.build();
    castPlayer.addListener(mockListener);
    int oldPlaybackType = castPlayer.getDeviceInfo().playbackType;

    castSessionListener.onSessionEnded(mockCastSession, /* error= */ 0);

    verify(mockListener).onDeviceInfoChanged(DEVICE_INFO_LOCAL);
    assertThat(oldPlaybackType).isEqualTo(DeviceInfo.PLAYBACK_TYPE_REMOTE);
    int newPlaybackType = castPlayer.getDeviceInfo().playbackType;
    assertThat(newPlaybackType).isEqualTo(DeviceInfo.PLAYBACK_TYPE_LOCAL);
  }

  @Test
  public void getDeviceInfo_whenCastSessionBecomesAvailable_goesFromLocalToRemote() {
    // CastPlayer starts in local playback.
    castPlayer = castPlayerBuilder.build();
    castPlayer.addListener(mockListener);
    int oldPlaybackType = castPlayer.getDeviceInfo().playbackType;

    castSessionListener.onSessionStarted(mockCastSession, /* sessionId= */ "ignored");

    verify(mockListener).onDeviceInfoChanged(RemoteCastPlayer.DEVICE_INFO_REMOTE_EMPTY);
    assertThat(oldPlaybackType).isEqualTo(DeviceInfo.PLAYBACK_TYPE_LOCAL);
    int newPlaybackType = castPlayer.getDeviceInfo().playbackType;
    assertThat(newPlaybackType).isEqualTo(DeviceInfo.PLAYBACK_TYPE_REMOTE);
  }

  @Test
  public void defaultTransferCallback_transfersStateWhenCastSessionBecomesAvailable() {
    String sampleUrl = "http://uri";
    // CastPlayer starts in local playback.
    castPlayer = castPlayerBuilder.build();
    castPlayer.setPlayWhenReady(true);
    castPlayer.setRepeatMode(Player.REPEAT_MODE_ONE);
    MediaItem mediaItem =
        new MediaItem.Builder().setUri(sampleUrl).setMimeType(MimeTypes.VIDEO_MP4).build();
    castPlayer.setMediaItems(
        ImmutableList.of(mediaItem), /* startIndex= */ 0, /* startPositionMs= */ 1234);
    ArgumentCaptor<MediaLoadRequestData> loadArgumentCaptor =
        ArgumentCaptor.forClass(MediaLoadRequestData.class);

    castSessionListener.onSessionStarted(mockCastSession, /* sessionId= */ "ignored");

    verify(mockRemoteMediaClient)
        .queueSetRepeatMode(MediaStatus.REPEAT_MODE_REPEAT_SINGLE, /* customData= */ null);
    verify(mockRemoteMediaClient).play();
    verify(mockRemoteMediaClient).load(loadArgumentCaptor.capture());
    MediaLoadRequestData mediaLoadRequestData = loadArgumentCaptor.getValue();
    MediaQueueData queueData = mediaLoadRequestData.getQueueData();
    assertThat(mediaLoadRequestData.getCurrentTime()).isEqualTo(1234L);
    assertThat(queueData.getStartIndex()).isEqualTo(0);
    assertThat(queueData.getStartTime()).isEqualTo(1234L);
    List<MediaQueueItem> mediaQueueItems = queueData.getItems();
    assertThat(mediaQueueItems.get(0).getMedia().getContentId()).isEqualTo(sampleUrl);
    assertThat(mediaQueueItems).hasSize(1);
  }

  @Test
  public void transferCallback_whenCastSessionBecomesAvailable_isCalledWithCorrectPlayers() {
    // CastPlayer starts in local playback.
    CastPlayer.TransferCallback mockTransferCallback = mock(CastPlayer.TransferCallback.class);
    castPlayer = castPlayerBuilder.setTransferCallback(mockTransferCallback).build();

    castSessionListener.onSessionStarted(mockCastSession, /* sessionId= */ "ignored");

    verify(mockTransferCallback).transferState(localPlayer, remoteCastPlayer);
  }

  @Test
  public void transferCallback_whenCastSessionBecomesUnavailable_isCalledWithCorrectPlayers() {
    castSessionListener.onSessionStarted(mockCastSession, /* sessionId= */ "ignored");
    CastPlayer.TransferCallback mockTransferCallback = mock(CastPlayer.TransferCallback.class);
    castPlayer = castPlayerBuilder.setTransferCallback(mockTransferCallback).build();

    castSessionListener.onSessionEnded(mockCastSession, /* error= */ 0);

    verify(mockTransferCallback).transferState(remoteCastPlayer, localPlayer);
  }

  @Test
  public void setRepeatMode_dependingOnSessionAvailability_isSentToCorrectPlayer() {
    // CastPlayer starts in local playback.
    castPlayer = castPlayerBuilder.build();

    castPlayer.setRepeatMode(Player.REPEAT_MODE_ONE);
    castSessionListener.onSessionStarted(mockCastSession, /* sessionId= */ "ignored");
    castPlayer.setRepeatMode(Player.REPEAT_MODE_ALL);

    assertThat(localPlayer.getRepeatMode()).isEqualTo(Player.REPEAT_MODE_ONE);
    assertThat(remoteCastPlayer.getRepeatMode()).isEqualTo(Player.REPEAT_MODE_ALL);
  }

  @Test
  public void release_whenCastSessionIsUnavailable_releasesBothPlayers() {
    // CastPlayer starts in local playback.
    castPlayer = castPlayerBuilder.build();

    castPlayer.release();

    assertThat(castPlayer.getDeviceInfo().playbackType).isEqualTo(DeviceInfo.PLAYBACK_TYPE_LOCAL);
    // removeSessionManagerListener indicates RemoteCastPlayer has been released.
    verify(mockSessionManager).removeSessionManagerListener(castSessionListener, CastSession.class);
    assertThat(localPlayer.released).isTrue();
  }

  @Test
  public void release_whenCastSessionIsAvailable_releasesBothPlayers() {
    castSessionListener.onSessionStarted(mockCastSession, /* sessionId= */ "ignored");
    castPlayer = castPlayerBuilder.build();

    castPlayer.release();

    assertThat(castPlayer.getDeviceInfo().playbackType).isEqualTo(DeviceInfo.PLAYBACK_TYPE_REMOTE);
    // removeSessionManagerListener indicates RemoteCastPlayer has been released.
    verify(mockSessionManager).removeSessionManagerListener(castSessionListener, CastSession.class);
    assertThat(localPlayer.released).isTrue();
  }

  @Test
  public void playerTransfer_whenSourcePlayerIsNonIdle_callsPrepare() {
    // We need a non empty timeline to be in a non-idle state, and check that the target player is
    // prepared as a result.
    when(mockMediaQueue.getItemIds()).thenReturn(new int[] {1});
    when(mockRemoteMediaClient.getPlayerState()).thenReturn(MediaStatus.PLAYER_STATE_PLAYING);
    castSessionListener.onSessionStarted(mockCastSession, /* sessionId= */ "ignored");
    castPlayer = castPlayerBuilder.build();

    castSessionListener.onSessionEnded(mockCastSession, /* error= */ 0);

    assertThat(localPlayer.getPlaybackState()).isEqualTo(Player.STATE_BUFFERING);
  }

  @Test
  public void playerTransfer_whenSourcePlayerIsIdle_doesNotCallPrepare() {
    // CastPlayer starts in local playback.
    castPlayer = castPlayerBuilder.build();

    castSessionListener.onSessionEnded(mockCastSession, /* error= */ 0);

    assertThat(localPlayer.getPlaybackState()).isEqualTo(Player.STATE_IDLE);
  }
}
