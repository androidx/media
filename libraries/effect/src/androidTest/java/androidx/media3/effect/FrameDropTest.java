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
package androidx.media3.effect;

import static android.graphics.Bitmap.createBitmap;
import static androidx.media3.effect.EffectsTestUtil.generateAndProcessFrames;
import static androidx.media3.effect.EffectsTestUtil.getAndAssertOutputBitmaps;
import static androidx.media3.effect.FrameDropEffect.createDefaultFrameDropEffect;
import static androidx.test.core.app.ApplicationProvider.getApplicationContext;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.TypefaceSpan;
import androidx.media3.common.C;
import androidx.media3.common.GlObjectsProvider;
import androidx.media3.common.GlTextureInfo;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.GlUtil;
import androidx.media3.common.util.Size;
import androidx.media3.test.utils.TextureBitmapReader;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.jspecify.annotations.Nullable;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
import org.junit.runner.RunWith;

/** Tests for {@link FrameDropEffect}. */
@RunWith(AndroidJUnit4.class)
public class FrameDropTest {
  @Rule public final TestName testName = new TestName();
  @Rule public final GlTestRule glTestRule = new GlTestRule();

  private static final String ASSET_PATH = "test-generated-goldens/FrameDropTest";

  private @MonotonicNonNull TextureBitmapReader textureBitmapReader;
  private @Nullable DefaultFrameDroppingShaderProgram defaultFrameDroppingShaderProgram;
  private String testId;

  @Before
  public void setUp() {
    textureBitmapReader = new TextureBitmapReader();
    testId = testName.getMethodName();
  }

  @After
  public void tearDown() throws VideoFrameProcessingException {
    if (defaultFrameDroppingShaderProgram != null) {
      defaultFrameDroppingShaderProgram.release();
    }
  }

  @Test
  public void frameDrop_withDefaultStrategy_outputsFramesAtTheCorrectPresentationTimesUs()
      throws Exception {
    ImmutableList<Long> frameTimesUs =
        ImmutableList.of(0L, 16_000L, 32_000L, 48_000L, 58_000L, 71_000L, 86_000L);
    FrameDropEffect frameDropEffect =
        FrameDropEffect.createDefaultFrameDropEffect(/* targetFrameRate= */ 30);

    ImmutableList<Long> actualPresentationTimesUs =
        generateAndProcessBlackTimeStampedFrames(frameTimesUs, frameDropEffect);

    assertThat(actualPresentationTimesUs).containsExactly(0L, 32_000L, 71_000L).inOrder();
    getAndAssertOutputBitmaps(textureBitmapReader, actualPresentationTimesUs, testId, ASSET_PATH);
  }

  @Test
  public void frameDrop_withSimpleStrategy_outputsFramesAtTheCorrectPresentationTimesUs()
      throws Exception {
    ImmutableList<Long> frameTimesUs =
        ImmutableList.of(0L, 250_000L, 500_000L, 750_000L, 1_000_000L, 1_500_000L);
    FrameDropEffect frameDropEffect =
        FrameDropEffect.createSimpleFrameDropEffect(
            /* expectedFrameRate= */ 6, /* targetFrameRate= */ 2);

    ImmutableList<Long> actualPresentationTimesUs =
        generateAndProcessBlackTimeStampedFrames(frameTimesUs, frameDropEffect);

    assertThat(actualPresentationTimesUs).containsExactly(0L, 750_000L).inOrder();
    getAndAssertOutputBitmaps(textureBitmapReader, actualPresentationTimesUs, testId, ASSET_PATH);
  }

  @Test
  public void frameDrop_withSimpleStrategy_outputsAllFrames() throws Exception {
    ImmutableList<Long> frameTimesUs = ImmutableList.of(0L, 333_333L, 666_667L);
    FrameDropEffect frameDropEffect =
        FrameDropEffect.createSimpleFrameDropEffect(
            /* expectedFrameRate= */ 3, /* targetFrameRate= */ 3);

    ImmutableList<Long> actualPresentationTimesUs =
        generateAndProcessBlackTimeStampedFrames(frameTimesUs, frameDropEffect);

    assertThat(actualPresentationTimesUs).containsExactly(0L, 333_333L, 666_667L).inOrder();
    getAndAssertOutputBitmaps(textureBitmapReader, actualPresentationTimesUs, testId, ASSET_PATH);
  }

  @Test
  public void
      signalEndOfCurrentInputStream_withDefaultStrategyAndSameSizeStreams_outputsExpectedFrames()
          throws Exception {
    GlObjectsProvider glObjectsProvider = new DefaultGlObjectsProvider();
    List<Long> outputPresentationTimesUs = new ArrayList<>();
    List<Size> outputSizes = new ArrayList<>();
    AtomicReference<@Nullable VideoFrameProcessingException> errorReference =
        new AtomicReference<>();
    defaultFrameDroppingShaderProgram =
        createDefaultFrameDroppingShaderProgram(
            /* targetFrameRate= */ 30f, outputPresentationTimesUs, outputSizes, errorReference);
    GlTextureInfo firstStreamTexture = createInputTexture(/* width= */ 100, /* height= */ 50);
    GlTextureInfo secondStreamTexture = createInputTexture(/* width= */ 100, /* height= */ 50);

    // Queue 60 fps input frames (~16 ms apart); expected output is 30 fps.
    queueInputFrames(
        defaultFrameDroppingShaderProgram,
        glObjectsProvider,
        firstStreamTexture,
        0L,
        16_000L,
        32_000L,
        48_000L);
    defaultFrameDroppingShaderProgram.signalEndOfCurrentInputStream();
    queueInputFrames(
        defaultFrameDroppingShaderProgram,
        glObjectsProvider,
        secondStreamTexture,
        1_000_000L,
        1_016_000L,
        1_032_000L,
        1_048_000L);
    defaultFrameDroppingShaderProgram.signalEndOfCurrentInputStream();

    assertThat(errorReference.get()).isNull();
    assertThat(outputPresentationTimesUs)
        .containsExactly(0L, 32_000L, 1_000_000L, 1_032_000L)
        .inOrder();
    assertThat(outputSizes)
        .containsExactly(new Size(100, 50), new Size(100, 50), new Size(100, 50), new Size(100, 50))
        .inOrder();
  }

  @Test
  public void
      signalEndOfCurrentInputStream_withDefaultStrategyAndDifferentSizeStreams_outputsExpectedFrames()
          throws Exception {
    GlObjectsProvider glObjectsProvider = new DefaultGlObjectsProvider();
    List<Long> outputPresentationTimesUs = new ArrayList<>();
    List<Size> outputSizes = new ArrayList<>();
    AtomicReference<@Nullable VideoFrameProcessingException> errorReference =
        new AtomicReference<>();
    defaultFrameDroppingShaderProgram =
        createDefaultFrameDroppingShaderProgram(
            /* targetFrameRate= */ 30f, outputPresentationTimesUs, outputSizes, errorReference);
    GlTextureInfo firstStreamTexture = createInputTexture(/* width= */ 100, /* height= */ 50);
    GlTextureInfo secondStreamTexture = createInputTexture(/* width= */ 50, /* height= */ 100);

    // Queue 60 fps input frames (~16 ms apart); expected output is 30 fps.
    queueInputFrames(
        defaultFrameDroppingShaderProgram,
        glObjectsProvider,
        firstStreamTexture,
        0L,
        16_000L,
        32_000L,
        48_000L);
    defaultFrameDroppingShaderProgram.signalEndOfCurrentInputStream();
    queueInputFrames(
        defaultFrameDroppingShaderProgram,
        glObjectsProvider,
        secondStreamTexture,
        1_000_000L,
        1_016_000L,
        1_032_000L,
        1_048_000L);
    defaultFrameDroppingShaderProgram.signalEndOfCurrentInputStream();

    assertThat(errorReference.get()).isNull();
    assertThat(outputPresentationTimesUs)
        .containsExactly(0L, 32_000L, 1_000_000L, 1_032_000L)
        .inOrder();
    assertThat(outputSizes)
        .containsExactly(new Size(100, 50), new Size(100, 50), new Size(50, 100), new Size(50, 100))
        .inOrder();
  }

  @Test
  public void flush_withDefaultStrategyAndSameSizeStreams_outputsExpectedFramesAfterFlush()
      throws Exception {
    GlObjectsProvider glObjectsProvider = new DefaultGlObjectsProvider();
    List<Long> outputPresentationTimesUs = new ArrayList<>();
    List<Size> outputSizes = new ArrayList<>();
    AtomicReference<@Nullable VideoFrameProcessingException> errorReference =
        new AtomicReference<>();
    defaultFrameDroppingShaderProgram =
        createDefaultFrameDroppingShaderProgram(
            /* targetFrameRate= */ 30f, outputPresentationTimesUs, outputSizes, errorReference);
    GlTextureInfo firstStreamTexture = createInputTexture(/* width= */ 100, /* height= */ 50);
    GlTextureInfo secondStreamTexture = createInputTexture(/* width= */ 100, /* height= */ 50);

    // Queue 60 fps input frames (~16 ms apart); expected output is 30 fps.
    queueInputFrames(
        defaultFrameDroppingShaderProgram,
        glObjectsProvider,
        firstStreamTexture,
        0L,
        16_000L,
        32_000L,
        48_000L);
    defaultFrameDroppingShaderProgram.flush();
    queueInputFrames(
        defaultFrameDroppingShaderProgram,
        glObjectsProvider,
        secondStreamTexture,
        1_000_000L,
        1_016_000L,
        1_032_000L,
        1_048_000L);

    assertThat(errorReference.get()).isNull();
    assertThat(outputPresentationTimesUs)
        .containsExactly(0L, 32_000L, 1_000_000L, 1_032_000L)
        .inOrder();
    assertThat(outputSizes)
        .containsExactly(new Size(100, 50), new Size(100, 50), new Size(100, 50), new Size(100, 50))
        .inOrder();
  }

  @Test
  public void flush_withDefaultStrategyAndDifferentSizeStreams_outputsExpectedFramesAfterFlush()
      throws Exception {
    GlObjectsProvider glObjectsProvider = new DefaultGlObjectsProvider();
    List<Long> outputPresentationTimesUs = new ArrayList<>();
    List<Size> outputSizes = new ArrayList<>();
    AtomicReference<@Nullable VideoFrameProcessingException> errorReference =
        new AtomicReference<>();
    defaultFrameDroppingShaderProgram =
        createDefaultFrameDroppingShaderProgram(
            /* targetFrameRate= */ 30f, outputPresentationTimesUs, outputSizes, errorReference);
    GlTextureInfo firstStreamTexture = createInputTexture(/* width= */ 100, /* height= */ 50);
    GlTextureInfo secondStreamTexture = createInputTexture(/* width= */ 50, /* height= */ 100);

    // Queue 60 fps input frames (~16 ms apart); expected output is 30 fps.
    queueInputFrames(
        defaultFrameDroppingShaderProgram,
        glObjectsProvider,
        firstStreamTexture,
        0L,
        16_000L,
        32_000L,
        48_000L);
    defaultFrameDroppingShaderProgram.flush();
    queueInputFrames(
        defaultFrameDroppingShaderProgram,
        glObjectsProvider,
        secondStreamTexture,
        1_000_000L,
        1_016_000L,
        1_032_000L,
        1_048_000L);

    assertThat(errorReference.get()).isNull();
    assertThat(outputPresentationTimesUs)
        .containsExactly(0L, 32_000L, 1_000_000L, 1_032_000L)
        .inOrder();
    assertThat(outputSizes)
        .containsExactly(new Size(100, 50), new Size(100, 50), new Size(50, 100), new Size(50, 100))
        .inOrder();
  }

  @Test
  public void queueInputFrame_withDefaultStrategyAndMidStreamSizeChange_outputsExpectedFrames()
      throws Exception {
    GlObjectsProvider glObjectsProvider = new DefaultGlObjectsProvider();
    List<Long> outputPresentationTimesUs = new ArrayList<>();
    List<Size> outputSizes = new ArrayList<>();
    AtomicReference<@Nullable VideoFrameProcessingException> errorReference =
        new AtomicReference<>();
    defaultFrameDroppingShaderProgram =
        createDefaultFrameDroppingShaderProgram(
            /* targetFrameRate= */ 30f, outputPresentationTimesUs, outputSizes, errorReference);
    GlTextureInfo firstSizeTexture = createInputTexture(/* width= */ 100, /* height= */ 50);
    GlTextureInfo secondSizeTexture = createInputTexture(/* width= */ 50, /* height= */ 100);

    // Queue 60 fps input frames (~16 ms apart) with a mid-stream size change; expected is 30 fps.
    queueInputFrames(
        defaultFrameDroppingShaderProgram, glObjectsProvider, firstSizeTexture, 0L, 16_000L);
    queueInputFrames(
        defaultFrameDroppingShaderProgram, glObjectsProvider, secondSizeTexture, 32_000L, 48_000L);
    defaultFrameDroppingShaderProgram.signalEndOfCurrentInputStream();

    assertThat(errorReference.get()).isNull();
    assertThat(outputPresentationTimesUs).containsExactly(0L, 32_000L).inOrder();
    assertThat(outputSizes).containsExactly(new Size(100, 50), new Size(50, 100)).inOrder();
  }

  private DefaultFrameDroppingShaderProgram createDefaultFrameDroppingShaderProgram(
      float targetFrameRate,
      List<Long> outputPresentationTimesUs,
      List<Size> outputSizes,
      AtomicReference<@Nullable VideoFrameProcessingException> errorReference)
      throws VideoFrameProcessingException {
    DefaultFrameDroppingShaderProgram shaderProgram =
        (DefaultFrameDroppingShaderProgram)
            createDefaultFrameDropEffect(targetFrameRate)
                .toGlShaderProgram(getApplicationContext(), /* useHdr= */ false);
    shaderProgram.setOutputListener(
        new GlShaderProgram.OutputListener() {
          @Override
          public void onOutputFrameAvailable(GlTextureInfo outputTexture, long presentationTimeUs) {
            outputPresentationTimesUs.add(presentationTimeUs);
            outputSizes.add(new Size(outputTexture.width, outputTexture.height));
            shaderProgram.releaseOutputFrame(outputTexture);
          }
        });
    shaderProgram.setErrorListener(directExecutor(), errorReference::set);
    return shaderProgram;
  }

  private GlTextureInfo createInputTexture(int width, int height) throws GlUtil.GlException {
    int texId =
        glTestRule.createGlTextureFromBitmap(createBitmap(width, height, Bitmap.Config.ARGB_8888));
    return new GlTextureInfo(
        texId, /* fboId= */ C.INDEX_UNSET, /* rboId= */ C.INDEX_UNSET, width, height);
  }

  private static void queueInputFrames(
      DefaultFrameDroppingShaderProgram shaderProgram,
      GlObjectsProvider glObjectsProvider,
      GlTextureInfo inputTexture,
      long... presentationTimesUs) {
    for (long presentationTimeUs : presentationTimesUs) {
      shaderProgram.queueInputFrame(glObjectsProvider, inputTexture, presentationTimeUs);
    }
  }

  private ImmutableList<Long> generateAndProcessBlackTimeStampedFrames(
      ImmutableList<Long> frameTimesUs, FrameDropEffect frameDropEffect) throws Exception {
    int blankFrameWidth = 100;
    int blankFrameHeight = 50;
    Consumer<SpannableString> textSpanConsumer =
        text -> {
          text.setSpan(
              new ForegroundColorSpan(Color.BLACK),
              /* start= */ 0,
              text.length(),
              Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
          text.setSpan(
              new AbsoluteSizeSpan(/* size= */ 24),
              /* start= */ 0,
              text.length(),
              Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
          text.setSpan(
              new TypefaceSpan(/* family= */ "sans-serif"),
              /* start= */ 0,
              text.length(),
              Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        };
    return generateAndProcessFrames(
        blankFrameWidth,
        blankFrameHeight,
        frameTimesUs,
        frameDropEffect,
        checkNotNull(textureBitmapReader),
        textSpanConsumer);
  }
}
