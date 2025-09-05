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

import android.net.Uri;
import android.util.JsonReader;
import android.util.JsonToken;
import androidx.annotation.Nullable;
import androidx.media3.common.AdPlaybackState.SkipInfo;
import androidx.media3.common.C;
import androidx.media3.exoplayer.hls.HlsInterstitialsAdsLoader.Asset;
import androidx.media3.exoplayer.hls.HlsInterstitialsAdsLoader.AssetList;
import androidx.media3.exoplayer.hls.HlsInterstitialsAdsLoader.StringAttribute;
import androidx.media3.exoplayer.upstream.ParsingLoadable;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

/** Parses a X-ASSET-LIST JSON object. */
/* package */ final class AssetListParser implements ParsingLoadable.Parser<AssetList> {

  /** The asset name of the assets array in a X-ASSET-LIST JSON object. */
  private static final String ASSET_LIST_JSON_NAME_ASSET_ARRAY = "ASSETS";

  /** The asset URI name in a X-ASSET-LIST JSON object. */
  private static final String ASSET_LIST_JSON_NAME_URI = "URI";

  /** The asset duration name in a X-ASSET-LIST JSON object. */
  private static final String ASSET_LIST_JSON_NAME_DURATION = "DURATION";

  /** The skip control field name in a X-ASSET-LIST JSON object. */
  private static final String ASSET_LIST_JSON_NAME_SKIP_CONTROL = "SKIP-CONTROL";

  /** The offset field name in a SKIP-CONTROL JSON object. */
  private static final String ASSET_LIST_JSON_NAME_OFFSET = "OFFSET";

  /** The label ID field name in a SKIP-CONTROL JSON object. */
  private static final String ASSET_LIST_JSON_NAME_LABEL_ID = "LABEL-ID";

  @Override
  public AssetList parse(Uri uri, InputStream inputStream) throws IOException {
    try (JsonReader reader = new JsonReader(new InputStreamReader(inputStream))) {
      if (reader.peek() != JsonToken.BEGIN_OBJECT) {
        return AssetList.EMPTY;
      }
      ImmutableList.Builder<Asset> assets = new ImmutableList.Builder<>();
      ImmutableList.Builder<StringAttribute> stringAttributes = new ImmutableList.Builder<>();
      @Nullable SkipInfo skipInfo = null;
      reader.beginObject();
      while (reader.hasNext()) {
        JsonToken token = reader.peek();
        if (token.equals(JsonToken.NAME)) {
          String name = reader.nextName();
          if (name.equals(ASSET_LIST_JSON_NAME_ASSET_ARRAY)
              && reader.peek() == JsonToken.BEGIN_ARRAY) {
            parseAssetArray(reader, assets);
          } else if (name.equals(ASSET_LIST_JSON_NAME_SKIP_CONTROL)
              && reader.peek() == JsonToken.BEGIN_OBJECT) {
            skipInfo = parseSkipInfo(reader);
          } else if (reader.peek() == JsonToken.STRING) {
            stringAttributes.add(new StringAttribute(name, reader.nextString()));
          } else {
            reader.skipValue();
          }
        }
      }
      reader.endObject();
      return new AssetList(assets.build(), stringAttributes.build(), skipInfo);
    }
  }

  @Nullable
  private static SkipInfo parseSkipInfo(JsonReader reader) throws IOException {
    reader.beginObject();
    long offsetUs = C.TIME_UNSET;
    long durationUs = C.TIME_UNSET;
    @Nullable String labelId = null;
    while (reader.hasNext()) {
      String name = reader.nextName();
      if (name.equals(ASSET_LIST_JSON_NAME_OFFSET) && reader.peek() == JsonToken.NUMBER) {
        offsetUs = (long) (reader.nextDouble() * C.MICROS_PER_SECOND);
      } else if (name.equals(ASSET_LIST_JSON_NAME_DURATION) && reader.peek() == JsonToken.NUMBER) {
        durationUs = (long) (reader.nextDouble() * C.MICROS_PER_SECOND);
      } else if (name.equals(ASSET_LIST_JSON_NAME_LABEL_ID) && reader.peek() == JsonToken.STRING) {
        labelId = reader.nextString();
      } else {
        reader.skipValue();
      }
    }
    reader.endObject();
    return offsetUs == C.TIME_UNSET && durationUs == C.TIME_UNSET && labelId == null
        ? null
        : new SkipInfo(offsetUs, durationUs, labelId);
  }

  private static void parseAssetArray(JsonReader reader, ImmutableList.Builder<Asset> assets)
      throws IOException {
    reader.beginArray();
    while (reader.hasNext()) {
      if (reader.peek() == JsonToken.BEGIN_OBJECT) {
        parseAssetObject(reader, assets);
      }
    }
    reader.endArray();
  }

  private static void parseAssetObject(JsonReader reader, ImmutableList.Builder<Asset> assets)
      throws IOException {
    reader.beginObject();
    String uri = null;
    long duration = C.TIME_UNSET;
    String name;
    while (reader.hasNext()) {
      name = reader.nextName();
      if (name.equals(ASSET_LIST_JSON_NAME_URI) && reader.peek() == JsonToken.STRING) {
        uri = reader.nextString();
      } else if (name.equals(ASSET_LIST_JSON_NAME_DURATION) && reader.peek() == JsonToken.NUMBER) {
        duration = (long) (reader.nextDouble() * C.MICROS_PER_SECOND);
      } else {
        reader.skipValue();
      }
    }
    if (uri != null && duration != C.TIME_UNSET) {
      assets.add(new Asset(Uri.parse(uri), duration));
    }
    reader.endObject();
  }
}
