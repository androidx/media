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
@file:Suppress("unused_parameter", "unused_variable", "unused", "CheckReturnValue")

package androidx.media3.docsamples.exoplayer

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.CmcdConfiguration
import androidx.media3.exoplayer.upstream.CmcdConfiguration.MODE_QUERY_PARAMETER
import com.google.common.collect.ImmutableListMultimap
import java.util.UUID

// Snippets for CMCD.

object CmcdKt {

  @OptIn(UnstableApi::class)
  fun defaultCmcdConfiguration(context: Context) {
    // [START default_cmcd_configuration]
    // Create media source factory and set default cmcdConfigurationFactory.
    val mediaSourceFactory =
      DefaultMediaSourceFactory(context)
        .setCmcdConfigurationFactory(CmcdConfiguration.Factory.DEFAULT)
    // [END default_cmcd_configuration]
  }

  @OptIn(UnstableApi::class)
  fun customCmcdConfiguration(context: Context) {
    // [START custom_cmcd_configuration]
    val cmcdConfigurationFactory =
      object : CmcdConfiguration.Factory {
        override fun createCmcdConfiguration(mediaItem: MediaItem): CmcdConfiguration {
          val cmcdRequestConfig =
            object : CmcdConfiguration.RequestConfig {
              override fun isKeyAllowed(key: String): Boolean {
                return key == "br" || key == "bl"
              }

              override fun getCustomData():
                ImmutableListMultimap<@CmcdConfiguration.HeaderKey String, String> {
                return ImmutableListMultimap.of(
                  CmcdConfiguration.KEY_CMCD_OBJECT,
                  "key1=stringValue",
                )
              }

              override fun getRequestedMaximumThroughputKbps(throughputKbps: Int): Int {
                return 5 * throughputKbps
              }
            }

          val sessionId = UUID.randomUUID().toString()
          val contentId = UUID.randomUUID().toString()

          return CmcdConfiguration(sessionId, contentId, cmcdRequestConfig, MODE_QUERY_PARAMETER)
        }
      }

    // Create media source factory and set your custom cmcdConfigurationFactory.
    val mediaSourceFactory =
      DefaultMediaSourceFactory(context).setCmcdConfigurationFactory(cmcdConfigurationFactory)
    // [END custom_cmcd_configuration]
  }
}
