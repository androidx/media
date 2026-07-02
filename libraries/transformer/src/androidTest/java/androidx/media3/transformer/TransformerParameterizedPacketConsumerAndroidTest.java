/*
 * Copyright 2026 The Android Open Source Project
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

import static androidx.media3.common.util.Util.isRunningOnEmulator;
import static androidx.media3.test.utils.AssetInfo.MP4_ADVANCED_ASSET;
import static androidx.media3.test.utils.CompositionAssetInfo.MULTI_SEQUENCE_CONFIGS;
import static androidx.media3.test.utils.CompositionAssetInfo.MULTI_SEQUENCE_VIDEO_CONFIGS;
import static androidx.media3.test.utils.CompositionAssetInfo.SINGLE_SEQUENCE_CONFIGS;
import static androidx.media3.test.utils.EditedMediaItemAssetInfo.VIDEO_ONLY_CLIPPED_HALF_SPEED;
import static androidx.media3.test.utils.EditedMediaItemAssetInfo.VIDEO_ONLY_CLIPPED_TWICE_SPEED;
import static androidx.media3.test.utils.FormatSupportAssumptions.assumeAllFormatsSupported;
import static androidx.media3.transformer.ExportResult.CONVERSION_PROCESS_TRANSCODED;
import static androidx.media3.transformer.GlFrameProcessorTestUtil.closeTestingGlResources;
import static com.google.common.truth.Truth.assertThat;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import android.content.Context;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.GlObjectsProvider;
import androidx.media3.common.MediaItem;
import androidx.media3.common.util.GlUtil;
import androidx.media3.common.util.Util;
import androidx.media3.effect.DefaultGlFrameProcessor;
import androidx.media3.effect.DefaultGlObjectsProvider;
import androidx.media3.effect.DefaultHardwareBufferEffectsPipeline;
import androidx.media3.effect.FrameProcessorUtils;
import androidx.media3.effect.ndk.HardwareBufferJni;
import androidx.media3.test.utils.CompositionAssetInfo;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.filters.SdkSuppress;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import com.google.testing.junit.testparameterinjector.TestParameterValuesProvider;
import java.io.File;
import java.util.List;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
import org.junit.runner.RunWith;

/** Parameterized end-to-end export tests for {@link Transformer}. */
@RunWith(TestParameterInjector.class)
// TODO: b/479415308 - Expand API versions below 28 once supported.
@SdkSuppress(minSdkVersion = 28)
public final class TransformerParameterizedPacketConsumerAndroidTest {

  private static final String PACKET_PROCESSOR = "packet_processor";
  private static final String DEFAULT_GL_FRAME_PROCESSOR = "default_gl_frame_processor";
  private static final long TEST_TIMEOUT_MS = isRunningOnEmulator() ? 20_000 : 10_000;

  @Rule public final TestName testName;

  private final Context context;
  private @MonotonicNonNull ListeningExecutorService glExecutorService;
  private @MonotonicNonNull GlObjectsProvider glObjectsProvider;
  private String testId;

  @TestParameter({PACKET_PROCESSOR, DEFAULT_GL_FRAME_PROCESSOR})
  public String pipelineMode;

  private static class TestConfigProvider extends TestParameterValuesProvider {
    @Override
    protected List<CompositionAssetInfo> provideValues(
        TestParameterValuesProvider.Context context) {
      ImmutableList<CompositionAssetInfo> allConfigs =
          new ImmutableList.Builder<CompositionAssetInfo>()
              .addAll(SINGLE_SEQUENCE_CONFIGS)
              .addAll(MULTI_SEQUENCE_VIDEO_CONFIGS)
              .addAll(MULTI_SEQUENCE_CONFIGS)
              .build();
      ImmutableList.Builder<CompositionAssetInfo> filteredConfigs = new ImmutableList.Builder<>();
      for (CompositionAssetInfo config : allConfigs) {
        String configString = config.toString();
        // DefaultGlFrameProcessor doesn't support speed changing video effects.
        // TODO: b/530108514 - Move tests to use EditedMediaItem.setSpeed().
        if (configString.contains(VIDEO_ONLY_CLIPPED_HALF_SPEED.name)
            || configString.contains(VIDEO_ONLY_CLIPPED_TWICE_SPEED.name)) {
          continue;
        }
        filteredConfigs.add(config);
      }
      return filteredConfigs.build();
    }
  }

  public TransformerParameterizedPacketConsumerAndroidTest() {
    context = ApplicationProvider.getApplicationContext();
    testName = new TestName();
  }

  @Before
  public void setUp() throws Exception {
    testId = testName.getMethodName();
    glExecutorService =
        MoreExecutors.listeningDecorator(Util.newSingleThreadExecutor("PacketProcessor:Effect"));
    if (pipelineMode.equals(DEFAULT_GL_FRAME_PROCESSOR)) {
      glObjectsProvider = new DefaultGlObjectsProvider();
      glExecutorService
          .submit(
              () -> {
                try {
                  FrameProcessorUtils.setupOpenGl(glObjectsProvider);
                } catch (GlUtil.GlException e) {
                  throw new AssertionError(e);
                }
              })
          .get(TEST_TIMEOUT_MS, MILLISECONDS);
    }
  }

  @After
  public void tearDown() {
    @Nullable Exception releasingException = null;
    if (pipelineMode.equals(DEFAULT_GL_FRAME_PROCESSOR)) {
      releasingException =
          closeTestingGlResources(glExecutorService, glObjectsProvider, TEST_TIMEOUT_MS);
    }
    if (glExecutorService != null) {
      glExecutorService.shutdown();
    }
    if (releasingException != null) {
      throw new AssertionError(releasingException);
    }
  }

  @Test
  public void export_completesSuccessfully(
      @TestParameter(valuesProvider = TestConfigProvider.class) CompositionAssetInfo testConfig)
      throws Exception {
    String testId = "export_" + pipelineMode + "_" + testConfig;
    Composition composition = testConfig.getComposition();
    assumeAllFormatsSupported(
        context,
        testId,
        /* inputFormats= */ testConfig.getAllVideoFormats(),
        /* outputFormat= */ testConfig.getVideoEncoderInputFormat());
    Transformer transformer = buildTransformer();

    ExportTestResult result =
        new TransformerAndroidTestRunner.Builder(context, transformer)
            .build()
            .run(testId, composition);

    assertThat(result.exportResult.exportException).isNull();
    assertThat(new File(result.filePath).length()).isGreaterThan(0);
    assertThat(result.exportResult.fileSizeBytes).isGreaterThan(0);
    if (result.exportResult.approximateDurationMs != C.TIME_UNSET) {
      assertThat(result.exportResult.approximateDurationMs).isGreaterThan(0);
    }
  }

  @Test
  public void export_forcesVideoTranscoding() throws Exception {
    Transformer transformer = buildTransformer();
    MediaItem mediaItem = MediaItem.fromUri(MP4_ADVANCED_ASSET.uri);

    ExportTestResult result =
        new TransformerAndroidTestRunner.Builder(context, transformer)
            .build()
            .run(testId, mediaItem);

    assertThat(result.exportResult.exportException).isNull();
    assertThat(result.exportResult.videoConversionProcess).isEqualTo(CONVERSION_PROCESS_TRANSCODED);
  }

  private Transformer buildTransformer() {
    Transformer.Builder transformerBuilder =
        new Transformer.Builder(context).setNativeHardwareBufferHelpers(HardwareBufferJni.INSTANCE);
    if (pipelineMode.equals(PACKET_PROCESSOR)) {
      transformerBuilder.setHardwareBufferEffectsPipeline(
          DefaultHardwareBufferEffectsPipeline.create(context, HardwareBufferJni.INSTANCE));
    } else if (pipelineMode.equals(DEFAULT_GL_FRAME_PROCESSOR)) {
      transformerBuilder.setFrameProcessorFactory(
          new DefaultGlFrameProcessor.Factory(
              context, glObjectsProvider, HardwareBufferJni.INSTANCE, glExecutorService));
    } else {
      throw new UnsupportedOperationException(pipelineMode);
    }
    return transformerBuilder.build();
  }
}
