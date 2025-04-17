/*
 * Copyright (C) 2025 The Android Open Source Project
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
package androidx.media3.decoder.ass;

import static com.google.common.truth.Truth.assertThat;

import android.graphics.Color;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Unit test for {@link LibassJNITest}.
 */
@RunWith(AndroidJUnit4.class)
public class LibassJNITest {

  private String buildHeader(@Nullable String ycbcrMatrix) {
    return "[Script Info]\n" +
        "ScriptType: v4.00+\n" +
        "WrapStyle: 0\n" +
        "ScaledBorderAndShadow: yes\n" +
        (ycbcrMatrix != null ? "YCbCr Matrix: " + ycbcrMatrix + "\n" : "") +
        "PlayResX: 640\n" +
        "PlayResY: 480\n" +
        "\n" +
        "[V4+ Styles]\n" +
        "Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding\n" +
        "Style: Default,Arial,48,&H00000000,&H000000FF,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,0,0,2,0,0,0,1\n";
  }

  private LibassJNI setupLibassJNI(@Nullable String ycbcrMatrix, int videoColorSpace, int videoColorRange,
      int initialRed, int initialGreen, int initialBlue) {
    LibassJNI libassJNI = new LibassJNI();
    libassJNI.setFrameSize(640, 480);
    libassJNI.setStorageSize(640, 480);
    libassJNI.setVideoColorProperties(videoColorSpace, videoColorRange);
    libassJNI.createTrack("0");

    libassJNI.processCodecPrivate("0", buildHeader(ycbcrMatrix).getBytes(StandardCharsets.UTF_8));

    String colorTag = "\\c&H" + String.format("%02x", initialBlue) + String.format("%02x", initialGreen) + String.format("%02x", initialRed) + "&";
    String line = "Dialogue: 0:00:00:00,0:00:05:00,1,0,Default,,0,0,0,,{\\an7\\pos(0,0)\\p1" + colorTag + "}m 0 0 l 640 0 640 480 0 480";
    byte[] line_bytes = line.getBytes(StandardCharsets.UTF_8);
    ByteBuffer line_buffer = ByteBuffer.wrap(line_bytes);
    libassJNI.prepareProcessChunk(line_buffer.array(), line_buffer.position(), line_buffer.remaining(), 0, "0");
    return libassJNI;
  }

  private void assertCenterPixel(AssRenderResult result, int expectedRed, int expectedGreen, int expectedBlue) {
    assertThat(result.changedSinceLastCall).isTrue();
    assertThat(result.bitmap).isNotNull();
    int color = result.bitmap.getPixel(640 / 2, 480 / 2);
    assertThat(Color.red(color)).isEqualTo(expectedRed);
    assertThat(Color.green(color)).isEqualTo(expectedGreen);
    assertThat(Color.blue(color)).isEqualTo(expectedBlue);
    assertThat(Color.alpha(color)).isEqualTo(255);
  }

  @Test
  public void renderFrameTV601ToTV709() {
    LibassJNI libassJNI = setupLibassJNI("TV.601", C.COLOR_SPACE_BT709, C.COLOR_RANGE_LIMITED, 150,100,80);
    AssRenderResult result = libassJNI.renderFrame("0", 0);
    assertCenterPixel(result, 155, 104, 78);
  }

  @Test
  public void renderFrameTV601ToPC709() {
    LibassJNI libassJNI = setupLibassJNI("TV.601", C.COLOR_SPACE_BT709, C.COLOR_RANGE_FULL, 150, 100, 80);
    AssRenderResult result = libassJNI.renderFrame("0", 0);
    assertCenterPixel(result, 150, 105, 83);
  }

  @Test
  public void renderFrameTV709ToTV601() {
    LibassJNI libassJNI = setupLibassJNI("TV.709", C.COLOR_SPACE_BT601, C.COLOR_RANGE_LIMITED, 150,100,80);
    AssRenderResult result = libassJNI.renderFrame("0", 0);
    assertCenterPixel(result, 146, 96, 81);
  }

  @Test
  public void renderFrameTV709ToPC601() {
    LibassJNI libassJNI = setupLibassJNI("TV.709", C.COLOR_SPACE_BT601, C.COLOR_RANGE_FULL, 150,100,80);
    AssRenderResult result = libassJNI.renderFrame("0", 0);
    assertCenterPixel(result, 142, 98, 85);
  }

  @Test
  public void renderFrameTVFCCToTV709() {
    LibassJNI libassJNI = setupLibassJNI("TV.FCC", C.COLOR_SPACE_BT709, C.COLOR_RANGE_LIMITED, 150,100,80);
    AssRenderResult result = libassJNI.renderFrame("0", 0);
    assertCenterPixel(result, 155, 104, 79);
  }

  @Test
  public void renderFrameTVFCCToPC709() {
    LibassJNI libassJNI = setupLibassJNI("TV.FCC", C.COLOR_SPACE_BT709, C.COLOR_RANGE_FULL, 150,100,80);
    AssRenderResult result = libassJNI.renderFrame("0", 0);
    assertCenterPixel(result, 150, 105, 83);
  }

  @Test
  public void renderFrameTV240MToTV709() {
    LibassJNI libassJNI = setupLibassJNI("TV.240M", C.COLOR_SPACE_BT709, C.COLOR_RANGE_LIMITED, 150,100,80);
    AssRenderResult result = libassJNI.renderFrame("0", 0);
    assertCenterPixel(result, 150, 100, 80);
  }

  @Test
  public void renderFrameTV240MToPC709() {
    LibassJNI libassJNI = setupLibassJNI("TV.240M", C.COLOR_SPACE_BT709, C.COLOR_RANGE_FULL, 150,100,80);
    AssRenderResult result = libassJNI.renderFrame("0", 0);
    assertCenterPixel(result, 146, 101, 84);
  }

  @Test
  public void renderFrameNone() {
    LibassJNI libassJNI = setupLibassJNI("None", C.COLOR_SPACE_BT709, C.COLOR_RANGE_LIMITED, 150,100,80);
    AssRenderResult result = libassJNI.renderFrame("0", 0);
    assertCenterPixel(result, 150,100,80);
  }

  @Test
  public void renderFrameNoYcbcrMatrix() {
    LibassJNI libassJNI = setupLibassJNI(null, C.COLOR_SPACE_BT709, C.COLOR_RANGE_LIMITED, 150,100,80);
    AssRenderResult result = libassJNI.renderFrame("0", 0);
    assertCenterPixel(result, 155, 104, 78);
  }
}