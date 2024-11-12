package androidx.media3.demo.main;

import android.os.Build;
import android.os.Bundle;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

public class MultiPlayerActivity extends AppCompatActivity {

  private Player topPlayer;
  private Player bottomPlayer;
  private PlayerView topPlayerView;
  private PlayerView bottomPlayerView;

  public MultiPlayerActivity() {
    super(R.layout.multi_player_activity);
  }

  @Override
  protected void onCreate(@Nullable Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    topPlayerView = findViewById(R.id.top_player);
    bottomPlayerView = findViewById(R.id.bottom_player);
  }

  @Override
  protected void onStart() {
    super.onStart();

    if (Build.VERSION.SDK_INT > Build.VERSION_CODES.M) {
      setupPlayers();
    }
  }

  @Override
  protected void onResume() {
    super.onResume();

    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.M) {
      setupPlayers();
    }
  }

  @Override
  protected void onPause() {
    super.onPause();

    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.M) {
      releasePlayers();
    }
  }

  @Override
  protected void onStop() {
    super.onStop();

    if (Build.VERSION.SDK_INT > Build.VERSION_CODES.M) {
      releasePlayers();
    }
  }

  private void setupPlayers() {
    // media.exolist.json > Widevine DASH (MP4, H264) > HD (cenc)
    topPlayer = createPlayer("https://storage.googleapis.com/wvmedia/cenc/h264/tears/tears.mpd");
    topPlayerView.setPlayer(topPlayer);
    topPlayerView.onResume();

    // media.exolist.json > Widevine DASH (MP4, H264) > UHD (cenc)
    bottomPlayer = createPlayer(
        "https://storage.googleapis.com/wvmedia/cenc/h264/tears/tears_uhd.mpd");
    bottomPlayerView.setPlayer(bottomPlayer);
    bottomPlayerView.onResume();
  }

  private Player createPlayer(String mediaUrl) {
    MediaItem mediaItem = new MediaItem.Builder()
        .setUri(mediaUrl)
        .setDrmConfiguration(
            new MediaItem.DrmConfiguration.Builder(C.WIDEVINE_UUID)
                .setLicenseUri(
                    "https://proxy.uat.widevine.com/proxy?video_id=2015_tears&provider=widevine_test")
                .setMultiSession(true)
                .build()
        )
        .build();

    Player player = new ExoPlayer.Builder(this).build();
    player.setMediaItem(mediaItem);
    player.prepare();
    player.play();

    return player;
  }

  private void releasePlayers() {
    topPlayerView.onPause();
    topPlayerView.setPlayer(null);
    topPlayer.release();

    bottomPlayerView.onPause();
    bottomPlayerView.setPlayer(null);
    bottomPlayer.release();
  }

}
