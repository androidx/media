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
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertThrows;

import android.net.Uri;
import android.util.Pair;
import androidx.media3.common.AdPlaybackState.SkipInfo;
import androidx.media3.common.C;
import androidx.media3.common.ParserException;
import androidx.media3.datasource.ByteArrayDataSource;
import androidx.media3.exoplayer.hls.HlsInterstitialsAdsLoader.Asset;
import androidx.media3.exoplayer.hls.HlsInterstitialsAdsLoader.AssetList;
import androidx.media3.exoplayer.upstream.ParsingLoadable;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.IOException;
import org.json.JSONObject;
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
            .getBytes(UTF_8);
    ParsingLoadable<Pair<AssetList, JSONObject>> parsingLoadable =
        new ParsingLoadable<>(
            new ByteArrayDataSource(assetListBytes),
            Uri.EMPTY,
            C.DATA_TYPE_AD,
            new AssetListParser());

    parsingLoadable.load();

    assertThat(parsingLoadable.getResult().first.assets)
        .containsExactly(
            new Asset(Uri.parse("http://1"), /* durationUs= */ 1_230_000L),
            new Asset(Uri.parse("http://2"), /* durationUs= */ 2_340_000L))
        .inOrder();
  }

  @Test
  public void load_withJsonArrayAsRoot_throwsParserException() {
    byte[] assetListBytes = "[]".getBytes(UTF_8);
    ParsingLoadable<Pair<AssetList, JSONObject>> parsingLoadable =
        new ParsingLoadable<>(
            new ByteArrayDataSource(assetListBytes),
            Uri.EMPTY,
            C.DATA_TYPE_AD,
            new AssetListParser());

    ParserException parserException = assertThrows(ParserException.class, parsingLoadable::load);

    assertThat(parserException)
        .hasMessageThat()
        .isEqualTo(
            "Value [] of type org.json.JSONArray cannot be converted to JSONObject"
                + " {contentIsMalformed=true, dataType=4}");
  }

  @Test
  public void load_missingAssetListAttribute_throwsParserException() {
    byte[] assetListBytes = "{}".getBytes(UTF_8);
    ParsingLoadable<Pair<AssetList, JSONObject>> parsingLoadable =
        new ParsingLoadable<>(
            new ByteArrayDataSource(assetListBytes),
            Uri.EMPTY,
            C.DATA_TYPE_AD,
            new AssetListParser());

    ParserException parserException = assertThrows(ParserException.class, parsingLoadable::load);

    assertThat(parserException)
        .hasMessageThat()
        .contains("missing ASSETS attribute {contentIsMalformed=true, dataType=4}");
  }

  @Test
  public void load_missingAssetUri_throwsParserException() throws IOException {
    byte[] assetListBytes =
        ("{\"ASSETS\": [ " + "{\"DURATION\": 12.0 }" + "]" + "}").getBytes(UTF_8);
    ParsingLoadable<Pair<AssetList, JSONObject>> parsingLoadable =
        new ParsingLoadable<>(
            new ByteArrayDataSource(assetListBytes),
            Uri.EMPTY,
            C.DATA_TYPE_AD,
            new AssetListParser());

    ParserException parserException = assertThrows(ParserException.class, parsingLoadable::load);

    assertThat(parserException)
        .hasMessageThat()
        .contains("missing URI attribute {contentIsMalformed=true, dataType=4}");
  }

  @Test
  public void load_missingAssetDuration_throwsParserException() throws IOException {
    byte[] assetListBytes =
        ("{\"ASSETS\": [ " + "{\"URI\": \"http://1\"}" + "]" + "}").getBytes(UTF_8);
    ParsingLoadable<Pair<AssetList, JSONObject>> parsingLoadable =
        new ParsingLoadable<>(
            new ByteArrayDataSource(assetListBytes),
            Uri.EMPTY,
            C.DATA_TYPE_AD,
            new AssetListParser());

    ParserException parserException = assertThrows(ParserException.class, parsingLoadable::load);

    assertThat(parserException)
        .hasMessageThat()
        .contains("missing DURATION attribute {contentIsMalformed=true, dataType=4}");
  }

  @Test
  public void load_emptyInputStream_throwsParserException() {
    ParsingLoadable<Pair<AssetList, JSONObject>> parsingLoadable =
        new ParsingLoadable<>(
            new ByteArrayDataSource(" ".getBytes(UTF_8)),
            Uri.EMPTY,
            C.DATA_TYPE_AD,
            new AssetListParser());

    ParserException parserException = assertThrows(ParserException.class, parsingLoadable::load);

    assertThat(parserException).hasMessageThat().contains("{contentIsMalformed=true, dataType=4}");
  }

  @Test
  public void load_emptyAssetArrayWithSkipControl_parsesCorrectly() throws IOException {
    byte[] assetListBytes =
        ("{\"ASSETS\": [],"
                + "\"SKIP-CONTROL\": {"
                + "\"OFFSET\": 4.56,"
                + "\"DURATION\": 7.89,"
                + "\"LABEL-ID\": \"test-label\""
                + "}"
                + " }")
            .getBytes(UTF_8);
    ParsingLoadable<Pair<AssetList, JSONObject>> parsingLoadable =
        new ParsingLoadable<>(
            new ByteArrayDataSource(assetListBytes),
            Uri.EMPTY,
            C.DATA_TYPE_AD,
            new AssetListParser());

    parsingLoadable.load();

    AssetList result = parsingLoadable.getResult().first;
    assertThat(result.assets).isEmpty();
    assertThat(result.skipInfo).isNotNull();
    assertThat(result.skipInfo.skipOffsetUs).isEqualTo(4_560_000L);
    assertThat(result.skipInfo.skipDurationUs).isEqualTo(7_890_000L);
    assertThat(result.skipInfo.labelId).isEqualTo("test-label");
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
            .getBytes(UTF_8);
    ParsingLoadable<Pair<AssetList, JSONObject>> parsingLoadable =
        new ParsingLoadable<>(
            new ByteArrayDataSource(assetListBytes),
            Uri.EMPTY,
            C.DATA_TYPE_AD,
            new AssetListParser());

    parsingLoadable.load();

    AssetList result = parsingLoadable.getResult().first;
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
            .getBytes(UTF_8);
    ParsingLoadable<Pair<AssetList, JSONObject>> parsingLoadable =
        new ParsingLoadable<>(
            new ByteArrayDataSource(assetListBytes),
            Uri.EMPTY,
            C.DATA_TYPE_AD,
            new AssetListParser());

    parsingLoadable.load();

    AssetList assetList = parsingLoadable.getResult().first;
    assertThat(assetList.assets)
        .containsExactly(new Asset(Uri.parse("http://1"), /* durationUs= */ 1_230_000L));
    assertThat(assetList.skipInfo).isNotNull();
    assertThat(assetList.skipInfo.skipOffsetUs).isEqualTo(4_560_000L);
    assertThat(assetList.skipInfo.skipDurationUs).isEqualTo(C.TIME_UNSET);
    assertThat(assetList.skipInfo.labelId).isNull();
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
            .getBytes(UTF_8);
    ParsingLoadable<Pair<AssetList, JSONObject>> parsingLoadable =
        new ParsingLoadable<>(
            new ByteArrayDataSource(assetListBytes),
            Uri.EMPTY,
            C.DATA_TYPE_AD,
            new AssetListParser());

    parsingLoadable.load();

    AssetList result = parsingLoadable.getResult().first;
    assertThat(result.assets)
        .containsExactly(new Asset(Uri.parse("http://1"), /* durationUs= */ 1_230_000L));
    assertThat(result.skipInfo).isNotNull();
    assertThat(result.skipInfo.skipOffsetUs).isEqualTo(0L); // show from the beginning
    assertThat(result.skipInfo.skipDurationUs).isEqualTo(4_560_000L);
    assertThat(result.skipInfo.labelId).isNull();
  }

  @Test
  public void load_withSkipControlInvalidOffset_throwParserException() {
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
            .getBytes(UTF_8);
    ParsingLoadable<Pair<AssetList, JSONObject>> parsingLoadable =
        new ParsingLoadable<>(
            new ByteArrayDataSource(assetListBytes),
            Uri.EMPTY,
            C.DATA_TYPE_AD,
            new AssetListParser());

    ParserException parserException = assertThrows(ParserException.class, parsingLoadable::load);

    assertThat(parserException)
        .hasMessageThat()
        .contains(
            "Value invalid at OFFSET of type java.lang.String cannot be converted to double"
                + " {contentIsMalformed=true, dataType=4}");
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
            .getBytes(UTF_8);
    ParsingLoadable<Pair<AssetList, JSONObject>> parsingLoadable =
        new ParsingLoadable<>(
            new ByteArrayDataSource(assetListBytes),
            Uri.EMPTY,
            C.DATA_TYPE_AD,
            new AssetListParser());

    parsingLoadable.load();

    AssetList result = parsingLoadable.getResult().first;
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
  public void load_withSkipControlInvalidDuration_throwsParserException() {
    byte[] assetListBytes =
        ("{\"ASSETS\": [ "
                + "{\"URI\": \"http://1\", \"DURATION\":1.23}"
                + "],"
                + "\"SKIP-CONTROL\": {"
                + "\"OFFSET\": 0,"
                + "\"DURATION\": \"invalid type\","
                + "}"
                + " }")
            .getBytes(UTF_8);
    ParsingLoadable<Pair<AssetList, JSONObject>> parsingLoadable =
        new ParsingLoadable<>(
            new ByteArrayDataSource(assetListBytes),
            Uri.EMPTY,
            C.DATA_TYPE_AD,
            new AssetListParser());

    ParserException parserException = assertThrows(ParserException.class, parsingLoadable::load);

    assertThat(parserException).hasMessageThat().contains("{contentIsMalformed=true, dataType=4}");
  }

  @Test
  public void load_withCustomJsonData_parsesCorrectly() throws Exception {
    byte[] assetListBytes =
        ("{\"ASSETS\": [ "
                + "{\"URI\": \"http://1\", \"DURATION\":1.23}"
                + "],"
                + "\"fooBarString\": \"test-string\","
                + "\"fooBarNumber\": 1.23,"
                + "\"fooBarBoolean\": false,"
                + "\"fooBarArray\": [\"foo\", \"bar\"],"
                + "\"fooObject\": {"
                + "\"fooNumber\": 4.56,"
                + "\"fooBoolean\": true,"
                + "\"fooString\": \"test-string\""
                + "}"
                + " }")
            .getBytes(UTF_8);
    ParsingLoadable<Pair<AssetList, JSONObject>> parsingLoadable =
        new ParsingLoadable<>(
            new ByteArrayDataSource(assetListBytes),
            Uri.EMPTY,
            C.DATA_TYPE_AD,
            new AssetListParser());

    parsingLoadable.load();

    AssetList assetList = parsingLoadable.getResult().first;
    assertThat(assetList.assets).hasSize(1);
    JSONObject rawJson = parsingLoadable.getResult().second;
    assertThat(rawJson.toString())
        .isEqualTo(new JSONObject(new String(assetListBytes, UTF_8)).toString());
  }
}
