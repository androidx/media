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
package androidx.media3.session.legacy;

import static androidx.annotation.RestrictTo.Scope.LIBRARY;
import static androidx.media3.session.legacy.MediaBrowserProtocol.CLIENT_MSG_ADD_SUBSCRIPTION;
import static androidx.media3.session.legacy.MediaBrowserProtocol.CLIENT_MSG_GET_MEDIA_ITEM;
import static androidx.media3.session.legacy.MediaBrowserProtocol.CLIENT_MSG_REGISTER_CALLBACK_MESSENGER;
import static androidx.media3.session.legacy.MediaBrowserProtocol.CLIENT_MSG_REMOVE_SUBSCRIPTION;
import static androidx.media3.session.legacy.MediaBrowserProtocol.CLIENT_MSG_SEARCH;
import static androidx.media3.session.legacy.MediaBrowserProtocol.CLIENT_MSG_SEND_CUSTOM_ACTION;
import static androidx.media3.session.legacy.MediaBrowserProtocol.CLIENT_MSG_UNREGISTER_CALLBACK_MESSENGER;
import static androidx.media3.session.legacy.MediaBrowserProtocol.CLIENT_VERSION_CURRENT;
import static androidx.media3.session.legacy.MediaBrowserProtocol.DATA_CALLBACK_TOKEN;
import static androidx.media3.session.legacy.MediaBrowserProtocol.DATA_CALLING_PID;
import static androidx.media3.session.legacy.MediaBrowserProtocol.DATA_CUSTOM_ACTION;
import static androidx.media3.session.legacy.MediaBrowserProtocol.DATA_CUSTOM_ACTION_EXTRAS;
import static androidx.media3.session.legacy.MediaBrowserProtocol.DATA_MEDIA_ITEM_ID;
import static androidx.media3.session.legacy.MediaBrowserProtocol.DATA_MEDIA_ITEM_LIST;
import static androidx.media3.session.legacy.MediaBrowserProtocol.DATA_NOTIFY_CHILDREN_CHANGED_OPTIONS;
import static androidx.media3.session.legacy.MediaBrowserProtocol.DATA_OPTIONS;
import static androidx.media3.session.legacy.MediaBrowserProtocol.DATA_PACKAGE_NAME;
import static androidx.media3.session.legacy.MediaBrowserProtocol.DATA_RESULT_RECEIVER;
import static androidx.media3.session.legacy.MediaBrowserProtocol.DATA_ROOT_HINTS;
import static androidx.media3.session.legacy.MediaBrowserProtocol.DATA_SEARCH_EXTRAS;
import static androidx.media3.session.legacy.MediaBrowserProtocol.DATA_SEARCH_QUERY;
import static androidx.media3.session.legacy.MediaBrowserProtocol.EXTRA_CALLING_PID;
import static androidx.media3.session.legacy.MediaBrowserProtocol.EXTRA_CLIENT_VERSION;
import static androidx.media3.session.legacy.MediaBrowserProtocol.EXTRA_MESSENGER_BINDER;
import static androidx.media3.session.legacy.MediaBrowserProtocol.EXTRA_SERVICE_VERSION;
import static androidx.media3.session.legacy.MediaBrowserProtocol.EXTRA_SESSION_BINDER;
import static androidx.media3.session.legacy.MediaBrowserProtocol.SERVICE_MSG_ON_LOAD_CHILDREN;
import static androidx.media3.session.legacy.MediaBrowserProtocol.SERVICE_VERSION_2;
import static com.google.common.base.Preconditions.checkNotNull;

import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.media.browse.MediaBrowser;
import android.os.BadParcelableException;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.Process;
import android.os.RemoteException;
import android.support.v4.os.ResultReceiver;
import android.text.TextUtils;
import android.util.Log;
import androidx.annotation.IntDef;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.RestrictTo;
import androidx.collection.ArrayMap;
import androidx.media3.common.util.NullableType;
import androidx.media3.session.legacy.MediaControllerCompat.TransportControls;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Browses media content offered by a {@link MediaBrowserServiceCompat}.
 *
 * <p>The app targeting API level 30 or higher must include a {@code <queries>} element in their
 * manifest to connect to a media browser service in another app. See the following example and <a
 * href="{@docRoot}training/package-visibility">this guide</a> for more information.
 *
 * <pre>{@code
 * <!-- As an intent action -->
 * <intent>
 *   <action android:name="android.media.browse.MediaBrowserService" />
 * </intent>
 * <!-- Or, as a package name -->
 * <package android:name="package_name_of_the_other_app" />
 * }</pre>
 *
 * <p>This object is not thread-safe. All calls should happen on the thread on which the browser was
 * constructed. All callback methods will be called from the thread on which the browser was
 * constructed. <div class="special reference">
 *
 * <h2>Developer Guides</h2>
 *
 * <p>For information about building your media application, read the <a
 * href="{@docRoot}guide/topics/media-apps/index.html">Media Apps</a> developer guide. </div>
 */
@RestrictTo(LIBRARY)
public final class MediaBrowserCompat {
  static final String TAG = "MediaBrowserCompat";
  static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

  /**
   * Used as an int extra field to denote the page number to subscribe. The value of {@code
   * EXTRA_PAGE} should be greater than or equal to 0.
   *
   * @see android.service.media.MediaBrowserService.BrowserRoot
   * @see #EXTRA_PAGE_SIZE
   */
  @SuppressLint("InlinedApi") // Inlined compile time constant.
  public static final String EXTRA_PAGE = MediaBrowser.EXTRA_PAGE;

  /**
   * Used as an int extra field to denote the number of media items in a page. The value of {@code
   * EXTRA_PAGE_SIZE} should be greater than or equal to 1.
   *
   * @see android.service.media.MediaBrowserService.BrowserRoot
   * @see #EXTRA_PAGE
   */
  @SuppressLint("InlinedApi") // Inlined compile time constant.
  public static final String EXTRA_PAGE_SIZE = MediaBrowser.EXTRA_PAGE_SIZE;

  /**
   * Predefined custom action to ask the connected service to download a specific {@link MediaItem}
   * for offline playback. The id of the media item must be passed in an extra bundle.
   *
   * @see #CUSTOM_ACTION_REMOVE_DOWNLOADED_FILE
   */
  public static final String CUSTOM_ACTION_DOWNLOAD = "android.support.v4.media.action.DOWNLOAD";

  /**
   * Predefined custom action to ask the connected service to remove the downloaded file of {@link
   * MediaItem} by the {@link #CUSTOM_ACTION_DOWNLOAD download} action. The id of the media item
   * must be passed in an extra bundle.
   *
   * @see #CUSTOM_ACTION_DOWNLOAD
   */
  public static final String CUSTOM_ACTION_REMOVE_DOWNLOADED_FILE =
      "android.support.v4.media.action.REMOVE_DOWNLOADED_FILE";

  /**
   * Used as a float extra field to denote the current progress during download. The value of this
   * field must be a float number within [0.0, 1.0].
   *
   * @see #CUSTOM_ACTION_DOWNLOAD
   * @see CustomActionCallback#onProgressUpdate
   */
  public static final String EXTRA_DOWNLOAD_PROGRESS =
      "android.media.browse.extra.DOWNLOAD_PROGRESS";

  private final MediaBrowserImpl impl;

  /**
   * Creates a media browser for the specified media browse service.
   *
   * @param context The context.
   * @param serviceComponent The component name of the media browse service.
   * @param callback The connection callback.
   * @param rootHints An optional bundle of service-specific arguments to send to the media browse
   *     service when connecting and retrieving the root id for browsing, or null if none. The
   *     contents of this bundle may affect the information returned when browsing.
   * @see MediaBrowserServiceCompat.BrowserRoot#EXTRA_RECENT
   * @see MediaBrowserServiceCompat.BrowserRoot#EXTRA_OFFLINE
   * @see MediaBrowserServiceCompat.BrowserRoot#EXTRA_SUGGESTED
   */
  public MediaBrowserCompat(
      Context context,
      ComponentName serviceComponent,
      ConnectionCallback callback,
      @Nullable Bundle rootHints) {
    // To workaround an issue of {@link #unsubscribe(String, SubscriptionCallback)} on API 24
    // and 25 devices, use the support library version of implementation on those devices.
    if (Build.VERSION.SDK_INT >= 26) {
      impl = new MediaBrowserImplApi26(context, serviceComponent, callback, rootHints);
    } else {
      impl = new MediaBrowserImplApi23(context, serviceComponent, callback, rootHints);
    }
  }

  /**
   * Connects to the media browse service. Internally, it binds to the service.
   *
   * <p>The connection callback specified in the constructor will be invoked when the connection
   * completes or fails.
   */
  public void connect() {
    Log.d(TAG, "Connecting to a MediaBrowserService.");
    impl.connect();
  }

  /** Disconnects from the media browse service. After this, no more callbacks will be received. */
  public void disconnect() {
    impl.disconnect();
  }

  /** Returns whether the browser is connected to the service. */
  public boolean isConnected() {
    return impl.isConnected();
  }

  /**
   * Gets the root id.
   *
   * <p>Note that the root id may become invalid or change when when the browser is disconnected.
   *
   * @throws IllegalStateException if not connected.
   */
  public String getRoot() {
    return impl.getRoot();
  }

  /**
   * Gets any extras for the media service.
   *
   * @return The extra bundle if it is connected and set, and {@code null} otherwise.
   * @throws IllegalStateException if not connected.
   */
  @Nullable
  public Bundle getExtras() {
    return impl.getExtras();
  }

  /**
   * Gets the media session token associated with the media browser.
   *
   * <p>Note that the session token may become invalid or change when when the browser is
   * disconnected.
   *
   * @return The session token for the browser, never null.
   * @throws IllegalStateException if not connected.
   */
  public MediaSessionCompat.Token getSessionToken() {
    return impl.getSessionToken();
  }

  /**
   * Queries with service-specific arguments for information about the media items that are
   * contained within the specified id and subscribes to receive updates when they change.
   *
   * <p>The list of subscriptions is maintained even when not connected and is restored after the
   * reconnection. It is ok to subscribe while not connected but the results will not be returned
   * until the connection completes.
   *
   * <p>If the id is already subscribed with a different callback then the new callback will replace
   * the previous one and the child data will be reloaded.
   *
   * @param parentId The id of the parent media item whose list of children will be subscribed.
   * @param options A bundle of service-specific arguments to send to the media browse service. The
   *     contents of this bundle may affect the information returned when browsing.
   * @param callback The callback to receive the list of children.
   */
  public void subscribe(String parentId, Bundle options, SubscriptionCallback callback) {
    // Check arguments.
    if (TextUtils.isEmpty(parentId)) {
      throw new IllegalArgumentException("parentId is empty");
    }
    impl.subscribe(parentId, options, callback);
  }

  /**
   * Unsubscribes for changes to the children of the specified media id.
   *
   * <p>The query callback will no longer be invoked for results associated with this id once this
   * method returns.
   *
   * @param parentId The id of the parent media item whose list of children will be unsubscribed.
   */
  public void unsubscribe(String parentId) {
    // Check arguments.
    if (TextUtils.isEmpty(parentId)) {
      throw new IllegalArgumentException("parentId is empty");
    }
    impl.unsubscribe(parentId, null);
  }

  /**
   * Unsubscribes for changes to the children of the specified media id.
   *
   * <p>The query callback will no longer be invoked for results associated with this id once this
   * method returns.
   *
   * @param parentId The id of the parent media item whose list of children will be unsubscribed.
   * @param callback A callback sent to the media browse service to subscribe.
   */
  public void unsubscribe(String parentId, SubscriptionCallback callback) {
    // Check arguments.
    if (TextUtils.isEmpty(parentId)) {
      throw new IllegalArgumentException("parentId is empty");
    }
    impl.unsubscribe(parentId, callback);
  }

  /**
   * Retrieves a specific {@link MediaItem} from the connected service. Not all services may support
   * this, so falling back to subscribing to the parent's id should be used when unavailable.
   *
   * @param mediaId The id of the item to retrieve.
   * @param cb The callback to receive the result on.
   */
  public void getItem(String mediaId, ItemCallback cb) {
    impl.getItem(mediaId, cb);
  }

  /**
   * Searches {@link MediaItem media items} from the connected service. Not all services may support
   * this, and {@link SearchCallback#onError} will be called if not implemented.
   *
   * @param query The search query that contains keywords separated by space. Should not be an empty
   *     string.
   * @param extras The bundle of service-specific arguments to send to the media browser service.
   *     The contents of this bundle may affect the search result.
   * @param callback The callback to receive the search result. Must be non-null.
   * @throws IllegalStateException if the browser is not connected to the media browser service.
   */
  public void search(final String query, @Nullable Bundle extras, SearchCallback callback) {
    if (TextUtils.isEmpty(query)) {
      throw new IllegalArgumentException("query cannot be empty");
    }
    impl.search(query, extras, callback);
  }

  /**
   * Sends a custom action to the connected service. If the service doesn't support the given
   * action, {@link CustomActionCallback#onError} will be called.
   *
   * @param action The custom action that will be sent to the connected service. Should not be an
   *     empty string.
   * @param extras The bundle of service-specific arguments to send to the media browser service.
   * @param callback The callback to receive the result of the custom action.
   * @see #CUSTOM_ACTION_DOWNLOAD
   * @see #CUSTOM_ACTION_REMOVE_DOWNLOADED_FILE
   */
  public void sendCustomAction(
      String action, @Nullable Bundle extras, @Nullable CustomActionCallback callback) {
    if (TextUtils.isEmpty(action)) {
      throw new IllegalArgumentException("action cannot be empty");
    }
    impl.sendCustomAction(action, extras, callback);
  }

  /**
   * Gets the options which is passed to {@link MediaBrowserServiceCompat#notifyChildrenChanged(
   * String, Bundle)} call that triggered {@link SubscriptionCallback#onChildrenLoaded}. This should
   * be called inside of {@link SubscriptionCallback#onChildrenLoaded}.
   *
   * @return A bundle which is passed to {@link MediaBrowserServiceCompat#notifyChildrenChanged(
   *     String, Bundle)}
   */
  @Nullable
  public Bundle getNotifyChildrenChangedOptions() {
    return impl.getNotifyChildrenChangedOptions();
  }

  /**
   * A class with information on a single media item for use in browsing/searching media. MediaItems
   * are application dependent so we cannot guarantee that they contain the right values.
   */
  @SuppressLint("BanParcelableUsage")
  public static class MediaItem implements Parcelable {
    private final int flags;
    private final MediaDescriptionCompat description;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef(
        flag = true,
        value = {FLAG_BROWSABLE, FLAG_PLAYABLE})
    private @interface Flags {}

    /** Flag: Indicates that the item has children of its own. */
    public static final int FLAG_BROWSABLE = 1 << 0;

    /**
     * Flag: Indicates that the item is playable.
     *
     * <p>The id of this item may be passed to {@link TransportControls#playFromMediaId(String,
     * Bundle)} to start playing it.
     */
    public static final int FLAG_PLAYABLE = 1 << 1;

    /**
     * Creates an instance from a framework {@link android.media.browse.MediaBrowser.MediaItem}
     * object.
     *
     * @param item A {@link android.media.browse.MediaBrowser.MediaItem} object.
     * @return An equivalent {@link MediaItem} object, or null if none.
     */
    @SuppressLint("WrongConstant")
    @Nullable
    public static MediaItem fromMediaItem(@Nullable MediaBrowser.MediaItem item) {
      if (item == null) {
        return null;
      }
      int flags = item.getFlags();
      MediaDescriptionCompat descriptionCompat =
          MediaDescriptionCompat.fromMediaDescription(item.getDescription());
      return new MediaItem(descriptionCompat, flags);
    }

    /**
     * Creates a list of {@link MediaItem} objects from a framework {@link
     * android.media.browse.MediaBrowser.MediaItem} object list.
     *
     * @param itemList A list of {@link android.media.browse.MediaBrowser.MediaItem} objects.
     * @return An equivalent list of {@link MediaItem} objects, or null if none.
     */
    @Nullable
    public static List<MediaItem> fromMediaItemList(
        @Nullable List<MediaBrowser.MediaItem> itemList) {
      if (itemList == null) {
        return null;
      }
      List<MediaItem> items = new ArrayList<>(itemList.size());
      for (MediaBrowser.MediaItem itemObj : itemList) {
        MediaItem item = fromMediaItem(itemObj);
        if (item != null) {
          items.add(item);
        }
      }
      return items;
    }

    /**
     * Create a new MediaItem for use in browsing media.
     *
     * @param description The description of the media, which must include a media id.
     * @param flags The flags for this item.
     */
    public MediaItem(@Nullable MediaDescriptionCompat description, @Flags int flags) {
      if (description == null) {
        throw new IllegalArgumentException("description cannot be null");
      }
      if (TextUtils.isEmpty(description.getMediaId())) {
        throw new IllegalArgumentException("description must have a non-empty media id");
      }
      this.flags = flags;
      this.description = description;
    }

    /** Private constructor. */
    MediaItem(Parcel in) {
      flags = in.readInt();
      description = MediaDescriptionCompat.CREATOR.createFromParcel(in);
    }

    @Override
    public int describeContents() {
      return 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
      out.writeInt(this.flags);
      description.writeToParcel(out, flags);
    }

    @Override
    public String toString() {
      final StringBuilder sb = new StringBuilder("MediaItem{");
      sb.append("mFlags=").append(flags);
      sb.append(", mDescription=").append(description);
      sb.append('}');
      return sb.toString();
    }

    public static final Parcelable.Creator<MediaItem> CREATOR =
        new Parcelable.Creator<MediaItem>() {
          @Override
          public MediaItem createFromParcel(Parcel in) {
            return new MediaItem(in);
          }

          @Override
          public MediaItem[] newArray(int size) {
            return new MediaItem[size];
          }
        };

    /** Gets the flags of the item. */
    @Flags
    public int getFlags() {
      return flags;
    }

    /**
     * Returns whether this item is browsable.
     *
     * @see #FLAG_BROWSABLE
     */
    public boolean isBrowsable() {
      return (flags & FLAG_BROWSABLE) != 0;
    }

    /**
     * Returns whether this item is playable.
     *
     * @see #FLAG_PLAYABLE
     */
    public boolean isPlayable() {
      return (flags & FLAG_PLAYABLE) != 0;
    }

    /** Returns the description of the media. */
    public MediaDescriptionCompat getDescription() {
      return description;
    }

    /**
     * Returns the media id in the {@link MediaDescriptionCompat} for this item.
     *
     * @see MediaMetadataCompat#METADATA_KEY_MEDIA_ID
     */
    @Nullable
    public String getMediaId() {
      return description.getMediaId();
    }
  }

  /** Callbacks for connection related events. */
  public static class ConnectionCallback {
    @Nullable final MediaBrowser.ConnectionCallback connectionCallbackFwk;
    @Nullable ConnectionCallbackInternal connectionCallbackInternal;

    public ConnectionCallback() {
      connectionCallbackFwk = new ConnectionCallbackImpl();
    }

    /**
     * Invoked after {@link MediaBrowserCompat#connect()} when the request has successfully
     * completed. This can also be called when the service is next running after it crashed or has
     * been killed.
     *
     * @see ServiceConnection#onServiceConnected(ComponentName, IBinder)
     * @see ServiceConnection#onServiceDisconnected(ComponentName)
     */
    public void onConnected() {}

    /**
     * Invoked when a connection to the browser service has been lost. This typically happens when
     * the process hosting the service has crashed or been killed. This does not remove the
     * connection itself -- this binding to the service will remain active, and {@link
     * #onConnected()} will be called when the service is next running.
     *
     * @see ServiceConnection#onServiceDisconnected(ComponentName)
     */
    public void onConnectionSuspended() {}

    /**
     * Invoked when the connection to the media browser service failed. Connection failures can
     * happen when the browser failed to bind to the service, or when it is rejected from the
     * service.
     */
    public void onConnectionFailed() {}

    void setInternalConnectionCallback(ConnectionCallbackInternal connectionCallbackInternal) {
      this.connectionCallbackInternal = connectionCallbackInternal;
    }

    interface ConnectionCallbackInternal {
      void onConnected();

      void onConnectionSuspended();

      void onConnectionFailed();
    }

    private class ConnectionCallbackImpl extends MediaBrowser.ConnectionCallback {
      ConnectionCallbackImpl() {}

      @Override
      public void onConnected() {
        if (connectionCallbackInternal != null) {
          connectionCallbackInternal.onConnected();
        }
        ConnectionCallback.this.onConnected();
      }

      @Override
      public void onConnectionSuspended() {
        if (connectionCallbackInternal != null) {
          connectionCallbackInternal.onConnectionSuspended();
        }
        ConnectionCallback.this.onConnectionSuspended();
      }

      @Override
      public void onConnectionFailed() {
        if (connectionCallbackInternal != null) {
          connectionCallbackInternal.onConnectionFailed();
        }
        ConnectionCallback.this.onConnectionFailed();
      }
    }
  }

  /** Callbacks for subscription related events. */
  public abstract static class SubscriptionCallback {
    final MediaBrowser.SubscriptionCallback subscriptionCallbackFwk;
    final IBinder token;
    @Nullable WeakReference<Subscription> subscriptionRef;

    public SubscriptionCallback() {
      token = new Binder();
      if (Build.VERSION.SDK_INT >= 26) {
        subscriptionCallbackFwk = new SubscriptionCallbackApi26();
      } else {
        subscriptionCallbackFwk = new SubscriptionCallbackApi23();
      }
    }

    /**
     * Called when the list of children is loaded or updated.
     *
     * @param parentId The media id of the parent media item.
     * @param children The children which were loaded.
     */
    public void onChildrenLoaded(@Nullable String parentId, @Nullable List<MediaItem> children) {}

    /**
     * Called when the list of children is loaded or updated.
     *
     * @param parentId The media id of the parent media item.
     * @param children The children which were loaded.
     * @param options A bundle of service-specific arguments to send to the media browse service.
     *     The contents of this bundle may affect the information returned when browsing.
     */
    public void onChildrenLoaded(
        @Nullable String parentId, @Nullable List<MediaItem> children, @Nullable Bundle options) {}

    /**
     * Called when the id doesn't exist or other errors in subscribing.
     *
     * <p>If this is called, the subscription remains until {@link MediaBrowserCompat#unsubscribe}
     * called, because some errors may heal themselves.
     *
     * @param parentId The media id of the parent media item whose children could not be loaded.
     */
    public void onError(@Nullable String parentId) {}

    /**
     * Called when the id doesn't exist or other errors in subscribing.
     *
     * <p>If this is called, the subscription remains until {@link MediaBrowserCompat#unsubscribe}
     * called, because some errors may heal themselves.
     *
     * @param parentId The media id of the parent media item whose children could not be loaded.
     * @param options A bundle of service-specific arguments sent to the media browse service.
     */
    public void onError(@Nullable String parentId, @Nullable Bundle options) {}

    void setSubscription(Subscription subscription) {
      subscriptionRef = new WeakReference<>(subscription);
    }

    private class SubscriptionCallbackApi23 extends MediaBrowser.SubscriptionCallback {
      SubscriptionCallbackApi23() {}

      @Override
      public void onChildrenLoaded(String parentId, List<MediaBrowser.MediaItem> children) {
        Subscription sub = subscriptionRef == null ? null : subscriptionRef.get();
        if (sub == null) {
          SubscriptionCallback.this.onChildrenLoaded(
              parentId, MediaItem.fromMediaItemList(children));
        } else {
          List<MediaBrowserCompat.MediaItem> itemList =
              checkNotNull(MediaItem.fromMediaItemList(children));
          final List<SubscriptionCallback> callbacks = sub.getCallbacks();
          final List<@NullableType Bundle> optionsList = sub.getOptionsList();
          for (int i = 0; i < callbacks.size(); ++i) {
            Bundle options = optionsList.get(i);
            if (options == null) {
              SubscriptionCallback.this.onChildrenLoaded(parentId, itemList);
            } else {
              SubscriptionCallback.this.onChildrenLoaded(
                  parentId, applyOptions(itemList, options), options);
            }
          }
        }
      }

      @Override
      public void onError(String parentId) {
        SubscriptionCallback.this.onError(parentId);
      }

      @Nullable
      List<MediaBrowserCompat.MediaItem> applyOptions(
          List<MediaBrowserCompat.MediaItem> list, final Bundle options) {
        int page = options.getInt(MediaBrowserCompat.EXTRA_PAGE, -1);
        int pageSize = options.getInt(MediaBrowserCompat.EXTRA_PAGE_SIZE, -1);
        if (page == -1 && pageSize == -1) {
          return list;
        }
        int fromIndex = pageSize * page;
        int toIndex = fromIndex + pageSize;
        if (page < 0 || pageSize < 1 || fromIndex >= list.size()) {
          return Collections.emptyList();
        }
        if (toIndex > list.size()) {
          toIndex = list.size();
        }
        return list.subList(fromIndex, toIndex);
      }
    }

    @RequiresApi(26)
    private class SubscriptionCallbackApi26 extends SubscriptionCallbackApi23 {
      SubscriptionCallbackApi26() {}

      @Override
      public void onChildrenLoaded(
          String parentId, List<MediaBrowser.MediaItem> children, Bundle options) {
        MediaSessionCompat.ensureClassLoader(options);
        SubscriptionCallback.this.onChildrenLoaded(
            parentId, MediaItem.fromMediaItemList(children), options);
      }

      @Override
      public void onError(String parentId, Bundle options) {
        MediaSessionCompat.ensureClassLoader(options);
        SubscriptionCallback.this.onError(parentId, options);
      }
    }
  }

  /** Callback for receiving the result of {@link #getItem}. */
  public abstract static class ItemCallback {
    final MediaBrowser.ItemCallback itemCallbackFwk;

    public ItemCallback() {
      itemCallbackFwk = new ItemCallbackImpl();
    }

    /**
     * Called when the item has been returned by the browser service.
     *
     * @param item The item that was returned or null if it doesn't exist.
     */
    public void onItemLoaded(@Nullable MediaItem item) {}

    /**
     * Called when the item doesn't exist or there was an error retrieving it.
     *
     * @param itemId The media id of the media item which could not be loaded.
     */
    public void onError(String itemId) {}

    private class ItemCallbackImpl extends MediaBrowser.ItemCallback {
      ItemCallbackImpl() {}

      @Override
      public void onItemLoaded(MediaBrowser.MediaItem item) {
        ItemCallback.this.onItemLoaded(MediaItem.fromMediaItem(item));
      }

      @Override
      public void onError(String itemId) {
        ItemCallback.this.onError(itemId);
      }
    }
  }

  /** Callback for receiving the result of {@link #search}. */
  public abstract static class SearchCallback {
    /**
     * Called when the {@link #search} finished successfully.
     *
     * @param query The search query sent for the search request to the connected service.
     * @param extras The bundle of service-specific arguments sent to the connected service.
     * @param items The list of media items which contains the search result.
     */
    public void onSearchResult(String query, @Nullable Bundle extras, List<MediaItem> items) {}

    /**
     * Called when an error happens while {@link #search} or the connected service doesn't support
     * {@link #search}.
     *
     * @param query The search query sent for the search request to the connected service.
     * @param extras The bundle of service-specific arguments sent to the connected service.
     */
    public void onError(String query, @Nullable Bundle extras) {}
  }

  /** Callback for receiving the result of {@link #sendCustomAction}. */
  public abstract static class CustomActionCallback {
    /**
     * Called when an interim update was delivered from the connected service while performing the
     * custom action.
     *
     * @param action The custom action sent to the connected service.
     * @param extras The bundle of service-specific arguments sent to the connected service.
     * @param data The additional data delivered from the connected service.
     */
    public void onProgressUpdate(String action, Bundle extras, Bundle data) {}

    /**
     * Called when the custom action finished successfully.
     *
     * @param action The custom action sent to the connected service.
     * @param extras The bundle of service-specific arguments sent to the connected service.
     * @param resultData The additional data delivered from the connected service.
     */
    public void onResult(String action, @Nullable Bundle extras, @Nullable Bundle resultData) {}

    /**
     * Called when an error happens while performing the custom action or the connected service
     * doesn't support the requested custom action.
     *
     * @param action The custom action sent to the connected service.
     * @param extras The bundle of service-specific arguments sent to the connected service.
     * @param data The additional data delivered from the connected service.
     */
    public void onError(String action, @Nullable Bundle extras, @Nullable Bundle data) {}
  }

  interface MediaBrowserImpl {
    void connect();

    void disconnect();

    boolean isConnected();

    String getRoot();

    @Nullable
    Bundle getExtras();

    MediaSessionCompat.Token getSessionToken();

    void subscribe(String parentId, @Nullable Bundle options, SubscriptionCallback callback);

    void unsubscribe(String parentId, @Nullable SubscriptionCallback callback);

    void getItem(String mediaId, ItemCallback cb);

    void search(String query, @Nullable Bundle extras, SearchCallback callback);

    void sendCustomAction(
        String action, @Nullable Bundle extras, @Nullable CustomActionCallback callback);

    @Nullable
    Bundle getNotifyChildrenChangedOptions();
  }

  interface MediaBrowserServiceCallbackImpl {
    void onLoadChildren(
        Messenger callback,
        @Nullable String parentId,
        @Nullable List<MediaItem> list,
        @Nullable Bundle options,
        @Nullable Bundle notifyChildrenChangedOptions);
  }

  static class MediaBrowserImplApi23
      implements MediaBrowserImpl,
          MediaBrowserServiceCallbackImpl,
          ConnectionCallback.ConnectionCallbackInternal {
    final Context context;
    protected final MediaBrowser browserFwk;
    protected final Bundle rootHints;

    @SuppressWarnings({
      "argument.type.incompatible",
      "assignment.type.incompatible"
    }) // Using this before constructor finishes
    protected final CallbackHandler handler = new CallbackHandler(this);

    private final ArrayMap<String, Subscription> subscriptions = new ArrayMap<>();

    protected int serviceVersion;
    @Nullable protected ServiceBinderWrapper serviceBinderWrapper;
    @Nullable protected Messenger callbacksMessenger;
    @Nullable private MediaSessionCompat.Token mediaSessionToken;
    @Nullable private Bundle notifyChildrenChangedOptions;

    @SuppressWarnings("argument.type.incompatible") // Using this before constructor finishes
    MediaBrowserImplApi23(
        Context context,
        ComponentName serviceComponent,
        ConnectionCallback callback,
        @Nullable Bundle rootHints) {
      this.context = context;
      this.rootHints = (rootHints != null ? new Bundle(rootHints) : new Bundle());
      this.rootHints.putInt(EXTRA_CLIENT_VERSION, CLIENT_VERSION_CURRENT);
      this.rootHints.putInt(EXTRA_CALLING_PID, Process.myPid());
      callback.setInternalConnectionCallback(this);
      browserFwk =
          new MediaBrowser(
              context,
              serviceComponent,
              checkNotNull(callback.connectionCallbackFwk),
              this.rootHints);
    }

    @Override
    public void connect() {
      browserFwk.connect();
    }

    @Override
    public void disconnect() {
      if (serviceBinderWrapper != null && callbacksMessenger != null) {
        try {
          serviceBinderWrapper.unregisterCallbackMessenger(callbacksMessenger);
        } catch (RemoteException e) {
          Log.i(TAG, "Remote error unregistering client messenger.");
        }
      }
      browserFwk.disconnect();
    }

    @Override
    public boolean isConnected() {
      return browserFwk.isConnected();
    }

    @Override
    public String getRoot() {
      return browserFwk.getRoot();
    }

    @Override
    @Nullable
    public Bundle getExtras() {
      return browserFwk.getExtras();
    }

    @Override
    public MediaSessionCompat.Token getSessionToken() {
      if (mediaSessionToken == null) {
        mediaSessionToken = MediaSessionCompat.Token.fromToken(browserFwk.getSessionToken());
      }
      return mediaSessionToken;
    }

    @Override
    public void subscribe(
        final String parentId, @Nullable Bundle options, final SubscriptionCallback callback) {
      // Update or create the subscription.
      Subscription sub = subscriptions.get(parentId);
      if (sub == null) {
        sub = new Subscription();
        subscriptions.put(parentId, sub);
      }
      callback.setSubscription(sub);
      Bundle copiedOptions = options == null ? null : new Bundle(options);
      sub.putCallback(copiedOptions, callback);

      if (serviceBinderWrapper == null) {
        // TODO: When MediaBrowser is connected to framework's MediaBrowserService,
        // subscribe with options won't work properly.
        browserFwk.subscribe(parentId, callback.subscriptionCallbackFwk);
      } else {
        try {
          serviceBinderWrapper.addSubscription(
              parentId, callback.token, copiedOptions, checkNotNull(callbacksMessenger));
        } catch (RemoteException e) {
          // Process is crashing. We will disconnect, and upon reconnect we will
          // automatically reregister. So nothing to do here.
          Log.i(TAG, "Remote error subscribing media item: " + parentId);
        }
      }
    }

    @Override
    public void unsubscribe(String parentId, @Nullable SubscriptionCallback callback) {
      Subscription sub = subscriptions.get(parentId);
      if (sub == null) {
        return;
      }

      ServiceBinderWrapper serviceBinderWrapper = this.serviceBinderWrapper;
      if (serviceBinderWrapper == null) {
        if (callback == null) {
          browserFwk.unsubscribe(parentId);
        } else {
          final List<SubscriptionCallback> callbacks = sub.getCallbacks();
          final List<@NullableType Bundle> optionsList = sub.getOptionsList();
          for (int i = callbacks.size() - 1; i >= 0; --i) {
            if (callbacks.get(i) == callback) {
              callbacks.remove(i);
              optionsList.remove(i);
            }
          }
          if (callbacks.isEmpty()) {
            browserFwk.unsubscribe(parentId);
          }
        }
      } else {
        // Tell the service if necessary.
        try {
          if (callback == null) {
            serviceBinderWrapper.removeSubscription(
                parentId, null, checkNotNull(callbacksMessenger));
          } else {
            final List<SubscriptionCallback> callbacks = sub.getCallbacks();
            final List<@NullableType Bundle> optionsList = sub.getOptionsList();
            for (int i = callbacks.size() - 1; i >= 0; --i) {
              if (callbacks.get(i) == callback) {
                serviceBinderWrapper.removeSubscription(
                    parentId, callback.token, checkNotNull(callbacksMessenger));
                callbacks.remove(i);
                optionsList.remove(i);
              }
            }
          }
        } catch (RemoteException ex) {
          // Process is crashing. We will disconnect, and upon reconnect we will
          // automatically reregister. So nothing to do here.
          Log.d(TAG, "removeSubscription failed with RemoteException parentId=" + parentId);
        }
      }

      if (sub.isEmpty() || callback == null) {
        subscriptions.remove(parentId);
      }
    }

    @Override
    public void getItem(final String mediaId, final ItemCallback cb) {
      if (serviceBinderWrapper == null) {
        browserFwk.getItem(mediaId, cb.itemCallbackFwk);
        return;
      }
      if (TextUtils.isEmpty(mediaId)) {
        throw new IllegalArgumentException("mediaId is empty");
      }
      if (!browserFwk.isConnected()) {
        Log.i(TAG, "Not connected, unable to retrieve the MediaItem.");
        handler.post(
            new Runnable() {
              @Override
              public void run() {
                cb.onError(mediaId);
              }
            });
        return;
      }
      if (serviceBinderWrapper == null) {
        handler.post(
            new Runnable() {
              @Override
              public void run() {
                // Default framework implementation.
                cb.onError(mediaId);
              }
            });
        return;
      }
      ResultReceiver receiver = new ItemReceiver(mediaId, cb, handler);
      try {
        serviceBinderWrapper.getMediaItem(mediaId, receiver, checkNotNull(callbacksMessenger));
      } catch (RemoteException e) {
        Log.i(TAG, "Remote error getting media item: " + mediaId);
        handler.post(
            new Runnable() {
              @Override
              public void run() {
                cb.onError(mediaId);
              }
            });
      }
    }

    @Override
    public void search(final String query, @Nullable Bundle extras, final SearchCallback callback) {
      if (!isConnected()) {
        throw new IllegalStateException("search() called while not connected");
      }
      if (serviceBinderWrapper == null) {
        Log.i(TAG, "The connected service doesn't support search.");
        handler.post(
            new Runnable() {
              @Override
              public void run() {
                // Default framework implementation.
                callback.onError(query, extras);
              }
            });
        return;
      }

      ResultReceiver receiver = new SearchResultReceiver(query, extras, callback, handler);
      try {
        serviceBinderWrapper.search(query, extras, receiver, checkNotNull(callbacksMessenger));
      } catch (RemoteException e) {
        Log.i(TAG, "Remote error searching items with query: " + query, e);
        handler.post(
            new Runnable() {
              @Override
              public void run() {
                callback.onError(query, extras);
              }
            });
      }
    }

    @Override
    public void sendCustomAction(
        final String action,
        @Nullable Bundle extras,
        @Nullable final CustomActionCallback callback) {
      if (!isConnected()) {
        throw new IllegalStateException(
            "Cannot send a custom action ("
                + action
                + ") with "
                + "extras "
                + extras
                + " because the browser is not connected to the "
                + "service.");
      }
      ServiceBinderWrapper serviceBinderWrapper = this.serviceBinderWrapper;
      if (serviceBinderWrapper == null) {
        Log.i(TAG, "The connected service doesn't support sendCustomAction.");
        if (callback != null) {
          handler.post(
              new Runnable() {
                @Override
                public void run() {
                  callback.onError(action, extras, null);
                }
              });
        }
        return;
      }

      ResultReceiver receiver = new CustomActionResultReceiver(action, extras, callback, handler);
      try {
        serviceBinderWrapper.sendCustomAction(
            action, extras, receiver, checkNotNull(callbacksMessenger));
      } catch (RemoteException e) {
        Log.i(
            TAG,
            "Remote error sending a custom action: action=" + action + ", extras=" + extras,
            e);
        if (callback != null) {
          handler.post(
              new Runnable() {
                @Override
                public void run() {
                  callback.onError(action, extras, null);
                }
              });
        }
      }
    }

    @Override
    public void onConnected() {
      Bundle extras;
      try {
        extras = browserFwk.getExtras();
      } catch (IllegalStateException e) {
        // Should not be here since onConnected() will be called in a connected state.
        Log.e(TAG, "Unexpected IllegalStateException", e);
        return;
      }
      if (extras == null) {
        return;
      }
      serviceVersion = extras.getInt(EXTRA_SERVICE_VERSION, 0);
      IBinder serviceBinder = extras.getBinder(EXTRA_MESSENGER_BINDER);
      if (serviceBinder != null) {
        ServiceBinderWrapper serviceBinderWrapper =
            new ServiceBinderWrapper(serviceBinder, rootHints);
        this.serviceBinderWrapper = serviceBinderWrapper;
        Messenger messenger = new Messenger(handler);
        this.callbacksMessenger = messenger;
        handler.setCallbacksMessenger(messenger);
        try {
          serviceBinderWrapper.registerCallbackMessenger(context, messenger);
        } catch (RemoteException e) {
          Log.i(TAG, "Remote error registering client messenger.");
        }
      }
      IMediaSession sessionToken =
          IMediaSession.Stub.asInterface(extras.getBinder(EXTRA_SESSION_BINDER));
      if (sessionToken != null) {
        mediaSessionToken =
            MediaSessionCompat.Token.fromToken(browserFwk.getSessionToken(), sessionToken);
      }
    }

    @Override
    public void onConnectionSuspended() {
      serviceBinderWrapper = null;
      callbacksMessenger = null;
      mediaSessionToken = null;
      handler.setCallbacksMessenger(null);
    }

    @Override
    public void onConnectionFailed() {
      // Do noting
    }

    @Override
    @SuppressWarnings({"ReferenceEquality"})
    public void onLoadChildren(
        Messenger callback,
        @Nullable String parentId,
        @Nullable List<MediaItem> list,
        @Nullable Bundle options,
        @Nullable Bundle notifyChildrenChangedOptions) {
      if (callbacksMessenger != callback) {
        return;
      }

      // Check that the subscription is still subscribed.
      Subscription subscription = parentId == null ? null : subscriptions.get(parentId);
      if (subscription == null) {
        if (DEBUG) {
          Log.d(TAG, "onLoadChildren for id that isn't subscribed id=" + parentId);
        }
        return;
      }

      // Tell the app.
      SubscriptionCallback subscriptionCallback = subscription.getCallback(options);
      if (subscriptionCallback != null) {
        if (options == null) {
          if (list == null) {
            subscriptionCallback.onError(parentId);
          } else {
            this.notifyChildrenChangedOptions = notifyChildrenChangedOptions;
            subscriptionCallback.onChildrenLoaded(parentId, list);
            this.notifyChildrenChangedOptions = null;
          }
        } else {
          if (list == null) {
            subscriptionCallback.onError(parentId, options);
          } else {
            this.notifyChildrenChangedOptions = notifyChildrenChangedOptions;
            subscriptionCallback.onChildrenLoaded(parentId, list, options);
            this.notifyChildrenChangedOptions = null;
          }
        }
      }
    }

    @Nullable
    @Override
    public Bundle getNotifyChildrenChangedOptions() {
      return notifyChildrenChangedOptions;
    }
  }

  @RequiresApi(26)
  static class MediaBrowserImplApi26 extends MediaBrowserImplApi23 {
    MediaBrowserImplApi26(
        Context context,
        ComponentName serviceComponent,
        ConnectionCallback callback,
        @Nullable Bundle rootHints) {
      super(context, serviceComponent, callback, rootHints);
    }

    @Override
    public void subscribe(
        String parentId, @Nullable Bundle options, SubscriptionCallback callback) {
      // From service v2, we use compat code when subscribing.
      // This is to prevent ClassNotFoundException when options has Parcelable in it.
      if (serviceBinderWrapper == null || serviceVersion < SERVICE_VERSION_2) {
        if (options == null) {
          browserFwk.subscribe(parentId, callback.subscriptionCallbackFwk);
        } else {
          browserFwk.subscribe(parentId, options, callback.subscriptionCallbackFwk);
        }
      } else {
        super.subscribe(parentId, options, callback);
      }
    }

    @Override
    public void unsubscribe(String parentId, @Nullable SubscriptionCallback callback) {
      // From service v2, we use compat code when subscribing.
      // This is to prevent ClassNotFoundException when options has Parcelable in it.
      if (serviceBinderWrapper == null || serviceVersion < SERVICE_VERSION_2) {
        if (callback == null) {
          browserFwk.unsubscribe(parentId);
        } else {
          browserFwk.unsubscribe(parentId, callback.subscriptionCallbackFwk);
        }
      } else {
        super.unsubscribe(parentId, callback);
      }
    }
  }

  private static class Subscription {
    private final List<SubscriptionCallback> callbacks;
    private final List<@NullableType Bundle> optionsList;

    public Subscription() {
      callbacks = new ArrayList<>();
      optionsList = new ArrayList<>();
    }

    public boolean isEmpty() {
      return callbacks.isEmpty();
    }

    public List<@NullableType Bundle> getOptionsList() {
      return optionsList;
    }

    public List<SubscriptionCallback> getCallbacks() {
      return callbacks;
    }

    @Nullable
    public SubscriptionCallback getCallback(@Nullable Bundle options) {
      for (int i = 0; i < optionsList.size(); ++i) {
        if (MediaBrowserCompatUtils.areSameOptions(optionsList.get(i), options)) {
          return callbacks.get(i);
        }
      }
      return null;
    }

    public void putCallback(@Nullable Bundle options, SubscriptionCallback callback) {
      for (int i = 0; i < optionsList.size(); ++i) {
        if (MediaBrowserCompatUtils.areSameOptions(optionsList.get(i), options)) {
          callbacks.set(i, callback);
          return;
        }
      }
      callbacks.add(callback);
      optionsList.add(options);
    }
  }

  private static class CallbackHandler extends Handler {
    private final WeakReference<MediaBrowserServiceCallbackImpl> callbackImplRef;
    @Nullable private WeakReference<Messenger> callbacksMessengerRef;

    CallbackHandler(MediaBrowserServiceCallbackImpl callbackImpl) {
      super();
      callbackImplRef = new WeakReference<>(callbackImpl);
    }

    @Override
    public void handleMessage(Message msg) {
      if (callbacksMessengerRef == null) {
        return;
      }
      Messenger callbacksMessenger = callbacksMessengerRef.get();
      MediaBrowserServiceCallbackImpl serviceCallback = callbackImplRef.get();
      if (callbacksMessenger == null || serviceCallback == null) {
        return;
      }
      Bundle data = msg.getData();
      MediaSessionCompat.ensureClassLoader(data);

      try {
        switch (msg.what) {
          case SERVICE_MSG_ON_LOAD_CHILDREN:
            {
              Bundle options = data.getBundle(DATA_OPTIONS);
              MediaSessionCompat.ensureClassLoader(options);

              Bundle notifyChildrenChangedOptions =
                  data.getBundle(DATA_NOTIFY_CHILDREN_CHANGED_OPTIONS);
              MediaSessionCompat.ensureClassLoader(notifyChildrenChangedOptions);

              serviceCallback.onLoadChildren(
                  callbacksMessenger,
                  data.getString(DATA_MEDIA_ITEM_ID),
                  LegacyParcelableUtil.convertList(
                      data.getParcelableArrayList(DATA_MEDIA_ITEM_LIST), MediaItem.CREATOR),
                  options,
                  notifyChildrenChangedOptions);
              break;
            }
          default:
            Log.w(
                TAG,
                "Unhandled message: "
                    + msg
                    + "\n  Client version: "
                    + CLIENT_VERSION_CURRENT
                    + "\n  Service version: "
                    + msg.arg1);
        }
      } catch (BadParcelableException e) {
        // Do not print the exception here, since it is already done by the Parcel class.
        Log.e(TAG, "Could not unparcel the data.");
      }
    }

    void setCallbacksMessenger(@Nullable Messenger callbacksMessenger) {
      callbacksMessengerRef = new WeakReference<>(callbacksMessenger);
    }
  }

  private static class ServiceBinderWrapper {
    private final Messenger messenger;
    @Nullable private final Bundle rootHints;

    public ServiceBinderWrapper(IBinder target, @Nullable Bundle rootHints) {
      messenger = new Messenger(target);
      this.rootHints = rootHints;
    }

    void addSubscription(
        String parentId,
        IBinder callbackToken,
        @Nullable Bundle options,
        Messenger callbacksMessenger)
        throws RemoteException {
      Bundle data = new Bundle();
      data.putString(DATA_MEDIA_ITEM_ID, parentId);
      data.putBinder(DATA_CALLBACK_TOKEN, callbackToken);
      data.putBundle(DATA_OPTIONS, options);
      sendRequest(CLIENT_MSG_ADD_SUBSCRIPTION, data, callbacksMessenger);
    }

    void removeSubscription(
        String parentId, @Nullable IBinder callbackToken, Messenger callbacksMessenger)
        throws RemoteException {
      Bundle data = new Bundle();
      data.putString(DATA_MEDIA_ITEM_ID, parentId);
      data.putBinder(DATA_CALLBACK_TOKEN, callbackToken);
      sendRequest(CLIENT_MSG_REMOVE_SUBSCRIPTION, data, callbacksMessenger);
    }

    void getMediaItem(String mediaId, ResultReceiver receiver, Messenger callbacksMessenger)
        throws RemoteException {
      Bundle data = new Bundle();
      data.putString(DATA_MEDIA_ITEM_ID, mediaId);
      data.putParcelable(
          DATA_RESULT_RECEIVER, LegacyParcelableUtil.convert(receiver, ResultReceiver.CREATOR));
      sendRequest(CLIENT_MSG_GET_MEDIA_ITEM, data, callbacksMessenger);
    }

    void registerCallbackMessenger(Context context, Messenger callbackMessenger)
        throws RemoteException {
      Bundle data = new Bundle();
      data.putString(DATA_PACKAGE_NAME, context.getPackageName());
      data.putInt(DATA_CALLING_PID, Process.myPid());
      data.putBundle(DATA_ROOT_HINTS, rootHints);
      sendRequest(CLIENT_MSG_REGISTER_CALLBACK_MESSENGER, data, callbackMessenger);
    }

    void unregisterCallbackMessenger(Messenger callbackMessenger) throws RemoteException {
      sendRequest(CLIENT_MSG_UNREGISTER_CALLBACK_MESSENGER, null, callbackMessenger);
    }

    void search(
        String query,
        @Nullable Bundle extras,
        ResultReceiver receiver,
        Messenger callbacksMessenger)
        throws RemoteException {
      Bundle data = new Bundle();
      data.putString(DATA_SEARCH_QUERY, query);
      data.putBundle(DATA_SEARCH_EXTRAS, extras);
      data.putParcelable(
          DATA_RESULT_RECEIVER, LegacyParcelableUtil.convert(receiver, ResultReceiver.CREATOR));
      sendRequest(CLIENT_MSG_SEARCH, data, callbacksMessenger);
    }

    void sendCustomAction(
        String action,
        @Nullable Bundle extras,
        ResultReceiver receiver,
        Messenger callbacksMessenger)
        throws RemoteException {
      Bundle data = new Bundle();
      data.putString(DATA_CUSTOM_ACTION, action);
      data.putBundle(DATA_CUSTOM_ACTION_EXTRAS, extras);
      data.putParcelable(
          DATA_RESULT_RECEIVER, LegacyParcelableUtil.convert(receiver, ResultReceiver.CREATOR));
      sendRequest(CLIENT_MSG_SEND_CUSTOM_ACTION, data, callbacksMessenger);
    }

    private void sendRequest(int what, @Nullable Bundle data, Messenger cbMessenger)
        throws RemoteException {
      Message msg = Message.obtain();
      msg.what = what;
      msg.arg1 = CLIENT_VERSION_CURRENT;
      if (data != null) {
        msg.setData(data);
      }
      msg.replyTo = cbMessenger;
      messenger.send(msg);
    }
  }

  @SuppressLint("RestrictedApi")
  private static class ItemReceiver extends ResultReceiver {
    private final String mediaId;
    private final ItemCallback callback;

    ItemReceiver(String mediaId, ItemCallback callback, Handler handler) {
      super(handler);
      this.mediaId = mediaId;
      this.callback = callback;
    }

    @Override
    protected void onReceiveResult(int resultCode, @Nullable Bundle resultData) {
      if (resultData != null) {
        resultData = MediaSessionCompat.unparcelWithClassLoader(resultData);
      }
      if (resultCode != MediaBrowserServiceCompat.RESULT_OK
          || resultData == null
          || !resultData.containsKey(MediaBrowserServiceCompat.KEY_MEDIA_ITEM)) {
        callback.onError(mediaId);
        return;
      }
      MediaItem item =
          LegacyParcelableUtil.convert(
              resultData.getParcelable(MediaBrowserServiceCompat.KEY_MEDIA_ITEM),
              MediaItem.CREATOR);
      callback.onItemLoaded(item);
    }
  }

  @SuppressLint("RestrictedApi")
  private static class SearchResultReceiver extends ResultReceiver {
    private final String query;
    @Nullable private final Bundle extras;
    private final SearchCallback callback;

    SearchResultReceiver(
        String query, @Nullable Bundle extras, SearchCallback callback, Handler handler) {
      super(handler);
      this.query = query;
      this.extras = extras;
      this.callback = callback;
    }

    @Override
    protected void onReceiveResult(int resultCode, @Nullable Bundle resultData) {
      if (resultData != null) {
        resultData = MediaSessionCompat.unparcelWithClassLoader(resultData);
      }
      if (resultCode != MediaBrowserServiceCompat.RESULT_OK
          || resultData == null
          || !resultData.containsKey(MediaBrowserServiceCompat.KEY_SEARCH_RESULTS)) {
        callback.onError(query, extras);
        return;
      }
      Parcelable[] items =
          resultData.getParcelableArray(MediaBrowserServiceCompat.KEY_SEARCH_RESULTS);
      if (items != null) {
        List<MediaItem> results = new ArrayList<>(items.length);
        for (Parcelable item : items) {
          results.add(LegacyParcelableUtil.convert(item, MediaItem.CREATOR));
        }
        callback.onSearchResult(query, extras, results);
      } else {
        callback.onError(query, extras);
      }
    }
  }

  @SuppressLint("RestrictedApi")
  private static class CustomActionResultReceiver extends ResultReceiver {
    private final String action;
    @Nullable private final Bundle extras;
    @Nullable private final CustomActionCallback callback;

    CustomActionResultReceiver(
        String action,
        @Nullable Bundle extras,
        @Nullable CustomActionCallback callback,
        Handler handler) {
      super(handler);
      this.action = action;
      this.extras = extras;
      this.callback = callback;
    }

    @Override
    protected void onReceiveResult(int resultCode, @Nullable Bundle resultData) {
      if (callback == null) {
        return;
      }
      MediaSessionCompat.ensureClassLoader(resultData);
      switch (resultCode) {
        case MediaBrowserServiceCompat.RESULT_PROGRESS_UPDATE:
          callback.onProgressUpdate(
              action,
              extras == null ? Bundle.EMPTY : extras,
              resultData == null ? Bundle.EMPTY : resultData);
          break;
        case MediaBrowserServiceCompat.RESULT_OK:
          callback.onResult(action, extras, resultData);
          break;
        case MediaBrowserServiceCompat.RESULT_ERROR:
          callback.onError(action, extras, resultData);
          break;
        default:
          Log.w(
              TAG,
              "Unknown result code: "
                  + resultCode
                  + " (extras="
                  + extras
                  + ", resultData="
                  + resultData
                  + ")");
          break;
      }
    }
  }
}
