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
package androidx.media3.common;

import static com.google.common.truth.Truth.assertThat;

import android.media.MediaFormat;
import android.os.Bundle;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit tests for {@link CodecParameters}. */
@RunWith(AndroidJUnit4.class)
public class CodecParametersTest {

  @Test
  public void setAndGet_singleParameter_retrievesCorrectly() {
    CodecParameters codecParameters = new CodecParameters();
    CodecParameter param = new CodecParameter("test-key", 123, CodecParameter.TYPE_INT);
    codecParameters.set(param);

    assertThat(codecParameters.get("test-key")).isSameInstanceAs(param);
  }

  @Test
  public void set_nullValueType_removesParameter() {
    CodecParameters codecParameters = new CodecParameters();
    codecParameters.set(new CodecParameter("test-key", 123, CodecParameter.TYPE_INT));

    assertThat(codecParameters.get("test-key")).isNotNull();

    codecParameters.set(new CodecParameter("test-key", null, CodecParameter.TYPE_NULL));

    assertThat(codecParameters.get("test-key")).isNull();
  }

  @Test
  public void clear_removesAllParameters() {
    CodecParameters codecParameters = new CodecParameters();
    codecParameters.set(new CodecParameter("key1", 1, CodecParameter.TYPE_INT));
    codecParameters.set(new CodecParameter("key2", "value2", CodecParameter.TYPE_STRING));

    assertThat(codecParameters.get().isEmpty()).isFalse();

    codecParameters.clear();

    assertThat(codecParameters.get().isEmpty()).isTrue();
  }

  @Test
  public void toBundle_withAllValueTypes_createsCorrectBundle() {
    CodecParameters codecParameters = new CodecParameters();
    byte[] testBytes = new byte[] {1, 2, 3};
    codecParameters.set(new CodecParameter("key-int", 10, CodecParameter.TYPE_INT));
    codecParameters.set(new CodecParameter("key-long", 20L, CodecParameter.TYPE_LONG));
    codecParameters.set(new CodecParameter("key-float", 30.0f, CodecParameter.TYPE_FLOAT));
    codecParameters.set(new CodecParameter("key-string", "forty", CodecParameter.TYPE_STRING));
    codecParameters.set(
        new CodecParameter(
            "key-byte-buffer", ByteBuffer.wrap(testBytes), CodecParameter.TYPE_BYTE_BUFFER));

    Bundle bundle = codecParameters.toBundle();

    assertThat(bundle.getInt("key-int")).isEqualTo(10);
    assertThat(bundle.getLong("key-long")).isEqualTo(20L);
    assertThat(bundle.getFloat("key-float")).isEqualTo(30.0f);
    assertThat(bundle.getString("key-string")).isEqualTo("forty");
    assertThat(bundle.getByteArray("key-byte-buffer")).isEqualTo(testBytes);
  }

  @Test
  public void addToMediaFormat_withAllValueTypes_addsToFormat() {
    CodecParameters codecParameters = new CodecParameters();
    byte[] testBytes = new byte[] {1, 2, 3};
    codecParameters.set(new CodecParameter("key-int", 10, CodecParameter.TYPE_INT));
    codecParameters.set(new CodecParameter("key-long", 20L, CodecParameter.TYPE_LONG));
    codecParameters.set(new CodecParameter("key-float", 30.0f, CodecParameter.TYPE_FLOAT));
    codecParameters.set(new CodecParameter("key-string", "forty", CodecParameter.TYPE_STRING));
    codecParameters.set(
        new CodecParameter(
            "key-byte-buffer", ByteBuffer.wrap(testBytes), CodecParameter.TYPE_BYTE_BUFFER));
    MediaFormat mediaFormat = new MediaFormat();

    codecParameters.addToMediaFormat(mediaFormat);

    assertThat(mediaFormat.getInteger("key-int")).isEqualTo(10);
    assertThat(mediaFormat.getLong("key-long")).isEqualTo(20L);
    assertThat(mediaFormat.getFloat("key-float")).isEqualTo(30.0f);
    assertThat(mediaFormat.getString("key-string")).isEqualTo("forty");
    assertThat(mediaFormat.getByteBuffer("key-byte-buffer")).isEqualTo(ByteBuffer.wrap(testBytes));
  }

  @Test
  public void setFromMediaFormat_withFilter_extractsCorrectValues() {
    MediaFormat mediaFormat = new MediaFormat();
    mediaFormat.setInteger("key-int", 10);
    mediaFormat.setString("key-string", "hello");
    mediaFormat.setLong("key-long-ignored", 99L);
    ArrayList<String> filterKeys = new ArrayList<>();
    filterKeys.add("key-int");
    filterKeys.add("key-string");
    CodecParameters codecParameters = new CodecParameters();

    codecParameters.setFromMediaFormat(mediaFormat, filterKeys);

    assertThat(codecParameters.get().size()).isEqualTo(2);
    CodecParameter intParam = codecParameters.get("key-int");
    assertThat(intParam.value).isEqualTo(10);
    assertThat(intParam.valueType).isEqualTo(CodecParameter.TYPE_INT);
    CodecParameter stringParam = codecParameters.get("key-string");
    assertThat(stringParam.value).isEqualTo("hello");
    assertThat(stringParam.valueType).isEqualTo(CodecParameter.TYPE_STRING);
    assertThat(codecParameters.get("key-long-ignored")).isNull();
  }
}
