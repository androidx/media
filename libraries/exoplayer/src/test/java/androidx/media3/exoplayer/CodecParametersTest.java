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
package androidx.media3.exoplayer;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import android.media.MediaFormat;
import android.os.Bundle;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;

/** Unit tests for {@link CodecParameters}. */
@RunWith(AndroidJUnit4.class)
public class CodecParametersTest {

  @Test
  public void builder_buildsInstanceWithAllParameterTypes() {
    byte[] testBytes = new byte[] {1, 2, 3};
    ByteBuffer byteBuffer = ByteBuffer.wrap(testBytes);

    CodecParameters parameters =
        new CodecParameters.Builder()
            .setInteger("key-int", 123)
            .setLong("key-long", 456L)
            .setFloat("key-float", 1.23f)
            .setString("key-string", "test-value")
            .setByteBuffer("key-byte-buffer", byteBuffer)
            .build();

    assertThat(parameters.keySet()).hasSize(5);
    assertThat(parameters.get("key-int")).isEqualTo(123);
    assertThat(parameters.get("key-long")).isEqualTo(456L);
    assertThat(parameters.get("key-float")).isEqualTo(1.23f);
    assertThat(parameters.get("key-string")).isEqualTo("test-value");
    // Verify the ByteBuffer content is correct
    ByteBuffer retrievedBuffer = (ByteBuffer) parameters.get("key-byte-buffer");
    assertThat(retrievedBuffer).isNotSameInstanceAs(byteBuffer);
    assertThat(retrievedBuffer.remaining()).isEqualTo(testBytes.length);
    byte[] retrievedBytes = new byte[retrievedBuffer.remaining()];
    retrievedBuffer.get(retrievedBytes);
    assertThat(retrievedBytes).isEqualTo(testBytes);
  }

  @Test
  public void buildUpon_toRemoveKey_createsModifiedCopy() {
    CodecParameters initialParams =
        new CodecParameters.Builder()
            .setInteger("key-to-keep", 1)
            .setString("key-to-remove", "value-to-remove")
            .build();

    CodecParameters modifiedParams = initialParams.buildUpon().remove("key-to-remove").build();

    assertThat(initialParams.keySet()).containsExactly("key-to-keep", "key-to-remove");
    assertThat(modifiedParams.keySet()).containsExactly("key-to-keep");
  }

  @Test
  public void keySet_returnsUnmodifiableSet() {
    CodecParameters parameters = new CodecParameters.Builder().setInteger("key1", 1).build();

    Set<String> keySet = parameters.keySet();

    assertThrows(UnsupportedOperationException.class, () -> keySet.add("key2"));
  }

  @Test
  @Config(sdk = 29)
  public void createFrom_populatesAllValuesFromMediaFormat() {
    MediaFormat mediaFormat = new MediaFormat();
    mediaFormat.setInteger("key-int", 1);
    mediaFormat.setLong("key-long", 2L);
    mediaFormat.setFloat("key-float", 3.0f);
    mediaFormat.setString("key-string", "value");
    mediaFormat.setInteger("key-ignored", 99);
    Set<String> keys =
        new HashSet<>(Arrays.asList("key-int", "key-long", "key-float", "key-string"));

    CodecParameters params = CodecParameters.createFrom(mediaFormat, keys).build();

    assertThat(params.get("key-int")).isEqualTo(1);
    assertThat(params.get("key-long")).isEqualTo(2L);
    assertThat(params.get("key-float")).isEqualTo(3.0f);
    assertThat(params.get("key-string")).isEqualTo("value");
    assertThat(params.get("key-ignored")).isNull();
  }

  @Test
  public void toBundle_convertsAllTypesCorrectly() {
    byte[] testBytes = new byte[] {1, 2, 3};
    CodecParameters parameters =
        new CodecParameters.Builder()
            .setInteger("key-int", 1)
            .setLong("key-long", 2L)
            .setFloat("key-float", 3.0f)
            .setString("key-string", "value")
            .setByteBuffer("key-byte-buffer", ByteBuffer.wrap(testBytes))
            .build();

    Bundle bundle = parameters.toBundle();

    assertThat(bundle.getInt("key-int")).isEqualTo(1);
    assertThat(bundle.getLong("key-long")).isEqualTo(2L);
    assertThat(bundle.getFloat("key-float")).isEqualTo(3.0f);
    assertThat(bundle.getString("key-string")).isEqualTo("value");
    assertThat(bundle.getByteArray("key-byte-buffer")).isEqualTo(testBytes);
  }

  @Test
  public void applyTo_setsAllValuesOnMediaFormat() {
    byte[] testBytes = new byte[] {4, 5, 6};
    CodecParameters parameters =
        new CodecParameters.Builder()
            .setInteger("key-int", 1)
            .setLong("key-long", 2L)
            .setFloat("key-float", 3.0f)
            .setString("key-string", "value")
            .setByteBuffer("key-byte-buffer", ByteBuffer.wrap(testBytes))
            .build();
    MediaFormat mediaFormat = new MediaFormat();

    parameters.applyTo(mediaFormat);

    assertThat(mediaFormat.getInteger("key-int")).isEqualTo(1);
    assertThat(mediaFormat.getLong("key-long")).isEqualTo(2L);
    assertThat(mediaFormat.getFloat("key-float")).isEqualTo(3.0f);
    assertThat(mediaFormat.getString("key-string")).isEqualTo("value");
    ByteBuffer byteBuffer = mediaFormat.getByteBuffer("key-byte-buffer");
    byte[] readBytes = new byte[byteBuffer.remaining()];
    byteBuffer.get(readBytes);
    assertThat(readBytes).isEqualTo(testBytes);
  }
}
