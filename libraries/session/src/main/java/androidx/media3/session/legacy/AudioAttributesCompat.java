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
import static androidx.media3.common.util.Assertions.checkNotNull;

import android.annotation.SuppressLint;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.os.Build;
import android.util.SparseIntArray;
import androidx.annotation.IntDef;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.RestrictTo;
import androidx.media3.common.util.UnstableApi;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;

/**
 * A class to encapsulate a collection of attributes describing information about an audio stream.
 *
 * <p><code>AudioAttributesCompat</code> supersede the notion of stream types (see for instance
 * {@link AudioManager#STREAM_MUSIC} or {@link AudioManager#STREAM_ALARM}) for defining the behavior
 * of audio playback. Attributes allow an application to specify more information than is conveyed
 * in a stream type by allowing the application to define:
 *
 * <ul>
 *   <li>usage: "why" you are playing a sound, what is this sound used for. This is achieved with
 *       the "usage" information. Examples of usage are {@link #USAGE_MEDIA} and {@link
 *       #USAGE_ALARM}. These two examples are the closest to stream types, but more detailed use
 *       cases are available. Usage information is more expressive than a stream type, and allows
 *       certain platforms or routing policies to use this information for more refined volume or
 *       routing decisions. Usage is the most important information to supply in <code>
 * AudioAttributesCompat</code> and it is recommended to build any instance with this information
 *       supplied, see {@link AudioAttributesCompat.Builder} for exceptions.
 *   <li>content type: "what" you are playing. The content type expresses the general category of
 *       the content. This information is optional. But in case it is known (for instance {@link
 *       #CONTENT_TYPE_MOVIE} for a movie streaming service or {@link #CONTENT_TYPE_MUSIC} for a
 *       music playback application) this information might be used by the audio framework to
 *       selectively configure some audio post-processing blocks.
 *   <li>flags: "how" is playback to be affected, see the flag definitions for the specific playback
 *       behaviors they control.
 * </ul>
 *
 * <p><code>AudioAttributesCompat</code> instance is built through its builder, {@link
 * AudioAttributesCompat.Builder}. Also see {@link android.media.AudioAttributes} for the framework
 * implementation of this class.
 */
@UnstableApi
@RestrictTo(LIBRARY)
public class AudioAttributesCompat {

  /** Content type value to use when the content type is unknown, or other than the ones defined. */
  public static final int CONTENT_TYPE_UNKNOWN = AudioAttributes.CONTENT_TYPE_UNKNOWN;

  /** Content type value to use when the content type is speech. */
  public static final int CONTENT_TYPE_SPEECH = AudioAttributes.CONTENT_TYPE_SPEECH;

  /** Content type value to use when the content type is music. */
  public static final int CONTENT_TYPE_MUSIC = AudioAttributes.CONTENT_TYPE_MUSIC;

  /**
   * Content type value to use when the content type is a soundtrack, typically accompanying a movie
   * or TV program.
   */
  public static final int CONTENT_TYPE_MOVIE = AudioAttributes.CONTENT_TYPE_MOVIE;

  /**
   * Content type value to use when the content type is a sound used to accompany a user action,
   * such as a beep or sound effect expressing a key click, or event, such as the type of a sound
   * for a bonus being received in a game. These sounds are mostly synthesized or short Foley
   * sounds.
   */
  public static final int CONTENT_TYPE_SONIFICATION = AudioAttributes.CONTENT_TYPE_SONIFICATION;

  /** Usage value to use when the usage is unknown. */
  public static final int USAGE_UNKNOWN = AudioAttributes.USAGE_UNKNOWN;

  /** Usage value to use when the usage is media, such as music, or movie soundtracks. */
  public static final int USAGE_MEDIA = AudioAttributes.USAGE_MEDIA;

  /** Usage value to use when the usage is voice communications, such as telephony or VoIP. */
  public static final int USAGE_VOICE_COMMUNICATION = AudioAttributes.USAGE_VOICE_COMMUNICATION;

  /**
   * Usage value to use when the usage is in-call signalling, such as with a "busy" beep, or DTMF
   * tones.
   */
  public static final int USAGE_VOICE_COMMUNICATION_SIGNALLING =
      AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING;

  /** Usage value to use when the usage is an alarm (e.g. wake-up alarm). */
  public static final int USAGE_ALARM = AudioAttributes.USAGE_ALARM;

  /**
   * Usage value to use when the usage is notification. See other notification usages for more
   * specialized uses.
   */
  public static final int USAGE_NOTIFICATION = AudioAttributes.USAGE_NOTIFICATION;

  /** Usage value to use when the usage is telephony ringtone. */
  public static final int USAGE_NOTIFICATION_RINGTONE = AudioAttributes.USAGE_NOTIFICATION_RINGTONE;

  /**
   * Usage value to use when the usage is a request to enter/end a communication, such as a VoIP
   * communication or video-conference.
   */
  public static final int USAGE_NOTIFICATION_COMMUNICATION_REQUEST =
      AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_REQUEST;

  /**
   * Usage value to use when the usage is notification for an "instant" communication such as a
   * chat, or SMS.
   */
  public static final int USAGE_NOTIFICATION_COMMUNICATION_INSTANT =
      AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_INSTANT;

  /**
   * Usage value to use when the usage is notification for a non-immediate type of communication
   * such as e-mail.
   */
  public static final int USAGE_NOTIFICATION_COMMUNICATION_DELAYED =
      AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_DELAYED;

  /**
   * Usage value to use when the usage is to attract the user's attention, such as a reminder or low
   * battery warning.
   */
  public static final int USAGE_NOTIFICATION_EVENT = AudioAttributes.USAGE_NOTIFICATION_EVENT;

  /** Usage value to use when the usage is for accessibility, such as with a screen reader. */
  public static final int USAGE_ASSISTANCE_ACCESSIBILITY =
      AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY;

  /** Usage value to use when the usage is driving or navigation directions. */
  public static final int USAGE_ASSISTANCE_NAVIGATION_GUIDANCE =
      AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE;

  /** Usage value to use when the usage is sonification, such as with user interface sounds. */
  public static final int USAGE_ASSISTANCE_SONIFICATION =
      AudioAttributes.USAGE_ASSISTANCE_SONIFICATION;

  /** Usage value to use when the usage is for game audio. */
  public static final int USAGE_GAME = AudioAttributes.USAGE_GAME;

  // usage not available to clients
  public static final int USAGE_VIRTUAL_SOURCE = 15; // AudioAttributes.USAGE_VIRTUAL_SOURCE;

  /**
   * Usage value to use for audio responses to user queries, audio instructions or help utterances.
   */
  @SuppressLint("InlinedApi") // save to access compile time constant
  public static final int USAGE_ASSISTANT = AudioAttributes.USAGE_ASSISTANT;

  /**
   * IMPORTANT: when adding new usage types, add them to SDK_USAGES and update SUPPRESSIBLE_USAGES
   * if applicable.
   */

  // private API
  private static final int SUPPRESSIBLE_NOTIFICATION = 1;

  private static final int SUPPRESSIBLE_CALL = 2;
  private static final SparseIntArray SUPPRESSIBLE_USAGES;

  static {
    SUPPRESSIBLE_USAGES = new SparseIntArray();
    SUPPRESSIBLE_USAGES.put(USAGE_NOTIFICATION, SUPPRESSIBLE_NOTIFICATION);
    SUPPRESSIBLE_USAGES.put(USAGE_NOTIFICATION_RINGTONE, SUPPRESSIBLE_CALL);
    SUPPRESSIBLE_USAGES.put(USAGE_NOTIFICATION_COMMUNICATION_REQUEST, SUPPRESSIBLE_CALL);
    SUPPRESSIBLE_USAGES.put(USAGE_NOTIFICATION_COMMUNICATION_INSTANT, SUPPRESSIBLE_NOTIFICATION);
    SUPPRESSIBLE_USAGES.put(USAGE_NOTIFICATION_COMMUNICATION_DELAYED, SUPPRESSIBLE_NOTIFICATION);
    SUPPRESSIBLE_USAGES.put(USAGE_NOTIFICATION_EVENT, SUPPRESSIBLE_NOTIFICATION);
  }

  @SuppressWarnings("unused")
  private static final int[] SDK_USAGES = {
    USAGE_UNKNOWN,
    USAGE_MEDIA,
    USAGE_VOICE_COMMUNICATION,
    USAGE_VOICE_COMMUNICATION_SIGNALLING,
    USAGE_ALARM,
    USAGE_NOTIFICATION,
    USAGE_NOTIFICATION_RINGTONE,
    USAGE_NOTIFICATION_COMMUNICATION_REQUEST,
    USAGE_NOTIFICATION_COMMUNICATION_INSTANT,
    USAGE_NOTIFICATION_COMMUNICATION_DELAYED,
    USAGE_NOTIFICATION_EVENT,
    USAGE_ASSISTANCE_ACCESSIBILITY,
    USAGE_ASSISTANCE_NAVIGATION_GUIDANCE,
    USAGE_ASSISTANCE_SONIFICATION,
    USAGE_GAME,
    USAGE_ASSISTANT,
  };

  /** Flag defining a behavior where the audibility of the sound will be ensured by the system. */
  public static final int FLAG_AUDIBILITY_ENFORCED = 0x1 << 0;

  static final int FLAG_SCO = 0x1 << 2;
  static final int INVALID_STREAM_TYPE = -1; // AudioSystem.STREAM_DEFAULT

  private final AudioAttributesImpl impl;

  AudioAttributesCompat(AudioAttributesImpl impl) {
    this.impl = impl;
  }

  // public API unique to AudioAttributesCompat

  /**
   * If the current SDK level is 21 or higher, return the {@link AudioAttributes} object inside this
   * {@link AudioAttributesCompat}. Otherwise <code>null</code>.
   *
   * @return the underlying {@link AudioAttributes} object or null
   */
  @Nullable
  public Object unwrap() {
    return impl.getAudioAttributes();
  }

  /**
   * Returns a stream type passed to {@link Builder#setLegacyStreamType(int)}, or best guessing from
   * flags and usage, or -1 if there is no converting logic in framework side (API 21+).
   *
   * @return the stream type {@see AudioManager}
   */
  public int getLegacyStreamType() {
    return impl.getLegacyStreamType();
  }

  /**
   * Creates an {@link AudioAttributesCompat} given an API 21 {@link AudioAttributes} object.
   *
   * @param aa an instance of {@link AudioAttributes}.
   * @return the new <code>AudioAttributesCompat</code>, or <code>null</code> on API &lt; 21
   */
  public static AudioAttributesCompat wrap(Object aa) {
    if (Build.VERSION.SDK_INT >= 26) {
      return new AudioAttributesCompat(new AudioAttributesImplApi26((AudioAttributes) aa));
    } else {
      return new AudioAttributesCompat(new AudioAttributesImplApi21((AudioAttributes) aa));
    }
  }

  // The rest of this file implements an approximation to AudioAttributes using old stream types

  /**
   * Returns the content type.
   *
   * @return one of the values that can be set in {@link Builder#setContentType(int)}
   */
  public int getContentType() {
    return impl.getContentType();
  }

  /**
   * Returns the usage.
   *
   * @return one of the values that can be set in {@link Builder#setUsage(int)}
   */
  public @AttributeUsage int getUsage() {
    return impl.getUsage();
  }

  /**
   * Returns the flags.
   *
   * @return a combined mask of all flags
   */
  public int getFlags() {
    return impl.getFlags();
  }

  /**
   * Builder class for {@link AudioAttributesCompat} objects.
   *
   * <p>example:
   *
   * <pre class="prettyprint">
   * new AudioAttributes.Builder()
   * .setUsage(AudioAttributes.USAGE_MEDIA)
   * .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
   * .build();
   * </pre>
   *
   * <p>By default all types of information (usage, content type, flags) conveyed by an <code>
   * AudioAttributesCompat</code> instance are set to "unknown". Unknown information will be
   * interpreted as a default value that is dependent on the context of use, for instance a {@link
   * android.media.MediaPlayer} will use a default usage of {@link
   * AudioAttributesCompat#USAGE_MEDIA}. See also {@link AudioAttributes.Builder}.
   */
  public static class Builder {
    final AudioAttributesImpl.Builder builderImpl;

    /**
     * Constructs a new Builder with the defaults. By default, usage and content type are
     * respectively {@link AudioAttributesCompat#USAGE_UNKNOWN} and {@link
     * AudioAttributesCompat#CONTENT_TYPE_UNKNOWN}, and flags are 0. It is recommended to configure
     * the usage (with {@link #setUsage(int)}) or deriving attributes from a legacy stream type
     * (with {@link #setLegacyStreamType(int)}) before calling {@link #build()} to override any
     * default playback behavior in terms of routing and volume management.
     */
    public Builder() {
      if (Build.VERSION.SDK_INT >= 26) {
        builderImpl = new AudioAttributesImplApi26.Builder();
      } else {
        builderImpl = new AudioAttributesImplApi21.Builder();
      }
    }

    /**
     * Constructs a new Builder from a given AudioAttributes
     *
     * @param aa the AudioAttributesCompat object whose data will be reused in the new Builder.
     */
    public Builder(AudioAttributesCompat aa) {
      if (Build.VERSION.SDK_INT >= 26) {
        builderImpl = new AudioAttributesImplApi26.Builder(checkNotNull(aa.unwrap()));
      } else {
        builderImpl = new AudioAttributesImplApi21.Builder(checkNotNull(aa.unwrap()));
      }
    }

    /**
     * Combines all of the attributes that have been set and return a new {@link
     * AudioAttributesCompat} object.
     *
     * @return a new {@link AudioAttributesCompat} object
     */
    public AudioAttributesCompat build() {
      return new AudioAttributesCompat(builderImpl.build());
    }

    /**
     * Sets the attribute describing what is the intended use of the audio signal, such as alarm or
     * ringtone.
     *
     * @param usage one of {@link AudioAttributesCompat#USAGE_UNKNOWN}, {@link
     *     AudioAttributesCompat#USAGE_MEDIA}, {@link
     *     AudioAttributesCompat#USAGE_VOICE_COMMUNICATION}, {@link
     *     AudioAttributesCompat#USAGE_VOICE_COMMUNICATION_SIGNALLING}, {@link
     *     AudioAttributesCompat#USAGE_ALARM}, {@link AudioAttributesCompat#USAGE_NOTIFICATION},
     *     {@link AudioAttributesCompat#USAGE_NOTIFICATION_RINGTONE}, {@link
     *     AudioAttributesCompat#USAGE_NOTIFICATION_COMMUNICATION_REQUEST}, {@link
     *     AudioAttributesCompat#USAGE_NOTIFICATION_COMMUNICATION_INSTANT}, {@link
     *     AudioAttributesCompat#USAGE_NOTIFICATION_COMMUNICATION_DELAYED}, {@link
     *     AudioAttributesCompat#USAGE_NOTIFICATION_EVENT}, {@link
     *     AudioAttributesCompat#USAGE_ASSISTANT}, {@link
     *     AudioAttributesCompat#USAGE_ASSISTANCE_ACCESSIBILITY}, {@link
     *     AudioAttributesCompat#USAGE_ASSISTANCE_NAVIGATION_GUIDANCE}, {@link
     *     AudioAttributesCompat#USAGE_ASSISTANCE_SONIFICATION}, {@link
     *     AudioAttributesCompat#USAGE_GAME}.
     * @return the same Builder instance.
     */
    public Builder setUsage(@AttributeUsage int usage) {
      builderImpl.setUsage(usage);
      return this;
    }

    /**
     * Sets the attribute describing the content type of the audio signal, such as speech, or music.
     *
     * @param contentType the content type values, one of {@link
     *     AudioAttributesCompat#CONTENT_TYPE_MOVIE}, {@link
     *     AudioAttributesCompat#CONTENT_TYPE_MUSIC}, {@link
     *     AudioAttributesCompat#CONTENT_TYPE_SONIFICATION}, {@link
     *     AudioAttributesCompat#CONTENT_TYPE_SPEECH}, {@link
     *     AudioAttributesCompat#CONTENT_TYPE_UNKNOWN}.
     * @return the same Builder instance.
     */
    public Builder setContentType(@AttributeContentType int contentType) {
      builderImpl.setContentType(contentType);
      return this;
    }

    /**
     * Sets the combination of flags.
     *
     * <p>This is a bitwise OR with the existing flags.
     *
     * @param flags The optional flag of {@link AudioAttributesCompat#FLAG_AUDIBILITY_ENFORCED}.
     * @return The same Builder instance.
     */
    public Builder setFlags(int flags) {
      builderImpl.setFlags(flags);
      return this;
    }

    /**
     * Sets attributes as inferred from the legacy stream types.
     *
     * <p>Warning: do not use this method in combination with setting any other attributes such as
     * usage, content type, or flags, as this method will overwrite (the more accurate) information
     * describing the use case previously set in the Builder. In general, avoid using it and prefer
     * setting usage and content type directly with {@link #setUsage(int)} and {@link
     * #setContentType(int)}.
     *
     * <p>Use this method when building an {@link AudioAttributes} instance to initialize some of
     * the attributes by information derived from a legacy stream type.
     *
     * @param streamType one of <code>AudioManager.STREAM_*</code>
     * @return this same Builder instance.
     */
    public Builder setLegacyStreamType(int streamType) {
      builderImpl.setLegacyStreamType(streamType);
      return this;
    }
  }

  @Override
  public int hashCode() {
    return impl.hashCode();
  }

  @Override
  public String toString() {
    return impl.toString();
  }

  abstract static class AudioManagerHidden {
    public static final int STREAM_BLUETOOTH_SCO = 6;
    public static final int STREAM_SYSTEM_ENFORCED = 7;
    public static final int STREAM_ACCESSIBILITY = 10;

    private AudioManagerHidden() {}
  }

  static int toVolumeStreamType(int flags, @AttributeUsage int usage) {
    // flags to stream type mapping
    if ((flags & FLAG_AUDIBILITY_ENFORCED) == FLAG_AUDIBILITY_ENFORCED) {
      return AudioManagerHidden.STREAM_SYSTEM_ENFORCED;
    }
    if ((flags & FLAG_SCO) == FLAG_SCO) {
      return AudioManagerHidden.STREAM_BLUETOOTH_SCO;
    }

    // usage to stream type mapping
    switch (usage) {
      case USAGE_ASSISTANCE_SONIFICATION:
        return AudioManager.STREAM_SYSTEM;
      case USAGE_VOICE_COMMUNICATION:
        return AudioManager.STREAM_VOICE_CALL;
      case USAGE_VOICE_COMMUNICATION_SIGNALLING:
        return AudioManager.STREAM_DTMF;
      case USAGE_ALARM:
        return AudioManager.STREAM_ALARM;
      case USAGE_NOTIFICATION_RINGTONE:
        return AudioManager.STREAM_RING;
      case USAGE_NOTIFICATION:
      case USAGE_NOTIFICATION_COMMUNICATION_REQUEST:
      case USAGE_NOTIFICATION_COMMUNICATION_INSTANT:
      case USAGE_NOTIFICATION_COMMUNICATION_DELAYED:
      case USAGE_NOTIFICATION_EVENT:
        return AudioManager.STREAM_NOTIFICATION;
      case USAGE_ASSISTANCE_ACCESSIBILITY:
        return AudioManagerHidden.STREAM_ACCESSIBILITY;
      default:
        return AudioManager.STREAM_MUSIC;
    }
  }

  @Override
  public boolean equals(@Nullable Object o) {
    if (!(o instanceof AudioAttributesCompat)) {
      return false;
    }
    AudioAttributesCompat that = (AudioAttributesCompat) o;
    return this.impl.equals(that.impl);
  }

  @IntDef({
    USAGE_UNKNOWN,
    USAGE_MEDIA,
    USAGE_VOICE_COMMUNICATION,
    USAGE_VOICE_COMMUNICATION_SIGNALLING,
    USAGE_ALARM,
    USAGE_NOTIFICATION,
    USAGE_NOTIFICATION_RINGTONE,
    USAGE_NOTIFICATION_COMMUNICATION_REQUEST,
    USAGE_NOTIFICATION_COMMUNICATION_INSTANT,
    USAGE_NOTIFICATION_COMMUNICATION_DELAYED,
    USAGE_NOTIFICATION_EVENT,
    USAGE_ASSISTANCE_ACCESSIBILITY,
    USAGE_ASSISTANCE_NAVIGATION_GUIDANCE,
    USAGE_ASSISTANCE_SONIFICATION,
    USAGE_GAME,
    USAGE_ASSISTANT,
    USAGE_VIRTUAL_SOURCE
  })
  @Retention(RetentionPolicy.SOURCE)
  private @interface AttributeUsage {}

  @IntDef({
    CONTENT_TYPE_UNKNOWN,
    CONTENT_TYPE_SPEECH,
    CONTENT_TYPE_MUSIC,
    CONTENT_TYPE_MOVIE,
    CONTENT_TYPE_SONIFICATION
  })
  @Retention(RetentionPolicy.SOURCE)
  private @interface AttributeContentType {}

  private interface AudioAttributesImpl {
    /** Gets framework {@link android.media.AudioAttributes}. */
    @Nullable
    Object getAudioAttributes();

    int getLegacyStreamType();

    int getContentType();

    @AudioAttributesCompat.AttributeUsage
    int getUsage();

    int getFlags();

    interface Builder {

      AudioAttributesImpl build();

      Builder setUsage(@AudioAttributesCompat.AttributeUsage int usage);

      Builder setContentType(@AudioAttributesCompat.AttributeContentType int contentType);

      Builder setFlags(int flags);

      Builder setLegacyStreamType(int streamType);
    }
  }

  private static class AudioAttributesImplApi21 implements AudioAttributesImpl {

    @Nullable public AudioAttributes audioAttributes;

    public final int legacyStreamType;

    AudioAttributesImplApi21(AudioAttributes audioAttributes) {
      this(audioAttributes, INVALID_STREAM_TYPE);
    }

    AudioAttributesImplApi21(AudioAttributes audioAttributes, int explicitLegacyStream) {
      this.audioAttributes = audioAttributes;
      legacyStreamType = explicitLegacyStream;
    }

    @Override
    @Nullable
    public Object getAudioAttributes() {
      return audioAttributes;
    }

    @Override
    public int getLegacyStreamType() {
      if (legacyStreamType != INVALID_STREAM_TYPE) {
        return legacyStreamType;
      }
      return AudioAttributesCompat.toVolumeStreamType(getFlags(), getUsage());
    }

    @Override
    public int getContentType() {
      return checkNotNull(audioAttributes).getContentType();
    }

    @Override
    public @AudioAttributesCompat.AttributeUsage int getUsage() {
      return checkNotNull(audioAttributes).getUsage();
    }

    @Override
    public int getFlags() {
      return checkNotNull(audioAttributes).getFlags();
    }

    @Override
    public int hashCode() {
      return checkNotNull(audioAttributes).hashCode();
    }

    @Override
    public boolean equals(@Nullable Object o) {
      if (!(o instanceof AudioAttributesImplApi21)) {
        return false;
      }
      final AudioAttributesImplApi21 that = (AudioAttributesImplApi21) o;
      return Objects.equals(audioAttributes, that.audioAttributes);
    }

    @Override
    public String toString() {
      return "AudioAttributesCompat: audioattributes=" + audioAttributes;
    }

    static class Builder implements AudioAttributesImpl.Builder {
      final AudioAttributes.Builder fwkBuilder;

      Builder() {
        fwkBuilder = new AudioAttributes.Builder();
      }

      Builder(Object aa) {
        fwkBuilder = new AudioAttributes.Builder((AudioAttributes) aa);
      }

      @Override
      public AudioAttributesImpl build() {
        return new AudioAttributesImplApi21(fwkBuilder.build());
      }

      @Override
      @SuppressLint("WrongConstant")
      public Builder setUsage(int usage) {
        if (usage == AudioAttributes.USAGE_ASSISTANT) {
          // TODO: shouldn't we keep the origin usage?
          usage = AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE;
        }
        fwkBuilder.setUsage(usage);
        return this;
      }

      @Override
      public Builder setContentType(int contentType) {
        fwkBuilder.setContentType(contentType);
        return this;
      }

      @Override
      public Builder setFlags(int flags) {
        fwkBuilder.setFlags(flags);
        return this;
      }

      @Override
      public Builder setLegacyStreamType(int streamType) {
        fwkBuilder.setLegacyStreamType(streamType);
        return this;
      }
    }
  }

  @RequiresApi(26)
  private static class AudioAttributesImplApi26 extends AudioAttributesImplApi21 {

    AudioAttributesImplApi26(AudioAttributes audioAttributes) {
      super(audioAttributes, INVALID_STREAM_TYPE);
    }

    @RequiresApi(26)
    static class Builder extends AudioAttributesImplApi21.Builder {
      Builder() {
        super();
      }

      Builder(Object aa) {
        super(aa);
      }

      @Override
      public AudioAttributesImpl build() {
        return new AudioAttributesImplApi26(fwkBuilder.build());
      }

      @SuppressLint("WrongConstant") // Setting AttributeUsage on platform API.
      @Override
      public Builder setUsage(@AttributeUsage int usage) {
        fwkBuilder.setUsage(usage);
        return this;
      }
    }
  }
}
