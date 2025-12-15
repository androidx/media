/*
 * Copyright 2021 The Android Open Source Project
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
package androidx.media3.demo.session

import android.app.PendingIntent
import android.app.PendingIntent.FLAG_IMMUTABLE
import android.app.PendingIntent.FLAG_UPDATE_CURRENT
import android.app.PendingIntent.getActivity
import android.content.Intent
import androidx.core.app.TaskStackBuilder

class PlaybackService : DemoPlaybackService() {

  override fun getSingleTopActivity(): PendingIntent? {
    return getActivity(
      this,
      0,
      Intent(this, PlayerActivity::class.java),
      FLAG_IMMUTABLE or FLAG_UPDATE_CURRENT,
    )
  }

  override fun getBackStackedActivity(): PendingIntent? {
    return TaskStackBuilder.create(this).run {
      addNextIntent(Intent(this@PlaybackService, MainActivity::class.java))
      addNextIntent(Intent(this@PlaybackService, PlayerActivity::class.java))
      getPendingIntent(0, FLAG_IMMUTABLE or FLAG_UPDATE_CURRENT)
    }
  }
}
