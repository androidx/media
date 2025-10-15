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

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.datastore.core.DataStore
import androidx.datastore.core.Serializer
import androidx.datastore.dataStore
import androidx.media3.cast.CastPlayer
import androidx.media3.common.AudioAttributes
import androidx.media3.common.Player
import androidx.media3.common.listenTo
import androidx.media3.common.util.UnstableApi
import androidx.media3.demo.session.service.R
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.util.EventLogger
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSession.ControllerInfo
import com.google.protobuf.ByteString
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch

open class DemoPlaybackService : MediaLibraryService() {

  private lateinit var mediaLibrarySession: MediaLibrarySession

  companion object {
    private const val NOTIFICATION_ID = 123
    private const val CHANNEL_ID = "demo_session_notification_channel_id"
  }

  object PreferenceDataStore {
    private val Context._dataStore: DataStore<Preferences> by
      dataStore(
        fileName = "preferences.pb",
        serializer =
          object : Serializer<Preferences> {
            override val defaultValue: Preferences = Preferences.getDefaultInstance()

            override suspend fun readFrom(input: InputStream): Preferences =
              Preferences.parseFrom(input)

            override suspend fun writeTo(preferences: Preferences, output: OutputStream) =
              preferences.writeTo(output)
          },
      )

    fun get(context: Context) = context.applicationContext._dataStore
  }

  /**
   * Returns the single top session activity. It is used by the notification when the app task is
   * active and an activity is in the fore or background.
   *
   * Tapping the notification then typically should trigger a single top activity. This way, the
   * user navigates to the previous activity when pressing back.
   *
   * If null is returned, [MediaSession.setSessionActivity] is not set by the demo service.
   */
  open fun getSingleTopActivity(): PendingIntent? = null

  /**
   * Returns a back stacked session activity that is used by the notification when the service is
   * running standalone as a foreground service. This is typically the case after the app has been
   * dismissed from the recent tasks, or after automatic playback resumption.
   *
   * Typically, a playback activity should be started with a stack of activities underneath. This
   * way, when pressing back, the user doesn't land on the home screen of the device, but on an
   * activity defined in the back stack.
   *
   * See [androidx.core.app.TaskStackBuilder] to construct a back stack.
   *
   * If null is returned, [MediaSession.setSessionActivity] is not set by the demo service.
   */
  open fun getBackStackedActivity(): PendingIntent? = null

  /**
   * Creates the library session callback to implement the domain logic. Can be overridden to return
   * an alternative callback, for example a subclass of [DemoMediaLibrarySessionCallback].
   *
   * This method is called when the session is built by the [DemoPlaybackService].
   */
  protected open fun createLibrarySessionCallback(): MediaLibrarySession.Callback {
    return DemoMediaLibrarySessionCallback(this)
  }

  @OptIn(UnstableApi::class) // MediaSessionService.setListener
  override fun onCreate() {
    super.onCreate()
    initializeSessionAndPlayer()
    setListener(MediaSessionServiceListener())
  }

  override fun onGetSession(controllerInfo: ControllerInfo): MediaLibrarySession {
    return mediaLibrarySession
  }

  // MediaSession.setSessionActivity
  // MediaSessionService.clearListener
  @OptIn(UnstableApi::class)
  override fun onDestroy() {
    getBackStackedActivity()?.let { mediaLibrarySession.setSessionActivity(it) }
    mediaLibrarySession.release()
    mediaLibrarySession.player.release()
    clearListener()
    super.onDestroy()
  }

  @OptIn(UnstableApi::class) // Player.listen
  private fun initializeSessionAndPlayer() {
    val player = buildPlayer()
    CoroutineScope(Dispatchers.Unconfined).launch {
      player.listenTo(Player.EVENT_IS_PLAYING_CHANGED, Player.EVENT_MEDIA_ITEM_TRANSITION) {
        storeCurrentMediaItem()
      }
    }

    mediaLibrarySession =
      MediaLibrarySession.Builder(this, player, createLibrarySessionCallback())
        .also { builder -> getSingleTopActivity()?.let { builder.setSessionActivity(it) } }
        .build()
  }

  @OptIn(UnstableApi::class)
  protected open fun buildPlayer(): Player {
    val exoPlayer =
      ExoPlayer.Builder(this)
        .setAudioAttributes(AudioAttributes.DEFAULT, /* handleAudioFocus= */ true)
        .build()
    exoPlayer.addAnalyticsListener(EventLogger())
    return CastPlayer.Builder(/* context= */ this).setLocalPlayer(exoPlayer).build()
  }

  @OptIn(UnstableApi::class) // BitmapLoader
  private fun storeCurrentMediaItem() {
    val mediaID = mediaLibrarySession.player.currentMediaItem?.mediaId
    if (mediaID == null) {
      return
    }
    val artworkUri = mediaLibrarySession.player.currentMediaItem?.mediaMetadata?.artworkUri
    val positionMs = mediaLibrarySession.player.currentPosition
    val durationMs = mediaLibrarySession.player.duration
    CoroutineScope(Dispatchers.IO).launch {
      PreferenceDataStore.get(this@DemoPlaybackService).updateData { preferences ->
        val builder =
          preferences
            .toBuilder()
            .setMediaId(mediaID)
            .setPositionMs(positionMs)
            .setDurationMs(durationMs)
        val artworkUriString = artworkUri?.toString() ?: ""
        if (artworkUriString != preferences.artworkOriginalUri) {
          builder.setArtworkOriginalUri(artworkUriString)
          if (artworkUri == null) {
            builder.setArtworkData(ByteString.EMPTY)
          } else {
            try {
              val bitmap = mediaLibrarySession.bitmapLoader.loadBitmap(artworkUri).await()
              if (bitmap != null) {
                val outputStream = ByteString.newOutput()
                bitmap.compress(Bitmap.CompressFormat.PNG, /* quality= */ 90, outputStream)
                builder.setArtworkData(outputStream.toByteString())
              }
            } catch (e: Exception) {
              // Bitmap loading failed. Do nothing.
            }
          }
        }
        builder.build()
      }
    }
  }

  suspend fun retrieveLastStoredMediaItem(): Preferences? {
    val preferences = PreferenceDataStore.get(this).data.first()
    return if (preferences != Preferences.getDefaultInstance()) preferences else null
  }

  @OptIn(UnstableApi::class) // MediaSessionService.Listener
  private inner class MediaSessionServiceListener : Listener {

    /**
     * This method is only required to be implemented on Android 12 or above when an attempt is made
     * by a media controller to resume playback when the {@link MediaSessionService} is in the
     * background.
     */
    override fun onForegroundServiceStartNotAllowedException() {
      if (
        Build.VERSION.SDK_INT >= 33 &&
          checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
      ) {
        // Notification permission is required but not granted
        return
      }
      val notificationManagerCompat = NotificationManagerCompat.from(this@DemoPlaybackService)
      ensureNotificationChannel(notificationManagerCompat)
      val builder =
        NotificationCompat.Builder(this@DemoPlaybackService, CHANNEL_ID)
          .setSmallIcon(R.drawable.media3_notification_small_icon)
          .setContentTitle(getString(R.string.notification_content_title))
          .setStyle(
            NotificationCompat.BigTextStyle().bigText(getString(R.string.notification_content_text))
          )
          .setPriority(NotificationCompat.PRIORITY_DEFAULT)
          .setAutoCancel(true)
          .also { builder -> getBackStackedActivity()?.let { builder.setContentIntent(it) } }
      notificationManagerCompat.notify(NOTIFICATION_ID, builder.build())
    }
  }

  private fun ensureNotificationChannel(notificationManagerCompat: NotificationManagerCompat) {
    if (
      Build.VERSION.SDK_INT < 26 ||
        notificationManagerCompat.getNotificationChannel(CHANNEL_ID) != null
    ) {
      return
    }

    val channel =
      NotificationChannel(
        CHANNEL_ID,
        getString(R.string.notification_channel_name),
        NotificationManager.IMPORTANCE_DEFAULT,
      )
    notificationManagerCompat.createNotificationChannel(channel)
  }
}
