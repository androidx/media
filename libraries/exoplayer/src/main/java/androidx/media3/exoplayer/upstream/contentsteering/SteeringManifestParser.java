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

import android.net.Uri;
import android.util.JsonReader;
import android.util.JsonToken;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.ParserException;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.upstream.ParsingLoadable;
import androidx.media3.exoplayer.upstream.contentsteering.SteeringManifest.PathwayClone;
import androidx.media3.exoplayer.upstream.contentsteering.SteeringManifest.UriReplacement;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

/** Parses a steering manifest JSON object. */
@UnstableApi
public final class SteeringManifestParser implements ParsingLoadable.Parser<SteeringManifest> {

  /** The version field name in a steering manifest JSON object. */
  private static final String STEERING_MANIFEST_JSON_NAME_VERSION = "VERSION";

  /** The ttl field name in a steering manifest JSON object. */
  private static final String STEERING_MANIFEST_JSON_NAME_TTL = "TTL";

  /** The reload URI field name in a steering manifest JSON object. */
  private static final String STEERING_MANIFEST_JSON_NAME_RELOAD_URI = "RELOAD-URI";

  /** The pathway priority field name in a steering manifest JSON object. */
  private static final String STEERING_MANIFEST_JSON_NAME_PATHWAY_PRIORITY = "PATHWAY-PRIORITY";

  /** The pathway clones field name in a steering manifest JSON object. */
  private static final String STEERING_MANIFEST_JSON_NAME_PATHWAY_CLONES = "PATHWAY-CLONES";

  /** The base ID field name in a pathway clone JSON object. */
  private static final String STEERING_MANIFEST_JSON_NAME_BASE_ID = "BASE-ID";

  /** The ID field name in a pathway clone JSON object. */
  private static final String STEERING_MANIFEST_JSON_NAME_ID = "ID";

  /** The uri replacement field name in a pathway clone JSON object. */
  private static final String STEERING_MANIFEST_JSON_NAME_URI_REPLACEMENT = "URI-REPLACEMENT";

  /** The host field name in a uri replacement JSON object. */
  private static final String STEERING_MANIFEST_JSON_NAME_HOST = "HOST";

  /** The params field name in a uri replacement JSON object. */
  private static final String STEERING_MANIFEST_JSON_NAME_PARAMS = "PARAMS";

  /** The per variant URIs field name in a uri replacement JSON object. */
  private static final String STEERING_MANIFEST_JSON_NAME_PER_VARIANT_URIS = "PER-VARIANT-URIS";

  /** The per rendition URIs field name in a uri replacement JSON object. */
  private static final String STEERING_MANIFEST_JSON_NAME_PER_RENDITION_URIS = "PER-RENDITION-URIS";

  @Override
  public SteeringManifest parse(Uri uri, InputStream inputStream) throws IOException {
    try (JsonReader reader = new JsonReader(new InputStreamReader(inputStream))) {
      if (!reader.peek().equals(JsonToken.BEGIN_OBJECT)) {
        throw ParserException.createForMalformedSteeringManifest(
            "Steering manifest JSON should be an object at root", /* cause= */ null);
      }
      int version = 1; // Default version == 1.
      long timeToLiveMs = C.TIME_UNSET;
      @Nullable Uri reloadUri = null;
      ImmutableList.Builder<String> pathwayPriority = new ImmutableList.Builder<>();
      ImmutableList.Builder<PathwayClone> pathwayClones = new ImmutableList.Builder<>();
      reader.beginObject();
      while (reader.hasNext()) {
        String name = reader.nextName();
        if (name.equals(STEERING_MANIFEST_JSON_NAME_VERSION)
            && reader.peek().equals(JsonToken.NUMBER)) {
          version = reader.nextInt();
        } else if (name.equals(STEERING_MANIFEST_JSON_NAME_TTL)
            && reader.peek().equals(JsonToken.NUMBER)) {
          timeToLiveMs = reader.nextInt() * 1000L;
        } else if (name.equals(STEERING_MANIFEST_JSON_NAME_RELOAD_URI)
            && reader.peek().equals(JsonToken.STRING)) {
          reloadUri = Uri.parse(reader.nextString());
        } else if (name.equals(STEERING_MANIFEST_JSON_NAME_PATHWAY_PRIORITY)
            && reader.peek().equals(JsonToken.BEGIN_ARRAY)) {
          parsePathwayPriorityArray(reader, pathwayPriority);
        } else if (name.equals(STEERING_MANIFEST_JSON_NAME_PATHWAY_CLONES)
            && reader.peek().equals(JsonToken.BEGIN_ARRAY)) {
          parsePathwayClonesArray(reader, pathwayClones);
        } else {
          reader.skipValue();
        }
      }
      reader.endObject();
      ImmutableList<String> pathwayPriorityList = pathwayPriority.build();
      if (pathwayPriorityList.isEmpty()) {
        throw ParserException.createForMalformedSteeringManifest(
            "PATHWAY-PRIORITY field is missing", /* cause= */ null);
      }
      return new SteeringManifest(
          version, timeToLiveMs, reloadUri, pathwayPriorityList, pathwayClones.build());
    }
  }

  private static PathwayClone parsePathwayClone(JsonReader reader) throws IOException {
    reader.beginObject();
    @Nullable String baseId = null;
    @Nullable String id = null;
    @Nullable UriReplacement uriReplacement = null;
    while (reader.hasNext()) {
      String name = reader.nextName();
      if (name.equals(STEERING_MANIFEST_JSON_NAME_BASE_ID)
          && reader.peek().equals(JsonToken.STRING)) {
        baseId = reader.nextString();
      } else if (name.equals(STEERING_MANIFEST_JSON_NAME_ID)
          && reader.peek().equals(JsonToken.STRING)) {
        id = reader.nextString();
      } else if (name.equals(STEERING_MANIFEST_JSON_NAME_URI_REPLACEMENT)
          && reader.peek().equals(JsonToken.BEGIN_OBJECT)) {
        uriReplacement = parseUriReplacement(reader);
      } else {
        reader.skipValue();
      }
    }
    reader.endObject();
    if (baseId == null) {
      throw ParserException.createForMalformedSteeringManifest(
          "BASE-ID field is missing in a PATHWAY-CLONE object", /* cause= */ null);
    }
    if (id == null) {
      throw ParserException.createForMalformedSteeringManifest(
          "ID field is missing in a PATHWAY-CLONE object", /* cause= */ null);
    }
    if (uriReplacement == null) {
      throw ParserException.createForMalformedSteeringManifest(
          "URI-REPLACEMENT field is missing in a PATHWAY-CLONE object", /* cause= */ null);
    }
    return new PathwayClone(baseId, id, uriReplacement);
  }

  private static UriReplacement parseUriReplacement(JsonReader reader) throws IOException {
    reader.beginObject();
    @Nullable String host = null;
    Map<String, String> params = new HashMap<>();
    Map<String, Uri> perVariantUris = new HashMap<>();
    Map<String, Uri> perRenditionUris = new HashMap<>();
    while (reader.hasNext()) {
      String name = reader.nextName();
      if (name.equals(STEERING_MANIFEST_JSON_NAME_HOST) && reader.peek().equals(JsonToken.STRING)) {
        host = reader.nextString();
        if (host.isEmpty()) {
          throw ParserException.createForMalformedSteeringManifest(
              "The HOST string is present but empty", /* cause= */ null);
        }
      } else if (name.equals(STEERING_MANIFEST_JSON_NAME_PARAMS)
          && reader.peek().equals(JsonToken.BEGIN_OBJECT)) {
        parseMap(reader, string -> string, params);
      } else if (name.equals(STEERING_MANIFEST_JSON_NAME_PER_VARIANT_URIS)
          && reader.peek().equals(JsonToken.BEGIN_OBJECT)) {
        parseMap(reader, Uri::parse, perVariantUris);
      } else if (name.equals(STEERING_MANIFEST_JSON_NAME_PER_RENDITION_URIS)
          && reader.peek().equals(JsonToken.BEGIN_OBJECT)) {
        parseMap(reader, Uri::parse, perRenditionUris);
      } else {
        reader.skipValue();
      }
    }
    reader.endObject();
    return new UriReplacement(host, params, perVariantUris, perRenditionUris);
  }

  private static void parsePathwayClonesArray(
      JsonReader reader, ImmutableList.Builder<PathwayClone> pathwayClones) throws IOException {
    reader.beginArray();
    boolean hasElement = false;
    while (reader.hasNext()) {
      if (reader.peek().equals(JsonToken.BEGIN_OBJECT)) {
        PathwayClone pathwayClone = parsePathwayClone(reader);
        pathwayClones.add(pathwayClone);
        hasElement = true;
      } else {
        reader.skipValue();
      }
    }
    reader.endArray();
    if (!hasElement) {
      throw ParserException.createForMalformedSteeringManifest(
          "The PATHWAY-CLONES array is present but empty", /* cause= */ null);
    }
  }

  private static void parsePathwayPriorityArray(
      JsonReader reader, ImmutableList.Builder<String> pathwayPriority) throws IOException {
    HashSet<String> pathwayIdSet = new HashSet<>();
    reader.beginArray();
    while (reader.hasNext()) {
      if (reader.peek().equals(JsonToken.STRING)) {
        String pathwayId = reader.nextString();
        if (!pathwayIdSet.add(pathwayId)) {
          throw ParserException.createForMalformedSteeringManifest(
              "The pathway ID ("
                  + pathwayId
                  + ") appears more than once in the PATHWAY-PRIORITY array",
              /* cause= */ null);
        }
        pathwayPriority.add(pathwayId);
      } else {
        reader.skipValue();
      }
    }
    reader.endArray();
    if (pathwayIdSet.isEmpty()) {
      throw ParserException.createForMalformedSteeringManifest(
          "The PATHWAY-PRIORITY array is present but empty", /* cause= */ null);
    }
  }

  private static <T> void parseMap(
      JsonReader reader, StringConverter<T> stringConverter, Map<String, T> map)
      throws IOException {
    reader.beginObject();
    while (reader.hasNext()) {
      String name = reader.nextName();
      if (reader.peek().equals(JsonToken.STRING)) {
        String value = reader.nextString();
        map.put(name, stringConverter.convert(value));
      } else {
        reader.skipValue();
      }
    }
    reader.endObject();
  }

  private interface StringConverter<T> {
    T convert(String string);
  }
}
