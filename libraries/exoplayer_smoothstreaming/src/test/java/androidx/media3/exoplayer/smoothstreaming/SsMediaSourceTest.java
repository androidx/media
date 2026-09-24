/*
 * Copyright 2023 The Android Open Source Project
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
package androidx.media3.exoplayer.smoothstreaming;

import static androidx.media3.common.util.Util.createHandlerForCurrentLooper;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.robolectric.Shadows.shadowOf;

import android.net.Uri;
import android.os.Looper;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.StreamKey;
import androidx.media3.common.Timeline;
import androidx.media3.datasource.ByteArrayDataSource;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.TransferListener;
import androidx.media3.exoplayer.analytics.PlayerId;
import androidx.media3.exoplayer.smoothstreaming.manifest.SsManifest;
import androidx.media3.exoplayer.smoothstreaming.manifest.SsManifestParser;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.MediaSourceEventListener;
import androidx.media3.exoplayer.trackselection.ExoTrackSelection;
import androidx.media3.exoplayer.upstream.BandwidthMeter;
import androidx.media3.exoplayer.upstream.CmcdConfiguration;
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy;
import androidx.media3.exoplayer.upstream.Loader;
import androidx.media3.exoplayer.upstream.LoaderErrorThrower;
import androidx.media3.exoplayer.upstream.ParsingLoadable;
import androidx.media3.extractor.text.SubtitleParser;
import androidx.media3.test.utils.FakeDataSource;
import androidx.media3.test.utils.TestUtil;
import androidx.media3.test.utils.robolectric.RobolectricUtil;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit test for {@link SsMediaSource}. */
@RunWith(AndroidJUnit4.class)
public class SsMediaSourceTest {

  private static final String SAMPLE_MANIFEST = "media/smooth-streaming/sample_ismc_1";

  @Test
  public void canUpdateMediaItem_withIrrelevantFieldsChanged_returnsTrue() {
    MediaItem initialMediaItem =
        new MediaItem.Builder()
            .setUri("http://test.test")
            .setStreamKeys(
                ImmutableList.of(new StreamKey(/* groupIndex= */ 1, /* streamIndex= */ 0)))
            .setDrmConfiguration(new MediaItem.DrmConfiguration.Builder(C.WIDEVINE_UUID).build())
            .build();
    MediaItem updatedMediaItem =
        TestUtil.buildFullyCustomizedMediaItem()
            .buildUpon()
            .setUri("http://test.test")
            .setStreamKeys(
                ImmutableList.of(new StreamKey(/* groupIndex= */ 1, /* streamIndex= */ 0)))
            .setDrmConfiguration(new MediaItem.DrmConfiguration.Builder(C.WIDEVINE_UUID).build())
            .build();
    MediaSource mediaSource =
        new SsMediaSource.Factory(() -> createSampleDataSource(SAMPLE_MANIFEST))
            .createMediaSource(initialMediaItem);

    boolean canUpdateMediaItem = mediaSource.canUpdateMediaItem(updatedMediaItem);

    assertThat(canUpdateMediaItem).isTrue();
  }

  @Test
  public void canUpdateMediaItem_withNullLocalConfiguration_returnsFalse() {
    MediaItem initialMediaItem = new MediaItem.Builder().setUri("http://test.test").build();
    MediaItem updatedMediaItem = new MediaItem.Builder().setMediaId("id").build();
    MediaSource mediaSource =
        new SsMediaSource.Factory(() -> createSampleDataSource(SAMPLE_MANIFEST))
            .createMediaSource(initialMediaItem);

    boolean canUpdateMediaItem = mediaSource.canUpdateMediaItem(updatedMediaItem);

    assertThat(canUpdateMediaItem).isFalse();
  }

  @Test
  public void canUpdateMediaItem_withChangedUri_returnsFalse() {
    MediaItem initialMediaItem = new MediaItem.Builder().setUri("http://test.test").build();
    MediaItem updatedMediaItem = new MediaItem.Builder().setUri("http://test2.test").build();
    MediaSource mediaSource =
        new SsMediaSource.Factory(() -> createSampleDataSource(SAMPLE_MANIFEST))
            .createMediaSource(initialMediaItem);

    boolean canUpdateMediaItem = mediaSource.canUpdateMediaItem(updatedMediaItem);

    assertThat(canUpdateMediaItem).isFalse();
  }

  @Test
  @SuppressWarnings("deprecation") // Testing deprecated method
  public void
      setExperimentalParseSubtitlesDuringExtraction_withNonDefaultChunkSourceFactory_setSucceeds() {
    SsMediaSource.Factory ssMediaSourceFactory =
        new SsMediaSource.Factory(
            /* chunkSourceFactory= */ this::createSampleSsChunkSource,
            /* manifestDataSourceFactory= */ () -> createSampleDataSource(SAMPLE_MANIFEST));
    ssMediaSourceFactory.experimentalParseSubtitlesDuringExtraction(false);
  }

  @Test
  @SuppressWarnings("deprecation") // Testing deprecated method
  public void
      setExperimentalParseSubtitlesDuringExtraction_withDefaultChunkSourceFactory_setSucceeds() {
    SsMediaSource.Factory ssMediaSourceFactory =
        new SsMediaSource.Factory(() -> createSampleDataSource(SAMPLE_MANIFEST));
    ssMediaSourceFactory.experimentalParseSubtitlesDuringExtraction(false);
    ssMediaSourceFactory.experimentalParseSubtitlesDuringExtraction(true);
  }

  @Test
  public void canUpdateMediaItem_withChangedStreamKeys_returnsFalse() {
    MediaItem initialMediaItem =
        new MediaItem.Builder()
            .setUri("http://test.test")
            .setStreamKeys(
                ImmutableList.of(new StreamKey(/* groupIndex= */ 1, /* streamIndex= */ 0)))
            .build();
    MediaItem updatedMediaItem =
        new MediaItem.Builder()
            .setUri("http://test.test")
            .setStreamKeys(
                ImmutableList.of(new StreamKey(/* groupIndex= */ 2, /* streamIndex= */ 2)))
            .build();
    MediaSource mediaSource =
        new SsMediaSource.Factory(() -> createSampleDataSource(SAMPLE_MANIFEST))
            .createMediaSource(initialMediaItem);

    boolean canUpdateMediaItem = mediaSource.canUpdateMediaItem(updatedMediaItem);

    assertThat(canUpdateMediaItem).isFalse();
  }

  @Test
  public void canUpdateMediaItem_withChangedDrmConfiguration_returnsFalse() {
    MediaItem initialMediaItem =
        new MediaItem.Builder()
            .setUri("http://test.test")
            .setDrmConfiguration(new MediaItem.DrmConfiguration.Builder(C.WIDEVINE_UUID).build())
            .build();
    MediaItem updatedMediaItem =
        new MediaItem.Builder()
            .setUri("http://test.test")
            .setDrmConfiguration(new MediaItem.DrmConfiguration.Builder(C.CLEARKEY_UUID).build())
            .build();
    MediaSource mediaSource =
        new SsMediaSource.Factory(() -> createSampleDataSource(SAMPLE_MANIFEST))
            .createMediaSource(initialMediaItem);

    boolean canUpdateMediaItem = mediaSource.canUpdateMediaItem(updatedMediaItem);

    assertThat(canUpdateMediaItem).isFalse();
  }

  @Test
  public void updateMediaItem_createsTimelineWithUpdatedItem() throws Exception {
    MediaItem initialMediaItem =
        new MediaItem.Builder().setUri("http://test.test").setTag("tag1").build();
    MediaItem updatedMediaItem =
        new MediaItem.Builder().setUri("http://test.test").setTag("tag2").build();
    MediaSource mediaSource =
        new SsMediaSource.Factory(() -> createSampleDataSource(SAMPLE_MANIFEST))
            .createMediaSource(initialMediaItem);

    mediaSource.updateMediaItem(updatedMediaItem);
    Timeline.Window window = prepareAndWaitForTimelineRefresh(mediaSource);

    assertThat(window.mediaItem).isEqualTo(updatedMediaItem);
  }

  @Test
  public void onLoadError_whenRetriesExhausted_returnsDontRetryFatalAndReportsCanceledTrue() {
    SsMediaSource mediaSource =
        new SsMediaSource.Factory(new DefaultHttpDataSource.Factory())
            .setLoadErrorHandlingPolicy(
                new DefaultLoadErrorHandlingPolicy(/* minimumLoadableRetryCount= */ 3))
            .createMediaSource(MediaItem.fromUri("http://example.com/manifest.ismc"));
    MediaSourceEventListener mockListener = mock(MediaSourceEventListener.class);
    mediaSource.addEventListener(createHandlerForCurrentLooper(), mockListener);
    ParsingLoadable<SsManifest> loadable =
        new ParsingLoadable.Builder<>(
                new DefaultHttpDataSource.Factory().createDataSource(),
                Uri.parse("http://example.com/manifest.ismc"),
                C.DATA_TYPE_MANIFEST,
                new SsManifestParser())
            .build();

    Loader.LoadErrorAction actionWithinLimit =
        mediaSource.onLoadError(
            loadable,
            /* elapsedRealtimeMs= */ 0,
            /* loadDurationMs= */ 0,
            new IOException("Transient error"),
            /* errorCount= */ 3);
    Loader.LoadErrorAction actionExhausted =
        mediaSource.onLoadError(
            loadable,
            /* elapsedRealtimeMs= */ 0,
            /* loadDurationMs= */ 0,
            new IOException("Transient error"),
            /* errorCount= */ 4);
    shadowOf(Looper.getMainLooper()).idle();

    assertThat(actionWithinLimit.isRetry()).isTrue();
    verify(mockListener)
        .onLoadError(anyInt(), any(), any(), any(), any(IOException.class), eq(false));
    assertThat(actionExhausted).isEqualTo(Loader.DONT_RETRY_FATAL);
    verify(mockListener)
        .onLoadError(anyInt(), any(), any(), any(), any(IOException.class), eq(true));
  }

  private static Timeline.Window prepareAndWaitForTimelineRefresh(MediaSource mediaSource)
      throws Exception {
    AtomicReference<Timeline.Window> windowReference = new AtomicReference<>();
    mediaSource.prepareSource(
        (source, timeline) ->
            windowReference.set(timeline.getWindow(/* windowIndex= */ 0, new Timeline.Window())),
        PlayerId.UNSET,
        BandwidthMeter.NO_OP);
    RobolectricUtil.runMainLooperUntil(() -> windowReference.get() != null);
    return windowReference.get();
  }

  private static DataSource createSampleDataSource(String fileName) {
    byte[] manifestData = new byte[0];
    try {
      manifestData = TestUtil.getByteArray(ApplicationProvider.getApplicationContext(), fileName);
    } catch (IOException e) {
      fail(e.getMessage());
    }
    return new ByteArrayDataSource(manifestData);
  }

  private SsChunkSource createSampleSsChunkSource(
      LoaderErrorThrower manifestLoaderErrorThrower,
      SsManifest manifest,
      int streamElementIndex,
      ExoTrackSelection trackSelection,
      @Nullable TransferListener transferListener,
      @Nullable CmcdConfiguration cmcdConfiguration) {
    return new DefaultSsChunkSource(
        manifestLoaderErrorThrower,
        manifest,
        streamElementIndex,
        trackSelection,
        new FakeDataSource(),
        cmcdConfiguration,
        SubtitleParser.Factory.UNSUPPORTED,
        /* parseSubtitlesDuringExtraction= */ false);
  }
}
