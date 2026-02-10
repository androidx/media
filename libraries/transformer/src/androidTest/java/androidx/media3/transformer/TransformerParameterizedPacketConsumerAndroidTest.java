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

import static androidx.media3.test.utils.FormatSupportAssumptions.assumeAllFormatsSupported;
import static androidx.media3.transformer.CompositionAssetInfo.MULTI_SEQUENCE_IMAGE_CONFIGS;
import static androidx.media3.transformer.CompositionAssetInfo.MULTI_SEQUENCE_MISMATCHED_DURATION_CONFIGS;
import static androidx.media3.transformer.CompositionAssetInfo.MULTI_SEQUENCE_VIDEO_CONFIGS;
import static androidx.media3.transformer.CompositionAssetInfo.SINGLE_SEQUENCE_CONFIGS;
import static com.google.common.truth.Truth.assertThat;

import android.content.Context;
import androidx.media3.common.C;
import androidx.media3.common.util.Util;
import androidx.media3.effect.DefaultHardwareBufferEffectsPipeline;
import androidx.media3.effect.ndk.NdkTransformerBuilder;
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
import org.junit.Test;
import org.junit.runner.RunWith;

/** Parameterized end-to-end export tests for {@link Transformer}. */
@RunWith(TestParameterInjector.class)
public final class TransformerParameterizedPacketConsumerAndroidTest {

  private final Context context = ApplicationProvider.getApplicationContext();
  private @MonotonicNonNull ListeningExecutorService glExecutorService;

  private static class TestConfigProvider extends TestParameterValuesProvider {
    @Override
    protected List<CompositionAssetInfo> provideValues(
        TestParameterValuesProvider.Context context) {
      return new ImmutableList.Builder<CompositionAssetInfo>()
          .addAll(SINGLE_SEQUENCE_CONFIGS)
          .addAll(MULTI_SEQUENCE_IMAGE_CONFIGS)
          .addAll(MULTI_SEQUENCE_VIDEO_CONFIGS)
          .addAll(MULTI_SEQUENCE_MISMATCHED_DURATION_CONFIGS)
          .build();
    }
  }

  @Before
  public void setUp() {
    glExecutorService =
        MoreExecutors.listeningDecorator(Util.newSingleThreadExecutor("PacketProcessor:Effect"));
  }

  @After
  public void tearDown() {
    if (glExecutorService != null) {
      glExecutorService.shutdown();
    }
  }

  // TODO: b/479415308 - Expand API versions below 34 once supported.
  @Test
  @SdkSuppress(minSdkVersion = 34)
  public void export_withPacketProcessor_completesSuccessfully(
      @TestParameter(valuesProvider = TestConfigProvider.class) CompositionAssetInfo testConfig)
      throws Exception {
    String testId = "export_withPacketProcessor_" + testConfig;
    Composition composition = testConfig.getComposition();
    assumeAllFormatsSupported(
        context,
        testId,
        /* inputFormats= */ testConfig.getAllVideoFormats(),
        /* outputFormat= */ testConfig.getVideoEncoderInputFormat());
    Transformer transformer =
        NdkTransformerBuilder.create(context)
            .setHardwareBufferEffectsPipeline(new DefaultHardwareBufferEffectsPipeline())
            .build();

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
}
