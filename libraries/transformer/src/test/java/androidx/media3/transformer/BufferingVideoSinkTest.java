/*
 * Copyright 2024 The Android Open Source Project
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

import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import android.content.Context;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.exoplayer.video.VideoSink;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InOrder;
import org.mockito.Mockito;

/** Tests for {@link BufferingVideoSink} */
@RunWith(AndroidJUnit4.class)
public class BufferingVideoSinkTest {

  private final Context context = ApplicationProvider.getApplicationContext();

  @Test
  public void executeOperation_withVideoSinkSet_callsVideoSink() throws Exception {
    BufferingVideoSink bufferingVideoSink = new BufferingVideoSink(context);
    VideoSink videoSinkMock = mock(VideoSink.class);

    bufferingVideoSink.setVideoSink(videoSinkMock);
    bufferingVideoSink.onRendererEnabled(/* mayRenderStartOfStream= */ true);
    bufferingVideoSink.onRendererStarted();

    InOrder inOrder = Mockito.inOrder(videoSinkMock);
    inOrder.verify(videoSinkMock).onRendererEnabled(/* mayRenderStartOfStream= */ true);
    inOrder.verify(videoSinkMock).onRendererStarted();
  }

  @Test
  public void setVideoSink_executesPendingOperations() throws Exception {
    BufferingVideoSink bufferingVideoSink = new BufferingVideoSink(context);
    VideoSink videoSinkMock = mock(VideoSink.class);

    bufferingVideoSink.onRendererEnabled(/* mayRenderStartOfStream= */ true);
    bufferingVideoSink.onRendererStarted();
    bufferingVideoSink.setVideoSink(videoSinkMock);

    InOrder inOrder = Mockito.inOrder(videoSinkMock);
    inOrder.verify(videoSinkMock).onRendererEnabled(/* mayRenderStartOfStream= */ true);
    inOrder.verify(videoSinkMock).onRendererStarted();
  }

  @Test
  public void setVideoSink_withFailingPendingOperation_throws() throws Exception {
    BufferingVideoSink bufferingVideoSink = new BufferingVideoSink(context);
    VideoSink videoSinkMock = mock(VideoSink.class);
    Format format = new Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).build();
    Mockito.doThrow(new VideoSink.VideoSinkException(new RuntimeException(), format))
        .when(videoSinkMock)
        .initialize(any());

    bufferingVideoSink.initialize(format);

    assertThrows(
        VideoSink.VideoSinkException.class, () -> bufferingVideoSink.setVideoSink(videoSinkMock));
  }

  @Test
  public void removeVideoSink_thenExecuteOperations_doesNotCallVideoSink() throws Exception {
    BufferingVideoSink bufferingVideoSink = new BufferingVideoSink(context);
    VideoSink videoSinkMock = mock(VideoSink.class);
    bufferingVideoSink.setVideoSink(videoSinkMock);

    bufferingVideoSink.removeVideoSink();
    bufferingVideoSink.onRendererEnabled(/* mayRenderStartOfStream= */ true);
    bufferingVideoSink.onRendererStarted();

    verify(videoSinkMock, never()).onRendererEnabled(/* mayRenderStartOfStream= */ true);
    verify(videoSinkMock, never()).onRendererStarted();
  }

  @Test
  public void clearPendingOperations_clearsPendingOperations() throws Exception {
    BufferingVideoSink bufferingVideoSink = new BufferingVideoSink(context);
    VideoSink videoSinkMock = mock(VideoSink.class);

    bufferingVideoSink.onRendererEnabled(/* mayRenderStartOfStream= */ true);
    bufferingVideoSink.onRendererStarted();
    bufferingVideoSink.clearPendingOperations();
    bufferingVideoSink.setVideoSink(videoSinkMock);

    verify(videoSinkMock, never()).onRendererEnabled(/* mayRenderStartOfStream= */ true);
    verify(videoSinkMock, never()).onRendererStarted();
  }
}
