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

import android.content.ComponentName
import android.content.Context
import android.graphics.Matrix
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.cast.MediaRouteButtonViewProvider
import androidx.media3.common.C.TRACK_TYPE_TEXT
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Player.EVENT_AUDIO_ATTRIBUTES_CHANGED
import androidx.media3.common.Player.EVENT_DEVICE_VOLUME_CHANGED
import androidx.media3.common.Player.EVENT_IS_PLAYING_CHANGED
import androidx.media3.common.Player.EVENT_MEDIA_ITEM_TRANSITION
import androidx.media3.common.Player.EVENT_MEDIA_METADATA_CHANGED
import androidx.media3.common.Player.EVENT_METADATA
import androidx.media3.common.Player.EVENT_REPEAT_MODE_CHANGED
import androidx.media3.common.Player.EVENT_TIMELINE_CHANGED
import androidx.media3.common.Player.EVENT_TRACKS_CHANGED
import androidx.media3.common.Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Brightness
import androidx.media3.effect.MatrixTransformation
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.PlayerView
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.Transformer
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch

// private const val TAG = "PlayerActivity"
private const val TAG = "PlayerActivity-mytag"

class PlayerActivity : AppCompatActivity() {
  private lateinit var controllerFuture: ListenableFuture<MediaController>
  private lateinit var controller: MediaController

  private lateinit var playerView: PlayerView
  private lateinit var mediaItemListView: ListView
  private lateinit var mediaItemListAdapter: MediaItemListAdapter
  private val mediaItemList: MutableList<MediaItem> = mutableListOf()

  @OptIn(UnstableApi::class) // PlayerView.hideController
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    lifecycleScope.launch {
      lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
        try {
          initializeController()
          awaitCancellation()
        } finally {
          playerView.player = null
          releaseController()
        }
      }
    }

    setContentView(R.layout.activity_player)
    playerView = findViewById(R.id.player_view)

    mediaItemListView = findViewById(R.id.current_playing_list)
    mediaItemListAdapter = MediaItemListAdapter(this, R.layout.folder_items, mediaItemList)
    mediaItemListView.adapter = mediaItemListAdapter
    mediaItemListView.setOnItemClickListener { _, _, position, _ ->
      run {
        if (controller.currentMediaItemIndex == position) {
          controller.playWhenReady = !controller.playWhenReady
          if (controller.playWhenReady) {
            playerView.hideController()
          }
        } else {
          controller.seekToDefaultPosition(/* mediaItemIndex= */ position)
          mediaItemListAdapter.notifyDataSetChanged()
        }
      }
    }
  }

  private suspend fun initializeController() {
    controllerFuture =
      MediaController.Builder(
        this,
        SessionToken(this, ComponentName(this, PlaybackService::class.java)),
      )
      .buildAsync()
    updateMediaMetadataUI()
    setController()
  }

  private fun releaseController() {
    MediaController.releaseFuture(controllerFuture)
  }

  @OptIn(UnstableApi::class) // PlayerView.setShowSubtitleButton
  private suspend fun setController() {
    try {
      controller = controllerFuture.await()
    } catch (t: Throwable) {
      Log.w(TAG, "Failed to connect to MediaController", t)
      return
    }
    playerView.player = controller
    playerView.setMediaRouteButtonViewProvider(MediaRouteButtonViewProvider())

    updateCurrentPlaylistUI()
    updateMediaMetadataUI()
    playerView.setShowSubtitleButton(controller.currentTracks.isTypeSupported(TRACK_TYPE_TEXT))

    controller.addListener(
      object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
          if (events.contains(EVENT_TRACKS_CHANGED)) {
            playerView.setShowSubtitleButton(player.currentTracks.isTypeSupported(TRACK_TYPE_TEXT))
          }
          if (events.contains(EVENT_AUDIO_ATTRIBUTES_CHANGED)) {
            // Toast.makeText(getApplicationContext(), " audio attr chg ", 0).show()
          }
          if (events.contains(EVENT_METADATA)) {
            // Toast.makeText(getApplicationContext(), " meta data event ", 0).show()
          }
          if (events.contains(EVENT_TIMELINE_CHANGED)) {
            // Toast.makeText(getApplicationContext(), " time line change ", 0).show()
            updateCurrentPlaylistUI()
          }
          if (events.contains(EVENT_MEDIA_METADATA_CHANGED)) {
            // Toast.makeText(getApplicationContext(), "meta data change ", 0).show()
            updateMediaMetadataUI()
          }
          if (events.contains(EVENT_IS_PLAYING_CHANGED)) {
            // Toast.makeText(getApplicationContext(), "play pause ", 0).show()
          }
          if (events.contains(EVENT_DEVICE_VOLUME_CHANGED)) {
            Toast.makeText(getApplicationContext(), " dev vol change ", 0).show()
          }
          if (events.contains(EVENT_MEDIA_ITEM_TRANSITION)) {
            // Trigger adapter update to change highlight of current item.
            mediaItemListAdapter.notifyDataSetChanged()
          }

          if (events.contains(EVENT_REPEAT_MODE_CHANGED)) {
            // Toast.makeText(getApplicationContext(), " repeat chg ", 0).show()
            Log.i(TAG, " repeat ch")
            try {
              updateMediaFilter2(player)
            } catch (e: Exception) {
              // Logger.logDebug("mytag", e.toString())
              Log.e(TAG, e.toString())
            }
          }

          if (events.contains(EVENT_SHUFFLE_MODE_ENABLED_CHANGED)) {
            // Toast.makeText(getApplicationContext(), " shuff chg ", 0).show()
            Log.i(TAG, " shuff ch")
            try {
              updateMediaFilter(player)
            } catch (e: Exception) {
              Log.e(TAG, e.toString())
            }
          }

      }
    }
  )
}

  private fun updateMediaFilter(
    // player: MediaController
    player: Player 
  ) {
    val transformationMatrix = Matrix()
    transformationMatrix.postScale(-1f, 1f)
    val flipEffect = MatrixTransformation { _: Long -> transformationMatrix }
    // val effects = listOf(MatrixTransformation { _: Long -> transformationMatrix })

    val brightnessEffect = Brightness(0.5f) // Increase brightness
    val effects = listOf(brightnessEffect, flipEffect)
    // Applying to an ExoPlayer instance
    // exoPlayer.setVideoEffects(videoEffects)

    // exoPlayer.setVideoEffects(effects)

    val mediaItem = player.currentMediaItem ?: return
    val editedMediaItem = EditedMediaItem.Builder(mediaItem)
    .setEffects(Effects(listOf(), effects)) // audioEffects, videoEffects
    // .setVideoEffects(effects)
    .build()

    Log.e(TAG, "apply filter on the fly")
    Toast.makeText(getApplicationContext(), "applying filter ", 0).show()
    // player.stop()
    player.setMediaItem(editedMediaItem.mediaItem)
    // player.prepare();
    // player.play();
  }

  private fun updateMediaFilter2(
    player: Player 
  ) {
    val transformationMatrix = Matrix()
    transformationMatrix.postScale(-1f, 1f)
    val flipEffect = MatrixTransformation { _: Long -> transformationMatrix }

    val brightnessEffect = Brightness(0.5f) // Increase brightness
    val effects = listOf(brightnessEffect, flipEffect)
    // exoPlayer.setVideoEffects(effects)

    val mediaItem = player.currentMediaItem ?: return
    val editedMediaItem = EditedMediaItem.Builder(mediaItem)
    .setEffects(Effects(listOf(), effects)) // audioEffects, videoEffects
    // .setVideoEffects(effects)
    .build()

    Log.i(TAG, "stop apply filter play")
    Toast.makeText(getApplicationContext(), "applying filter 2 ", 0).show()
    player.stop()
    player.setMediaItem(editedMediaItem.mediaItem)
    player.prepare();
    player.play();
  }

  private fun updateMediaMetadataUI() {
    if (!::controller.isInitialized || controller.mediaItemCount == 0) {
      findViewById<TextView>(R.id.media_title).text = getString(R.string.waiting_for_metadata)
      findViewById<TextView>(R.id.media_artist).text = ""
      return
    }

    val mediaMetadata = controller.mediaMetadata
    val title: CharSequence = mediaMetadata.title ?: ""

    findViewById<TextView>(R.id.media_title).text = title
    findViewById<TextView>(R.id.media_artist).text = mediaMetadata.artist
  }

  private fun updateCurrentPlaylistUI() {
    if (!::controller.isInitialized) {
      return
    }
    mediaItemList.clear()
    for (i in 0 until controller.mediaItemCount) {
      mediaItemList.add(controller.getMediaItemAt(i))
    }
    mediaItemListAdapter.notifyDataSetChanged()
  }

  private inner class MediaItemListAdapter(
    context: Context,
    viewID: Int,
    mediaItemList: List<MediaItem>,
  ) : ArrayAdapter<MediaItem>(context, viewID, mediaItemList) {
    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
      val mediaItem = getItem(position)!!
      val returnConvertView =
      convertView ?: LayoutInflater.from(context).inflate(R.layout.playlist_items, parent, false)

      returnConvertView.findViewById<TextView>(R.id.media_item).text = mediaItem.mediaMetadata.title

      val deleteButton = returnConvertView.findViewById<Button>(R.id.delete_button)
      if (::controller.isInitialized && position == controller.currentMediaItemIndex) {
        // Styles for the current media item list item.
        returnConvertView.setBackgroundColor(
          ContextCompat.getColor(context, R.color.playlist_item_background)
        )
        returnConvertView
        .findViewById<TextView>(R.id.media_item)
        .setTextColor(ContextCompat.getColor(context, R.color.white))
        deleteButton.visibility = View.GONE
      } else {
        // Styles for any other media item list item.
        returnConvertView.setBackgroundColor(
          ContextCompat.getColor(context, R.color.player_background)
        )
        returnConvertView
        .findViewById<TextView>(R.id.media_item)
        .setTextColor(ContextCompat.getColor(context, R.color.white))
        deleteButton.visibility = View.VISIBLE
        deleteButton.setOnClickListener {
          controller.removeMediaItem(position)
          updateCurrentPlaylistUI()
        }
      }

      return returnConvertView
    }
  }
}
