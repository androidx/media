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

import android.annotation.SuppressLint;
import android.graphics.Bitmap;
import android.media.MediaDescription;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.RestrictTo;
import androidx.media3.common.util.UnstableApi;

/**
 * A simple set of metadata for a media item suitable for display. This can be created using the
 * Builder.
 */
@UnstableApi
@RestrictTo(LIBRARY)
@SuppressLint("BanParcelableUsage")
public final class MediaDescriptionCompat implements Parcelable {

  /**
   * Used as a long extra field to indicate the bluetooth folder type of the media item as specified
   * in the section 6.10.2.2 of the Bluetooth AVRCP 1.5. This is valid only for {@link
   * MediaBrowserCompat.MediaItem} with {@link MediaBrowserCompat.MediaItem#FLAG_BROWSABLE}. The
   * value should be one of the following:
   *
   * <ul>
   *   <li>{@link #BT_FOLDER_TYPE_MIXED}
   *   <li>{@link #BT_FOLDER_TYPE_TITLES}
   *   <li>{@link #BT_FOLDER_TYPE_ALBUMS}
   *   <li>{@link #BT_FOLDER_TYPE_ARTISTS}
   *   <li>{@link #BT_FOLDER_TYPE_GENRES}
   *   <li>{@link #BT_FOLDER_TYPE_PLAYLISTS}
   *   <li>{@link #BT_FOLDER_TYPE_YEARS}
   * </ul>
   *
   * @see #getExtras()
   */
  @SuppressLint("InlinedApi") // Inlined compile time constant
  public static final String EXTRA_BT_FOLDER_TYPE = MediaDescription.EXTRA_BT_FOLDER_TYPE;

  /**
   * The type of folder that is unknown or contains media elements of mixed types as specified in
   * the section 6.10.2.2 of the Bluetooth AVRCP 1.5.
   */
  public static final long BT_FOLDER_TYPE_MIXED = 0;

  /**
   * The type of folder that contains media elements only as specified in the section 6.10.2.2 of
   * the Bluetooth AVRCP 1.5.
   */
  public static final long BT_FOLDER_TYPE_TITLES = 1;

  /**
   * The type of folder that contains folders categorized by album as specified in the section
   * 6.10.2.2 of the Bluetooth AVRCP 1.5.
   */
  public static final long BT_FOLDER_TYPE_ALBUMS = 2;

  /**
   * The type of folder that contains folders categorized by artist as specified in the section
   * 6.10.2.2 of the Bluetooth AVRCP 1.5.
   */
  public static final long BT_FOLDER_TYPE_ARTISTS = 3;

  /**
   * The type of folder that contains folders categorized by genre as specified in the section
   * 6.10.2.2 of the Bluetooth AVRCP 1.5.
   */
  public static final long BT_FOLDER_TYPE_GENRES = 4;

  /**
   * The type of folder that contains folders categorized by playlist as specified in the section
   * 6.10.2.2 of the Bluetooth AVRCP 1.5.
   */
  public static final long BT_FOLDER_TYPE_PLAYLISTS = 5;

  /**
   * The type of folder that contains folders categorized by year as specified in the section
   * 6.10.2.2 of the Bluetooth AVRCP 1.5.
   */
  public static final long BT_FOLDER_TYPE_YEARS = 6;

  /**
   * Used as a long extra field to indicate the download status of the media item. The value should
   * be one of the following:
   *
   * <ul>
   *   <li>{@link #STATUS_NOT_DOWNLOADED}
   *   <li>{@link #STATUS_DOWNLOADING}
   *   <li>{@link #STATUS_DOWNLOADED}
   * </ul>
   *
   * @see #getExtras()
   */
  public static final String EXTRA_DOWNLOAD_STATUS = "android.media.extra.DOWNLOAD_STATUS";

  /**
   * The status value to indicate the media item is not downloaded.
   *
   * @see #EXTRA_DOWNLOAD_STATUS
   */
  public static final long STATUS_NOT_DOWNLOADED = 0;

  /**
   * The status value to indicate the media item is being downloaded.
   *
   * @see #EXTRA_DOWNLOAD_STATUS
   */
  public static final long STATUS_DOWNLOADING = 1;

  /**
   * The status value to indicate the media item is downloaded for later offline playback.
   *
   * @see #EXTRA_DOWNLOAD_STATUS
   */
  public static final long STATUS_DOWNLOADED = 2;

  /**
   * Custom key to store a media URI on API 21-22 devices (before it became part of the framework
   * class) when parceling/converting to and from framework objects.
   */
  public static final String DESCRIPTION_KEY_MEDIA_URI =
      "android.support.v4.media.description.MEDIA_URI";

  /** Custom key to store whether the original Bundle provided by the developer was null */
  public static final String DESCRIPTION_KEY_NULL_BUNDLE_FLAG =
      "android.support.v4.media.description.NULL_BUNDLE_FLAG";

  /** A unique persistent id for the content or null. */
  @Nullable private final String mMediaId;

  /** A primary title suitable for display or null. */
  @Nullable private final CharSequence mTitle;

  /** A subtitle suitable for display or null. */
  @Nullable private final CharSequence mSubtitle;

  /** A description suitable for display or null. */
  @Nullable private final CharSequence mDescription;

  /** A bitmap icon suitable for display or null. */
  @Nullable private final Bitmap mIcon;

  /** A Uri for an icon suitable for display or null. */
  @Nullable private final Uri mIconUri;

  /** Extras for opaque use by apps/system. */
  @Nullable private final Bundle mExtras;

  /** A Uri to identify this content. */
  @Nullable private final Uri mMediaUri;

  /** A cached copy of the equivalent framework object. */
  @Nullable private MediaDescription mDescriptionFwk;

  MediaDescriptionCompat(
      @Nullable String mediaId,
      @Nullable CharSequence title,
      @Nullable CharSequence subtitle,
      @Nullable CharSequence description,
      @Nullable Bitmap icon,
      @Nullable Uri iconUri,
      @Nullable Bundle extras,
      @Nullable Uri mediaUri) {
    mMediaId = mediaId;
    mTitle = title;
    mSubtitle = subtitle;
    mDescription = description;
    mIcon = icon;
    mIconUri = iconUri;
    mExtras = extras;
    mMediaUri = mediaUri;
  }

  /** Returns the media id or null. See {@link MediaMetadataCompat#METADATA_KEY_MEDIA_ID}. */
  @Nullable
  public String getMediaId() {
    return mMediaId;
  }

  /**
   * Returns a title suitable for display or null.
   *
   * @return A title or null.
   */
  @Nullable
  public CharSequence getTitle() {
    return mTitle;
  }

  /**
   * Returns a subtitle suitable for display or null.
   *
   * @return A subtitle or null.
   */
  @Nullable
  public CharSequence getSubtitle() {
    return mSubtitle;
  }

  /**
   * Returns a description suitable for display or null.
   *
   * @return A description or null.
   */
  @Nullable
  public CharSequence getDescription() {
    return mDescription;
  }

  /**
   * Returns a bitmap icon suitable for display or null.
   *
   * @return An icon or null.
   */
  @Nullable
  public Bitmap getIconBitmap() {
    return mIcon;
  }

  /**
   * Returns a Uri for an icon suitable for display or null.
   *
   * @return An icon uri or null.
   */
  @Nullable
  public Uri getIconUri() {
    return mIconUri;
  }

  /**
   * Returns any extras that were added to the description.
   *
   * @return A bundle of extras or null.
   */
  @Nullable
  public Bundle getExtras() {
    return mExtras;
  }

  /**
   * Returns a Uri representing this content or null.
   *
   * @return A media Uri or null.
   */
  @Nullable
  public Uri getMediaUri() {
    return mMediaUri;
  }

  @Override
  public int describeContents() {
    return 0;
  }

  @Override
  public void writeToParcel(Parcel dest, int flags) {
    getMediaDescription().writeToParcel(dest, flags);
  }

  @Override
  public String toString() {
    return mTitle + ", " + mSubtitle + ", " + mDescription;
  }

  /**
   * Gets the underlying framework {@link android.media.MediaDescription} object.
   *
   * @return An equivalent {@link android.media.MediaDescription} object.
   */
  public MediaDescription getMediaDescription() {
    if (mDescriptionFwk != null) {
      return mDescriptionFwk;
    }
    MediaDescription.Builder bob = new MediaDescription.Builder();
    bob.setMediaId(mMediaId);
    bob.setTitle(mTitle);
    bob.setSubtitle(mSubtitle);
    bob.setDescription(mDescription);
    bob.setIconBitmap(mIcon);
    bob.setIconUri(mIconUri);
    // Media URI was not added until API 23, so add it to the Bundle of extras to
    // ensure the data is not lost - this ensures that
    // fromMediaDescription(getMediaDescription(mediaDescriptionCompat)) returns
    // an equivalent MediaDescriptionCompat on all API levels
    if (Build.VERSION.SDK_INT < 23 && mMediaUri != null) {
      Bundle extras;
      if (mExtras == null) {
        extras = new Bundle();
        extras.putBoolean(DESCRIPTION_KEY_NULL_BUNDLE_FLAG, true);
      } else {
        extras = new Bundle(mExtras);
      }
      extras.putParcelable(DESCRIPTION_KEY_MEDIA_URI, mMediaUri);
      bob.setExtras(extras);
    } else {
      bob.setExtras(mExtras);
    }
    if (Build.VERSION.SDK_INT >= 23) {
      Api23Impl.setMediaUri(bob, mMediaUri);
    }
    mDescriptionFwk = bob.build();

    return mDescriptionFwk;
  }

  /**
   * Creates an instance from a framework {@link android.media.MediaDescription} object.
   *
   * @param description A {@link android.media.MediaDescription} object.
   * @return An equivalent {@link MediaMetadataCompat} object.
   */
  public static MediaDescriptionCompat fromMediaDescription(MediaDescription description) {
    Builder bob = new Builder();
    bob.setMediaId(description.getMediaId());
    bob.setTitle(description.getTitle());
    bob.setSubtitle(description.getSubtitle());
    bob.setDescription(description.getDescription());
    bob.setIconBitmap(description.getIconBitmap());
    bob.setIconUri(description.getIconUri());
    Bundle extras = description.getExtras();
    extras = MediaSessionCompat.unparcelWithClassLoader(extras);
    if (extras != null) {
      extras = new Bundle(extras);
    }
    Uri mediaUri = null;
    if (extras != null) {
      mediaUri = extras.getParcelable(DESCRIPTION_KEY_MEDIA_URI);
      if (mediaUri != null) {
        if (extras.containsKey(DESCRIPTION_KEY_NULL_BUNDLE_FLAG) && extras.size() == 2) {
          // The extras were only created for the media URI, so we set it back to null to
          // ensure mediaDescriptionCompat.getExtras() equals
          // fromMediaDescription(getMediaDescription(mediaDescriptionCompat)).getExtras()
          extras = null;
        } else {
          // Remove media URI keys to ensure mediaDescriptionCompat.getExtras().keySet()
          // equals fromMediaDescription(getMediaDescription(mediaDescriptionCompat))
          // .getExtras().keySet()
          extras.remove(DESCRIPTION_KEY_MEDIA_URI);
          extras.remove(DESCRIPTION_KEY_NULL_BUNDLE_FLAG);
        }
      }
    }
    bob.setExtras(extras);
    if (mediaUri != null) {
      bob.setMediaUri(mediaUri);
    } else if (Build.VERSION.SDK_INT >= 23) {
      bob.setMediaUri(Api23Impl.getMediaUri(description));
    }
    MediaDescriptionCompat descriptionCompat = bob.build();
    descriptionCompat.mDescriptionFwk = description;
    return descriptionCompat;
  }

  public static final Parcelable.Creator<MediaDescriptionCompat> CREATOR =
      new Parcelable.Creator<MediaDescriptionCompat>() {
        @Override
        public MediaDescriptionCompat createFromParcel(Parcel in) {
          return fromMediaDescription(MediaDescription.CREATOR.createFromParcel(in));
        }

        @Override
        public MediaDescriptionCompat[] newArray(int size) {
          return new MediaDescriptionCompat[size];
        }
      };

  /** Builder for {@link MediaDescriptionCompat} objects. */
  public static final class Builder {
    @Nullable private String mMediaId;
    @Nullable private CharSequence mTitle;
    @Nullable private CharSequence mSubtitle;
    @Nullable private CharSequence mDescription;
    @Nullable private Bitmap mIcon;
    @Nullable private Uri mIconUri;
    @Nullable private Bundle mExtras;
    @Nullable private Uri mMediaUri;

    /** Creates an initially empty builder. */
    public Builder() {}

    /**
     * Sets the media id.
     *
     * @param mediaId The unique id for the item or null.
     * @return this
     */
    public Builder setMediaId(@Nullable String mediaId) {
      mMediaId = mediaId;
      return this;
    }

    /**
     * Sets the title.
     *
     * @param title A title suitable for display to the user or null.
     * @return this
     */
    public Builder setTitle(@Nullable CharSequence title) {
      mTitle = title;
      return this;
    }

    /**
     * Sets the subtitle.
     *
     * @param subtitle A subtitle suitable for display to the user or null.
     * @return this
     */
    public Builder setSubtitle(@Nullable CharSequence subtitle) {
      mSubtitle = subtitle;
      return this;
    }

    /**
     * Sets the description.
     *
     * @param description A description suitable for display to the user or null.
     * @return this
     */
    public Builder setDescription(@Nullable CharSequence description) {
      mDescription = description;
      return this;
    }

    /**
     * Sets the icon.
     *
     * @param icon A {@link Bitmap} icon suitable for display to the user or null.
     * @return this
     */
    public Builder setIconBitmap(@Nullable Bitmap icon) {
      mIcon = icon;
      return this;
    }

    /**
     * Sets the icon uri.
     *
     * @param iconUri A {@link Uri} for an icon suitable for display to the user or null.
     * @return this
     */
    public Builder setIconUri(@Nullable Uri iconUri) {
      mIconUri = iconUri;
      return this;
    }

    /**
     * Sets a bundle of extras.
     *
     * @param extras The extras to include with this description or null.
     * @return this
     */
    public Builder setExtras(@Nullable Bundle extras) {
      mExtras = extras;
      return this;
    }

    /**
     * Sets the media uri.
     *
     * @param mediaUri The content's {@link Uri} for the item or null.
     * @return this
     */
    public Builder setMediaUri(@Nullable Uri mediaUri) {
      mMediaUri = mediaUri;
      return this;
    }

    /**
     * Creates a {@link MediaDescriptionCompat} instance with the specified fields.
     *
     * @return A MediaDescriptionCompat instance.
     */
    public MediaDescriptionCompat build() {
      return new MediaDescriptionCompat(
          mMediaId, mTitle, mSubtitle, mDescription, mIcon, mIconUri, mExtras, mMediaUri);
    }
  }

  @RequiresApi(23)
  private static class Api23Impl {
    private Api23Impl() {}

    static void setMediaUri(MediaDescription.Builder builder, @Nullable Uri mediaUri) {
      builder.setMediaUri(mediaUri);
    }

    @Nullable
    static Uri getMediaUri(MediaDescription description) {
      return description.getMediaUri();
    }
  }
}
