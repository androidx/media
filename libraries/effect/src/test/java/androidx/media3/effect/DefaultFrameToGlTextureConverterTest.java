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
package androidx.media3.effect;

import static androidx.test.core.app.ApplicationProvider.getApplicationContext;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static org.junit.Assert.assertThrows;

import androidx.media3.common.ColorInfo;
import androidx.media3.common.Format;
import androidx.media3.common.video.DefaultImagePlanesFrame;
import androidx.media3.common.video.DefaultImagePlanesFrame.DefaultPlane;
import androidx.media3.common.video.Frame;
import androidx.media3.common.video.FrameProcessor;
import androidx.media3.common.video.ImagePlanesFrame;
import androidx.media3.test.utils.FakeFrameProcessor;
import androidx.media3.test.utils.FakeHardwareBufferJniWrapper;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.nio.ByteBuffer;
import java.util.concurrent.Executor;
import org.jspecify.annotations.Nullable;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Unit tests for {@link DefaultFrameToGlTextureConverter}.
 *
 * <p>This test class covers unsupported frame types and no-op behavior after closing. Converting
 * supported frames requires an OpenGL ES context and is tested in {@code
 * DefaultGlFrameProcessorTest} in {@code androidTest}.
 */
@RunWith(AndroidJUnit4.class)
public final class DefaultFrameToGlTextureConverterTest {

  private static final Executor DIRECT_EXECUTOR = directExecutor();

  private FrameProcessor.Listener listener;
  private DefaultFrameToGlTextureConverter converter;

  @Before
  public void setUp() {
    listener = new FakeFrameProcessor.Listener();
    converter =
        new DefaultFrameToGlTextureConverter(
            getApplicationContext(),
            new FakeHardwareBufferJniWrapper(),
            ColorInfo.SDR_BT709_LIMITED,
            /* errorConsumer= */ error -> {});
  }

  @After
  public void tearDown() throws Exception {
    converter.close();
  }

  @Test
  public void convert_unsupportedFrameType_throwsIllegalArgumentException() {
    Frame unsupportedFrame = createUnsupportedFrame();

    assertThrows(
        IllegalArgumentException.class,
        () ->
            converter.convert(
                unsupportedFrame,
                /* glExecutor= */ DIRECT_EXECUTOR,
                /* listenerExecutor= */ DIRECT_EXECUTOR,
                listener));
  }

  @Test
  public void convert_whenClosed_returnsNull() throws Exception {
    ImagePlanesFrame frame = createImagePlanesFrame();
    converter.close();

    @Nullable GlTextureFrame convertedFrame =
        converter.convert(
            frame,
            /* glExecutor= */ DIRECT_EXECUTOR,
            /* listenerExecutor= */ DIRECT_EXECUTOR,
            listener);

    assertThat(convertedFrame).isNull();
  }

  @Test
  public void close_calledMultipleTimes_doesNotThrow() throws Exception {
    converter.close();

    converter.close();
  }

  private static ImagePlanesFrame createImagePlanesFrame() {
    return new DefaultImagePlanesFrame.Builder(
            ImmutableList.of(
                new DefaultPlane(
                    ByteBuffer.allocateDirect(64), /* rowStride= */ 16, /* pixelStride= */ 4)))
        .setFormat(new Format.Builder().setWidth(4).setHeight(4).build())
        .build();
  }

  private static Frame createUnsupportedFrame() {
    return new Frame() {
      @Override
      public long getContentTimeUs() {
        return 0;
      }

      @Override
      public Format getFormat() {
        return new Format.Builder().build();
      }

      @Override
      public ImmutableMap<String, Object> getMetadata() {
        return ImmutableMap.of();
      }
    };
  }
}
