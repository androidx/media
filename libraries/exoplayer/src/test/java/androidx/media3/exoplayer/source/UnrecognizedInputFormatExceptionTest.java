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
package androidx.media3.exoplayer.source;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.US_ASCII;

import android.net.Uri;
import androidx.media3.extractor.mp4.AtomSizeTooSmallSniffFailure;
import androidx.media3.extractor.mp4.UnsupportedBrandsSniffFailure;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import com.google.common.primitives.Ints;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class UnrecognizedInputFormatExceptionTest {

  @Test
  public void messageIncludesSniffFailureDetails() {
    Uri uri = Uri.parse("http://example.test/path.mp4");
    UnrecognizedInputFormatException exception =
        new UnrecognizedInputFormatException(
            "msg",
            uri,
            ImmutableList.of(
                new UnsupportedBrandsSniffFailure(
                    /* majorBrand= */ fourccToInt("abcd"),
                    /* compatibleBrands= */ new int[] {fourccToInt("efgh"), fourccToInt("ijkl")}),
                new AtomSizeTooSmallSniffFailure(
                    fourccToInt("free"), /* atomSize= */ 3, /* minimumHeaderSize= */ 8)));

    String message = exception.getMessage();

    assertThat(message).contains("abcd");
    assertThat(message).contains("efgh");
    assertThat(message).contains("ijkl");
    assertThat(message).contains("free");
    assertThat(message).contains("size=3");
  }

  private static int fourccToInt(String fourcc) {
    byte[] fourccBytes = fourcc.getBytes(US_ASCII);
    checkArgument(fourccBytes.length == 4);
    return Ints.fromByteArray(fourccBytes);
  }
}
