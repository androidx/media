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
 *
 */

package androidx.media3.transformer;

import static androidx.media3.transformer.AndroidTestUtil.BT601_MP4_ASSET_FRAME_COUNT;
import static androidx.media3.transformer.AndroidTestUtil.BT601_MP4_ASSET_URI_STRING;
import static androidx.media3.transformer.AndroidTestUtil.JPG_ASSET_URI_STRING;
import static androidx.media3.transformer.AndroidTestUtil.MP4_ASSET_FRAME_COUNT;
import static androidx.media3.transformer.AndroidTestUtil.MP4_ASSET_URI_STRING;
import static androidx.media3.transformer.AndroidTestUtil.PNG_ASSET_URI_STRING;
import static androidx.media3.transformer.AndroidTestUtil.getFormatForTestFile;
import static com.google.common.truth.Truth.assertThat;

import android.content.Context;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.Assertions;
import androidx.media3.common.util.Util;
import androidx.media3.effect.Presentation;
import androidx.test.core.app.ApplicationProvider;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import java.io.File;
import java.math.RoundingMode;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;

// TODO: b/343362776 - Add tests to assert enough silence is generated.
// TODO: b/346289922 - Consider checking frame counts with extractors.
// TODO: b/345483531 - Add support for asserting on duration for image only sequences.
// TODO: b/345483531 - Split single item export tests into a separate class.
// TODO: b/345483531 - Generate all permutations of all combinations of input files.

/** Parameterized end to end {@linkplain EditedMediaItemSequence sequence} export tests. */
@RunWith(Parameterized.class)
public class ParameterizedInputSequenceExportTest {
  private static final ImageItemConfig PNG_ITEM =
      new ImageItemConfig(PNG_ASSET_URI_STRING, /* frameCount= */ 34);
  private static final ImageItemConfig JPG_ITEM =
      new ImageItemConfig(JPG_ASSET_URI_STRING, /* frameCount= */ 41);
  private static final VideoItemConfig BT709_ITEM =
      new VideoItemConfig(MP4_ASSET_URI_STRING, MP4_ASSET_FRAME_COUNT);
  private static final VideoItemConfig BT601_ITEM =
      new VideoItemConfig(BT601_MP4_ASSET_URI_STRING, BT601_MP4_ASSET_FRAME_COUNT);

  @Parameters(name = "{0}")
  public static ImmutableList<SequenceConfig> params() {
    return ImmutableList.of(
        new SequenceConfig(PNG_ITEM),
        new SequenceConfig(PNG_ITEM, PNG_ITEM),
        new SequenceConfig(PNG_ITEM, JPG_ITEM),
        new SequenceConfig(PNG_ITEM, BT601_ITEM),
        new SequenceConfig(PNG_ITEM, BT709_ITEM),
        new SequenceConfig(JPG_ITEM),
        new SequenceConfig(JPG_ITEM, PNG_ITEM),
        new SequenceConfig(JPG_ITEM, JPG_ITEM),
        new SequenceConfig(JPG_ITEM, BT601_ITEM),
        new SequenceConfig(JPG_ITEM, BT709_ITEM),
        new SequenceConfig(BT601_ITEM),
        new SequenceConfig(BT601_ITEM, PNG_ITEM),
        new SequenceConfig(BT601_ITEM, JPG_ITEM),
        new SequenceConfig(BT601_ITEM, BT601_ITEM),
        new SequenceConfig(BT601_ITEM, BT709_ITEM),
        new SequenceConfig(BT709_ITEM),
        new SequenceConfig(BT709_ITEM, PNG_ITEM),
        new SequenceConfig(BT709_ITEM, JPG_ITEM),
        new SequenceConfig(BT709_ITEM, BT601_ITEM),
        new SequenceConfig(BT709_ITEM, BT709_ITEM),
        new SequenceConfig(
            BT709_ITEM, BT709_ITEM, PNG_ITEM, JPG_ITEM, BT709_ITEM, PNG_ITEM, BT709_ITEM),
        new SequenceConfig(
            PNG_ITEM, BT709_ITEM, BT709_ITEM, PNG_ITEM, PNG_ITEM, BT709_ITEM, PNG_ITEM),
        new SequenceConfig(
            PNG_ITEM, BT709_ITEM, BT601_ITEM, PNG_ITEM, PNG_ITEM, BT601_ITEM, PNG_ITEM),
        new SequenceConfig(
            PNG_ITEM, JPG_ITEM, BT709_ITEM, BT601_ITEM, BT709_ITEM, PNG_ITEM, BT601_ITEM),
        new SequenceConfig(
            BT601_ITEM, BT709_ITEM, PNG_ITEM, JPG_ITEM, BT709_ITEM, PNG_ITEM, BT601_ITEM));
  }

  @Rule public final TestName testName = new TestName();

  @Parameter public SequenceConfig sequence;

  @Test
  public void export_completesWithCorrectFrameCount() throws Exception {
    Context context = ApplicationProvider.getApplicationContext();
    String testId = testName.getMethodName();
    assumeSequenceFormatsSupported(context, testId, sequence);
    Transformer transformer =
        new Transformer.Builder(context)
            .setEncoderFactory(
                new DefaultEncoderFactory.Builder(context).setEnableFallback(false).build())
            .build();

    ExportTestResult result =
        new TransformerAndroidTestRunner.Builder(context, transformer)
            .build()
            .run(testId, sequence.buildComposition());

    assertThat(result.exportResult.videoFrameCount).isEqualTo(sequence.totalExpectedFrameCount);
    assertThat(new File(result.filePath).length()).isGreaterThan(0);
  }

  private static void assumeSequenceFormatsSupported(
      Context context, String testId, SequenceConfig sequence) throws Exception {
    Assertions.checkState(!sequence.itemConfigs.isEmpty());
    Format outputFormat = Assertions.checkNotNull(sequence.itemConfigs.get(0).outputFormat);
    for (ItemConfig item : sequence.itemConfigs) {
      AndroidTestUtil.assumeFormatsSupported(context, testId, item.format, outputFormat);
    }
  }

  /** Test parameters for an {@link EditedMediaItemSequence}. */
  private static final class SequenceConfig {
    public final int totalExpectedFrameCount;
    public final ImmutableList<ItemConfig> itemConfigs;

    public SequenceConfig(ItemConfig... itemConfigs) {
      this.itemConfigs = ImmutableList.copyOf(itemConfigs);
      int frameCountSum = 0;
      for (ItemConfig item : itemConfigs) {
        frameCountSum += item.frameCount;
      }
      this.totalExpectedFrameCount = frameCountSum;
    }

    /**
     * Builds a {@link Composition} from the sequence configuration.
     *
     * <p>{@link Presentation} of {@code width 480, height 360} is used to ensure software encoders
     * can encode.
     */
    public Composition buildComposition() {
      ImmutableList.Builder<EditedMediaItem> editedMediaItems = new ImmutableList.Builder<>();
      for (ItemConfig itemConfig : itemConfigs) {
        editedMediaItems.add(itemConfig.build());
      }

      return new Composition.Builder(new EditedMediaItemSequence(editedMediaItems.build()))
          .setEffects(
              new Effects(
                  /* audioProcessors= */ ImmutableList.of(),
                  ImmutableList.of(
                      Presentation.createForWidthAndHeight(
                          /* width= */ 480, /* height= */ 360, Presentation.LAYOUT_SCALE_TO_FIT))))
          .build();
    }

    @Override
    public String toString() {
      StringBuilder stringBuilder = new StringBuilder();
      stringBuilder.append("Seq{");
      for (ItemConfig itemConfig : itemConfigs) {
        stringBuilder.append(itemConfig).append(",");
      }
      stringBuilder.replace(stringBuilder.length() - 2, stringBuilder.length(), "}");
      return stringBuilder.toString();
    }
  }

  /** Test parameters for an {@link EditedMediaItem}. */
  private abstract static class ItemConfig {
    public final int frameCount;

    private final String uri;
    @Nullable private final Format format;
    @Nullable private final Format outputFormat;

    public ItemConfig(
        String uri, int frameCount, @Nullable Format format, @Nullable Format outputFormat) {
      this.uri = uri;
      this.frameCount = frameCount;
      this.format = format;
      this.outputFormat = outputFormat;
    }

    public final EditedMediaItem build() {
      EditedMediaItem.Builder builder = new EditedMediaItem.Builder(MediaItem.fromUri(uri));
      onBuild(builder);
      return builder.build();
    }

    /**
     * Called when an {@link EditedMediaItem} is being {@linkplain #build() built}.
     *
     * @param builder The {@link EditedMediaItem.Builder} to optionally modify before the item is
     *     built.
     */
    protected abstract void onBuild(EditedMediaItem.Builder builder);

    @Override
    public String toString() {
      return Iterables.getLast(Splitter.on("/").splitToList(uri));
    }
  }

  /** {@link ItemConfig} for an image {@link EditedMediaItem} with a duration of one second. */
  private static final class ImageItemConfig extends ItemConfig {

    // Image by default are encoded in H265 and BT709 SDR.
    private static final Format OUTPUT_FORMAT =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_H265)
            .setFrameRate(30.f)
            .setWidth(480)
            .setHeight(360)
            .setColorInfo(ColorInfo.SDR_BT709_LIMITED)
            .build();

    private final long durationUs;
    private final int frameRate;

    public ImageItemConfig(String uri, int frameCount) {
      this(uri, frameCount, C.MICROS_PER_SECOND);
    }

    public ImageItemConfig(String uri, int frameRate, long durationUs) {
      super(
          uri,
          /* frameCount= */ (int)
              Util.scaleLargeValue(
                  frameRate, durationUs, C.MICROS_PER_SECOND, RoundingMode.CEILING),
          /* format= */ null,
          OUTPUT_FORMAT);
      this.frameRate = frameRate;
      this.durationUs = durationUs;
    }

    @Override
    protected void onBuild(EditedMediaItem.Builder builder) {
      builder.setFrameRate(frameRate).setDurationUs(durationUs);
    }
  }

  /**
   * {@link ItemConfig} for a video {@link EditedMediaItem}.
   *
   * <p>Audio is removed and a {@link Presentation} of specified {@code height=360}.
   */
  private static final class VideoItemConfig extends ItemConfig {
    public VideoItemConfig(String uri, int frameCount) {
      super(uri, frameCount, getFormatForTestFile(uri), getFormatForTestFile(uri));
    }

    @Override
    protected void onBuild(EditedMediaItem.Builder builder) {
      builder
          .setEffects(
              new Effects(
                  /* audioProcessors= */ ImmutableList.of(),
                  ImmutableList.of(Presentation.createForHeight(360))))
          .setRemoveAudio(true);
    }
  }
}
