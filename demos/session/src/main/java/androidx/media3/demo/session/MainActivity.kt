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
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.cast.MediaRouteButtonFactory
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaBrowser
import androidx.media3.session.SessionToken
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch

private const val TAG = "MainActivity"

class MainActivity : AppCompatActivity() {
  private var browser: MediaBrowser? = null

  private lateinit var mediaListAdapter: FolderMediaItemArrayAdapter
  private lateinit var mediaListView: ListView
  private val treePathStack: ArrayDeque<MediaItem> = ArrayDeque()
  private var subItemMediaList: MutableList<MediaItem> = mutableListOf()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    lifecycleScope.launch {
      lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
        try {
          initializeBrowser()
          awaitCancellation()
        } finally {
          releaseBrowser()
        }
      }
    }
    // setting up the layout
    setContentView(R.layout.activity_main)
    mediaListView = findViewById(R.id.media_list_view)
    mediaListAdapter = FolderMediaItemArrayAdapter(this, R.layout.folder_items, subItemMediaList)
    mediaListView.adapter = mediaListAdapter

    // setting up on click. When user click on an item, try to display it
    mediaListView.setOnItemClickListener { _, _, position, _ ->
      val selectedMediaItem = checkNotNull(mediaListAdapter.getItem(position))
      // TODO(b/192235359): handle the case where the item is playable but it is not a folder
      if (selectedMediaItem.mediaMetadata.isPlayable == true) {
        val intent = PlayableFolderActivity.createIntent(this, selectedMediaItem.mediaId)
        startActivity(intent)
      } else {
        lifecycleScope.launch { pushPathStack(selectedMediaItem) }
      }
    }

    findViewById<ExtendedFloatingActionButton>(R.id.open_player_floating_button)
      .setOnClickListener {
        // Start the session activity that shows the playback activity. The System UI uses the same
        // intent in the same way to start the activity from the notification.
        browser?.sessionActivity?.send()
      }

    onBackPressedDispatcher.addCallback(
      object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
          lifecycleScope.launch { popPathStack() }
        }
      }
    )

    if (
      Build.VERSION.SDK_INT >= 33 &&
        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
          PackageManager.PERMISSION_GRANTED
    ) {
      requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), /* requestCode= */ 0)
    }
  }

  @OptIn(UnstableApi::class) // MediaRouteButtonFactory is unstable API.
  override fun onCreateOptionsMenu(menu: Menu): Boolean {
    super.onCreateOptionsMenu(menu)
    getMenuInflater().inflate(R.menu.menu, menu)
    MediaRouteButtonFactory.setUpMediaRouteButton(this, menu, R.id.cast_menu_item)
    return true
  }

  override fun onOptionsItemSelected(item: MenuItem): Boolean {
    if (item.itemId == android.R.id.home) {
      onBackPressedDispatcher.onBackPressed()
      return true
    }
    return super.onOptionsItemSelected(item)
  }

  override fun onRequestPermissionsResult(
    requestCode: Int,
    permissions: Array<out String>,
    grantResults: IntArray,
  ) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    if (grantResults.isEmpty()) {
      // Empty results are triggered if a permission is requested while another request was already
      // pending and can be safely ignored in this case.
      return
    }
    if (grantResults[0] != PackageManager.PERMISSION_GRANTED) {
      Toast.makeText(applicationContext, R.string.notification_permission_denied, Toast.LENGTH_LONG)
        .show()
    }
  }

  private suspend fun initializeBrowser() {
    val browser =
      try {
        MediaBrowser.Builder(
            this,
            SessionToken(this, ComponentName(this, PlaybackService::class.java)),
          )
          .buildAsync()
          .await()
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.w(TAG, "Failed to connect to MediaBrowser", e)
        return
      }
    this.browser = browser
    pushRoot(browser)
  }

  private fun releaseBrowser() {
    browser?.release()
    browser = null
  }

  private suspend fun displayChildrenList(mediaItem: MediaItem) {
    val browser = this.browser ?: return

    supportActionBar!!.setDisplayHomeAsUpEnabled(treePathStack.size != 1)

    val result =
      browser
        .getChildren(
          mediaItem.mediaId,
          /* page= */ 0,
          /* pageSize= */ Int.MAX_VALUE,
          /* params= */ null,
        )
        .await()
    subItemMediaList.clear()
    if (result.resultCode == LibraryResult.RESULT_SUCCESS) {
      subItemMediaList.addAll(checkNotNull(result.value))
    } else {
      Log.w(TAG, "Failed to get children for ${mediaItem.mediaId}: ${result.resultCode}")
    }
    mediaListAdapter.notifyDataSetChanged()
  }

  private suspend fun pushPathStack(mediaItem: MediaItem) {
    treePathStack.addLast(mediaItem)
    displayChildrenList(treePathStack.last())
  }

  private suspend fun popPathStack() {
    treePathStack.removeLast()
    if (treePathStack.isEmpty()) {
      finish()
      return
    }

    displayChildrenList(treePathStack.last())
  }

  private suspend fun pushRoot(browser: MediaBrowser) {
    // browser can be initialized many times
    // only push root at the first initialization
    if (!treePathStack.isEmpty()) {
      return
    }
    val result = browser.getLibraryRoot(/* params= */ null).await()
    if (result.resultCode == LibraryResult.RESULT_SUCCESS) {
      val root = checkNotNull(result.value)
      pushPathStack(root)
    } else {
      Log.w(TAG, "Failed to get library root: ${result.resultCode}")
    }
  }

  private class FolderMediaItemArrayAdapter(
    context: Context,
    viewID: Int,
    mediaItemList: List<MediaItem>,
  ) : ArrayAdapter<MediaItem>(context, viewID, mediaItemList) {
    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
      val mediaItem = getItem(position)!!
      val returnConvertView =
        convertView ?: LayoutInflater.from(context).inflate(R.layout.folder_items, parent, false)

      returnConvertView.findViewById<TextView>(R.id.media_item).text = mediaItem.mediaMetadata.title
      return returnConvertView
    }
  }
}
