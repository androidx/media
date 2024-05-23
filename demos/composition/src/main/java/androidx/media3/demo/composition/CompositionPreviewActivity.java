/*
 * Copyright 2024 The Android Open Source Project
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
package androidx.media3.demo.composition;

import static androidx.media3.common.util.Assertions.checkNotNull;
import static androidx.media3.common.util.Assertions.checkState;
import static androidx.media3.common.util.Assertions.checkStateNotNull;

import android.app.Activity;
import android.content.DialogInterface;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.AppCompatButton;
import androidx.appcompat.widget.AppCompatTextView;
import androidx.media3.common.Effect;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.audio.SonicAudioProcessor;
import androidx.media3.common.util.Clock;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.Util;
import androidx.media3.effect.RgbFilter;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.CompositionPlayer;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.EditedMediaItemSequence;
import androidx.media3.transformer.Effects;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.JsonUtil;
import androidx.media3.transformer.Transformer;
import androidx.media3.ui.PlayerView;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.common.base.Stopwatch;
import com.google.common.base.Ticker;
import com.google.common.collect.ImmutableList;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * An {@link Activity} that previews compositions, using {@link
 * androidx.media3.transformer.CompositionPlayer}.
 */
public final class CompositionPreviewActivity extends AppCompatActivity {
  private static final String TAG = "CompPreviewActivity";

  private final ArrayList<String> sequenceAssetTitles = new ArrayList<>();

  @Nullable private boolean[] selectedMediaItems = null;
  private String[] presetFileDescriptions = new String[0];
  @Nullable private AssetItemAdapter assetItemAdapter;
  @Nullable private CompositionPlayer compositionPlayer;
  @Nullable private Transformer transformer;
  @Nullable private File outputFile;
  private @MonotonicNonNull PlayerView playerView;
  private @MonotonicNonNull AppCompatButton exportButton;
  private @MonotonicNonNull AppCompatTextView exportInformationTextView;
  private @MonotonicNonNull Stopwatch exportStopwatch;

  @Override
  protected void onCreate(@Nullable Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.composition_preview_activity);
    playerView = findViewById(R.id.composition_player_view);

    findViewById(R.id.preview_button).setOnClickListener(this::previewComposition);
    findViewById(R.id.edit_sequence_button).setOnClickListener(this::selectPresetFile);
    RecyclerView presetList = findViewById(R.id.composition_preset_list);
    presetList.addItemDecoration(new DividerItemDecoration(this, DividerItemDecoration.VERTICAL));
    LinearLayoutManager layoutManager =
        new LinearLayoutManager(this, LinearLayoutManager.VERTICAL, /* reverseLayout= */ false);
    presetList.setLayoutManager(layoutManager);

    exportInformationTextView = findViewById(R.id.export_information_text);
    exportButton = findViewById(R.id.composition_export_button);
    exportButton.setOnClickListener(this::exportComposition);

    presetFileDescriptions = getResources().getStringArray(R.array.preset_descriptions);
    // Select two media items by default.
    selectedMediaItems = new boolean[presetFileDescriptions.length];
    selectedMediaItems[0] = true;
    selectedMediaItems[2] = true;
    for (int i = 0; i < checkNotNull(selectedMediaItems).length; i++) {
      if (checkNotNull(selectedMediaItems)[i]) {
        sequenceAssetTitles.add(presetFileDescriptions[i]);
      }
    }
    assetItemAdapter = new AssetItemAdapter(sequenceAssetTitles);
    presetList.setAdapter(assetItemAdapter);

    exportStopwatch =
        Stopwatch.createUnstarted(
            new Ticker() {
              @Override
              public long read() {
                return android.os.SystemClock.elapsedRealtimeNanos();
              }
            });
  }

  @Override
  protected void onStart() {
    super.onStart();
    checkStateNotNull(playerView).onResume();
  }

  @Override
  protected void onStop() {
    super.onStop();
    checkStateNotNull(playerView).onPause();
    releasePlayer();
    cancelExport();
    checkStateNotNull(exportStopwatch).reset();
  }

  private Composition prepareComposition() {
    // Reading from resources here does not create a performance bottleneck, this
    // method is called as part of more expensive operations.
    String[] presetFileUris = getResources().getStringArray(/* id= */ R.array.preset_uris);
    checkState(
        /* expression= */ checkStateNotNull(presetFileUris).length == presetFileDescriptions.length,
        /* errorMessage= */ "Unexpected array length "
            + getResources().getResourceName(R.array.preset_uris));
    int[] presetDurationsUs = getResources().getIntArray(/* id= */ R.array.preset_durations);
    checkState(
        /* expression= */ checkStateNotNull(presetDurationsUs).length
            == presetFileDescriptions.length,
        /* errorMessage= */ "Unexpected array length "
            + getResources().getResourceName(R.array.preset_durations));

    List<EditedMediaItem> mediaItems = new ArrayList<>();
    ImmutableList<Effect> effects =
        ImmutableList.of(
            MatrixTransformationFactory.createDizzyCropEffect(), RgbFilter.createGrayscaleFilter());
    for (int i = 0; i < checkNotNull(selectedMediaItems).length; i++) {
      if (checkNotNull(selectedMediaItems)[i]) {
        SonicAudioProcessor pitchChanger = new SonicAudioProcessor();
        pitchChanger.setPitch(mediaItems.size() % 2 == 0 ? 2f : 0.2f);
        MediaItem mediaItem =
            new MediaItem.Builder()
                .setUri(presetFileUris[i])
                .setImageDurationMs(Util.usToMs(presetDurationsUs[i])) // Ignored for audio/video
                .build();
        EditedMediaItem.Builder itemBuilder =
            new EditedMediaItem.Builder(mediaItem)
                .setEffects(
                    new Effects(
                        /* audioProcessors= */ ImmutableList.of(pitchChanger),
                        /* videoEffects= */ effects))
                .setDurationUs(presetDurationsUs[i]);
        mediaItems.add(itemBuilder.build());
      }
    }
    EditedMediaItemSequence videoSequence = new EditedMediaItemSequence(mediaItems);
    SonicAudioProcessor sampleRateChanger = new SonicAudioProcessor();
    sampleRateChanger.setOutputSampleRateHz(8_000);
    return new Composition.Builder(/* sequences= */ ImmutableList.of(videoSequence))
        .setEffects(
            new Effects(
                /* audioProcessors= */ ImmutableList.of(sampleRateChanger),
                /* videoEffects= */ ImmutableList.of()))
        .build();
  }

  private void previewComposition(View view) {
    releasePlayer();
    Composition composition = prepareComposition();
    checkStateNotNull(playerView).setPlayer(null);

    CompositionPlayer player = new CompositionPlayer.Builder(getApplicationContext()).build();
    this.compositionPlayer = player;
    checkStateNotNull(playerView).setPlayer(compositionPlayer);
    checkStateNotNull(playerView).setControllerAutoShow(false);
    player.addListener(
        new Player.Listener() {
          @Override
          public void onPlayerError(PlaybackException error) {
            Toast.makeText(getApplicationContext(), "Preview error: " + error, Toast.LENGTH_LONG)
                .show();
            Log.e(TAG, "Preview error", error);
          }
        });
    player.setComposition(composition);
    player.prepare();
    player.play();
  }

  private void selectPresetFile(View view) {
    new AlertDialog.Builder(/* context= */ this)
        .setTitle(R.string.select_preset_file_title)
        .setMultiChoiceItems(
            presetFileDescriptions,
            checkNotNull(selectedMediaItems),
            this::selectPresetFileInDialog)
        .setPositiveButton(android.R.string.ok, /* listener= */ null)
        .setCancelable(false)
        .create()
        .show();
  }

  private void selectPresetFileInDialog(DialogInterface dialog, int which, boolean isChecked) {
    if (selectedMediaItems == null) {
      return;
    }
    selectedMediaItems[which] = isChecked;
    // The items will be added to a the sequence in the order they were selected.
    if (isChecked) {
      sequenceAssetTitles.add(presetFileDescriptions[which]);
      checkNotNull(assetItemAdapter).notifyItemInserted(sequenceAssetTitles.size() - 1);
    } else {
      int index = sequenceAssetTitles.indexOf(presetFileDescriptions[which]);
      sequenceAssetTitles.remove(presetFileDescriptions[which]);
      checkNotNull(assetItemAdapter).notifyItemRemoved(index);
    }
  }

  private void exportComposition(View view) {
    // Cancel and clean up files from any ongoing export.
    cancelExport();

    Composition composition = prepareComposition();

    try {
      outputFile =
          createExternalCacheFile(
              "composition-preview-" + Clock.DEFAULT.elapsedRealtime() + ".mp4");
    } catch (IOException e) {
      Toast.makeText(
              getApplicationContext(),
              "Aborting export! Unable to create output file: " + e,
              Toast.LENGTH_LONG)
          .show();
      Log.e(TAG, "Aborting export! Unable to create output file: " + e);
      return;
    }
    String filePath = outputFile.getAbsolutePath();

    transformer =
        new Transformer.Builder(this)
            .addListener(
                new Transformer.Listener() {
                  @Override
                  public void onCompleted(Composition composition, ExportResult exportResult) {
                    checkStateNotNull(exportStopwatch).stop();
                    long elapsedTimeMs = exportStopwatch.elapsed(TimeUnit.MILLISECONDS);
                    String details =
                        getString(R.string.export_completed, elapsedTimeMs / 1000.f, filePath);
                    Log.i(TAG, details);
                    checkStateNotNull(exportInformationTextView).setText(details);

                    try {
                      JSONObject resultJson =
                          JsonUtil.exportResultAsJsonObject(exportResult)
                              .put("elapsedTimeMs", elapsedTimeMs)
                              .put("device", JsonUtil.getDeviceDetailsAsJsonObject());
                      for (String line : Util.split(resultJson.toString(2), "\n")) {
                        Log.i(TAG, line);
                      }
                    } catch (JSONException e) {
                      Log.w(TAG, "Unable to convert exportResult to JSON", e);
                    }
                  }

                  @Override
                  public void onError(
                      Composition composition,
                      ExportResult exportResult,
                      ExportException exportException) {
                    checkStateNotNull(exportStopwatch).stop();
                    Toast.makeText(
                            getApplicationContext(),
                            "Export error: " + exportException,
                            Toast.LENGTH_LONG)
                        .show();
                    Log.e(TAG, "Export error", exportException);
                    checkStateNotNull(exportInformationTextView).setText(R.string.export_error);
                  }
                })
            .build();

    checkStateNotNull(exportInformationTextView).setText(R.string.export_started);
    checkStateNotNull(exportStopwatch).reset();
    exportStopwatch.start();
    checkStateNotNull(transformer).start(composition, filePath);
    Log.i(TAG, "Export started");
  }

  private void releasePlayer() {
    if (compositionPlayer != null) {
      compositionPlayer.release();
      compositionPlayer = null;
    }
  }

  /** Cancels any ongoing export operation, and deletes output file contents. */
  private void cancelExport() {
    if (transformer != null) {
      transformer.cancel();
      transformer = null;
    }
    if (outputFile != null) {
      outputFile.delete();
      outputFile = null;
    }
    checkStateNotNull(exportInformationTextView).setText("");
  }

  /**
   * Creates a {@link File} of the {@code fileName} in the application cache directory.
   *
   * <p>If a file of that name already exists, it is overwritten.
   */
  // TODO: b/320636291 - Refactor duplicate createExternalCacheFile functions.
  private File createExternalCacheFile(String fileName) throws IOException {
    File file = new File(getExternalCacheDir(), fileName);
    if (file.exists() && !file.delete()) {
      throw new IOException("Could not delete file: " + file.getAbsolutePath());
    }
    if (!file.createNewFile()) {
      throw new IOException("Could not create file: " + file.getAbsolutePath());
    }
    return file;
  }
}
