/*
 * Copyright 2024 The Android Open Source Project
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
package androidx.media3.exoplayer.hls;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import android.net.Uri;
import androidx.media3.common.AdPlaybackState.SkipInfo;
import androidx.media3.common.C;
import androidx.media3.datasource.ByteArrayDataSource;
import androidx.media3.exoplayer.hls.HlsInterstitialsAdsLoader.Asset;
import androidx.media3.exoplayer.hls.HlsInterstitialsAdsLoader.AssetList;
import androidx.media3.exoplayer.hls.HlsInterstitialsAdsLoader.StringAttribute;
import androidx.media3.exoplayer.upstream.ParsingLoadable;
import androidx.media3.test.utils.TestUtil;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.Charset;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class AssetListParserTest {

  @Test
  public void load() throws IOException {
    byte[] assetListBytes =
        ("{\"ASSETS\": [ "
                + "{\"URI\": \"http://1\", \"DURATION\":1.23},"
                + "{\"URI\": \"http://2\", \"DURATION\":2.34}"
                + "] }")
            .getBytes(Charset.defaultCharset());
    ParsingLoadable<AssetList> parsingLoadable =
        new ParsingLoadable<>(
            new ByteArrayDataSource(assetListBytes),
            Uri.EMPTY,
            C.DATA_TYPE_AD,
            new AssetListParser());

    parsingLoadable.load();

    assertThat(parsingLoadable.getResult().assets)
        .containsExactly(
            new Asset(Uri.parse("http://1"), /* durationUs= */ 1_230_000L),
            new Asset(Uri.parse("http://2"), /* durationUs= */ 2_340_000L))
        .inOrder();
  }

  @Test
  public void load_fileWithDisturbingJsonJunk_parsesCorrectly() throws IOException {
    byte[] assetListBytes =
        TestUtil.getByteArray(
            ApplicationProvider.getApplicationContext(),
            "media/hls/interstitials/x_asset_list_mixed_elements.json");
    ParsingLoadable<AssetList> parsingLoadable =
        new ParsingLoadable<>(
            new ByteArrayDataSource(assetListBytes),
            Uri.EMPTY,
            C.DATA_TYPE_AD,
            new AssetListParser());

    parsingLoadable.load();

    assertThat(parsingLoadable.getResult().assets)
        .containsExactly(
            new Asset(Uri.parse("http://1"), 12_123_000L),
            new Asset(Uri.parse("http://2"), 22_123_000L),
            new Asset(Uri.parse("http://3"), 32_122_999L),
            new Asset(Uri.parse("http://4"), 42_123_000L))
        .inOrder();
    assertThat(parsingLoadable.getResult().stringAttributes)
        .containsExactly(
            new StringAttribute("foo", "foo"),
            new StringAttribute("fooBar", "fooBar"),
            new StringAttribute("ASSETS", "stringValue"))
        .inOrder();
  }

  @Test
  public void load_withJsonArrayAsRoot_emptyResult() throws IOException {
    byte[] assetListBytes = "[]".getBytes(Charset.defaultCharset());
    ParsingLoadable<AssetList> parsingLoadable =
        new ParsingLoadable<>(
            new ByteArrayDataSource(assetListBytes),
            Uri.EMPTY,
            C.DATA_TYPE_AD,
            new AssetListParser());

    parsingLoadable.load();

    assertThat(parsingLoadable.getResult().assets).isEmpty();
    assertThat(parsingLoadable.getResult().stringAttributes).isEmpty();
  }

  @Test
  public void load_emptyInputStream_throwsEOFException() throws IOException {
    ParsingLoadable<AssetList> parsingLoadable =
        new ParsingLoadable<>(
            new ByteArrayDataSource(" ".getBytes(Charset.defaultCharset())),
            Uri.EMPTY,
            C.DATA_TYPE_AD,
            new AssetListParser());

    assertThrows(EOFException.class, parsingLoadable::load);
  }

  @Test
  public void load_withSkipControl_parsesCorrectly() throws IOException {
    byte[] assetListBytes =
        ("{\"ASSETS\": [ "
                + "{\"URI\": \"http://1\", \"DURATION\":1.23}"
                + "],"
                + "\"SKIP-CONTROL\": {"
                + "\"OFFSET\": 4.56,"
                + "\"DURATION\": 7.89,"
                + "\"LABEL-ID\": \"test-label\""
                + "}"
                + " }")
            .getBytes(Charset.defaultCharset());
    ParsingLoadable<AssetList> parsingLoadable =
        new ParsingLoadable<>(
            new ByteArrayDataSource(assetListBytes),
            Uri.EMPTY,
            C.DATA_TYPE_AD,
            new AssetListParser());

    parsingLoadable.load();

    AssetList result = parsingLoadable.getResult();
    assertThat(result.assets)
        .containsExactly(new Asset(Uri.parse("http://1"), /* durationUs= */ 1_230_000L));
    assertThat(result.skipInfo).isNotNull();
    assertThat(result.skipInfo.skipOffsetUs).isEqualTo(4_560_000L);
    assertThat(result.skipInfo.skipDurationUs).isEqualTo(7_890_000L);
    assertThat(result.skipInfo.labelId).isEqualTo("test-label");
  }

  @Test
  public void load_withSkipControlNoDuration_correctDefaultForOffset() throws IOException {
    byte[] assetListBytes =
        ("{\"ASSETS\": [ "
                + "{\"URI\": \"http://1\", \"DURATION\":1.23}"
                + "],"
                + "\"SKIP-CONTROL\": {"
                + "\"OFFSET\": 4.56"
                + "}"
                + " }")
            .getBytes(Charset.defaultCharset());
    ParsingLoadable<AssetList> parsingLoadable =
        new ParsingLoadable<>(
            new ByteArrayDataSource(assetListBytes),
            Uri.EMPTY,
            C.DATA_TYPE_AD,
            new AssetListParser());

    parsingLoadable.load();

    AssetList result = parsingLoadable.getResult();
    assertThat(result.assets)
        .containsExactly(new Asset(Uri.parse("http://1"), /* durationUs= */ 1_230_000L));
    assertThat(result.skipInfo).isNotNull();
    assertThat(result.skipInfo.skipOffsetUs).isEqualTo(4_560_000L);
    assertThat(result.skipInfo.skipDurationUs).isEqualTo(C.TIME_UNSET);
    assertThat(result.skipInfo.labelId).isNull();
  }

  @Test
  public void load_withSkipControlNoOffset_correctDefaultForOffsetIsZero() throws IOException {
    byte[] assetListBytes =
        ("{\"ASSETS\": [ "
                + "{\"URI\": \"http://1\", \"DURATION\":1.23}"
                + "],"
                + "\"SKIP-CONTROL\": {"
                + "\"DURATION\": 4.56"
                + "}"
                + " }")
            .getBytes(Charset.defaultCharset());
    ParsingLoadable<AssetList> parsingLoadable =
        new ParsingLoadable<>(
            new ByteArrayDataSource(assetListBytes),
            Uri.EMPTY,
            C.DATA_TYPE_AD,
            new AssetListParser());

    parsingLoadable.load();

    AssetList result = parsingLoadable.getResult();
    assertThat(result.assets)
        .containsExactly(new Asset(Uri.parse("http://1"), /* durationUs= */ 1_230_000L));
    assertThat(result.skipInfo).isNotNull();
    assertThat(result.skipInfo.skipOffsetUs).isEqualTo(0L); // show from the beginning
    assertThat(result.skipInfo.skipDurationUs).isEqualTo(4_560_000L);
    assertThat(result.skipInfo.labelId).isNull();
  }

  @Test
  public void load_withSkipControlInvalidOffset_useDefaultOffset() throws IOException {
    byte[] assetListBytes =
        ("{\"ASSETS\": [ "
                + "{\"URI\": \"http://1\", \"DURATION\":1.23}"
                + "],"
                + "\"SKIP-CONTROL\": {"
                + "\"OFFSET\": \"invalid\","
                + "\"DURATION\": 7.89,"
                + "\"LABEL-ID\": \"test-label\""
                + "}"
                + " }")
            .getBytes(Charset.defaultCharset());
    ParsingLoadable<AssetList> parsingLoadable =
        new ParsingLoadable<>(
            new ByteArrayDataSource(assetListBytes),
            Uri.EMPTY,
            C.DATA_TYPE_AD,
            new AssetListParser());

    parsingLoadable.load();

    AssetList result = parsingLoadable.getResult();
    assertThat(result.assets)
        .containsExactly(new Asset(Uri.parse("http://1"), /* durationUs= */ 1_230_000L));
    assertThat(result.skipInfo)
        .isEqualTo(
            new SkipInfo(/* skipOffsetUs= */ 0, /* skipDurationUs= */ 7_890_000L, "test-label"));
  }

  @Test
  public void load_withSkipControlMissingOffset_useOffsetDefault() throws IOException {
    byte[] assetListBytes =
        ("{\"ASSETS\": [ "
                + "{\"URI\": \"http://1\", \"DURATION\":1.23}"
                + "],"
                + "\"SKIP-CONTROL\": {"
                + "\"DURATION\": 7.89,"
                + "\"LABEL-ID\": \"test-label\""
                + "}"
                + " }")
            .getBytes(Charset.defaultCharset());
    ParsingLoadable<AssetList> parsingLoadable =
        new ParsingLoadable<>(
            new ByteArrayDataSource(assetListBytes),
            Uri.EMPTY,
            C.DATA_TYPE_AD,
            new AssetListParser());

    parsingLoadable.load();

    AssetList result = parsingLoadable.getResult();
    assertThat(result.assets)
        .containsExactly(new Asset(Uri.parse("http://1"), /* durationUs= */ 1_230_000L));
    assertThat(result.skipInfo)
        .isEqualTo(
            new SkipInfo(
                /* skipOffsetUs= */ 0,
                /* skipDurationUs= */ 7_890_000L,
                /* labelId= */ "test-label"));
  }

  @Test
  public void load_withSkipControlInvalidValues_parsesCorrectly() throws IOException {
    byte[] assetListBytes =
        ("{\"ASSETS\": [ "
                + "{\"URI\": \"http://1\", \"DURATION\":1.23}"
                + "],"
                + "\"SKIP-CONTROL\": {"
                + "\"OFFSET\": 0,"
                + "\"DURATION\": \"invalid type\","
                + "\"LABEL-ID\": -12"
                + "}"
                + " }")
            .getBytes(Charset.defaultCharset());
    ParsingLoadable<AssetList> parsingLoadable =
        new ParsingLoadable<>(
            new ByteArrayDataSource(assetListBytes),
            Uri.EMPTY,
            C.DATA_TYPE_AD,
            new AssetListParser());

    parsingLoadable.load();

    AssetList result = parsingLoadable.getResult();
    assertThat(result.assets)
        .containsExactly(new Asset(Uri.parse("http://1"), /* durationUs= */ 1_230_000L));
    assertThat(result.skipInfo).isNotNull();
    assertThat(result.skipInfo.skipOffsetUs).isEqualTo(0);
    assertThat(result.skipInfo.skipDurationUs).isEqualTo(C.TIME_UNSET);
    assertThat(result.skipInfo.labelId).isNull();
  }
}
