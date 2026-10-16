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

import android.annotation.SuppressLint
import android.content.ComponentName
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.cast.MediaRouteButtonViewProvider
import androidx.media3.common.C
import androidx.media3.common.C.TRACK_TYPE_TEXT
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Player.EVENT_MEDIA_ITEM_TRANSITION
import androidx.media3.common.Player.EVENT_MEDIA_METADATA_CHANGED
import androidx.media3.common.Player.EVENT_TRACKS_CHANGED
import androidx.media3.common.Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch

private const val TAG = "PlayerActivity"

class PlayerActivity : AppCompatActivity() {
  private lateinit var controllerFuture: ListenableFuture<MediaController>
  private lateinit var controller: MediaController

  private lateinit var playerView: PlayerView
  private lateinit var mediaItemListView: RecyclerView
  private lateinit var mediaItemListAdapter: MediaItemListAdapter
  private val mediaItemList: MutableList<MediaItem> = mutableListOf()
  private val mediaItemDragCallback = MediaItemDragCallback()
  private val itemTouchHelper = ItemTouchHelper(mediaItemDragCallback)

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
    mediaItemListAdapter = MediaItemListAdapter()
    mediaItemListView.layoutManager = LinearLayoutManager(this)
    mediaItemListView.adapter = mediaItemListAdapter
    val divider = DividerItemDecoration(this, DividerItemDecoration.VERTICAL)
    divider.setDrawable(checkNotNull(ContextCompat.getDrawable(this, R.drawable.divider)))
    mediaItemListView.addItemDecoration(divider)
    itemTouchHelper.attachToRecyclerView(mediaItemListView)
  }

  @OptIn(UnstableApi::class) // PlayerView.hideController
  private fun onMediaItemClicked(position: Int) {
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
        override fun onTimelineChanged(
          timeline: Timeline,
          reason: @Player.TimelineChangeReason Int,
        ) {
          if (reason == TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) {
            updateCurrentPlaylistUI()
          }
        }

        override fun onEvents(player: Player, events: Player.Events) {
          if (events.contains(EVENT_TRACKS_CHANGED)) {
            playerView.setShowSubtitleButton(player.currentTracks.isTypeSupported(TRACK_TYPE_TEXT))
          }
          if (events.contains(EVENT_MEDIA_METADATA_CHANGED)) {
            updateMediaMetadataUI()
          }
          if (events.contains(EVENT_MEDIA_ITEM_TRANSITION) && !mediaItemDragCallback.isDragging) {
            // Trigger adapter update to change highlight of current item.
            mediaItemListAdapter.notifyDataSetChanged()
          }
        }
      }
    )
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

  private inner class MediaItemListAdapter : RecyclerView.Adapter<MediaItemViewHolder>() {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MediaItemViewHolder {
      val itemView =
        LayoutInflater.from(parent.context).inflate(R.layout.playlist_items, parent, false)
      return MediaItemViewHolder(itemView)
    }

    override fun onBindViewHolder(holder: MediaItemViewHolder, position: Int) {
      holder.bind(position)
    }

    override fun getItemCount(): Int = mediaItemList.size
  }

  // The drag handle isn't accessible, but accessibility actions to reorder items are added in
  // bind().
  @SuppressLint("ClickableViewAccessibility")
  private inner class MediaItemViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
    private val titleView: TextView = itemView.findViewById(R.id.media_item)
    private val deleteButton: Button = itemView.findViewById(R.id.delete_button)
    private var moveUpActionId = View.NO_ID
    private var moveDownActionId = View.NO_ID

    init {
      itemView.setOnClickListener {
        val position = bindingAdapterPosition
        if (position != RecyclerView.NO_POSITION) {
          onMediaItemClicked(position)
        }
      }
      deleteButton.setOnClickListener {
        val position = bindingAdapterPosition
        if (position != RecyclerView.NO_POSITION) {
          controller.removeMediaItem(position)
        }
      }
      // Touching the drag handle starts dragging immediately. Long pressing anywhere else on the
      // item also starts dragging.
      itemView.findViewById<View>(R.id.drag_handle).setOnTouchListener { _, event ->
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
          itemTouchHelper.startDrag(this)
          true
        } else {
          false
        }
      }
    }

    fun bind(position: Int) {
      titleView.text = mediaItemList[position].mediaMetadata.title
      if (::controller.isInitialized && position == controller.currentMediaItemIndex) {
        // Styles for the current media item list item.
        itemView.setBackgroundColor(
          ContextCompat.getColor(this@PlayerActivity, R.color.playlist_item_background)
        )
        deleteButton.visibility = View.GONE
      } else {
        // Styles for any other media item list item.
        itemView.setBackgroundColor(
          ContextCompat.getColor(this@PlayerActivity, R.color.player_background)
        )
        deleteButton.visibility = View.VISIBLE
      }

      // Let accessibility services reorder items without dragging.
      ViewCompat.removeAccessibilityAction(itemView, moveUpActionId)
      ViewCompat.removeAccessibilityAction(itemView, moveDownActionId)
      moveUpActionId =
        if (position > 0) addMoveAccessibilityAction(R.string.move_up, offset = -1) else View.NO_ID
      moveDownActionId =
        if (position < mediaItemList.lastIndex) {
          addMoveAccessibilityAction(R.string.move_down, offset = 1)
        } else {
          View.NO_ID
        }
    }

    private fun addMoveAccessibilityAction(@StringRes labelResId: Int, offset: Int): Int =
      ViewCompat.addAccessibilityAction(itemView, getString(labelResId)) { _, _ ->
        val position = bindingAdapterPosition
        val canMove =
          position != RecyclerView.NO_POSITION && position + offset in mediaItemList.indices
        if (canMove) {
          controller.moveMediaItem(position, position + offset)
        }
        canMove
      }
  }

  private inner class MediaItemDragCallback :
    ItemTouchHelper.SimpleCallback(
      /* dragDirs= */ ItemTouchHelper.UP or ItemTouchHelper.DOWN,
      /* swipeDirs= */ 0,
    ) {

    var isDragging = false
      private set

    private var draggingFromPosition = C.INDEX_UNSET
    private var draggingToPosition = C.INDEX_UNSET

    override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
      super.onSelectedChanged(viewHolder, actionState)
      if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && viewHolder != null) {
        isDragging = true
        // Lift the dragged item. Its style is restored when it's rebound after the drag.
        viewHolder.itemView.setBackgroundColor(
          ContextCompat.getColor(this@PlayerActivity, R.color.playlist_item_dragged_background)
        )
      }
    }

    override fun onMove(
      recyclerView: RecyclerView,
      viewHolder: RecyclerView.ViewHolder,
      target: RecyclerView.ViewHolder,
    ): Boolean {
      val fromPosition = viewHolder.bindingAdapterPosition
      val toPosition = target.bindingAdapterPosition
      if (fromPosition == RecyclerView.NO_POSITION || toPosition == RecyclerView.NO_POSITION) {
        return false
      }
      if (draggingFromPosition == C.INDEX_UNSET) {
        draggingFromPosition = fromPosition
      }
      draggingToPosition = toPosition
      mediaItemList.add(toPosition, mediaItemList.removeAt(fromPosition))
      mediaItemListAdapter.notifyItemMoved(fromPosition, toPosition)
      return true
    }

    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
      // Swiping is disabled.
    }

    override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
      super.clearView(recyclerView, viewHolder)
      isDragging = false
      if (
        draggingFromPosition != C.INDEX_UNSET &&
          draggingToPosition != C.INDEX_UNSET &&
          draggingFromPosition != draggingToPosition
      ) {
        controller.moveMediaItem(draggingFromPosition, draggingToPosition)
      } else {
        mediaItemListAdapter.notifyDataSetChanged()
      }
      draggingFromPosition = C.INDEX_UNSET
      draggingToPosition = C.INDEX_UNSET
    }
  }
}
