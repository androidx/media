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
import static com.google.common.base.Preconditions.checkNotNull;

import android.annotation.SuppressLint;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.net.Uri;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;
import androidx.annotation.Nullable;
import androidx.annotation.RestrictTo;
import androidx.annotation.StringDef;
import androidx.collection.ArrayMap;
import androidx.media3.session.legacy.MediaControllerCompat.TransportControls;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/** Contains metadata about an item, such as the title, artist, etc. */
@RestrictTo(LIBRARY)
@SuppressLint("BanParcelableUsage")
public final class MediaMetadataCompat implements Parcelable {
  private static final String TAG = "MediaMetadata";

  /** The title of the media. */
  public static final String METADATA_KEY_TITLE = MediaMetadata.METADATA_KEY_TITLE;

  /** The artist of the media. */
  public static final String METADATA_KEY_ARTIST = MediaMetadata.METADATA_KEY_ARTIST;

  /**
   * The duration of the media in ms. A negative duration indicates that the duration is unknown (or
   * infinite).
   */
  public static final String METADATA_KEY_DURATION = MediaMetadata.METADATA_KEY_DURATION;

  /** The album title for the media. */
  public static final String METADATA_KEY_ALBUM = MediaMetadata.METADATA_KEY_ALBUM;

  /** The author of the media. */
  public static final String METADATA_KEY_AUTHOR = MediaMetadata.METADATA_KEY_AUTHOR;

  /** The writer of the media. */
  public static final String METADATA_KEY_WRITER = MediaMetadata.METADATA_KEY_WRITER;

  /** The composer of the media. */
  public static final String METADATA_KEY_COMPOSER = MediaMetadata.METADATA_KEY_COMPOSER;

  /** The compilation status of the media. */
  public static final String METADATA_KEY_COMPILATION = MediaMetadata.METADATA_KEY_COMPILATION;

  /**
   * The date the media was created or published. The format is unspecified but RFC 3339 is
   * recommended.
   */
  public static final String METADATA_KEY_DATE = MediaMetadata.METADATA_KEY_DATE;

  /** The year the media was created or published as a long. */
  public static final String METADATA_KEY_YEAR = MediaMetadata.METADATA_KEY_YEAR;

  /** The genre of the media. */
  public static final String METADATA_KEY_GENRE = MediaMetadata.METADATA_KEY_GENRE;

  /** The track number for the media. */
  public static final String METADATA_KEY_TRACK_NUMBER = MediaMetadata.METADATA_KEY_TRACK_NUMBER;

  /** The number of tracks in the media's original source. */
  public static final String METADATA_KEY_NUM_TRACKS = MediaMetadata.METADATA_KEY_NUM_TRACKS;

  /** The disc number for the media's original source. */
  public static final String METADATA_KEY_DISC_NUMBER = MediaMetadata.METADATA_KEY_DISC_NUMBER;

  /** The artist for the album of the media's original source. */
  public static final String METADATA_KEY_ALBUM_ARTIST = MediaMetadata.METADATA_KEY_ALBUM_ARTIST;

  /**
   * The artwork for the media as a {@link Bitmap}.
   *
   * <p>The artwork should be relatively small and may be scaled down if it is too large. For higher
   * resolution artwork {@link #METADATA_KEY_ART_URI} should be used instead.
   */
  public static final String METADATA_KEY_ART = MediaMetadata.METADATA_KEY_ART;

  /** The artwork for the media as a Uri style String. */
  public static final String METADATA_KEY_ART_URI = MediaMetadata.METADATA_KEY_ART_URI;

  /**
   * The artwork for the album of the media's original source as a {@link Bitmap}. The artwork
   * should be relatively small and may be scaled down if it is too large. For higher resolution
   * artwork {@link #METADATA_KEY_ALBUM_ART_URI} should be used instead.
   */
  public static final String METADATA_KEY_ALBUM_ART = MediaMetadata.METADATA_KEY_ALBUM_ART;

  /** The artwork for the album of the media's original source as a Uri style String. */
  public static final String METADATA_KEY_ALBUM_ART_URI = MediaMetadata.METADATA_KEY_ALBUM_ART_URI;

  /**
   * The user's rating for the media.
   *
   * @see RatingCompat
   */
  public static final String METADATA_KEY_USER_RATING = MediaMetadata.METADATA_KEY_USER_RATING;

  /**
   * The overall rating for the media.
   *
   * @see RatingCompat
   */
  public static final String METADATA_KEY_RATING = MediaMetadata.METADATA_KEY_RATING;

  /**
   * A title that is suitable for display to the user. This will generally be the same as {@link
   * #METADATA_KEY_TITLE} but may differ for some formats. When displaying media described by this
   * metadata this should be preferred if present.
   */
  public static final String METADATA_KEY_DISPLAY_TITLE = MediaMetadata.METADATA_KEY_DISPLAY_TITLE;

  /**
   * A subtitle that is suitable for display to the user. When displaying a second line for media
   * described by this metadata this should be preferred to other fields if present.
   */
  public static final String METADATA_KEY_DISPLAY_SUBTITLE =
      MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE;

  /**
   * A description that is suitable for display to the user. When displaying more information for
   * media described by this metadata this should be preferred to other fields if present.
   */
  public static final String METADATA_KEY_DISPLAY_DESCRIPTION =
      MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION;

  /**
   * An icon or thumbnail that is suitable for display to the user. When displaying an icon for
   * media described by this metadata this should be preferred to other fields if present. This must
   * be a {@link Bitmap}.
   *
   * <p>The icon should be relatively small and may be scaled down if it is too large. For higher
   * resolution artwork {@link #METADATA_KEY_DISPLAY_ICON_URI} should be used instead.
   */
  public static final String METADATA_KEY_DISPLAY_ICON = MediaMetadata.METADATA_KEY_DISPLAY_ICON;

  /**
   * An icon or thumbnail that is suitable for display to the user. When displaying more information
   * for media described by this metadata the display description should be preferred to other
   * fields when present. This must be a Uri style String.
   */
  public static final String METADATA_KEY_DISPLAY_ICON_URI =
      MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI;

  /**
   * A String key for identifying the content. This value is specific to the service providing the
   * content. If used, this should be a persistent unique key for the underlying content.
   */
  public static final String METADATA_KEY_MEDIA_ID = MediaMetadata.METADATA_KEY_MEDIA_ID;

  /**
   * A Uri formatted String representing the content. This value is specific to the service
   * providing the content. It may be used with {@link TransportControls#playFromUri(Uri, Bundle)}
   * to initiate playback when provided by a {@link MediaBrowserCompat} connected to the same app.
   */
  @SuppressLint("InlinedApi") // Inlined compile time constant
  public static final String METADATA_KEY_MEDIA_URI = MediaMetadata.METADATA_KEY_MEDIA_URI;

  /**
   * The bluetooth folder type of the media specified in the section 6.10.2.2 of the Bluetooth AVRCP
   * 1.5. It should be one of the following:
   *
   * <ul>
   *   <li>{@link MediaDescriptionCompat#BT_FOLDER_TYPE_MIXED}
   *   <li>{@link MediaDescriptionCompat#BT_FOLDER_TYPE_TITLES}
   *   <li>{@link MediaDescriptionCompat#BT_FOLDER_TYPE_ALBUMS}
   *   <li>{@link MediaDescriptionCompat#BT_FOLDER_TYPE_ARTISTS}
   *   <li>{@link MediaDescriptionCompat#BT_FOLDER_TYPE_GENRES}
   *   <li>{@link MediaDescriptionCompat#BT_FOLDER_TYPE_PLAYLISTS}
   *   <li>{@link MediaDescriptionCompat#BT_FOLDER_TYPE_YEARS}
   * </ul>
   */
  @SuppressLint("InlinedApi") // Inlined compile time constant
  public static final String METADATA_KEY_BT_FOLDER_TYPE =
      MediaMetadata.METADATA_KEY_BT_FOLDER_TYPE;

  /**
   * Whether the media is an advertisement. A value of 0 indicates it is not an advertisement. A
   * value of 1 or non-zero indicates it is an advertisement. If not specified, this value is set to
   * 0 by default.
   */
  public static final String METADATA_KEY_ADVERTISEMENT = "android.media.metadata.ADVERTISEMENT";

  /**
   * The download status of the media which will be used for later offline playback. It should be
   * one of the following:
   *
   * <ul>
   *   <li>{@link MediaDescriptionCompat#STATUS_NOT_DOWNLOADED}
   *   <li>{@link MediaDescriptionCompat#STATUS_DOWNLOADING}
   *   <li>{@link MediaDescriptionCompat#STATUS_DOWNLOADED}
   * </ul>
   */
  public static final String METADATA_KEY_DOWNLOAD_STATUS =
      "android.media.metadata.DOWNLOAD_STATUS";

  @StringDef(
      value = {
        METADATA_KEY_TITLE,
        METADATA_KEY_ARTIST,
        METADATA_KEY_ALBUM,
        METADATA_KEY_AUTHOR,
        METADATA_KEY_WRITER,
        METADATA_KEY_COMPOSER,
        METADATA_KEY_COMPILATION,
        METADATA_KEY_DATE,
        METADATA_KEY_GENRE,
        METADATA_KEY_ALBUM_ARTIST,
        METADATA_KEY_ART_URI,
        METADATA_KEY_ALBUM_ART_URI,
        METADATA_KEY_DISPLAY_TITLE,
        METADATA_KEY_DISPLAY_SUBTITLE,
        METADATA_KEY_DISPLAY_DESCRIPTION,
        METADATA_KEY_DISPLAY_ICON_URI,
        METADATA_KEY_MEDIA_ID,
        METADATA_KEY_MEDIA_URI
      },
      open = true)
  @Retention(RetentionPolicy.SOURCE)
  private @interface TextKey {}

  @StringDef(
      value = {
        METADATA_KEY_DURATION,
        METADATA_KEY_YEAR,
        METADATA_KEY_TRACK_NUMBER,
        METADATA_KEY_NUM_TRACKS,
        METADATA_KEY_DISC_NUMBER,
        METADATA_KEY_BT_FOLDER_TYPE,
        METADATA_KEY_ADVERTISEMENT,
        METADATA_KEY_DOWNLOAD_STATUS
      },
      open = true)
  @Retention(RetentionPolicy.SOURCE)
  private @interface LongKey {}

  @StringDef({METADATA_KEY_ART, METADATA_KEY_ALBUM_ART, METADATA_KEY_DISPLAY_ICON})
  @Retention(RetentionPolicy.SOURCE)
  private @interface BitmapKey {}

  @StringDef({METADATA_KEY_USER_RATING, METADATA_KEY_RATING})
  @Retention(RetentionPolicy.SOURCE)
  private @interface RatingKey {}

  static final int METADATA_TYPE_LONG = 0;
  static final int METADATA_TYPE_TEXT = 1;
  static final int METADATA_TYPE_BITMAP = 2;
  static final int METADATA_TYPE_RATING = 3;
  static final ArrayMap<String, Integer> METADATA_KEYS_TYPE;

  static {
    METADATA_KEYS_TYPE = new ArrayMap<>();
    METADATA_KEYS_TYPE.put(METADATA_KEY_TITLE, METADATA_TYPE_TEXT);
    METADATA_KEYS_TYPE.put(METADATA_KEY_ARTIST, METADATA_TYPE_TEXT);
    METADATA_KEYS_TYPE.put(METADATA_KEY_DURATION, METADATA_TYPE_LONG);
    METADATA_KEYS_TYPE.put(METADATA_KEY_ALBUM, METADATA_TYPE_TEXT);
    METADATA_KEYS_TYPE.put(METADATA_KEY_AUTHOR, METADATA_TYPE_TEXT);
    METADATA_KEYS_TYPE.put(METADATA_KEY_WRITER, METADATA_TYPE_TEXT);
    METADATA_KEYS_TYPE.put(METADATA_KEY_COMPOSER, METADATA_TYPE_TEXT);
    METADATA_KEYS_TYPE.put(METADATA_KEY_COMPILATION, METADATA_TYPE_TEXT);
    METADATA_KEYS_TYPE.put(METADATA_KEY_DATE, METADATA_TYPE_TEXT);
    METADATA_KEYS_TYPE.put(METADATA_KEY_YEAR, METADATA_TYPE_LONG);
    METADATA_KEYS_TYPE.put(METADATA_KEY_GENRE, METADATA_TYPE_TEXT);
    METADATA_KEYS_TYPE.put(METADATA_KEY_TRACK_NUMBER, METADATA_TYPE_LONG);
    METADATA_KEYS_TYPE.put(METADATA_KEY_NUM_TRACKS, METADATA_TYPE_LONG);
    METADATA_KEYS_TYPE.put(METADATA_KEY_DISC_NUMBER, METADATA_TYPE_LONG);
    METADATA_KEYS_TYPE.put(METADATA_KEY_ALBUM_ARTIST, METADATA_TYPE_TEXT);
    METADATA_KEYS_TYPE.put(METADATA_KEY_ART, METADATA_TYPE_BITMAP);
    METADATA_KEYS_TYPE.put(METADATA_KEY_ART_URI, METADATA_TYPE_TEXT);
    METADATA_KEYS_TYPE.put(METADATA_KEY_ALBUM_ART, METADATA_TYPE_BITMAP);
    METADATA_KEYS_TYPE.put(METADATA_KEY_ALBUM_ART_URI, METADATA_TYPE_TEXT);
    METADATA_KEYS_TYPE.put(METADATA_KEY_USER_RATING, METADATA_TYPE_RATING);
    METADATA_KEYS_TYPE.put(METADATA_KEY_RATING, METADATA_TYPE_RATING);
    METADATA_KEYS_TYPE.put(METADATA_KEY_DISPLAY_TITLE, METADATA_TYPE_TEXT);
    METADATA_KEYS_TYPE.put(METADATA_KEY_DISPLAY_SUBTITLE, METADATA_TYPE_TEXT);
    METADATA_KEYS_TYPE.put(METADATA_KEY_DISPLAY_DESCRIPTION, METADATA_TYPE_TEXT);
    METADATA_KEYS_TYPE.put(METADATA_KEY_DISPLAY_ICON, METADATA_TYPE_BITMAP);
    METADATA_KEYS_TYPE.put(METADATA_KEY_DISPLAY_ICON_URI, METADATA_TYPE_TEXT);
    METADATA_KEYS_TYPE.put(METADATA_KEY_MEDIA_ID, METADATA_TYPE_TEXT);
    METADATA_KEYS_TYPE.put(METADATA_KEY_BT_FOLDER_TYPE, METADATA_TYPE_LONG);
    METADATA_KEYS_TYPE.put(METADATA_KEY_MEDIA_URI, METADATA_TYPE_TEXT);
    METADATA_KEYS_TYPE.put(METADATA_KEY_ADVERTISEMENT, METADATA_TYPE_LONG);
    METADATA_KEYS_TYPE.put(METADATA_KEY_DOWNLOAD_STATUS, METADATA_TYPE_LONG);
  }

  public static final @TextKey String[] PREFERRED_DESCRIPTION_ORDER = {
    METADATA_KEY_TITLE,
    METADATA_KEY_ARTIST,
    METADATA_KEY_ALBUM,
    METADATA_KEY_ALBUM_ARTIST,
    METADATA_KEY_WRITER,
    METADATA_KEY_AUTHOR,
    METADATA_KEY_COMPOSER,
    METADATA_KEY_DISPLAY_SUBTITLE,
    METADATA_KEY_DISPLAY_DESCRIPTION
  };

  final Bundle bundle;
  @Nullable private MediaMetadata metadataFwk;

  MediaMetadataCompat(Bundle bundle) {
    this.bundle = new Bundle(bundle);
    MediaSessionCompat.ensureClassLoader(this.bundle);
  }

  MediaMetadataCompat(Parcel in) {
    bundle = checkNotNull(in.readBundle(MediaSessionCompat.class.getClassLoader()));
  }

  /**
   * Returns true if the given key is contained in the metadata
   *
   * @param key a String key
   * @return true if the key exists in this metadata, false otherwise
   */
  public boolean containsKey(String key) {
    return bundle.containsKey(key);
  }

  /**
   * Returns the value associated with the given key, or null if no mapping of the desired type
   * exists for the given key or a null value is explicitly associated with the key.
   *
   * @param key The key the value is stored under
   * @return a CharSequence value, or null
   */
  @Nullable
  public CharSequence getText(@TextKey String key) {
    return bundle.getCharSequence(key);
  }

  /**
   * Returns the value associated with the given key, or null if no mapping of the desired type
   * exists for the given key or a null value is explicitly associated with the key.
   *
   * @param key The key the value is stored under
   * @return a String value, or null
   */
  @Nullable
  public String getString(@TextKey String key) {
    CharSequence text = bundle.getCharSequence(key);
    if (text != null) {
      return text.toString();
    }
    return null;
  }

  /**
   * Returns the value associated with the given key, or 0L if no long exists for the given key.
   *
   * @param key The key the value is stored under
   * @return a long value
   */
  public long getLong(@LongKey String key) {
    return bundle.getLong(key, 0);
  }

  /**
   * Return a {@link RatingCompat} for the given key or null if no rating exists for the given key.
   *
   * @param key The key the value is stored under
   * @return A {@link RatingCompat} or null
   */
  @Nullable
  public RatingCompat getRating(@RatingKey String key) {
    RatingCompat rating = null;
    try {
      // On platform version 19 or higher, bundle stores a Rating object. Convert it to
      // RatingCompat.
      rating = RatingCompat.fromRating(bundle.getParcelable(key));
    } catch (Exception e) {
      // ignore, value was not a Rating
      Log.w(TAG, "Failed to retrieve a key as Rating.", e);
    }
    return rating;
  }

  /**
   * Return a {@link Bitmap} for the given key or null if no bitmap exists for the given key.
   *
   * @param key The key the value is stored under
   * @return A {@link Bitmap} or null
   */
  @Nullable
  public Bitmap getBitmap(@BitmapKey String key) {
    Bitmap bmp = null;
    try {
      bmp = bundle.getParcelable(key);
    } catch (Exception e) {
      // ignore, value was not a bitmap
      Log.w(TAG, "Failed to retrieve a key as Bitmap.", e);
    }
    return bmp;
  }

  @Override
  public int describeContents() {
    return 0;
  }

  @Override
  public void writeToParcel(Parcel dest, int flags) {
    dest.writeBundle(bundle);
  }

  /**
   * Get the number of fields in this metadata.
   *
   * @return The number of fields in the metadata.
   */
  public int size() {
    return bundle.size();
  }

  /**
   * Gets a copy of the bundle for this metadata object. This is available to support backwards
   * compatibility.
   *
   * @return A copy of the bundle for this metadata object.
   */
  public Bundle getBundle() {
    return new Bundle(bundle);
  }

  /**
   * Creates an instance from a framework {@link android.media.MediaMetadata} object.
   *
   * <p>This method is only supported on {@link android.os.Build.VERSION_CODES#LOLLIPOP} and later.
   *
   * @param metadataObj A {@link android.media.MediaMetadata} object, or null if none.
   * @return An equivalent {@link MediaMetadataCompat} object, or null if none.
   */
  @Nullable
  public static MediaMetadataCompat fromMediaMetadata(@Nullable Object metadataObj) {
    if (metadataObj != null) {
      Parcel p = Parcel.obtain();
      ((MediaMetadata) metadataObj).writeToParcel(p, 0);
      p.setDataPosition(0);
      MediaMetadataCompat metadata = MediaMetadataCompat.CREATOR.createFromParcel(p);
      p.recycle();
      metadata.metadataFwk = (MediaMetadata) metadataObj;
      return metadata;
    } else {
      return null;
    }
  }

  /**
   * Gets the underlying framework {@link android.media.MediaMetadata} object.
   *
   * @return An equivalent {@link android.media.MediaMetadata} object.
   */
  @SuppressWarnings("nullness") // MediaMetadata.Builder accepts null values but is not annotated.
  public MediaMetadata getMediaMetadata() {
    if (metadataFwk == null) {
      MediaMetadata.Builder builder = new MediaMetadata.Builder();
      for (String key : bundle.keySet()) {
        Integer type = METADATA_KEYS_TYPE.get(key);
        if (type == null) {
          type = -1;
        }
        switch (type) {
          case METADATA_TYPE_TEXT:
            builder.putText(key, bundle.getString(key));
            break;
          case METADATA_TYPE_LONG:
            builder.putLong(key, bundle.getLong(key));
            break;
          case METADATA_TYPE_BITMAP:
            builder.putBitmap(key, bundle.getParcelable(key));
            break;
          case METADATA_TYPE_RATING:
            builder.putRating(key, bundle.getParcelable(key));
            break;
          default:
            Object value = bundle.get(key);
            if (value == null || value instanceof CharSequence) {
              builder.putText(key, (CharSequence) value);
            } else if (value instanceof Long) {
              builder.putLong(key, (Long) value);
            } else {
              // values of complex types are not preserved.
            }
            break;
        }
      }
      metadataFwk = builder.build();
    }
    return metadataFwk;
  }

  public static final Parcelable.Creator<MediaMetadataCompat> CREATOR =
      new Parcelable.Creator<MediaMetadataCompat>() {
        @Override
        public MediaMetadataCompat createFromParcel(Parcel in) {
          return new MediaMetadataCompat(in);
        }

        @Override
        public MediaMetadataCompat[] newArray(int size) {
          return new MediaMetadataCompat[size];
        }
      };

  /**
   * Use to build MediaMetadata objects. The system defined metadata keys must use the appropriate
   * data type.
   */
  public static final class Builder {
    private final Bundle bundle;

    /**
     * Create an empty Builder. Any field that should be included in the {@link MediaMetadataCompat}
     * must be added.
     */
    public Builder() {
      bundle = new Bundle();
    }

    /**
     * Put a CharSequence value into the metadata. Custom keys may be used, but if the METADATA_KEYs
     * defined in this class are used they may only be one of the following:
     *
     * <ul>
     *   <li>{@link #METADATA_KEY_TITLE}
     *   <li>{@link #METADATA_KEY_ARTIST}
     *   <li>{@link #METADATA_KEY_ALBUM}
     *   <li>{@link #METADATA_KEY_AUTHOR}
     *   <li>{@link #METADATA_KEY_WRITER}
     *   <li>{@link #METADATA_KEY_COMPOSER}
     *   <li>{@link #METADATA_KEY_DATE}
     *   <li>{@link #METADATA_KEY_GENRE}
     *   <li>{@link #METADATA_KEY_ALBUM_ARTIST}
     *   <li>{@link #METADATA_KEY_ART_URI}
     *   <li>{@link #METADATA_KEY_ALBUM_ART_URI}
     *   <li>{@link #METADATA_KEY_DISPLAY_TITLE}
     *   <li>{@link #METADATA_KEY_DISPLAY_SUBTITLE}
     *   <li>{@link #METADATA_KEY_DISPLAY_DESCRIPTION}
     *   <li>{@link #METADATA_KEY_DISPLAY_ICON_URI}
     * </ul>
     *
     * @param key The key for referencing this value
     * @param value The CharSequence value to store
     * @return The Builder to allow chaining
     */
    public Builder putText(@TextKey String key, CharSequence value) {
      Integer type = METADATA_KEYS_TYPE.get(key);
      if (type != null && type != METADATA_TYPE_TEXT) {
        throw new IllegalArgumentException(
            "The " + key + " key cannot be used to put a CharSequence");
      }
      bundle.putCharSequence(key, value);
      return this;
    }

    /**
     * Put a String value into the metadata. Custom keys may be used, but if the METADATA_KEYs
     * defined in this class are used they may only be one of the following:
     *
     * <ul>
     *   <li>{@link #METADATA_KEY_TITLE}
     *   <li>{@link #METADATA_KEY_ARTIST}
     *   <li>{@link #METADATA_KEY_ALBUM}
     *   <li>{@link #METADATA_KEY_AUTHOR}
     *   <li>{@link #METADATA_KEY_WRITER}
     *   <li>{@link #METADATA_KEY_COMPOSER}
     *   <li>{@link #METADATA_KEY_DATE}
     *   <li>{@link #METADATA_KEY_GENRE}
     *   <li>{@link #METADATA_KEY_ALBUM_ARTIST}
     *   <li>{@link #METADATA_KEY_ART_URI}
     *   <li>{@link #METADATA_KEY_ALBUM_ART_URI}
     *   <li>{@link #METADATA_KEY_DISPLAY_TITLE}
     *   <li>{@link #METADATA_KEY_DISPLAY_SUBTITLE}
     *   <li>{@link #METADATA_KEY_DISPLAY_DESCRIPTION}
     *   <li>{@link #METADATA_KEY_DISPLAY_ICON_URI}
     * </ul>
     *
     * @param key The key for referencing this value
     * @param value The String value to store
     * @return The Builder to allow chaining
     */
    public Builder putString(@TextKey String key, String value) {
      Integer type = METADATA_KEYS_TYPE.get(key);
      if (type != null && type != METADATA_TYPE_TEXT) {
        throw new IllegalArgumentException("The " + key + " key cannot be used to put a String");
      }
      bundle.putCharSequence(key, value);
      return this;
    }

    /**
     * Put a long value into the metadata. Custom keys may be used, but if the METADATA_KEYs defined
     * in this class are used they may only be one of the following:
     *
     * <ul>
     *   <li>{@link #METADATA_KEY_DURATION}
     *   <li>{@link #METADATA_KEY_TRACK_NUMBER}
     *   <li>{@link #METADATA_KEY_NUM_TRACKS}
     *   <li>{@link #METADATA_KEY_DISC_NUMBER}
     *   <li>{@link #METADATA_KEY_YEAR}
     *   <li>{@link #METADATA_KEY_BT_FOLDER_TYPE}
     *   <li>{@link #METADATA_KEY_ADVERTISEMENT}
     *   <li>{@link #METADATA_KEY_DOWNLOAD_STATUS}
     * </ul>
     *
     * @param key The key for referencing this value
     * @param value The String value to store
     * @return The Builder to allow chaining
     */
    public Builder putLong(@LongKey String key, long value) {
      Integer type = METADATA_KEYS_TYPE.get(key);
      if (type != null && type != METADATA_TYPE_LONG) {
        throw new IllegalArgumentException("The " + key + " key cannot be used to put a long");
      }
      bundle.putLong(key, value);
      return this;
    }

    /**
     * Put a {@link RatingCompat} into the metadata. Custom keys may be used, but if the
     * METADATA_KEYs defined in this class are used they may only be one of the following:
     *
     * <ul>
     *   <li>{@link #METADATA_KEY_RATING}
     *   <li>{@link #METADATA_KEY_USER_RATING}
     * </ul>
     *
     * @param key The key for referencing this value
     * @param value The String value to store
     * @return The Builder to allow chaining
     */
    public Builder putRating(@RatingKey String key, RatingCompat value) {
      Integer type = METADATA_KEYS_TYPE.get(key);
      if (type != null && type != METADATA_TYPE_RATING) {
        throw new IllegalArgumentException("The " + key + " key cannot be used to put a Rating");
      }
      // On platform version 19 or higher, use Rating instead of RatingCompat so bundle
      // can be unmarshalled.
      bundle.putParcelable(key, (Parcelable) value.getRating());
      return this;
    }

    /**
     * Put a {@link Bitmap} into the metadata. Custom keys may be used, but if the METADATA_KEYs
     * defined in this class are used they may only be one of the following:
     *
     * <ul>
     *   <li>{@link #METADATA_KEY_ART}
     *   <li>{@link #METADATA_KEY_ALBUM_ART}
     *   <li>{@link #METADATA_KEY_DISPLAY_ICON}
     * </ul>
     *
     * Large bitmaps may be scaled down when {@link
     * androidx.media3.session.legacy.MediaSessionCompat#setMetadata} is called. To pass full
     * resolution images {@link Uri Uris} should be used with {@link #putString}.
     *
     * @param key The key for referencing this value
     * @param value The Bitmap to store
     * @return The Builder to allow chaining
     */
    public Builder putBitmap(@BitmapKey String key, Bitmap value) {
      Integer type = METADATA_KEYS_TYPE.get(key);
      if (type != null && type != METADATA_TYPE_BITMAP) {
        throw new IllegalArgumentException("The " + key + " key cannot be used to put a Bitmap");
      }
      bundle.putParcelable(key, value);
      return this;
    }

    /**
     * Creates a {@link MediaMetadataCompat} instance with the specified fields.
     *
     * @return The new MediaMetadata instance
     */
    public MediaMetadataCompat build() {
      return new MediaMetadataCompat(bundle);
    }
  }
}
