/*
 * Copyright (C) 2018 The Android Open Source Project
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
package androidx.media3.exoplayer.video.spherical;

import static com.google.common.truth.Truth.assertThat;

import androidx.media3.common.C;
import androidx.media3.common.util.Util;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.util.Arrays;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Tests for {@link ProjectionDecoder}. */
@RunWith(AndroidJUnit4.class)
public final class ProjectionDecoderTest {

  private static final byte[] PROJ_DATA =
      Util.getBytesFromHexString(
          "0000008D70726F6A0000008579746D7000000000ABA158D672617720000000716D65736800000006BF800000"
              + "3F8000003F0000003F2AAAAB000000003EAAAAAB000000100024200104022430010421034020400123"
              + "1020401013020010102222001001003100200010320010000000010000000000240084009066080420"
              + "9020108421002410860214C1200660");

  private static final int MSHP_OFFSET = 16;
  private static final int VERTEX_COUNT = 36;
  private static final float[] FIRST_VERTEX = {-1.0f, -1.0f, 1.0f};
  private static final float[] LAST_VERTEX = {1.0f, -1.0f, -1.0f};
  private static final float[] FIRST_UV = {0.5f, 1.0f};
  private static final float[] LAST_UV = {1.0f, 1.0f};

  @Test
  public void decodeProj() {
    testDecoding(PROJ_DATA);
  }

  @Test
  public void decodeMshp() {
    testDecoding(Arrays.copyOfRange(PROJ_DATA, MSHP_OFFSET, PROJ_DATA.length));
  }

  @Test
  public void decodeInvalidCoordinateCount() {
    // Modify PROJ_DATA to have a negative coordinate count (-1).
    byte[] data = PROJ_DATA.clone();
    data[39] = (byte) 0xFF; // Set coordinateCount to -1 (last byte of int at offset 36)
    data[38] = (byte) 0xFF;
    data[37] = (byte) 0xFF;
    data[36] = (byte) 0xFF;

    assertThat(ProjectionDecoder.decode(data, C.STEREO_MODE_MONO)).isNull();
  }

  @Test
  public void decodeZeroCoordinateCount() {
    byte[] data = PROJ_DATA.clone();
    data[39] = 0;
    data[38] = 0;
    data[37] = 0;
    data[36] = 0;

    assertThat(ProjectionDecoder.decode(data, C.STEREO_MODE_MONO)).isNull();
  }

  @Test
  public void decodeInvalidVertexCount() {
    // Modify PROJ_DATA to have a negative vertex count (-1).
    byte[] data = PROJ_DATA.clone();
    data[67] = (byte) 0xFF; // Set vertexCount to -1 (at offset 64)
    data[66] = (byte) 0xFF;
    data[65] = (byte) 0xFF;
    data[64] = (byte) 0xFF;

    assertThat(ProjectionDecoder.decode(data, C.STEREO_MODE_MONO)).isNull();
  }

  @Test
  public void decodeZeroSubMeshCount() {
    byte[] data = PROJ_DATA.clone();
    // subMeshCount is read as 32 bits from the bitstream after coordinate/vertex data.
    // In PROJ_DATA, this occurs at approximately offset 108.
    data[111] = 0;
    data[110] = 0;
    data[109] = 0;
    data[108] = 0;

    assertThat(ProjectionDecoder.decode(data, C.STEREO_MODE_MONO)).isNull();
  }

  @Test
  public void decode_negativeSubMeshCount_returnsNull() {
    byte[] data = PROJ_DATA.clone();
    data[111] = (byte) 0xFF;
    data[110] = (byte) 0xFF;
    data[109] = (byte) 0xFF;
    data[108] = (byte) 0xFF;

    Projection projection = ProjectionDecoder.decode(data, C.STEREO_MODE_MONO);

    assertThat(projection).isNull();
  }

  @Test
  public void decode_zeroTriangleIndexCount_returnsNull() {
    byte[] data = PROJ_DATA.clone();
    data[117] = 0;
    data[116] = 0;
    data[115] = 0;
    data[114] = 0;

    Projection projection = ProjectionDecoder.decode(data, C.STEREO_MODE_MONO);

    assertThat(projection).isNull();
  }

  @Test
  public void decode_negativeTriangleIndexCount_returnsNull() {
    byte[] data = PROJ_DATA.clone();
    data[117] = (byte) 0xFF;
    data[116] = (byte) 0xFF;
    data[115] = (byte) 0xFF;
    data[114] = (byte) 0xFF;

    Projection projection = ProjectionDecoder.decode(data, C.STEREO_MODE_MONO);

    assertThat(projection).isNull();
  }

  private static void testDecoding(byte[] data) {
    Projection projection = ProjectionDecoder.decode(data, C.STEREO_MODE_MONO);
    assertThat(projection).isNotNull();
    assertThat(projection.stereoMode).isEqualTo(C.STEREO_MODE_MONO);
    assertThat(projection.leftMesh).isNotNull();
    assertThat(projection.rightMesh).isNotNull();
    assertThat(projection.singleMesh).isTrue();
    testSubMesh(projection.leftMesh);
  }

  /** Tests the that SubMesh (mesh with the video) contains expected data. */
  private static void testSubMesh(Projection.Mesh leftMesh) {
    assertThat(leftMesh.getSubMeshCount()).isEqualTo(1);

    Projection.SubMesh subMesh = leftMesh.getSubMesh(0);
    assertThat(subMesh.mode).isEqualTo(Projection.DRAW_MODE_TRIANGLES);

    float[] vertices = subMesh.vertices;
    float[] uv = subMesh.textureCoords;
    assertThat(vertices.length).isEqualTo(VERTEX_COUNT * 3);
    assertThat(subMesh.textureCoords.length).isEqualTo(VERTEX_COUNT * 2);

    // Test first vertex
    testCoordinate(FIRST_VERTEX, vertices, /* offset= */ 0);
    // Test last vertex
    testCoordinate(LAST_VERTEX, vertices, /* offset= */ VERTEX_COUNT * 3 - 3);

    // Test first uv
    testCoordinate(FIRST_UV, uv, /* offset= */ 0);
    // Test last uv
    testCoordinate(LAST_UV, uv, /* offset= */ VERTEX_COUNT * 2 - 2);
  }

  /** Tests that the output coordinates match the expected. */
  private static void testCoordinate(float[] expected, float[] output, int offset) {
    float[] adjustedOutput = Arrays.copyOfRange(output, offset, offset + expected.length);
    assertThat(adjustedOutput).isEqualTo(expected);
  }
}
