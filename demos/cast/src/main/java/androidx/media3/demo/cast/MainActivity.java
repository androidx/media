/*
 * Copyright (C) 2017 The Android Open Source Project
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
package androidx.media3.demo.cast;

import android.content.Context;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.ColorUtils;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.util.Assertions;
import androidx.media3.common.util.Util;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.RecyclerView.ViewHolder;
import com.google.android.gms.cast.framework.CastButtonFactory;
import com.google.android.gms.cast.framework.CastContext;
import com.google.android.gms.dynamite.DynamiteModule;
import com.google.common.util.concurrent.MoreExecutors;

/**
 * An activity that plays video using {@link ExoPlayer} and supports casting using ExoPlayer's Cast
 * extension.
 */
public class MainActivity extends AppCompatActivity
    implements OnClickListener, PlayerManager.Listener {

  private PlayerView playerView;
  private PlayerManager playerManager;
  private RecyclerView mediaQueueList;
  private MediaQueueListAdapter mediaQueueListAdapter;
  private CastContext castContext;

  // Activity lifecycle methods.

  @Override
  public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    // Getting the cast context later than onStart can cause device discovery not to take place.
    try {
      castContext = CastContext.getSharedInstance(this, MoreExecutors.directExecutor()).getResult();
    } catch (RuntimeException e) {
      Throwable cause = e.getCause();
      while (cause != null) {
        if (cause instanceof DynamiteModule.LoadingException) {
          setContentView(R.layout.cast_context_error);
          return;
        }
        cause = cause.getCause();
      }
      // Unknown error. We propagate it.
      throw e;
    }

    setContentView(R.layout.main_activity);

    playerView = findViewById(R.id.player_view);
    playerView.requestFocus();

    mediaQueueList = findViewById(R.id.sample_list);
    ItemTouchHelper helper = new ItemTouchHelper(new RecyclerViewCallback());
    helper.attachToRecyclerView(mediaQueueList);
    mediaQueueList.setLayoutManager(new LinearLayoutManager(this));
    mediaQueueList.setHasFixedSize(true);
    mediaQueueListAdapter = new MediaQueueListAdapter();

    findViewById(R.id.add_sample_button).setOnClickListener(this);
  }

  @Override
  public boolean onCreateOptionsMenu(Menu menu) {
    super.onCreateOptionsMenu(menu);
    getMenuInflater().inflate(R.menu.menu, menu);
    CastButtonFactory.setUpMediaRouteButton(this, menu, R.id.media_route_menu_item);
    return true;
  }

  @Override
  public void onResume() {
    super.onResume();
    if (castContext == null) {
      // There is no Cast context to work with. Do nothing.
      return;
    }
    playerManager =
        new PlayerManager(/* listener= */ this, this, playerView, /* context= */ castContext);
    mediaQueueList.setAdapter(mediaQueueListAdapter);
  }

  @Override
  public void onPause() {
    super.onPause();
    if (castContext == null) {
      // Nothing to release.
      return;
    }
    mediaQueueListAdapter.notifyItemRangeRemoved(0, mediaQueueListAdapter.getItemCount());
    mediaQueueList.setAdapter(null);
    playerManager.release();
    playerManager = null;
  }

  // Activity input.

  @Override
  public boolean dispatchKeyEvent(KeyEvent event) {
    // If the event was not handled then see if the player view can handle it.
    return super.dispatchKeyEvent(event) || playerManager.dispatchKeyEvent(event);
  }

  @Override
  public void onClick(View view) {
    new AlertDialog.Builder(this)
        .setTitle(R.string.add_samples)
        .setView(buildSampleListView())
        .setPositiveButton(android.R.string.ok, null)
        .create()
        .show();
  }

  // PlayerManager.Listener implementation.

  @Override
  public void onQueuePositionChanged(int previousIndex, int newIndex) {
    if (previousIndex != C.INDEX_UNSET) {
      mediaQueueListAdapter.notifyItemChanged(previousIndex);
    }
    if (newIndex != C.INDEX_UNSET) {
      mediaQueueListAdapter.notifyItemChanged(newIndex);
    }
  }

  @Override
  public void onUnsupportedTrack(int trackType) {
    if (trackType == C.TRACK_TYPE_AUDIO) {
      showToast(R.string.error_unsupported_audio);
    } else if (trackType == C.TRACK_TYPE_VIDEO) {
      showToast(R.string.error_unsupported_video);
    }
  }

  // Internal methods.

  private void showToast(int messageId) {
    Toast.makeText(getApplicationContext(), messageId, Toast.LENGTH_LONG).show();
  }

  private View buildSampleListView() {
    View dialogList = getLayoutInflater().inflate(R.layout.sample_list, null);
    ListView sampleList = dialogList.findViewById(R.id.sample_list);
    sampleList.setAdapter(new SampleListAdapter(this));
    sampleList.setOnItemClickListener(
        (parent, view, position, id) -> {
          playerManager.addItem(DemoUtil.SAMPLES.get(position));
          mediaQueueListAdapter.notifyItemInserted(playerManager.getMediaQueueSize() - 1);
        });
    return dialogList;
  }

  // Internal classes.

  private class MediaQueueListAdapter extends RecyclerView.Adapter<QueueItemViewHolder> {

    @Override
    public QueueItemViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
      TextView v =
          (TextView)
              LayoutInflater.from(parent.getContext())
                  .inflate(android.R.layout.simple_list_item_1, parent, false);
      return new QueueItemViewHolder(v);
    }

    @Override
    public void onBindViewHolder(QueueItemViewHolder holder, int position) {
      holder.item = Assertions.checkNotNull(playerManager.getItem(position));

      TextView view = holder.textView;
      view.setText(holder.item.mediaMetadata.title);
      // TODO: Solve coloring using the theme's ColorStateList.
      view.setTextColor(
          ColorUtils.setAlphaComponent(
              view.getCurrentTextColor(),
              position == playerManager.getCurrentItemIndex() ? 255 : 100));
    }

    @Override
    public int getItemCount() {
      return playerManager.getMediaQueueSize();
    }
  }

  private class RecyclerViewCallback extends ItemTouchHelper.SimpleCallback {

    private int draggingFromPosition;
    private int draggingToPosition;

    public RecyclerViewCallback() {
      super(ItemTouchHelper.UP | ItemTouchHelper.DOWN, ItemTouchHelper.START | ItemTouchHelper.END);
      draggingFromPosition = C.INDEX_UNSET;
      draggingToPosition = C.INDEX_UNSET;
    }

    @Override
    public boolean onMove(
        RecyclerView list, RecyclerView.ViewHolder origin, RecyclerView.ViewHolder target) {
      int fromPosition = origin.getBindingAdapterPosition();
      int toPosition = target.getBindingAdapterPosition();
      if (draggingFromPosition == C.INDEX_UNSET) {
        // A drag has started, but changes to the media queue will be reflected in clearView().
        draggingFromPosition = fromPosition;
      }
      draggingToPosition = toPosition;
      mediaQueueListAdapter.notifyItemMoved(fromPosition, toPosition);
      return true;
    }

    @Override
    public void onSwiped(RecyclerView.ViewHolder viewHolder, int direction) {
      int position = viewHolder.getBindingAdapterPosition();
      QueueItemViewHolder queueItemHolder = (QueueItemViewHolder) viewHolder;
      if (playerManager.removeItem(queueItemHolder.item)) {
        mediaQueueListAdapter.notifyItemRemoved(position);
        // Update whichever item took its place, in case it became the new selected item.
        mediaQueueListAdapter.notifyItemChanged(position);
      }
    }

    @Override
    public void clearView(RecyclerView recyclerView, ViewHolder viewHolder) {
      super.clearView(recyclerView, viewHolder);
      if (draggingFromPosition != C.INDEX_UNSET) {
        QueueItemViewHolder queueItemHolder = (QueueItemViewHolder) viewHolder;
        // A drag has ended. We reflect the media queue change in the player.
        if (!playerManager.moveItem(queueItemHolder.item, draggingToPosition)) {
          // The move failed. The entire sequence of onMove calls since the drag started needs to be
          // invalidated.
          mediaQueueListAdapter.notifyDataSetChanged();
        }
      }
      draggingFromPosition = C.INDEX_UNSET;
      draggingToPosition = C.INDEX_UNSET;
    }
  }

  private class QueueItemViewHolder extends RecyclerView.ViewHolder implements OnClickListener {

    public final TextView textView;
    public MediaItem item;

    public QueueItemViewHolder(TextView textView) {
      super(textView);
      this.textView = textView;
      textView.setOnClickListener(this);
    }

    @Override
    public void onClick(View v) {
      playerManager.selectQueueItem(getBindingAdapterPosition());
    }
  }

  private static final class SampleListAdapter extends ArrayAdapter<MediaItem> {

    public SampleListAdapter(Context context) {
      super(context, android.R.layout.simple_list_item_1, DemoUtil.SAMPLES);
    }

    @Override
    public View getView(int position, @Nullable View convertView, ViewGroup parent) {
      View view = super.getView(position, convertView, parent);
      ((TextView) view).setText(Util.castNonNull(getItem(position)).mediaMetadata.title);
      return view;
    }
  }
}
