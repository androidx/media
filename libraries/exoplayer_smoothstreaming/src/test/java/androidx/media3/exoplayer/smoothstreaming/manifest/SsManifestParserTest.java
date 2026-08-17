/*
 * Copyright (C) 2016 The Android Open Source Project
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
package androidx.media3.exoplayer.smoothstreaming.manifest;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import android.net.Uri;
import androidx.media3.common.ParserException;
import androidx.media3.datasource.DataSourceInputStream;
import androidx.media3.datasource.DataSpec;
import androidx.media3.test.utils.FakeDataSet;
import androidx.media3.test.utils.FakeDataSource;
import androidx.media3.test.utils.TestUtil;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.IOException;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit tests for {@link SsManifestParser}. */
@RunWith(AndroidJUnit4.class)
public final class SsManifestParserTest {

  private static final String SAMPLE_ISMC_1 = "media/smooth-streaming/sample_ismc_1";
  private static final String SAMPLE_ISMC_2 = "media/smooth-streaming/sample_ismc_2";

  @Test
  public void parse_withUpstreamError_throwsUpstreamException() {
    SsManifestParser parser = new SsManifestParser();
    IOException expectedException = new IOException("expected");
    Uri uri = Uri.parse("http://test.test");
    DataSourceInputStream inputStream =
        new DataSourceInputStream(
            new FakeDataSource(
                new FakeDataSet()
                    .newData(uri)
                    .appendReadError(expectedException)
                    .appendReadData(/* length= */ 2)
                    .endData()),
            new DataSpec(uri));

    IOException thrownException =
        assertThrows(IOException.class, () -> parser.parse(uri, inputStream));
    assertThat(thrownException).isEqualTo(expectedException);
  }

  @Test
  public void parse_withMalformedManifest_throwsParserExceptionForMalformedContent() {
    SsManifestParser parser = new SsManifestParser();
    Uri uri = Uri.parse("http://test.test");
    DataSourceInputStream inputStream =
        new DataSourceInputStream(
            new FakeDataSource(new FakeDataSet().setRandomData(uri, /* length= */ 100)),
            new DataSpec(uri));

    ParserException thrownException =
        assertThrows(ParserException.class, () -> parser.parse(uri, inputStream));
    assertThat(thrownException.contentIsMalformed).isTrue();
  }

  /** Simple test to ensure the sample manifests parse without any exceptions being thrown. */
  @Test
  public void parseSmoothStreamingManifest() throws Exception {
    SsManifestParser parser = new SsManifestParser();
    parser.parse(
        Uri.parse("https://example.com/test.ismc"),
        TestUtil.getInputStream(ApplicationProvider.getApplicationContext(), SAMPLE_ISMC_1));
    parser.parse(
        Uri.parse("https://example.com/test.ismc"),
        TestUtil.getInputStream(ApplicationProvider.getApplicationContext(), SAMPLE_ISMC_2));
  }

  @Test
  public void parse_populatesFormatLabelWithStreamIndexName() throws Exception {
    SsManifestParser parser = new SsManifestParser();
    SsManifest ssManifest =
        parser.parse(
            Uri.parse("https://example.com/test.ismc"),
            TestUtil.getInputStream(ApplicationProvider.getApplicationContext(), SAMPLE_ISMC_1));

    assertThat(ssManifest.streamElements[0].formats[0].label).isEqualTo("video");
    assertThat(ssManifest.streamElements[0].formats[0].labels.get(0).value).isEqualTo("video");
  }
}
