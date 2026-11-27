/*
 * Copyright 2026 The Android Open Source Project
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
package androidx.media3.docsamples.exoplayer;

import static androidx.media3.exoplayer.upstream.CmcdConfiguration.MODE_QUERY_PARAMETER;

import android.content.Context;
import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.upstream.CmcdConfiguration;
import androidx.media3.exoplayer.upstream.CmcdConfiguration.HeaderKey;
import com.google.common.collect.ImmutableListMultimap;
import java.util.UUID;

/** Snippets for CMCD. */
@SuppressWarnings({"unused", "CheckReturnValue", "PrivateConstructorForUtilityClass"})
public class Cmcd {

  @OptIn(markerClass = UnstableApi.class)
  public static void defaultCmcdConfiguration(Context context) {
    // [START default_cmcd_configuration]
    // Create media source factory and set default cmcdConfigurationFactory.
    MediaSource.Factory mediaSourceFactory =
        new DefaultMediaSourceFactory(context)
            .setCmcdConfigurationFactory(CmcdConfiguration.Factory.DEFAULT);
    // [END default_cmcd_configuration]
  }

  @OptIn(markerClass = UnstableApi.class)
  public static void customCmcdConfiguration(Context context) {
    // [START custom_cmcd_configuration]
    CmcdConfiguration.Factory cmcdConfigurationFactory =
        mediaItem -> {
          CmcdConfiguration.RequestConfig cmcdRequestConfig =
              new CmcdConfiguration.RequestConfig() {
                @Override
                public boolean isKeyAllowed(String key) {
                  return key.equals("br") || key.equals("bl");
                }

                @Override
                public ImmutableListMultimap<@HeaderKey String, String> getCustomData() {
                  return ImmutableListMultimap.of(
                      CmcdConfiguration.KEY_CMCD_OBJECT, "key1=stringValue");
                }

                @Override
                public int getRequestedMaximumThroughputKbps(int throughputKbps) {
                  return 5 * throughputKbps;
                }
              };

          String sessionId = UUID.randomUUID().toString();
          String contentId = UUID.randomUUID().toString();

          return new CmcdConfiguration(
              sessionId, contentId, cmcdRequestConfig, MODE_QUERY_PARAMETER);
        };

    // Create media source factory and set your custom cmcdConfigurationFactory.
    MediaSource.Factory mediaSourceFactory =
        new DefaultMediaSourceFactory(context)
            .setCmcdConfigurationFactory(cmcdConfigurationFactory);
    // [END custom_cmcd_configuration]
  }
}
