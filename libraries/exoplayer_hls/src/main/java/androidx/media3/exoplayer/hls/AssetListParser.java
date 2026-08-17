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

import static com.google.common.io.ByteStreams.toByteArray;
import static java.nio.charset.StandardCharsets.UTF_8;

import android.net.Uri;
import android.util.Pair;
import androidx.annotation.Nullable;
import androidx.media3.common.AdPlaybackState.SkipInfo;
import androidx.media3.common.C;
import androidx.media3.common.ParserException;
import androidx.media3.exoplayer.hls.HlsInterstitialsAdsLoader.Asset;
import androidx.media3.exoplayer.hls.HlsInterstitialsAdsLoader.AssetList;
import androidx.media3.exoplayer.upstream.ParsingLoadable;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.io.InputStream;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Parses a X-ASSET-LIST JSON object. */
/* package */ final class AssetListParser
    implements ParsingLoadable.Parser<Pair<AssetList, JSONObject>> {

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
  public Pair<AssetList, JSONObject> parse(Uri uri, InputStream inputStream) throws IOException {
    try {
      JSONObject jsonObject = new JSONObject(new String(toByteArray(inputStream), UTF_8));
      return new Pair<>(getAssetListFromRawJson(jsonObject), jsonObject);
    } catch (IOException | JSONException e) {
      throw ParserException.createForMalformedManifest(e.getMessage(), e);
    }
  }

  private static AssetList getAssetListFromRawJson(JSONObject jsonObject) throws JSONException {
    if (!jsonObject.has(ASSET_LIST_JSON_NAME_ASSET_ARRAY)) {
      throw new JSONException("missing " + ASSET_LIST_JSON_NAME_ASSET_ARRAY + " attribute");
    }
    ImmutableList.Builder<Asset> assets = new ImmutableList.Builder<>();
    @Nullable SkipInfo skipInfo = null;
    JSONArray jsonArray = jsonObject.getJSONArray(ASSET_LIST_JSON_NAME_ASSET_ARRAY);
    for (int i = 0; i < jsonArray.length(); i++) {
      JSONObject assetObject = jsonArray.getJSONObject(i);
      if (!assetObject.has(ASSET_LIST_JSON_NAME_URI)) {
        throw new JSONException("missing " + ASSET_LIST_JSON_NAME_URI + " attribute");
      }
      if (!assetObject.has(ASSET_LIST_JSON_NAME_DURATION)) {
        throw new JSONException("missing " + ASSET_LIST_JSON_NAME_DURATION + " attribute");
      }
      Uri assetUri = Uri.parse(assetObject.getString(ASSET_LIST_JSON_NAME_URI));
      long durationUs =
          (long) (assetObject.getDouble(ASSET_LIST_JSON_NAME_DURATION) * C.MICROS_PER_SECOND);
      assets.add(new Asset(assetUri, durationUs));
    }

    if (jsonObject.has(ASSET_LIST_JSON_NAME_SKIP_CONTROL)) {
      JSONObject skipControlObject = jsonObject.getJSONObject(ASSET_LIST_JSON_NAME_SKIP_CONTROL);
      long offsetUs = 0;
      if (skipControlObject.has(ASSET_LIST_JSON_NAME_OFFSET)) {
        offsetUs =
            (long) (skipControlObject.getDouble(ASSET_LIST_JSON_NAME_OFFSET) * C.MICROS_PER_SECOND);
      }
      long durationUs = C.TIME_UNSET;
      if (skipControlObject.has(ASSET_LIST_JSON_NAME_DURATION)) {
        durationUs =
            (long)
                (skipControlObject.getDouble(ASSET_LIST_JSON_NAME_DURATION) * C.MICROS_PER_SECOND);
      }
      @Nullable String labelId = null;
      if (skipControlObject.has(ASSET_LIST_JSON_NAME_LABEL_ID)) {
        labelId = skipControlObject.getString(ASSET_LIST_JSON_NAME_LABEL_ID);
      }
      skipInfo = new SkipInfo(offsetUs, durationUs, labelId);
    }
    return new AssetList(assets.build(), skipInfo);
  }
}
