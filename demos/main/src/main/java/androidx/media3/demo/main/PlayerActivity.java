/*
 * Copyright (C) 2016 The Android Open Source Project
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
package androidx.media3.demo.main;

import android.content.ContentResolver;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.util.Pair;
import android.view.KeyEvent;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.ErrorMessageProvider;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.Tracks;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.datasource.DataSchemeDataSource;
import androidx.media3.datasource.DataSource;
import androidx.media3.exoplayer.CodecParameters;
import androidx.media3.exoplayer.CodecParametersChangeListener;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.RenderersFactory;
import androidx.media3.exoplayer.drm.DefaultDrmSessionManagerProvider;
import androidx.media3.exoplayer.drm.FrameworkMediaDrm;
import androidx.media3.exoplayer.ima.ImaAdsLoader;
import androidx.media3.exoplayer.ima.ImaServerSideAdInsertionMediaSource;
import androidx.media3.exoplayer.mediacodec.MediaCodecRenderer.DecoderInitializationException;
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil.DecoderQueryException;
import androidx.media3.exoplayer.offline.DownloadRequest;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.ads.AdsLoader;
import androidx.media3.exoplayer.util.DebugTextViewHelper;
import androidx.media3.exoplayer.util.EventLogger;
import androidx.media3.ui.PlayerView;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/** An activity that plays media using {@link ExoPlayer}. */
@UnstableApi
public class PlayerActivity extends AppCompatActivity
    implements OnClickListener, PlayerView.ControllerVisibilityListener {

  // Saved instance state keys.

  private static final String KEY_TRACK_SELECTION_PARAMETERS = "track_selection_parameters";
  private static final String KEY_SERVER_SIDE_ADS_LOADER_STATE = "server_side_ads_loader_state";
  private static final String KEY_ITEM_INDEX = "item_index";
  private static final String KEY_POSITION = "position";
  private static final String KEY_AUTO_PLAY = "auto_play";

  protected PlayerView playerView;
  protected LinearLayout debugRootView;
  protected TextView debugTextView;
  protected @Nullable ExoPlayer player;

  private boolean isShowingTrackSelectionDialog;
  private Button selectTracksButton;
  private DataSource.Factory dataSourceFactory;
  private List<MediaItem> mediaItems;
  private TrackSelectionParameters trackSelectionParameters;
  private DebugTextViewHelper debugViewHelper;
  private Tracks lastSeenTracks;
  private boolean startAutoPlay;
  private int startItemIndex;
  private long startPosition;

  private static final int MAX_PERSISTENCE_STORAGE = 33920;
  private @Nullable ByteBuffer persistence_buffer;
  private @Nullable String persistence_storage_path;

  // For ad playback only.

  @Nullable private AdsLoader clientSideAdsLoader;

  @Nullable private ImaServerSideAdInsertionMediaSource.AdsLoader serverSideAdsLoader;

  private ImaServerSideAdInsertionMediaSource.AdsLoader.@MonotonicNonNull State
      serverSideAdsLoaderState;

  // Activity lifecycle.

  @Override
  public void onCreate(@Nullable Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    dataSourceFactory = DemoUtil.getDataSourceFactory(/* context= */ this);

    setContentView();
    debugRootView = findViewById(R.id.controls_root);
    debugTextView = findViewById(R.id.debug_text_view);
    selectTracksButton = findViewById(R.id.select_tracks_button);
    selectTracksButton.setOnClickListener(this);

    playerView = findViewById(R.id.player_view);
    playerView.setControllerVisibilityListener(this);
    playerView.setErrorMessageProvider(new PlayerErrorMessageProvider());
    playerView.requestFocus();

    if (savedInstanceState != null) {
      trackSelectionParameters =
          TrackSelectionParameters.fromBundle(
              savedInstanceState.getBundle(KEY_TRACK_SELECTION_PARAMETERS));
      startAutoPlay = savedInstanceState.getBoolean(KEY_AUTO_PLAY);
      startItemIndex = savedInstanceState.getInt(KEY_ITEM_INDEX);
      startPosition = savedInstanceState.getLong(KEY_POSITION);
      restoreServerSideAdsLoaderState(savedInstanceState);
    } else {
      trackSelectionParameters = new TrackSelectionParameters.Builder().build();
      clearStartPosition();
    }

    persistence_storage_path = getFilesDir().getAbsolutePath() + "/persist.bin";
    persistence_buffer = ByteBuffer.allocateDirect(MAX_PERSISTENCE_STORAGE);
  }

  @Override
  public void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    releasePlayer();
    releaseClientSideAdsLoader();
    clearStartPosition();
    setIntent(intent);
  }

  @Override
  public void onStart() {
    super.onStart();
    if (Build.VERSION.SDK_INT > 23) {
      initializePlayer();
      if (playerView != null) {
        playerView.onResume();
      }
    }
  }

  @Override
  public void onResume() {
    super.onResume();
    if (Build.VERSION.SDK_INT == 23 || player == null) {
      initializePlayer();
      if (playerView != null) {
        playerView.onResume();
      }
    }
  }

  @Override
  public void onPause() {
    super.onPause();
    if (Build.VERSION.SDK_INT == 23) {
      if (playerView != null) {
        playerView.onPause();
      }
      releasePlayer();
    }
  }

  @Override
  public void onStop() {
    super.onStop();
    if (Build.VERSION.SDK_INT > 23) {
      if (playerView != null) {
        playerView.onPause();
      }
      releasePlayer();
    }
  }

  @Override
  public void onDestroy() {
    super.onDestroy();
    releaseClientSideAdsLoader();
  }

  @Override
  public void onRequestPermissionsResult(
      int requestCode, String[] permissions, int[] grantResults) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    if (grantResults.length == 0) {
      // Empty results are triggered if a permission is requested while another request was already
      // pending and can be safely ignored in this case.
      return;
    }
    if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
      initializePlayer();
    } else {
      showToast(R.string.storage_permission_denied);
      finish();
    }
  }

  @Override
  public void onSaveInstanceState(Bundle outState) {
    super.onSaveInstanceState(outState);
    updateTrackSelectorParameters();
    updateStartPosition();
    outState.putBundle(KEY_TRACK_SELECTION_PARAMETERS, trackSelectionParameters.toBundle());
    outState.putBoolean(KEY_AUTO_PLAY, startAutoPlay);
    outState.putInt(KEY_ITEM_INDEX, startItemIndex);
    outState.putLong(KEY_POSITION, startPosition);
    saveServerSideAdsLoaderState(outState);
  }

  // Activity input

  @Override
  public boolean dispatchKeyEvent(KeyEvent event) {
    // See whether the player view wants to handle media or DPAD keys events.
    return playerView.dispatchKeyEvent(event) || super.dispatchKeyEvent(event);
  }

  // OnClickListener methods

  @Override
  public void onClick(View view) {
    if (view == selectTracksButton
        && !isShowingTrackSelectionDialog
        && TrackSelectionDialog.willHaveContent(player)) {
      isShowingTrackSelectionDialog = true;
      TrackSelectionDialog trackSelectionDialog =
          TrackSelectionDialog.createForPlayer(
              player,
              /* onDismissListener= */ dismissedDialog -> isShowingTrackSelectionDialog = false);
      trackSelectionDialog.show(getSupportFragmentManager(), /* tag= */ null);
    }
  }

  // PlayerView.ControllerVisibilityListener implementation

  @Override
  public void onVisibilityChanged(int visibility) {
    debugRootView.setVisibility(visibility);
  }

  // Internal methods

  protected void setContentView() {
    setContentView(R.layout.player_activity);
  }

  /**
   * @return Whether initialization was successful.
   */
  @OptIn(markerClass = UnstableApi.class)
  protected boolean initializePlayer() {
    Intent intent = getIntent();
    if (player == null) {

      mediaItems = createMediaItems(intent);
      if (mediaItems.isEmpty()) {
        return false;
      }

      lastSeenTracks = Tracks.EMPTY;
      ExoPlayer.Builder playerBuilder =
          new ExoPlayer.Builder(/* context= */ this)
              .setMediaSourceFactory(createMediaSourceFactory());
      setRenderersFactory(
          playerBuilder, intent.getBooleanExtra(IntentUtil.PREFER_EXTENSION_DECODERS_EXTRA, false));
      player = playerBuilder.build();
      player.setTrackSelectionParameters(trackSelectionParameters);
      player.addListener(new PlayerEventListener());
      player.addAnalyticsListener(new EventLogger());
      player.setAudioAttributes(AudioAttributes.DEFAULT, /* handleAudioFocus= */ true);
      player.setPlayWhenReady(startAutoPlay);
      playerView.setPlayer(player);
      configurePlayerWithServerSideAdsLoader();
      debugViewHelper = new DebugTextViewHelper(player, debugTextView);
      debugViewHelper.start();

      ArrayList<String> filterKeys = new ArrayList<>();
      filterKeys.add("mpegh-ui-config");
      filterKeys.add("mpegh-ui-persistence-buffer");
      player.addAudioCodecParametersChangeListener(codecParameters -> {
        for (String s : codecParameters.keySet()) {
          Log.w("PlayerActivity", "key = " + s);
        }
        if (codecParameters.get("mpegh-ui-config") != null) {
          Log.e("PlayerActivity", "MPEG-H UI ASI = " + codecParameters.get("mpegh-ui-config"));
        }
        if (codecParameters.get("mpegh-ui-persistence-buffer") != null) {
          ByteBuffer tmp = (ByteBuffer)codecParameters.get("mpegh-ui-persistence-buffer");
          if (tmp != null && persistence_buffer != null && !tmp.equals(persistence_buffer)) {
            tmp.rewind();
            persistence_buffer.rewind();
            persistence_buffer.put(tmp);
          }
          if (persistence_buffer != null && persistence_storage_path != null) {
            try {
              RandomAccessFile persistence_dump = new RandomAccessFile(persistence_storage_path, "rw");
              persistence_buffer.rewind();
              FileChannel channel = persistence_dump.getChannel();
              int bytesWritten = channel.write(persistence_buffer);
              channel.close();
              persistence_dump.close();
            } catch (IOException e) {
              Log.w("PlayerActivity",
                  "unable to write persistence storage to " + persistence_storage_path);
              e.printStackTrace();
            }
          }
        }
      }, filterKeys);
    }
    boolean haveStartPosition = startItemIndex != C.INDEX_UNSET;
    if (haveStartPosition) {
      player.seekTo(startItemIndex, startPosition);
    }
    player.setMediaItems(mediaItems, /* resetPosition= */ !haveStartPosition);
    player.prepare();
    String repeatModeExtra = intent.getStringExtra(IntentUtil.REPEAT_MODE_EXTRA);
    if (repeatModeExtra != null) {
      player.setRepeatMode(IntentUtil.parseRepeatModeExtra(repeatModeExtra));
    }
    updateButtonVisibility();

    // --------------------------- TESTING CODE ONLY -- REMOVE AGAIN ----------------------------
    // The following MPEG-H UI ActionEvent commands are only useful for the media item
    // https://media.githubusercontent.com/media/Fraunhofer-IIS/mpegh-test-content/main/TRI_Fileset_17_514H_D1_D2_D3_O1_24bit1080p50.mp4
    if (persistence_buffer != null && persistence_storage_path != null ) {
      if (new File(persistence_storage_path).exists()) {
        try {
          RandomAccessFile persistence_dump = new RandomAccessFile(persistence_storage_path, "r");
          FileChannel channel = persistence_dump.getChannel();
          int bytesRead = channel.read(persistence_buffer);
          channel.close();
          persistence_dump.close();
          persistence_buffer.rewind();
        } catch (IOException e) {
          Log.w("PlayerActivity", "no persistence dump file available");
          e.printStackTrace();
        }
      }

      CodecParameters.Builder codecParametersBuilderPersistence = new CodecParameters.Builder();
      codecParametersBuilderPersistence.setByteBuffer("mpegh-ui-persistence-buffer", persistence_buffer);
      player.setAudioCodecParameters(codecParametersBuilderPersistence.build());
    }


    // Simulate sleep so the MPEG-D DRC can change during runtime
    Handler test = new Handler(Looper.getMainLooper());
    test.postDelayed(() -> {
      CodecParameters.Builder codecParametersBuilder = new CodecParameters.Builder();
      String command = "<ActionEvent uuid=\"7D130000-0000-0000-0000-0000DD78AA1B\" version=\"11.0\" actionType=\"30\" paramInt=\"10\" />";
      codecParametersBuilder.setString("mpegh-ui-command", command);
      if (player != null) {
        player.setAudioCodecParameters(codecParametersBuilder.build());
      }
    }, 5000);

    test.postDelayed(() -> {
      CodecParameters.Builder codecParametersBuilder = new CodecParameters.Builder();
      String command = "<ActionEvent uuid=\"7D130000-0000-0000-0000-0000DD78AA1B\" version=\"11.0\" actionType=\"30\" paramInt=\"20\" />";
      codecParametersBuilder.setString("mpegh-ui-command", command);
      if (player != null) {
        player.setAudioCodecParameters(codecParametersBuilder.build());
      }
    }, 10000);

    test.postDelayed(() -> {
      CodecParameters.Builder codecParametersBuilder = new CodecParameters.Builder();
      String command = "<ActionEvent uuid=\"7D130000-0000-0000-0000-0000DD78AA1B\" version=\"11.0\" actionType=\"30\" paramInt=\"0\" />";
      codecParametersBuilder.setString("mpegh-ui-command", command);
      if (player != null) {
        player.setAudioCodecParameters(codecParametersBuilder.build());
      }
    }, 15000);

    test.postDelayed(() -> {
      CodecParameters.Builder codecParametersBuilder = new CodecParameters.Builder();
      codecParametersBuilder.setInteger("mpegh-ui-force-update", 1);
      if (player != null) {
        player.setAudioCodecParameters(codecParametersBuilder.build());
      }
    }, 20000);
    // --------------------------- TESTING CODE ONLY -- REMOVE AGAIN ----------------------------
    return true;
  }

  @OptIn(markerClass = UnstableApi.class) // DRM configuration
  private MediaSource.Factory createMediaSourceFactory() {
    DefaultDrmSessionManagerProvider drmSessionManagerProvider =
        new DefaultDrmSessionManagerProvider();
    drmSessionManagerProvider.setDrmHttpDataSourceFactory(
        DemoUtil.getHttpDataSourceFactory(/* context= */ this));
    ImaServerSideAdInsertionMediaSource.AdsLoader.Builder serverSideAdLoaderBuilder =
        new ImaServerSideAdInsertionMediaSource.AdsLoader.Builder(/* context= */ this, playerView);
    if (serverSideAdsLoaderState != null) {
      serverSideAdLoaderBuilder.setAdsLoaderState(serverSideAdsLoaderState);
    }
    serverSideAdsLoader = serverSideAdLoaderBuilder.build();
    ImaServerSideAdInsertionMediaSource.Factory imaServerSideAdInsertionMediaSourceFactory =
        new ImaServerSideAdInsertionMediaSource.Factory(
            serverSideAdsLoader,
            new DefaultMediaSourceFactory(/* context= */ this)
                .setDataSourceFactory(dataSourceFactory));
    return new DefaultMediaSourceFactory(/* context= */ this)
        .setDataSourceFactory(dataSourceFactory)
        .setDrmSessionManagerProvider(drmSessionManagerProvider)
        .setLocalAdInsertionComponents(
            this::getClientSideAdsLoader, /* adViewProvider= */ playerView)
        .setServerSideAdInsertionMediaSourceFactory(imaServerSideAdInsertionMediaSourceFactory);
  }

  @OptIn(markerClass = UnstableApi.class)
  private void setRenderersFactory(
      ExoPlayer.Builder playerBuilder, boolean preferExtensionDecoders) {
    RenderersFactory renderersFactory =
        DemoUtil.buildRenderersFactory(/* context= */ this, preferExtensionDecoders);
    playerBuilder.setRenderersFactory(renderersFactory);
  }

  private void configurePlayerWithServerSideAdsLoader() {
    serverSideAdsLoader.setPlayer(player);
  }

  private List<MediaItem> createMediaItems(Intent intent) {
    String action = intent.getAction();
    boolean actionIsListView = IntentUtil.ACTION_VIEW_LIST.equals(action);
    if (!actionIsListView && !IntentUtil.ACTION_VIEW.equals(action)) {
      showToast(getString(R.string.unexpected_intent_action, action));
      finish();
      return Collections.emptyList();
    }

    List<MediaItem> mediaItems =
        createMediaItems(intent, DemoUtil.getDownloadTracker(/* context= */ this));
    for (int i = 0; i < mediaItems.size(); i++) {
      MediaItem mediaItem = mediaItems.get(i);

      if (!Util.checkCleartextTrafficPermitted(mediaItem)) {
        showToast(R.string.error_cleartext_not_permitted);
        finish();
        return Collections.emptyList();
      }
      if (Util.maybeRequestReadStoragePermission(/* activity= */ this, mediaItem)) {
        // The player will be reinitialized if the permission is granted.
        return Collections.emptyList();
      }

      MediaItem.DrmConfiguration drmConfiguration = mediaItem.localConfiguration.drmConfiguration;
      if (drmConfiguration != null) {
        if (!FrameworkMediaDrm.isCryptoSchemeSupported(drmConfiguration.scheme)) {
          showToast(R.string.error_drm_unsupported_scheme);
          finish();
          return Collections.emptyList();
        }
      }
    }
    return mediaItems;
  }

  private AdsLoader getClientSideAdsLoader(MediaItem.AdsConfiguration adsConfiguration) {
    // The ads loader is reused for multiple playbacks, so that ad playback can resume.
    if (clientSideAdsLoader == null) {
      clientSideAdsLoader = new ImaAdsLoader.Builder(/* context= */ this).build();
    }
    clientSideAdsLoader.setPlayer(player);
    return clientSideAdsLoader;
  }

  protected void releasePlayer() {
    if (player != null) {
      updateTrackSelectorParameters();
      updateStartPosition();
      releaseServerSideAdsLoader();
      debugViewHelper.stop();
      debugViewHelper = null;
      player.release();
      player = null;
      playerView.setPlayer(/* player= */ null);
      mediaItems = Collections.emptyList();
    }
    if (clientSideAdsLoader != null) {
      clientSideAdsLoader.setPlayer(null);
    } else {
      playerView.getAdViewGroup().removeAllViews();
    }
  }

  private void releaseServerSideAdsLoader() {
    serverSideAdsLoaderState = serverSideAdsLoader.release();
    serverSideAdsLoader = null;
  }

  private void releaseClientSideAdsLoader() {
    if (clientSideAdsLoader != null) {
      clientSideAdsLoader.release();
      clientSideAdsLoader = null;
      playerView.getAdViewGroup().removeAllViews();
    }
  }

  private void saveServerSideAdsLoaderState(Bundle outState) {
    if (serverSideAdsLoaderState != null) {
      outState.putBundle(KEY_SERVER_SIDE_ADS_LOADER_STATE, serverSideAdsLoaderState.toBundle());
    }
  }

  private void restoreServerSideAdsLoaderState(Bundle savedInstanceState) {
    Bundle adsLoaderStateBundle = savedInstanceState.getBundle(KEY_SERVER_SIDE_ADS_LOADER_STATE);
    if (adsLoaderStateBundle != null) {
      serverSideAdsLoaderState =
          ImaServerSideAdInsertionMediaSource.AdsLoader.State.fromBundle(adsLoaderStateBundle);
    }
  }

  private void updateTrackSelectorParameters() {
    if (player != null) {
      trackSelectionParameters = player.getTrackSelectionParameters();
    }
  }

  private void updateStartPosition() {
    if (player != null) {
      startAutoPlay = player.getPlayWhenReady();
      startItemIndex = player.getCurrentMediaItemIndex();
      startPosition = Math.max(0, player.getContentPosition());
    }
  }

  protected void clearStartPosition() {
    startAutoPlay = true;
    startItemIndex = C.INDEX_UNSET;
    startPosition = C.TIME_UNSET;
  }

  // User controls

  private void updateButtonVisibility() {
    selectTracksButton.setEnabled(player != null && TrackSelectionDialog.willHaveContent(player));
  }

  private void showControls() {
    debugRootView.setVisibility(View.VISIBLE);
  }

  private void showToast(int messageId) {
    showToast(getString(messageId));
  }

  private void showToast(String message) {
    Toast.makeText(getApplicationContext(), message, Toast.LENGTH_LONG).show();
  }

  private class PlayerEventListener implements Player.Listener {

    @Override
    public void onPlaybackStateChanged(@Player.State int playbackState) {
      if (playbackState == Player.STATE_ENDED) {
        showControls();
      }
      updateButtonVisibility();
    }

    @Override
    public void onPlayerError(PlaybackException error) {
      if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
        player.seekToDefaultPosition();
        player.prepare();
      } else {
        updateButtonVisibility();
        showControls();
      }
    }

    @Override
    @SuppressWarnings("ReferenceEquality")
    public void onTracksChanged(Tracks tracks) {
      updateButtonVisibility();
      if (tracks == lastSeenTracks) {
        return;
      }
      if (tracks.containsType(C.TRACK_TYPE_VIDEO)
          && !tracks.isTypeSupported(C.TRACK_TYPE_VIDEO, /* allowExceedsCapabilities= */ true)) {
        showToast(R.string.error_unsupported_video);
      }
      if (tracks.containsType(C.TRACK_TYPE_AUDIO)
          && !tracks.isTypeSupported(C.TRACK_TYPE_AUDIO, /* allowExceedsCapabilities= */ true)) {
        showToast(R.string.error_unsupported_audio);
      }
      lastSeenTracks = tracks;
    }

    @OptIn(markerClass = UnstableApi.class) // For PlayerView.setTimeBarScrubbingEnabled
    @Override
    public void onMediaItemTransition(@Nullable MediaItem mediaItem, int reason) {
      if (playerView == null) {
        return;
      }
      if (mediaItem == null) {
        playerView.setTimeBarScrubbingEnabled(false);
        return;
      }
      String uriScheme = mediaItem.localConfiguration.uri.getScheme();
      playerView.setTimeBarScrubbingEnabled(
          TextUtils.isEmpty(uriScheme)
              || uriScheme.equals(ContentResolver.SCHEME_FILE)
              || uriScheme.equals("asset")
              || uriScheme.equals(DataSchemeDataSource.SCHEME_DATA)
              || uriScheme.equals(ContentResolver.SCHEME_ANDROID_RESOURCE));
    }
  }

  private class PlayerErrorMessageProvider implements ErrorMessageProvider<PlaybackException> {

    @OptIn(markerClass = UnstableApi.class) // Using decoder exceptions
    @Override
    public Pair<Integer, String> getErrorMessage(PlaybackException e) {
      String errorString = getString(R.string.error_generic);
      Throwable cause = e.getCause();
      if (cause instanceof DecoderInitializationException) {
        // Special case for decoder initialization failures.
        DecoderInitializationException decoderInitializationException =
            (DecoderInitializationException) cause;
        if (decoderInitializationException.codecInfo == null) {
          if (decoderInitializationException.getCause() instanceof DecoderQueryException) {
            errorString = getString(R.string.error_querying_decoders);
          } else if (decoderInitializationException.secureDecoderRequired) {
            errorString =
                getString(
                    R.string.error_no_secure_decoder, decoderInitializationException.mimeType);
          } else {
            errorString =
                getString(R.string.error_no_decoder, decoderInitializationException.mimeType);
          }
        } else {
          errorString =
              getString(
                  R.string.error_instantiating_decoder,
                  decoderInitializationException.codecInfo.name);
        }
      }
      return Pair.create(0, errorString);
    }
  }

  private static List<MediaItem> createMediaItems(Intent intent, DownloadTracker downloadTracker) {
    List<MediaItem> mediaItems = new ArrayList<>();
    for (MediaItem item : IntentUtil.createMediaItemsFromIntent(intent)) {
      mediaItems.add(
          maybeSetDownloadProperties(
              item, downloadTracker.getDownloadRequest(item.localConfiguration.uri)));
    }
    return mediaItems;
  }

  @OptIn(markerClass = UnstableApi.class) // Using Download API
  private static MediaItem maybeSetDownloadProperties(
      MediaItem item, @Nullable DownloadRequest downloadRequest) {
    if (downloadRequest == null) {
      return item;
    }
    MediaItem.Builder builder = item.buildUpon();
    builder
        .setMediaId(downloadRequest.id)
        .setUri(downloadRequest.uri)
        .setCustomCacheKey(downloadRequest.customCacheKey)
        .setMimeType(downloadRequest.mimeType)
        .setStreamKeys(downloadRequest.streamKeys);
    @Nullable
    MediaItem.DrmConfiguration drmConfiguration = item.localConfiguration.drmConfiguration;
    if (drmConfiguration != null) {
      builder.setDrmConfiguration(
          drmConfiguration.buildUpon().setKeySetId(downloadRequest.keySetId).build());
    }
    return builder.build();
  }
}
