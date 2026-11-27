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
package androidx.media3.exoplayer.upstream.contentsteering;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import android.net.Uri;
import androidx.media3.common.C;
import androidx.media3.common.ParserException;
import androidx.media3.datasource.ByteArrayDataSource;
import androidx.media3.exoplayer.upstream.ParsingLoadable;
import androidx.media3.exoplayer.upstream.contentsteering.SteeringManifest.PathwayClone;
import androidx.media3.exoplayer.upstream.contentsteering.SteeringManifest.UriReplacement;
import androidx.media3.test.utils.TestUtil;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.EOFException;
import java.nio.charset.Charset;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Test for {@link SteeringManifestParser}. */
@RunWith(AndroidJUnit4.class)
public final class SteeringManifestParserTest {

  private static final String STEERING_MANIFEST_MIXED_ELEMENTS =
      "media/steering-manifests/steering_manifest_mixed_elements.json";

  @Test
  public void load_validJson_parsesCorrectly() throws Exception {
    byte[] steeringManifestBytes =
        TestUtil.getByteArray(
            ApplicationProvider.getApplicationContext(), STEERING_MANIFEST_MIXED_ELEMENTS);
    ParsingLoadable<SteeringManifest> parsingLoadable =
        new ParsingLoadable<>(
            new ByteArrayDataSource(steeringManifestBytes),
            Uri.EMPTY,
            C.DATA_TYPE_STEERING_MANIFEST,
            new SteeringManifestParser());
    PathwayClone expectedPathwayClone1 =
        new PathwayClone(
            /* baseId= */ "CDN-A",
            /* id= */ "CDN-A-CLONE",
            new UriReplacement(
                /* host= */ "backup2.example.com",
                /* params= */ ImmutableMap.of("token", "dkfs1239414"),
                /* perVariantUris= */ ImmutableMap.of(
                    "Video-128",
                    Uri.parse("https://q.example.com/video12/low/video.m3u8"),
                    "Video-768",
                    Uri.parse("https://q.example.com/video12/hi/video.m3u8")),
                /* perRenditionUris= */ ImmutableMap.of(
                    "Audio-37262", Uri.parse("https://q.example.com/video12/eng.m3u8"))));
    PathwayClone expectedPathwayClone2 =
        new PathwayClone(
            /* baseId= */ "CDN-B",
            /* id= */ "CDN-B-CLONE",
            new UriReplacement(
                /* host= */ "backup3.example.com",
                /* params= */ ImmutableMap.of("token", "dkfs1239415"),
                /* perVariantUris= */ ImmutableMap.of(),
                /* perRenditionUris= */ ImmutableMap.of()));
    PathwayClone expectedPathwayClone3 =
        new PathwayClone(
            /* baseId= */ "CDN-A",
            /* id= */ "CDN-A-CLONE-3",
            new UriReplacement(
                /* host= */ null,
                /* params= */ ImmutableMap.of(),
                /* perVariantUris= */ ImmutableMap.of(),
                /* perRenditionUris= */ ImmutableMap.of()));
    SteeringManifest expectedSteeringManifest =
        new SteeringManifest(
            /* version= */ 1,
            /* timeToLiveMs= */ 300_000L,
            /* reloadUri= */ Uri.parse("http://reload"),
            /* pathwayPriority= */ ImmutableList.of("CDN-A-CLONE", "CDN-B-CLONE", "CDN-A", "CDN-B"),
            /* pathwayClones= */ ImmutableList.of(
                expectedPathwayClone1, expectedPathwayClone2, expectedPathwayClone3));

    parsingLoadable.load();

    assertThat(parsingLoadable.getResult()).isEqualTo(expectedSteeringManifest);
  }

  @Test
  public void load_validJsonWithoutOptionalFields_parsesCorrectlyWithNullFields() throws Exception {
    byte[] steeringManifestBytes =
        "{\"VERSION\": 1, \"TTL\": 300, \"PATHWAY-PRIORITY\": [\"CDN-A\", \"CDN-B\"]}"
            .getBytes(Charset.defaultCharset());
    ParsingLoadable<SteeringManifest> parsingLoadable =
        new ParsingLoadable<>(
            new ByteArrayDataSource(steeringManifestBytes),
            Uri.EMPTY,
            C.DATA_TYPE_STEERING_MANIFEST,
            new SteeringManifestParser());

    parsingLoadable.load();

    SteeringManifest expectedSteeringManifest =
        new SteeringManifest(
            /* version= */ 1,
            /* timeToLiveMs= */ 300_000L,
            /* reloadUri= */ null,
            /* pathwayPriority= */ ImmutableList.of("CDN-A", "CDN-B"),
            /* pathwayClones= */ ImmutableList.of());
    assertThat(parsingLoadable.getResult()).isEqualTo(expectedSteeringManifest);
  }

  @Test
  public void load_missingVersionField_parsesCorrectlyWithDefaultVersion() throws Exception {
    byte[] steeringManifestBytes =
        "{\"TTL\": 300, \"PATHWAY-PRIORITY\": [\"CDN-A\", \"CDN-B\"]}"
            .getBytes(Charset.defaultCharset());
    ParsingLoadable<SteeringManifest> parsingLoadable =
        new ParsingLoadable<>(
            new ByteArrayDataSource(steeringManifestBytes),
            Uri.EMPTY,
            C.DATA_TYPE_STEERING_MANIFEST,
            new SteeringManifestParser());

    parsingLoadable.load();

    SteeringManifest expectedSteeringManifest =
        new SteeringManifest(
            /* version= */ 1,
            /* timeToLiveMs= */ 300_000L,
            /* reloadUri= */ null,
            /* pathwayPriority= */ ImmutableList.of("CDN-A", "CDN-B"),
            /* pathwayClones= */ ImmutableList.of());
    assertThat(parsingLoadable.getResult()).isEqualTo(expectedSteeringManifest);
  }

  @Test
  public void load_missingTtlField_parsesCorrectlyWithUnsetTtl() throws Exception {
    byte[] steeringManifestBytes =
        "{\"VERSION\": 1, \"PATHWAY-PRIORITY\": [\"CDN-A\", \"CDN-B\"]}"
            .getBytes(Charset.defaultCharset());
    ParsingLoadable<SteeringManifest> parsingLoadable =
        new ParsingLoadable<>(
            new ByteArrayDataSource(steeringManifestBytes),
            Uri.EMPTY,
            C.DATA_TYPE_STEERING_MANIFEST,
            new SteeringManifestParser());

    parsingLoadable.load();

    SteeringManifest expectedSteeringManifest =
        new SteeringManifest(
            /* version= */ 1,
            /* timeToLiveMs= */ C.TIME_UNSET,
            /* reloadUri= */ null,
            /* pathwayPriority= */ ImmutableList.of("CDN-A", "CDN-B"),
            /* pathwayClones= */ ImmutableList.of());
    assertThat(parsingLoadable.getResult()).isEqualTo(expectedSteeringManifest);
  }

  @Test
  public void load_missingPathwayPriorityArrayField_throwsParserException() {
    byte[] steeringManifestBytes =
        "{\"VERSION\": 1, \"TTL\": 300}".getBytes(Charset.defaultCharset());
    ParsingLoadable<SteeringManifest> parsingLoadable =
        new ParsingLoadable<>(
            new ByteArrayDataSource(steeringManifestBytes),
            Uri.EMPTY,
            C.DATA_TYPE_STEERING_MANIFEST,
            new SteeringManifestParser());

    ParserException exception = assertThrows(ParserException.class, parsingLoadable::load);
    assertThat(exception.dataType).isEqualTo(C.DATA_TYPE_STEERING_MANIFEST);
    assertThat(exception).hasMessageThat().contains("PATHWAY-PRIORITY field is missing");
  }

  @Test
  public void load_emptyPathwayPriorityArray_throwsParserException() {
    byte[] steeringManifestBytes =
        "{\"VERSION\": 1, \"TTL\": 300, \"PATHWAY-PRIORITY\": []}"
            .getBytes(Charset.defaultCharset());
    ParsingLoadable<SteeringManifest> parsingLoadable =
        new ParsingLoadable<>(
            new ByteArrayDataSource(steeringManifestBytes),
            Uri.EMPTY,
            C.DATA_TYPE_STEERING_MANIFEST,
            new SteeringManifestParser());

    ParserException exception = assertThrows(ParserException.class, parsingLoadable::load);
    assertThat(exception.dataType).isEqualTo(C.DATA_TYPE_STEERING_MANIFEST);
    assertThat(exception)
        .hasMessageThat()
        .contains("The PATHWAY-PRIORITY array is present but empty");
  }

  @Test
  public void load_duplicatedPathwayIdsInPathwayPriorityArray_throwsParserException() {
    byte[] steeringManifestBytes =
        "{\"VERSION\": 1, \"TTL\": 300, \"PATHWAY-PRIORITY\": [\"CDN-A\", \"CDN-A\"]}"
            .getBytes(Charset.defaultCharset());
    ParsingLoadable<SteeringManifest> parsingLoadable =
        new ParsingLoadable<>(
            new ByteArrayDataSource(steeringManifestBytes),
            Uri.EMPTY,
            C.DATA_TYPE_STEERING_MANIFEST,
            new SteeringManifestParser());

    ParserException exception = assertThrows(ParserException.class, parsingLoadable::load);
    assertThat(exception.dataType).isEqualTo(C.DATA_TYPE_STEERING_MANIFEST);
    assertThat(exception)
        .hasMessageThat()
        .contains(
            "The pathway ID (CDN-A) appears more than " + "once in the PATHWAY-PRIORITY array");
  }

  @Test
  public void load_emptyPathwayCloneArray_throwsParserException() {
    byte[] steeringManifestBytes =
        ("{"
                + "\"VERSION\": 1, "
                + "\"TTL\": 300, "
                + "\"RELOAD-URI\": "
                + "\"http://reload\", "
                + "\"PATHWAY-PRIORITY\": [\"CDN-A-CLONE\", \"CDN-A\"], "
                + "\"PATHWAY-CLONES\": []}")
            .getBytes(Charset.defaultCharset());

    ParsingLoadable<SteeringManifest> parsingLoadable =
        new ParsingLoadable<>(
            new ByteArrayDataSource(steeringManifestBytes),
            Uri.EMPTY,
            C.DATA_TYPE_STEERING_MANIFEST,
            new SteeringManifestParser());

    ParserException exception = assertThrows(ParserException.class, parsingLoadable::load);
    assertThat(exception.dataType).isEqualTo(C.DATA_TYPE_STEERING_MANIFEST);
    assertThat(exception)
        .hasMessageThat()
        .contains("The PATHWAY-CLONES array is present but empty");
  }

  @Test
  public void load_missingBaseIdInPathwayClone_throwsParserException() {
    byte[] steeringManifestBytes =
        ("{"
                + "\"VERSION\": 1, "
                + "\"TTL\": 300, "
                + "\"RELOAD-URI\": "
                + "\"http://reload\", "
                + "\"PATHWAY-PRIORITY\": [\"CDN-A-CLONE\", \"CDN-A\"], "
                + "\"PATHWAY-CLONES\": [{"
                + "\"ID\": \"CDN-A-CLONE\", "
                + "\"URI-REPLACEMENT\": {"
                + "\"HOST\": \"backup3.example.com\", "
                + "\"PARAMS\": {"
                + "\"token\": \"dkfs1239417\"}"
                + "}"
                + "}"
                + "]}")
            .getBytes(Charset.defaultCharset());

    ParsingLoadable<SteeringManifest> parsingLoadable =
        new ParsingLoadable<>(
            new ByteArrayDataSource(steeringManifestBytes),
            Uri.EMPTY,
            C.DATA_TYPE_STEERING_MANIFEST,
            new SteeringManifestParser());

    ParserException exception = assertThrows(ParserException.class, parsingLoadable::load);
    assertThat(exception.dataType).isEqualTo(C.DATA_TYPE_STEERING_MANIFEST);
    assertThat(exception)
        .hasMessageThat()
        .contains("BASE-ID field is missing in a PATHWAY-CLONE object");
  }

  @Test
  public void load_missingIdInPathwayClone_throwsParserException() {
    byte[] steeringManifestBytes =
        ("{"
                + "\"VERSION\": 1, "
                + "\"TTL\": 300, "
                + "\"RELOAD-URI\": \"http://reload\", "
                + "\"PATHWAY-PRIORITY\": [\"CDN-A-CLONE\", \"CDN-A\"], "
                + "\"PATHWAY-CLONES\": [{"
                + "\"BASE-ID\": \"CDN-A\", "
                + "\"URI-REPLACEMENT\": {"
                + "\"HOST\": \"backup3.example.com\", "
                + "\"PARAMS\": {"
                + "\"token\": \"dkfs1239417\"}"
                + "}"
                + "}"
                + "]}")
            .getBytes(Charset.defaultCharset());

    ParsingLoadable<SteeringManifest> parsingLoadable =
        new ParsingLoadable<>(
            new ByteArrayDataSource(steeringManifestBytes),
            Uri.EMPTY,
            C.DATA_TYPE_STEERING_MANIFEST,
            new SteeringManifestParser());

    ParserException exception = assertThrows(ParserException.class, parsingLoadable::load);
    assertThat(exception.dataType).isEqualTo(C.DATA_TYPE_STEERING_MANIFEST);
    assertThat(exception)
        .hasMessageThat()
        .contains("ID field is missing in a PATHWAY-CLONE object");
  }

  @Test
  public void load_missingUriReplacementInPathwayClone_throwsParserException() {
    byte[] steeringManifestBytes =
        ("{"
                + "\"VERSION\": 1, "
                + "\"TTL\": 300, "
                + "\"RELOAD-URI\": \"http://reload\", "
                + "\"PATHWAY-PRIORITY\": [\"CDN-A-CLONE\", \"CDN-A\"], "
                + "\"PATHWAY-CLONES\": [{"
                + "\"BASE-ID\": \"CDN-A\", "
                + "\"ID\": \"CDN-A-CLONE\""
                + "}"
                + "]}")
            .getBytes(Charset.defaultCharset());

    ParsingLoadable<SteeringManifest> parsingLoadable =
        new ParsingLoadable<>(
            new ByteArrayDataSource(steeringManifestBytes),
            Uri.EMPTY,
            C.DATA_TYPE_STEERING_MANIFEST,
            new SteeringManifestParser());

    ParserException exception = assertThrows(ParserException.class, parsingLoadable::load);
    assertThat(exception.dataType).isEqualTo(C.DATA_TYPE_STEERING_MANIFEST);
    assertThat(exception)
        .hasMessageThat()
        .contains("URI-REPLACEMENT field is missing in a PATHWAY-CLONE object");
  }

  @Test
  public void load_withJsonArrayAsRoot_throwsParserException() {
    byte[] assetListBytes = "[]".getBytes(Charset.defaultCharset());
    ParsingLoadable<SteeringManifest> parsingLoadable =
        new ParsingLoadable<>(
            new ByteArrayDataSource(assetListBytes),
            Uri.EMPTY,
            C.DATA_TYPE_STEERING_MANIFEST,
            new SteeringManifestParser());

    ParserException exception = assertThrows(ParserException.class, parsingLoadable::load);
    assertThat(exception.dataType).isEqualTo(C.DATA_TYPE_STEERING_MANIFEST);
    assertThat(exception)
        .hasMessageThat()
        .contains("Steering manifest JSON should be an object at root");
  }

  @Test
  public void load_emptyInputStream_throwsEOFException() {
    ParsingLoadable<SteeringManifest> parsingLoadable =
        new ParsingLoadable<>(
            new ByteArrayDataSource(" ".getBytes(Charset.defaultCharset())),
            Uri.EMPTY,
            C.DATA_TYPE_STEERING_MANIFEST,
            new SteeringManifestParser());

    assertThrows(EOFException.class, parsingLoadable::load);
  }
}
