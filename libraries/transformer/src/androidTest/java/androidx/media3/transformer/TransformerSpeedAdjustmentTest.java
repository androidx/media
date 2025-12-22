/*
 * Copyright 2025 The Android Open Source Project
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

import static androidx.media3.test.utils.AssetInfo.MP4_ASSET;
import static androidx.media3.test.utils.AssetInfo.MP4_ASSET_WITH_INCREASING_TIMESTAMPS_320W_240H_5S;
import static androidx.media3.transformer.AndroidTestUtil.getVideoSampleTimesUs;
import static com.google.common.truth.Truth.assertThat;

import android.content.Context;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaItem.ClippingConfiguration;
import androidx.media3.common.audio.SpeedProvider;
import androidx.media3.effect.GlEffect;
import androidx.media3.effect.GlShaderProgram;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import com.google.common.truth.Correspondence;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
import org.junit.runner.RunWith;

/**
 * Instrumentation tests for {@linkplain EditedMediaItem.Builder#setSpeed speed adjustment} in
 * export.
 */
@RunWith(AndroidJUnit4.class)
public class TransformerSpeedAdjustmentTest {

  private static final SpeedProvider SPEED_PROVIDER_CONSTANT =
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
          if (timeUs >= 500_000) {
            return C.TIME_UNSET;
          }
          return 500_000;
        }
      };

  private static final SpeedProvider SPEED_PROVIDER_MULTIPLE_SPEEDS_WITH_SHORT_REGION =
      new SpeedProvider() {
        @Override
        public float getSpeed(long timeUs) {
          if (timeUs >= 100_000) {
            return 0.5f;
          }
          return 2f;
        }

        @Override
        public long getNextSpeedChangeTimeUs(long timeUs) {
          if (timeUs >= 100_000) {
            return C.TIME_UNSET;
          }
          return 100_000;
        }
      };
  private final Context context = ApplicationProvider.getApplicationContext();
  @Rule public final TestName testName = new TestName();
  private String testId;

  @Before
  public void setUp() {
    testId = testName.getMethodName();
  }

  @Test
  public void setSpeed_singleAssetWith1xSpeedProvider_doesNotModifyTimestamps() throws Exception {
    Transformer transformer = new Transformer.Builder(context).build();
    EditedMediaItem item =
        new EditedMediaItem.Builder(MediaItem.fromUri(MP4_ASSET.uri))
            .setSpeed(SPEED_PROVIDER_CONSTANT)
            .build();

    ExportTestResult result =
        new TransformerAndroidTestRunner.Builder(context, transformer).build().run(testId, item);

    ImmutableList<Long> timestamps = getTimestampsFromFile(result.filePath, context, testId);
    assertThat(timestamps).containsExactlyElementsIn(MP4_ASSET.videoTimestampsUs).inOrder();
  }

  @Test
  public void setSpeed_singleAssetAtDoubleSpeed_halvesAllTimestamps() throws Exception {
    Transformer transformer = new Transformer.Builder(context).build();
    EditedMediaItem item =
        new EditedMediaItem.Builder(MediaItem.fromUri(MP4_ASSET.uri))
            .setSpeed(SPEED_PROVIDER_2X)
            .build();

    ExportTestResult result =
        new TransformerAndroidTestRunner.Builder(context, transformer).build().run(testId, item);

    ImmutableList<Long> timestamps = getTimestampsFromFile(result.filePath, context, testId);

    // Allow a tolerance of half a tick: 90000 (tbn) / 1000000 = ~11.1us.
    assertThat(timestamps)
        .comparingElementsUsing(Correspondence.tolerance(6))
        .containsExactly(
            0L, 16_683L, 33_366L, 50_050L, 66_733L, 83_416L, 100_100L, 116_783L, 133_466L, 150_150L,
            166_833L, 183_516L, 200_200L, 216_883L, 233_566L, 250_250L, 266_933L, 283_616L,
            300_300L, 316_983L, 333_666L, 350_350L, 367_033L, 383_716L, 400_400L, 417_083L,
            433_766L, 450_450L, 467_133L, 483_816L)
        .inOrder();
  }

  @Test
  public void setSpeed_twoItemsWithConstantSpeed_doesNotModifyTimestamps() throws Exception {
    Transformer transformer = new Transformer.Builder(context).build();
    EditedMediaItem item =
        new EditedMediaItem.Builder(MediaItem.fromUri(MP4_ASSET.uri))
            .setSpeed(SPEED_PROVIDER_CONSTANT)
            .build();
    Composition composition =
        new Composition.Builder(
                EditedMediaItemSequence.withAudioAndVideoFrom(ImmutableList.of(item, item)))
            .build();

    ExportTestResult result =
        new TransformerAndroidTestRunner.Builder(context, transformer)
            .build()
            .run(testId, composition);

    ImmutableList<Long> timestamps = getTimestampsFromFile(result.filePath, context, testId);
    assertThat(timestamps)
        .containsExactly(
            0L, 33_366L, 66_733L, 100_100L, 133_466L, 166_833L, 200_200L, 233_566L, 266_933L,
            300_300L, 333_666L, 367_033L, 400_400L, 433_766L, 467_133L, 500_500L, 533_866L,
            567_233L, 600_600L, 633_966L, 667_333L, 700_700L, 734_066L, 767_433L, 800_800L,
            834_166L, 867_533L, 900_900L, 934_266L, 967_633L, 1024000L, 1057366L, 1090733L,
            1124100L, 1157466L, 1190833L, 1224200L, 1257566L, 1290933L, 1324300L, 1357666L,
            1391033L, 1424400L, 1457766L, 1491133L, 1524500L, 1557866L, 1591233L, 1624600L,
            1657966L, 1691333L, 1724700L, 1758066L, 1791433L, 1824800L, 1858166L, 1891533L,
            1924900L, 1958266L, 1991633L)
        .inOrder();
  }

  @Test
  public void setSpeed_twoItemsWith2xSpeed_halvesTimestamps() throws Exception {
    Transformer transformer = new Transformer.Builder(context).build();
    EditedMediaItem item =
        new EditedMediaItem.Builder(MediaItem.fromUri(MP4_ASSET.uri))
            .setSpeed(SPEED_PROVIDER_2X)
            .build();
    Composition composition =
        new Composition.Builder(
                EditedMediaItemSequence.withAudioAndVideoFrom(ImmutableList.of(item, item)))
            .build();

    ExportTestResult result =
        new TransformerAndroidTestRunner.Builder(context, transformer)
            .build()
            .run(testId, composition);

    ImmutableList<Long> timestamps = getTimestampsFromFile(result.filePath, context, testId);
    // Allow a tolerance of half a tick: 90000 (tbn) / 1000000 = ~11.1us.
    assertThat(timestamps)
        .comparingElementsUsing(Correspondence.tolerance(6))
        .containsExactly(
            0L, 16_683L, 33_366L, 50_050L, 66_733L, 83_416L, 100_100L, 116_783L, 133_466L, 150_150L,
            166_833L, 183_516L, 200_200L, 216_883L, 233_566L, 250_250L, 266_933L, 283_616L,
            300_300L, 316_983L, 333_666L, 350_350L, 367_033L, 383_716L, 400_400L, 417_083L,
            433_766L, 450_450L, 467_133L, 483_816L, 512000L, 528683L, 545366L, 562050L, 578733L,
            595416L, 612100L, 628783L, 645466L, 662150L, 678833L, 695516L, 712200L, 728883L,
            745566L, 762250L, 778933L, 795616L, 812300L, 828983L, 845666L, 862350L, 879033L,
            895716L, 912400L, 929083L, 945766L, 962450L, 979133L, 995816L)
        .inOrder();
  }

  @Test
  public void setSpeed_twoVideoOnlyItemsWith2xSpeed_halvesTimestamps() throws Exception {
    Transformer transformer = new Transformer.Builder(context).build();
    EditedMediaItem item =
        new EditedMediaItem.Builder(MediaItem.fromUri(MP4_ASSET.uri))
            .setSpeed(SPEED_PROVIDER_2X)
            .build();
    Composition composition =
        new Composition.Builder(EditedMediaItemSequence.withVideoFrom(ImmutableList.of(item, item)))
            .build();

    ExportTestResult result =
        new TransformerAndroidTestRunner.Builder(context, transformer)
            .build()
            .run(testId, composition);

    ImmutableList<Long> timestamps = getTimestampsFromFile(result.filePath, context, testId);
    // Allow a tolerance of half a tick: 90000 (tbn) / 1000000 = ~11.1us.
    assertThat(timestamps)
        .comparingElementsUsing(Correspondence.tolerance(6))
        .containsExactly(
            0L, 16_683L, 33_366L, 50_050L, 66_733L, 83_416L, 100_100L, 116_783L, 133_466L, 150_150L,
            166_833L, 183_516L, 200_200L, 216_883L, 233_566L, 250_250L, 266_933L, 283_616L,
            300_300L, 316_983L, 333_666L, 350_350L, 367_033L, 383_716L, 400_400L, 417_083L,
            433_766L, 450_450L, 467_133L, 483_816L, 512000L, 528683L, 545366L, 562050L, 578733L,
            595416L, 612100L, 628783L, 645466L, 662150L, 678833L, 695516L, 712200L, 728883L,
            745566L, 762250L, 778933L, 795616L, 812300L, 828983L, 845666L, 862350L, 879033L,
            895716L, 912400L, 929083L, 945766L, 962450L, 979133L, 995816L)
        .inOrder();
  }

  @Test
  public void setSpeed_twoClippedItemsWith2xSpeed_halvesTimestamps() throws Exception {
    ClippingConfiguration configuration =
        new ClippingConfiguration.Builder().setStartPositionMs(250).setEndPositionMs(750).build();
    Transformer transformer = new Transformer.Builder(context).build();
    EditedMediaItem item =
        new EditedMediaItem.Builder(
                MediaItem.fromUri(MP4_ASSET.uri)
                    .buildUpon()
                    .setClippingConfiguration(configuration)
                    .build())
            .setSpeed(SPEED_PROVIDER_2X)
            .build();
    Composition composition =
        new Composition.Builder(
                EditedMediaItemSequence.withAudioAndVideoFrom(ImmutableList.of(item, item)))
            .build();

    ExportTestResult result =
        new TransformerAndroidTestRunner.Builder(context, transformer)
            .build()
            .run(testId, composition);

    ImmutableList<Long> timestamps = getTimestampsFromFile(result.filePath, context, testId);
    // Allow a tolerance of half a tick: 90000 (tbn) / 1000000 = ~11.1us.
    // First frame after 250ms is at 266933us, but ExoPlayer moves it to position 0us.
    assertThat(timestamps)
        .comparingElementsUsing(Correspondence.tolerance(6))
        .containsExactly(
            0, 25150, 41833, 58516, 75200, 91883, 108566, 125250, 141933, 158616, 175300, 191983,
            208666, 225350, 258466, 275150, 291833, 308516, 325200, 341883, 358566, 375250, 391933,
            408616, 425300, 441983, 458666, 475350)
        .inOrder();
  }

  @Test
  public void setSpeed_twoItemsWithVariableSpeed_modifiesTimestampsAsExpected() throws Exception {
    Transformer transformer = new Transformer.Builder(context).build();
    EditedMediaItem item =
        new EditedMediaItem.Builder(MediaItem.fromUri(MP4_ASSET.uri))
            .setSpeed(SPEED_PROVIDER_MULTIPLE_SPEEDS)
            .build();
    Composition composition =
        new Composition.Builder(
                EditedMediaItemSequence.withAudioAndVideoFrom(ImmutableList.of(item, item)))
            .build();

    ExportTestResult result =
        new TransformerAndroidTestRunner.Builder(context, transformer)
            .build()
            .run(testId, composition);

    ImmutableList<Long> timestamps = getTimestampsFromFile(result.filePath, context, testId);
    // Allow a tolerance of half a tick: 90000 (tbn) / 1000000 = ~11.1us.
    assertThat(timestamps)
        .comparingElementsUsing(Correspondence.tolerance(6))
        .containsExactly(
            0L, 16_683L, 33_366L, 50_050L, 66_733L, 83_416L, 100_100L, 116_783L, 133_466L, 150_150L,
            166_833L, 183_516L, 200_200L, 216_883L, 233_566L, 251000, 317732, 384466, 451200,
            517932, 584666, 651400, 718132, 784866, 851600, 918332, 985066, 1051800, 1118532,
            1185266, 1298000, 1314683, 1331366, 1348050, 1364733, 1381416, 1398100, 1414783,
            1431466, 1448150, 1464833, 1481516, 1498200, 1514883, 1531566, 1549000, 1615732,
            1682466, 1749200, 1815932, 1882666, 1949400, 2016132, 2082866, 2149600, 2216332,
            2283066, 2349800, 2416532, 2483266)
        .inOrder();
  }

  @Test
  public void setSpeed_twoItemsWithVariableSpeedAndClipping_modifiesTimestampsAsExpected()
      throws Exception {
    Transformer transformer = new Transformer.Builder(context).build();
    EditedMediaItem item =
        new EditedMediaItem.Builder(
                MediaItem.fromUri(MP4_ASSET.uri)
                    .buildUpon()
                    .setClippingConfiguration(
                        new ClippingConfiguration.Builder().setStartPositionMs(750).build())
                    .build())
            .setSpeed(SPEED_PROVIDER_MULTIPLE_SPEEDS_WITH_SHORT_REGION)
            .build();
    Composition composition =
        new Composition.Builder(
                EditedMediaItemSequence.withAudioAndVideoFrom(ImmutableList.of(item, item)))
            .build();

    ExportTestResult result =
        new TransformerAndroidTestRunner.Builder(context, transformer)
            .build()
            .run(testId, composition);

    ImmutableList<Long> timestamps = getTimestampsFromFile(result.filePath, context, testId);
    // Allow a tolerance of half a tick: 90000 (tbn) / 1000000 = ~11.1us.
    assertThat(timestamps)
        .comparingElementsUsing(Correspondence.tolerance(6))
        .containsExactly(
            0L, 25_400L, 42_083L, 85_066L, 151_800L, 218_532L, 285_266L, 406_716L, 423_400L,
            440_083L, 483_066L, 549_800L, 616_532L, 683_266L)
        .inOrder();
  }

  @Test
  public void setSpeed_withTargetFrameRate_outputFrameCountIsCorrect() throws Exception {
    Transformer transformer = new Transformer.Builder(context).build();
    EditedMediaItem item =
        new EditedMediaItem.Builder(
                MediaItem.fromUri(MP4_ASSET_WITH_INCREASING_TIMESTAMPS_320W_240H_5S.uri))
            .setSpeed(SPEED_PROVIDER_2X)
            .setFrameRate(30)
            .build();

    ExportTestResult result =
        new TransformerAndroidTestRunner.Builder(context, transformer).build().run(testId, item);

    ImmutableList<Long> videoSampleTimesUs = getVideoSampleTimesUs(result.filePath);
    // Input: 5 sec video at 60fps with 2X speed; Output: 2.5 sec at 30fps = ~75 frames
    assertThat(videoSampleTimesUs)
        .containsExactly(
            0L,
            33_333L,
            66_666L,
            100_000L,
            133_333L,
            166_666L,
            200_000L,
            233_333L,
            266_666L,
            300_000L,
            333_333L,
            366_666L,
            400_000L,
            433_333L,
            466_666L,
            500_000L,
            533_333L,
            566_666L,
            600_000L,
            633_333L,
            666_666L,
            700_000L,
            733_333L,
            766_666L,
            800_000L,
            833_333L,
            866_666L,
            900_000L,
            933_333L,
            966_666L,
            1_000_000L,
            1_033_333L,
            1_066_666L,
            1_100_000L,
            1_133_333L,
            1_166_666L,
            1_200_000L,
            1_233_333L,
            1_266_666L,
            1_300_000L,
            1_333_333L,
            1_366_666L,
            1_400_000L,
            1_433_333L,
            1_466_666L,
            1_500_000L,
            1_533_333L,
            1_566_666L,
            1_600_000L,
            1_633_333L,
            1_666_666L,
            1_700_000L,
            1_733_333L,
            1_766_666L,
            1_800_000L,
            1_833_333L,
            1_866_666L,
            1_900_000L,
            1_933_333L,
            1_966_666L,
            2_000_000L,
            2_033_333L,
            2_066_666L,
            2_100_000L,
            2_133_333L,
            2_166_666L,
            2_200_000L,
            2_233_333L,
            2_266_666L,
            2_300_000L,
            2_333_333L,
            2_366_666L,
            2_400_000L,
            2_433_333L,
            2_466_666L)
        .inOrder();
  }

  @Test
  public void setSpeed_withHighSpeedAndTargetFrameRate_outputFrameCountIsCorrect()
      throws Exception {
    Transformer transformer = new Transformer.Builder(context).build();
    EditedMediaItem item =
        new EditedMediaItem.Builder(
                MediaItem.fromUri(MP4_ASSET_WITH_INCREASING_TIMESTAMPS_320W_240H_5S.uri))
            .setSpeed(
                new SpeedProvider() {
                  @Override
                  public float getSpeed(long timeUs) {
                    return 20;
                  }

                  @Override
                  public long getNextSpeedChangeTimeUs(long timeUs) {
                    return C.TIME_UNSET;
                  }
                })
            .setFrameRate(30)
            .build();

    ExportTestResult result =
        new TransformerAndroidTestRunner.Builder(context, transformer).build().run(testId, item);

    ImmutableList<Long> videoSampleTimesUs = getVideoSampleTimesUs(result.filePath);
    // Input: 5 sec video at 60fps with 20X speed; Output: 0.25 sec at 30fps = ~8 frames
    assertThat(videoSampleTimesUs)
        .containsExactly(0L, 33_333L, 66_666L, 100_000L, 133_333L, 166_666L, 200_000L, 233_333L)
        .inOrder();
  }

  @Test
  public void setSpeed_withVariableSpeedAndTargetFrameRate_outputFrameCountIsCorrect()
      throws Exception {
    Transformer transformer = new Transformer.Builder(context).build();
    EditedMediaItem item =
        new EditedMediaItem.Builder(
                MediaItem.fromUri(MP4_ASSET_WITH_INCREASING_TIMESTAMPS_320W_240H_5S.uri))
            .setSpeed(
                new SpeedProvider() {
                  @Override
                  public float getSpeed(long timeUs) {
                    if (timeUs >= 4_500_000L) {
                      return 0.5f;
                    } else if (timeUs >= 2_500_000L) {
                      return 1f;
                    }
                    return 5f;
                  }

                  @Override
                  public long getNextSpeedChangeTimeUs(long timeUs) {
                    if (timeUs >= 4_500_000L) {
                      return C.TIME_UNSET;
                    } else if (timeUs >= 2_500_000L) {
                      return 4_500_000L;
                    }
                    return 2_500_000L;
                  }
                })
            .setFrameRate(30)
            .build();

    ExportTestResult result =
        new TransformerAndroidTestRunner.Builder(context, transformer).build().run(testId, item);

    ImmutableList<Long> videoSampleTimesUs = getVideoSampleTimesUs(result.filePath);
    // (2.5 sec at 5X = 0.5 sec) + (2 sec at 1X = 2 sec) + (0.5 sec at 0.5X = 1 sec) = 3.5 sec =
    // ~105 frames
    assertThat(videoSampleTimesUs)
        .containsExactly(
            0L,
            33_333L,
            66_666L,
            100_000L,
            133_333L,
            166_666L,
            200_000L,
            233_333L,
            266_666L,
            300_000L,
            333_333L,
            366_666L,
            400_000L,
            433_333L,
            466_666L,
            500_000L,
            533_333L,
            566_666L,
            600_000L,
            633_333L,
            666_666L,
            700_000L,
            733_333L,
            766_666L,
            800_000L,
            833_333L,
            866_666L,
            900_000L,
            933_333L,
            966_666L,
            1_000_000L,
            1_033_333L,
            1_066_666L,
            1_100_000L,
            1_133_333L,
            1_166_666L,
            1_200_000L,
            1_233_333L,
            1_266_666L,
            1_300_000L,
            1_333_333L,
            1_366_666L,
            1_400_000L,
            1_433_333L,
            1_466_666L,
            1_500_000L,
            1_533_333L,
            1_566_666L,
            1_600_000L,
            1_633_333L,
            1_666_666L,
            1_700_000L,
            1_733_333L,
            1_766_666L,
            1_800_000L,
            1_833_333L,
            1_866_666L,
            1_900_000L,
            1_933_333L,
            1_966_666L,
            2_000_000L,
            2_033_333L,
            2_066_666L,
            2_100_000L,
            2_133_333L,
            2_166_666L,
            2_200_000L,
            2_233_333L,
            2_266_666L,
            2_300_000L,
            2_333_333L,
            2_366_666L,
            2_400_000L,
            2_433_333L,
            2_466_666L,
            2_500_000L,
            2_533_333L,
            2_566_666L,
            2_600_000L,
            2_633_333L,
            2_666_666L,
            2_700_000L,
            2_733_333L,
            2_766_666L,
            2_800_000L,
            2_833_333L,
            2_866_666L,
            2_900_000L,
            2_933_333L,
            2_966_666L,
            3_000_000L,
            3_033_333L,
            3_066_666L,
            3_100_000L,
            3_133_333L,
            3_166_666L,
            3_200_000L,
            3_233_333L,
            3_266_666L,
            3_300_000L,
            3_333_333L,
            3_366_666L,
            3_400_000L,
            3_433_333L,
            3_466_666L)
        .inOrder();
  }

  private static Effects glShaderProgramToEffects(GlShaderProgram program) {
    return new Effects(ImmutableList.of(), ImmutableList.of((GlEffect) (c, useHdr) -> program));
  }

  private static ImmutableList<Long> getTimestampsFromFile(
      String filePath, Context context, String testId) throws Exception {
    Transformer transformer = new Transformer.Builder(context).build();

    InputTimestampRecordingShaderProgram timestampRecorder =
        new InputTimestampRecordingShaderProgram();
    EditedMediaItem item =
        new EditedMediaItem.Builder(MediaItem.fromUri(filePath))
            .setEffects(glShaderProgramToEffects(timestampRecorder))
            .build();

    ExportTestResult result =
        new TransformerAndroidTestRunner.Builder(context, transformer).build().run(testId, item);
    return timestampRecorder.getInputTimestampsUs();
  }
}
