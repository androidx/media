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
package androidx.media3.session;

import static androidx.media3.common.util.Assertions.checkArgument;
import static androidx.media3.common.util.Assertions.checkNotNull;
import static androidx.media3.common.util.Assertions.checkState;
import static java.lang.annotation.ElementType.TYPE_USE;

import android.content.ContentResolver;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import androidx.annotation.DrawableRes;
import androidx.annotation.IntDef;
import androidx.annotation.Nullable;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import com.google.common.base.Objects;
import com.google.common.collect.ImmutableList;
import com.google.common.primitives.ImmutableIntArray;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.errorprone.annotations.CheckReturnValue;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;

/**
 * A button for a {@link SessionCommand} or {@link Player.Command} that can be displayed by
 * controllers.
 *
 * @see MediaSession#setCustomLayout(MediaSession.ControllerInfo, List)
 * @see MediaController.Listener#onCustomLayoutChanged(MediaController, List)
 */
public final class CommandButton {

  // TODO: b/328238954 - Stabilize these constants and the corresponding methods, and deprecate the
  //  methods that do not use these constants.
  /** An icon constant for a button. Must be one of the {@code CommandButton.ICON_} constants. */
  @UnstableApi
  @Documented
  @Retention(RetentionPolicy.SOURCE)
  @Target(TYPE_USE)
  @IntDef({
    ICON_UNDEFINED,
    ICON_PLAY,
    ICON_PAUSE,
    ICON_STOP,
    ICON_NEXT,
    ICON_PREVIOUS,
    ICON_SKIP_FORWARD,
    ICON_SKIP_FORWARD_5,
    ICON_SKIP_FORWARD_10,
    ICON_SKIP_FORWARD_15,
    ICON_SKIP_FORWARD_30,
    ICON_SKIP_BACK,
    ICON_SKIP_BACK_5,
    ICON_SKIP_BACK_10,
    ICON_SKIP_BACK_15,
    ICON_SKIP_BACK_30,
    ICON_FAST_FORWARD,
    ICON_REWIND,
    ICON_REPEAT_ALL,
    ICON_REPEAT_ONE,
    ICON_REPEAT_OFF,
    ICON_SHUFFLE_ON,
    ICON_SHUFFLE_OFF,
    ICON_SHUFFLE_STAR,
    ICON_HEART_FILLED,
    ICON_HEART_UNFILLED,
    ICON_STAR_FILLED,
    ICON_STAR_UNFILLED,
    ICON_BOOKMARK_FILLED,
    ICON_BOOKMARK_UNFILLED,
    ICON_THUMB_UP_FILLED,
    ICON_THUMB_UP_UNFILLED,
    ICON_THUMB_DOWN_FILLED,
    ICON_THUMB_DOWN_UNFILLED,
    ICON_FLAG_FILLED,
    ICON_FLAG_UNFILLED,
    ICON_PLUS,
    ICON_MINUS,
    ICON_PLAYLIST_ADD,
    ICON_PLAYLIST_REMOVE,
    ICON_QUEUE_ADD,
    ICON_QUEUE_NEXT,
    ICON_QUEUE_REMOVE,
    ICON_BLOCK,
    ICON_PLUS_CIRCLE_FILLED,
    ICON_PLUS_CIRCLE_UNFILLED,
    ICON_MINUS_CIRCLE_FILLED,
    ICON_MINUS_CIRCLE_UNFILLED,
    ICON_CHECK_CIRCLE_FILLED,
    ICON_CHECK_CIRCLE_UNFILLED,
    ICON_PLAYBACK_SPEED,
    ICON_PLAYBACK_SPEED_0_5,
    ICON_PLAYBACK_SPEED_0_8,
    ICON_PLAYBACK_SPEED_1_0,
    ICON_PLAYBACK_SPEED_1_2,
    ICON_PLAYBACK_SPEED_1_5,
    ICON_PLAYBACK_SPEED_1_8,
    ICON_PLAYBACK_SPEED_2_0,
    ICON_SETTINGS,
    ICON_QUALITY,
    ICON_SUBTITLES,
    ICON_SUBTITLES_OFF,
    ICON_CLOSED_CAPTIONS,
    ICON_CLOSED_CAPTIONS_OFF,
    ICON_SYNC,
    ICON_SHARE,
    ICON_VOLUME_UP,
    ICON_VOLUME_DOWN,
    ICON_VOLUME_OFF,
    ICON_ARTIST,
    ICON_ALBUM,
    ICON_RADIO,
    ICON_SIGNAL,
    ICON_FEED
  })
  public @interface Icon {}

  // Note: The constant values of these icons matches the Material Design code points.

  /**
   * An icon constant representing an undefined icon, for example a custom icon not covered by the
   * existing constants.
   */
  @UnstableApi public static final int ICON_UNDEFINED = 0;

  /** An icon showing a play symbol (a right facing triangle). */
  @UnstableApi public static final int ICON_PLAY = 0xe037;

  /** An icon showing a pause symbol (two vertical bars). */
  @UnstableApi public static final int ICON_PAUSE = 0xe034;

  /** An icon showing a stop symbol (a square). */
  @UnstableApi public static final int ICON_STOP = 0xe047;

  /** An icon showing a next symbol (a right facing triangle with a vertical bar). */
  @UnstableApi public static final int ICON_NEXT = 0xe044;

  /** An icon showing a previous symbol (a left facing triangle with a vertical bar). */
  @UnstableApi public static final int ICON_PREVIOUS = 0xe045;

  /** An icon showing a skip forward symbol (an open clock-wise arrow). */
  @UnstableApi public static final int ICON_SKIP_FORWARD = 0xf6f4;

  /**
   * An icon showing a skip forward 5 seconds symbol (an open clockwise arrow with the number 5).
   */
  @UnstableApi public static final int ICON_SKIP_FORWARD_5 = 0xe058;

  /**
   * An icon showing a skip forward 10 seconds symbol (an open clockwise arrow with the number 10).
   */
  @UnstableApi public static final int ICON_SKIP_FORWARD_10 = 0xe056;

  /**
   * An icon showing a skip forward 15 seconds symbol (an open clockwise arrow with the number 15).
   */
  @UnstableApi public static final int ICON_SKIP_FORWARD_15 = 0xfe056;

  /**
   * An icon showing a skip forward 30 seconds symbol (an open clockwise arrow with the number 30).
   */
  @UnstableApi public static final int ICON_SKIP_FORWARD_30 = 0xe057;

  /** An icon showing a skip back symbol (an open anti-clockwise arrow). */
  @UnstableApi public static final int ICON_SKIP_BACK = 0xe042;

  /**
   * An icon showing a skip back 5 seconds symbol (an open anti-clockwise arrow with the number 5).
   */
  @UnstableApi public static final int ICON_SKIP_BACK_5 = 0xe05b;

  /**
   * An icon showing a skip back 10 seconds symbol (an open anti-clockwise arrow with the number
   * 10).
   */
  @UnstableApi public static final int ICON_SKIP_BACK_10 = 0xe059;

  /**
   * An icon showing a skip back 15 seconds symbol (an open anti-clockwise arrow with the number
   * 15).
   */
  @UnstableApi public static final int ICON_SKIP_BACK_15 = 0xfe059;

  /**
   * An icon showing a skip back 30 seconds symbol (an open anti-clockwise arrow with the number
   * 30).
   */
  @UnstableApi public static final int ICON_SKIP_BACK_30 = 0xe05a;

  /** An icon showing a fast forward symbol (two right facing triangles). */
  @UnstableApi public static final int ICON_FAST_FORWARD = 0xe01f;

  /** An icon showing a rewind symbol (two left facing triangles). */
  @UnstableApi public static final int ICON_REWIND = 0xe020;

  /** An icon showing a repeat all symbol (two open clockwise arrows). */
  @UnstableApi public static final int ICON_REPEAT_ALL = 0xe040;

  /** An icon showing a repeat one symbol (two open clockwise arrows with an overlaid number 1). */
  @UnstableApi public static final int ICON_REPEAT_ONE = 0xe041;

  /**
   * An icon showing a disabled repeat symbol (two open clockwise arrows, in a color representing a
   * disabled state).
   */
  @UnstableApi public static final int ICON_REPEAT_OFF = 0xfe040;

  /** An icon showing a shuffle symbol (two diagonal upward and downward facing arrows). */
  @UnstableApi public static final int ICON_SHUFFLE_ON = 0xe043;

  /**
   * An icon showing a disabled shuffle symbol (two diagonal upward and downward facing arrows, in a
   * color representing a disabled state).
   */
  @UnstableApi public static final int ICON_SHUFFLE_OFF = 0xfe044;

  /**
   * An icon showing a shuffle symbol with a start (two diagonal upward and downward facing arrows
   * with an overlaid star).
   */
  @UnstableApi public static final int ICON_SHUFFLE_STAR = 0xfe043;

  /** An icon showing a filled heart symbol. */
  @UnstableApi public static final int ICON_HEART_FILLED = 0xfe87d;

  /** An icon showing an unfilled heart symbol. */
  @UnstableApi public static final int ICON_HEART_UNFILLED = 0xe87d;

  /** An icon showing a filled star symbol. */
  @UnstableApi public static final int ICON_STAR_FILLED = 0xfe838;

  /** An icon showing an unfilled star symbol. */
  @UnstableApi public static final int ICON_STAR_UNFILLED = 0xe838;

  /** An icon showing a filled bookmark symbol. */
  @UnstableApi public static final int ICON_BOOKMARK_FILLED = 0xfe866;

  /** An icon showing an unfilled bookmark symbol. */
  @UnstableApi public static final int ICON_BOOKMARK_UNFILLED = 0xe866;

  /** An icon showing a filled thumb-up symbol. */
  @UnstableApi public static final int ICON_THUMB_UP_FILLED = 0xfe8dc;

  /** An icon showing an unfilled thumb-up symbol. */
  @UnstableApi public static final int ICON_THUMB_UP_UNFILLED = 0xe8dc;

  /** An icon showing a filled thumb-down symbol. */
  @UnstableApi public static final int ICON_THUMB_DOWN_FILLED = 0xfe8db;

  /** An icon showing an unfilled thumb-down symbol. */
  @UnstableApi public static final int ICON_THUMB_DOWN_UNFILLED = 0xe8db;

  /** An icon showing a filled flag symbol. */
  @UnstableApi public static final int ICON_FLAG_FILLED = 0xfe153;

  /** An icon showing an unfilled flag symbol. */
  @UnstableApi public static final int ICON_FLAG_UNFILLED = 0xe153;

  /** An icon showing a plus symbol. */
  @UnstableApi public static final int ICON_PLUS = 0xe145;

  /** An icon showing a minus symbol. */
  @UnstableApi public static final int ICON_MINUS = 0xe15b;

  /** An icon showing an add to playlist symbol (multiple horizontal bars with a small plus). */
  @UnstableApi public static final int ICON_PLAYLIST_ADD = 0xe03b;

  /**
   * An icon showing an remove from playlist symbol (multiple horizontal bars with a small minus).
   */
  @UnstableApi public static final int ICON_PLAYLIST_REMOVE = 0xeb80;

  /** An icon showing an add to queue symbol (a stylized TV with a plus). */
  @UnstableApi public static final int ICON_QUEUE_ADD = 0xe05c;

  /**
   * An icon showing a play next queue item symbol (a stylized TV with a plus and a right-facing
   * arrow).
   */
  @UnstableApi public static final int ICON_QUEUE_NEXT = 0xe066;

  /** An icon showing a remove from queue symbol (a stylized TV with a minus). */
  @UnstableApi public static final int ICON_QUEUE_REMOVE = 0xe067;

  /** An icon showing a block symbol (a circle with a diagonal line). */
  @UnstableApi public static final int ICON_BLOCK = 0xe14b;

  /** An icon showing a filled circle with a plus. */
  @UnstableApi public static final int ICON_PLUS_CIRCLE_FILLED = 0xfe147;

  /** An icon showing an unfilled circle with a plus. */
  @UnstableApi public static final int ICON_PLUS_CIRCLE_UNFILLED = 0xe147;

  /** An icon showing a filled circle with a minus. */
  @UnstableApi public static final int ICON_MINUS_CIRCLE_FILLED = 0xfe148;

  /** An icon showing an unfilled circle with a minus. */
  @UnstableApi public static final int ICON_MINUS_CIRCLE_UNFILLED = 0xfe149;

  /** An icon showing a filled circle with a check mark. */
  @UnstableApi public static final int ICON_CHECK_CIRCLE_FILLED = 0xfe86c;

  /** An icon showing a unfilled circle with a check mark. */
  @UnstableApi public static final int ICON_CHECK_CIRCLE_UNFILLED = 0xe86c;

  /**
   * An icon showing a playback speed symbol (a right facing triangle in a circle with half-dashed,
   * half-solid contour).
   */
  @UnstableApi public static final int ICON_PLAYBACK_SPEED = 0xe068;

  /** An icon showing a 0.5x speed symbol. */
  @UnstableApi public static final int ICON_PLAYBACK_SPEED_0_5 = 0xf4e2;

  /** An icon showing a 0.8x speed symbol. */
  @UnstableApi public static final int ICON_PLAYBACK_SPEED_0_8 = 0xff4e2;

  /** An icon showing a 1.0x speed symbol. */
  @UnstableApi public static final int ICON_PLAYBACK_SPEED_1_0 = 0xefcd;

  /** An icon showing a 1.2x speed symbol. */
  @UnstableApi public static final int ICON_PLAYBACK_SPEED_1_2 = 0xf4e1;

  /** An icon showing a 1.5x speed symbol. */
  @UnstableApi public static final int ICON_PLAYBACK_SPEED_1_5 = 0xf4e0;

  /** An icon showing a 1.8x speed symbol. */
  @UnstableApi public static final int ICON_PLAYBACK_SPEED_1_8 = 0xff4e0;

  /** An icon showing a 2.0x speed symbol. */
  @UnstableApi public static final int ICON_PLAYBACK_SPEED_2_0 = 0xf4eb;

  /** An icon showing a settings symbol (a stylized cog). */
  @UnstableApi public static final int ICON_SETTINGS = 0xe8b8;

  /** An icon showing a quality selection symbol (multiple horizontal bars with sliders). */
  @UnstableApi public static final int ICON_QUALITY = 0xe429;

  /** An icon showing a subtitles symbol (a rectangle filled with dots and horizontal lines). */
  @UnstableApi public static final int ICON_SUBTITLES = 0xe048;

  /**
   * An icon showing a subtitles off symbol (a rectangle filled with dots and horizontal lines, with
   * a large diagonal line across).
   */
  @UnstableApi public static final int ICON_SUBTITLES_OFF = 0xef72;

  /** An icon showing a closed caption symbol (a rectangle with the letters CC). */
  @UnstableApi public static final int ICON_CLOSED_CAPTIONS = 0xe01c;

  /**
   * An icon showing a closed caption off symbol (a rectangle with the letters CC, with a large
   * diagonal line across).
   */
  @UnstableApi public static final int ICON_CLOSED_CAPTIONS_OFF = 0xf1dc;

  /** An icon showing a sync symbol (two open anti-clockwise arrows). */
  @UnstableApi public static final int ICON_SYNC = 0xe627;

  /**
   * An icon showing a share symbol (three dots connected by two diagonal lines, open on the right).
   */
  @UnstableApi public static final int ICON_SHARE = 0xe80d;

  /** An icon showing a volume up symbol (a stylized speaker with multiple sound waves). */
  @UnstableApi public static final int ICON_VOLUME_UP = 0xe050;

  /** An icon showing a volume down symbol (a stylized speaker with a single small sound wave). */
  @UnstableApi public static final int ICON_VOLUME_DOWN = 0xe04d;

  /**
   * An icon showing a volume off symbol (a stylized speaker with multiple sound waves, with a large
   * diagonal line across).
   */
  @UnstableApi public static final int ICON_VOLUME_OFF = 0xe04f;

  /** An icon showing an artist symbol (a stylized person with a musical note). */
  @UnstableApi public static final int ICON_ARTIST = 0xe01a;

  /** An icon showing an album symbol (a stylized LP record). */
  @UnstableApi public static final int ICON_ALBUM = 0xe019;

  /** An icon showing a radio symbol (left and right facing sound waves). */
  @UnstableApi public static final int ICON_RADIO = 0xe51e;

  /** An icon showing an signal symbol (a vertical mast with circular sounds waves). */
  @UnstableApi public static final int ICON_SIGNAL = 0xf048;

  /**
   * An icon showing an feed symbol (a dot in the bottom-left with multiple concentric quarter
   * circles).
   */
  @UnstableApi public static final int ICON_FEED = 0xe0e5;

  // TODO: b/332877990 - Stabilize these constants and other slot APIs
  /**
   * A slot at which a button can be displayed in a UI surface. Must be one of the {@code
   * CommandButton.SLOT_} constants.
   */
  @UnstableApi
  @Documented
  @Retention(RetentionPolicy.SOURCE)
  @Target(TYPE_USE)
  @IntDef({
    SLOT_CENTRAL,
    SLOT_BACK,
    SLOT_FORWARD,
    SLOT_BACK_SECONDARY,
    SLOT_FORWARD_SECONDARY,
    SLOT_OVERFLOW
  })
  public @interface Slot {}

  /** A central slot in a playback control UI, most commonly used for play or pause actions. */
  @UnstableApi public static final int SLOT_CENTRAL = 1;

  /**
   * A slot in a playback control UI for backward-directed playback actions, most commonly used for
   * previous or rewind actions.
   */
  @UnstableApi public static final int SLOT_BACK = 2;

  /**
   * A slot in a playback control UI for forward-directed playback actions, most commonly used for
   * next or fast-forward actions.
   */
  @UnstableApi public static final int SLOT_FORWARD = 3;

  /**
   * A slot in a playback control UI for secondary backward-directed playback actions, most commonly
   * used for previous or rewind actions.
   */
  @UnstableApi public static final int SLOT_BACK_SECONDARY = 4;

  /**
   * A slot in a playback control UI for secondary forward-directed playback actions, most commonly
   * used for next or fast-forward actions.
   */
  @UnstableApi public static final int SLOT_FORWARD_SECONDARY = 5;

  /** A slot in a playback control UI for additional actions that don't fit into other slots. */
  @UnstableApi public static final int SLOT_OVERFLOW = 6;

  /** A builder for {@link CommandButton}. */
  public static final class Builder {

    private final @Icon int icon;

    @Nullable private SessionCommand sessionCommand;
    private @Player.Command int playerCommand;
    @DrawableRes private int iconResId;
    @Nullable private Uri iconUri;
    private CharSequence displayName;
    private Bundle extras;
    private boolean enabled;
    @Nullable private ImmutableIntArray slots;

    /**
     * [will be deprecated] Use {@link #Builder(int)} instead to define the {@link Icon} for this
     * button. A separate resource id via {@link #setIconResId(int)} is no longer required unless
     * for {@link #ICON_UNDEFINED}.
     */
    public Builder() {
      this(ICON_UNDEFINED);
    }

    /**
     * Creates a builder.
     *
     * @param icon The {@link Icon} that should be shown for this button.
     */
    @UnstableApi
    public Builder(@Icon int icon) {
      this(icon, getIconResIdForIconConstant(icon));
    }

    // Internal version of constructor that assigns an additionally known icon resource id
    // immediately. This is needed for R8 resource shrinking efficiency to know that the icon
    // doesn't need to be resolved to any of the bundled icon drawables.
    /* package */ Builder(@Icon int icon, @DrawableRes int iconResId) {
      this.icon = icon;
      this.iconResId = iconResId;
      displayName = "";
      extras = Bundle.EMPTY;
      playerCommand = Player.COMMAND_INVALID;
      enabled = true;
    }

    /**
     * Sets the {@link SessionCommand} that is required to be {@linkplain
     * MediaController#isSessionCommandAvailable available} when the button is clicked.
     *
     * <p>Cannot set this if a player command is already set via {@link #setPlayerCommand(int)}.
     *
     * @param sessionCommand The {@link SessionCommand}.
     * @return This builder for chaining.
     */
    @CanIgnoreReturnValue
    public Builder setSessionCommand(SessionCommand sessionCommand) {
      checkNotNull(sessionCommand, "sessionCommand should not be null.");
      checkArgument(
          playerCommand == Player.COMMAND_INVALID,
          "playerCommands is already set. Only one of sessionCommand and playerCommand should be"
              + " set.");
      this.sessionCommand = sessionCommand;
      return this;
    }

    /**
     * Sets the {@link Player.Command} that is required to be {@linkplain
     * MediaController#isCommandAvailable available} when the button is clicked.
     *
     * <p>Cannot set this if a session command is already set via {@link
     * #setSessionCommand(SessionCommand)}.
     *
     * @param playerCommand The {@link Player.Command}.
     * @return This builder for chaining.
     */
    @CanIgnoreReturnValue
    public Builder setPlayerCommand(@Player.Command int playerCommand) {
      checkArgument(
          sessionCommand == null,
          "sessionCommand is already set. Only one of sessionCommand and playerCommand should be"
              + " set.");
      this.playerCommand = playerCommand;
      return this;
    }

    /**
     * [will be deprecated] The icon should be defined with the constructor {@link Icon} parameter
     * in {@link #Builder(int)} instead. Only in case the existing list of icons is not sufficient,
     * use {@link #ICON_UNDEFINED} and set a separate resource id with {@link #setCustomIconResId}.
     */
    @CanIgnoreReturnValue
    public Builder setIconResId(@DrawableRes int resId) {
      return setCustomIconResId(resId);
    }

    /**
     * Sets the resource id of an icon that is used when the predefined {@link Icon} is not
     * available or set to {@link #ICON_UNDEFINED}.
     *
     * @param resId The resource id of a custom icon.
     * @return This builder for chaining.
     */
    @UnstableApi
    @CanIgnoreReturnValue
    public Builder setCustomIconResId(@DrawableRes int resId) {
      iconResId = resId;
      return this;
    }

    /**
     * Sets a {@linkplain ContentResolver#SCHEME_CONTENT content} or {@linkplain
     * ContentResolver#SCHEME_ANDROID_RESOURCE resource} {@link Uri} for the icon of this button.
     *
     * <p>Note that this {@link Uri} may be used when the predefined {@link Icon} is not available
     * or set to {@link #ICON_UNDEFINED}. It can be used in addition to {@link #setCustomIconResId}
     * for consumers that are capable of loading the content or resource {@link Uri}.
     *
     * @param uri The uri to an icon.
     * @return This builder for chaining.
     */
    @UnstableApi
    @CanIgnoreReturnValue
    public Builder setIconUri(Uri uri) {
      checkArgument(
          Objects.equal(uri.getScheme(), ContentResolver.SCHEME_CONTENT)
              || Objects.equal(uri.getScheme(), ContentResolver.SCHEME_ANDROID_RESOURCE),
          "Only content or resource Uris are supported for CommandButton");
      this.iconUri = uri;
      return this;
    }

    /**
     * Sets a display name of this button.
     *
     * @param displayName The display name.
     * @return This builder for chaining.
     */
    @CanIgnoreReturnValue
    public Builder setDisplayName(CharSequence displayName) {
      this.displayName = displayName;
      return this;
    }

    /**
     * Sets whether the button is enabled.
     *
     * <p>Note that this value will be set to {@code false} for {@link MediaController} instances if
     * the corresponding command is not available to this controller (see {@link #setPlayerCommand}
     * and {@link #setSessionCommand}).
     *
     * <p>The default value is {@code true}.
     *
     * @param enabled Whether the button is enabled.
     * @return This builder for chaining.
     */
    @CanIgnoreReturnValue
    public Builder setEnabled(boolean enabled) {
      this.enabled = enabled;
      return this;
    }

    /**
     * Sets an extra {@link Bundle} of this button.
     *
     * @param extras The extra {@link Bundle}.
     * @return This builder for chaining.
     */
    @CanIgnoreReturnValue
    public Builder setExtras(Bundle extras) {
      this.extras = new Bundle(extras);
      return this;
    }

    /**
     * Sets the allowed {@link Slot} positions for this button.
     *
     * <p>The button is only allowed in the defined slots. If none of the slots can display the
     * button, either because the slots do not exist, are already occupied or the UI surface does
     * not allow the specific type of button in these slots, the button will not be displayed at
     * all.
     *
     * <p>When multiple slots are provided, they define a preference order. The button will be
     * placed in the first slot in the list that exists, isn't already occupied and that allows this
     * type of button.
     *
     * <p>When not specified, the default value depends on the associated {@link #setPlayerCommand
     * player command} and the {@link Icon} set in the constructor:
     *
     * <ul>
     *   <li>{@link Player#COMMAND_PLAY_PAUSE} and/or {@link #ICON_PLAY}, {@link #ICON_PAUSE}:
     *       {@link #SLOT_CENTRAL}
     *   <li>{@link Player#COMMAND_SEEK_TO_PREVIOUS}, {@link
     *       Player#COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM}, {@link Player#COMMAND_SEEK_BACK} and/or
     *       {@link #ICON_PREVIOUS}, {@link #ICON_SKIP_BACK}, {@link #ICON_REWIND}: {@link
     *       #SLOT_BACK}
     *   <li>{@link Player#COMMAND_SEEK_TO_NEXT}, {@link Player#COMMAND_SEEK_TO_NEXT_MEDIA_ITEM},
     *       {@link Player#COMMAND_SEEK_FORWARD} and/or {@link #ICON_NEXT}, {@link
     *       #ICON_SKIP_FORWARD}, {@link #ICON_FAST_FORWARD}: {@link #SLOT_FORWARD}
     *   <li>Anything else: {@link #SLOT_OVERFLOW}
     * </ul>
     *
     * @param slots The list of allowed {@link Slot} positions. Must not be empty.
     * @return This builder for chaining.
     */
    @UnstableApi
    @CanIgnoreReturnValue
    public Builder setSlots(@Slot int... slots) {
      checkArgument(slots.length != 0);
      this.slots = ImmutableIntArray.copyOf(slots);
      return this;
    }

    /** Builds a {@link CommandButton}. */
    public CommandButton build() {
      checkState(
          (sessionCommand == null) != (playerCommand == Player.COMMAND_INVALID),
          "Exactly one of sessionCommand and playerCommand should be set");
      if (slots == null) {
        slots = ImmutableIntArray.of(getDefaultSlot(playerCommand, icon));
      }
      return new CommandButton(
          sessionCommand,
          playerCommand,
          icon,
          iconResId,
          iconUri,
          displayName,
          extras,
          enabled,
          slots);
    }
  }

  /** The session command of the button. Will be {@code null} if {@link #playerCommand} is set. */
  @Nullable public final SessionCommand sessionCommand;

  /**
   * The {@link Player.Command} command of the button. Will be {@link Player#COMMAND_INVALID} if
   * {@link #sessionCommand} is set.
   */
  public final @Player.Command int playerCommand;

  /** The {@link Icon} of the button. */
  @UnstableApi public final @Icon int icon;

  /**
   * The icon resource id of the button that is used when the predefined {@link #icon} is not
   * available or set to {@link #ICON_UNDEFINED}. Can be {@code 0} if not needed.
   */
  @DrawableRes public final int iconResId;

  /**
   * The {@linkplain ContentResolver#SCHEME_CONTENT content} or {@linkplain
   * ContentResolver#SCHEME_ANDROID_RESOURCE resource} {@link Uri} for the icon of the button that
   * is used when the predefined {@link #icon} is not available or set to {@link #ICON_UNDEFINED}.
   * Can be {@code null}.
   *
   * <p>Note that this value can be used in addition to {@link #iconResId} for consumers that are
   * capable of loading the content or resource {@link Uri}.
   */
  @UnstableApi @Nullable public final Uri iconUri;

  /**
   * The display name of the button. Can be empty if the command is predefined and a custom name
   * isn't needed.
   */
  public final CharSequence displayName;

  /**
   * The extra {@link Bundle} of the button. It's private information between session and
   * controller.
   */
  @UnstableApi public final Bundle extras;

  /**
   * The allowed {@link Slot} positions for this button.
   *
   * <p>The button is only allowed in the defined slots. If none of the slots can display the
   * button, either because the slots do not exist, are already occupied or the UI surface does not
   * allow the specific type of button in these slots, the button will not be displayed at all.
   *
   * <p>When multiple slots are provided, they define a preference order. The button will be placed
   * in the first slot in the list that exists, isn't already occupied and that allows this type of
   * button.
   */
  @UnstableApi public final ImmutableIntArray slots;

  /**
   * Whether the button is enabled.
   *
   * <p>Note that this value will be set to {@code false} for {@link MediaController} instances if
   * the corresponding command is not available to this controller (see {@link #playerCommand} and
   * {@link #sessionCommand}).
   */
  public final boolean isEnabled;

  private CommandButton(
      @Nullable SessionCommand sessionCommand,
      @Player.Command int playerCommand,
      @Icon int icon,
      @DrawableRes int iconResId,
      @Nullable Uri iconUri,
      CharSequence displayName,
      Bundle extras,
      boolean enabled,
      ImmutableIntArray slots) {
    this.sessionCommand = sessionCommand;
    this.playerCommand = playerCommand;
    this.icon = icon;
    this.iconResId = iconResId;
    this.iconUri = iconUri;
    this.displayName = displayName;
    this.extras = new Bundle(extras);
    this.isEnabled = enabled;
    this.slots = slots;
  }

  /** Returns a copy with the new {@link #isEnabled} flag. */
  @CheckReturnValue
  /* package */ CommandButton copyWithIsEnabled(boolean isEnabled) {
    // Because this method is supposed to be used by the library only, this method has been chosen
    // over the conventional `buildUpon` approach. This aims for keeping this separate from the
    // public Builder-API used by apps.
    if (this.isEnabled == isEnabled) {
      return this;
    }
    return new CommandButton(
        sessionCommand,
        playerCommand,
        icon,
        iconResId,
        iconUri,
        displayName,
        new Bundle(extras),
        isEnabled,
        slots);
  }

  /** Checks the given command button for equality while ignoring {@link #extras}. */
  @Override
  public boolean equals(@Nullable Object obj) {
    if (this == obj) {
      return true;
    }
    if (!(obj instanceof CommandButton)) {
      return false;
    }
    CommandButton button = (CommandButton) obj;
    return Objects.equal(sessionCommand, button.sessionCommand)
        && playerCommand == button.playerCommand
        && icon == button.icon
        && iconResId == button.iconResId
        && Objects.equal(iconUri, button.iconUri)
        && TextUtils.equals(displayName, button.displayName)
        && isEnabled == button.isEnabled
        && slots.equals(button.slots);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(
        sessionCommand, playerCommand, icon, iconResId, displayName, isEnabled, iconUri, slots);
  }

  /**
   * Returns a list of command buttons with the {@link CommandButton#isEnabled} flag set to false if
   * the corresponding command is not available.
   */
  /* package */ static ImmutableList<CommandButton> copyWithUnavailableButtonsDisabled(
      List<CommandButton> commandButtons,
      SessionCommands sessionCommands,
      Player.Commands playerCommands) {
    ImmutableList.Builder<CommandButton> updatedButtons = new ImmutableList.Builder<>();
    for (int i = 0; i < commandButtons.size(); i++) {
      CommandButton button = commandButtons.get(i);
      if (isButtonCommandAvailable(button, sessionCommands, playerCommands)) {
        updatedButtons.add(button);
      } else {
        updatedButtons.add(button.copyWithIsEnabled(false));
      }
    }
    return updatedButtons.build();
  }

  /**
   * Returns whether the required command ({@link #playerCommand} or {@link #sessionCommand}) for
   * the button is available.
   *
   * @param button The command button.
   * @param sessionCommands The available session commands.
   * @param playerCommands The available player commands.
   * @return Whether the command required for this button is available.
   */
  /* package */ static boolean isButtonCommandAvailable(
      CommandButton button, SessionCommands sessionCommands, Player.Commands playerCommands) {
    return (button.sessionCommand != null && sessionCommands.contains(button.sessionCommand))
        || (button.playerCommand != Player.COMMAND_INVALID
            && playerCommands.contains(button.playerCommand));
  }

  private static final String FIELD_SESSION_COMMAND = Util.intToStringMaxRadix(0);
  private static final String FIELD_PLAYER_COMMAND = Util.intToStringMaxRadix(1);
  private static final String FIELD_ICON_RES_ID = Util.intToStringMaxRadix(2);
  private static final String FIELD_DISPLAY_NAME = Util.intToStringMaxRadix(3);
  private static final String FIELD_EXTRAS = Util.intToStringMaxRadix(4);
  private static final String FIELD_ENABLED = Util.intToStringMaxRadix(5);
  private static final String FIELD_ICON_URI = Util.intToStringMaxRadix(6);
  private static final String FIELD_ICON = Util.intToStringMaxRadix(7);
  private static final String FIELD_SLOTS = Util.intToStringMaxRadix(8);

  @UnstableApi
  public Bundle toBundle() {
    Bundle bundle = new Bundle();
    if (sessionCommand != null) {
      bundle.putBundle(FIELD_SESSION_COMMAND, sessionCommand.toBundle());
    }
    if (playerCommand != Player.COMMAND_INVALID) {
      bundle.putInt(FIELD_PLAYER_COMMAND, playerCommand);
    }
    if (icon != ICON_UNDEFINED) {
      bundle.putInt(FIELD_ICON, icon);
    }
    if (iconResId != 0) {
      bundle.putInt(FIELD_ICON_RES_ID, iconResId);
    }
    if (displayName != "") {
      bundle.putCharSequence(FIELD_DISPLAY_NAME, displayName);
    }
    if (!extras.isEmpty()) {
      bundle.putBundle(FIELD_EXTRAS, extras);
    }
    if (iconUri != null) {
      bundle.putParcelable(FIELD_ICON_URI, iconUri);
    }
    if (!isEnabled) {
      bundle.putBoolean(FIELD_ENABLED, isEnabled);
    }
    if (slots.length() != 1 || slots.get(0) != SLOT_OVERFLOW) {
      bundle.putIntArray(FIELD_SLOTS, slots.toArray());
    }
    return bundle;
  }

  /**
   * @deprecated Use {@link #fromBundle(Bundle, int)} instead.
   */
  @Deprecated
  @UnstableApi
  public static CommandButton fromBundle(Bundle bundle) {
    return fromBundle(bundle, MediaSessionStub.VERSION_INT);
  }

  /** Restores a {@code CommandButton} from a {@link Bundle}. */
  @UnstableApi
  public static CommandButton fromBundle(Bundle bundle, int sessionInterfaceVersion) {
    @Nullable Bundle sessionCommandBundle = bundle.getBundle(FIELD_SESSION_COMMAND);
    @Nullable
    SessionCommand sessionCommand =
        sessionCommandBundle == null ? null : SessionCommand.fromBundle(sessionCommandBundle);
    @Player.Command
    int playerCommand =
        bundle.getInt(FIELD_PLAYER_COMMAND, /* defaultValue= */ Player.COMMAND_INVALID);
    int iconResId = bundle.getInt(FIELD_ICON_RES_ID, /* defaultValue= */ 0);
    CharSequence displayName = bundle.getCharSequence(FIELD_DISPLAY_NAME, /* defaultValue= */ "");
    @Nullable Bundle extras = bundle.getBundle(FIELD_EXTRAS);
    // Before sessionInterfaceVersion == 3, the session expected this value to be meaningless and we
    // can only assume it was meant to be true.
    boolean enabled =
        sessionInterfaceVersion < 3 || bundle.getBoolean(FIELD_ENABLED, /* defaultValue= */ true);
    @Nullable Uri iconUri = bundle.getParcelable(FIELD_ICON_URI);
    @Icon int icon = bundle.getInt(FIELD_ICON, /* defaultValue= */ ICON_UNDEFINED);
    @Nullable
    @Slot
    int[] slots = bundle.getIntArray(FIELD_SLOTS);
    Builder builder = new Builder(icon, iconResId);
    if (sessionCommand != null) {
      builder.setSessionCommand(sessionCommand);
    }
    if (playerCommand != Player.COMMAND_INVALID) {
      builder.setPlayerCommand(playerCommand);
    }
    if (iconUri != null
        && (Objects.equal(iconUri.getScheme(), ContentResolver.SCHEME_CONTENT)
            || Objects.equal(iconUri.getScheme(), ContentResolver.SCHEME_ANDROID_RESOURCE))) {
      builder.setIconUri(iconUri);
    }
    return builder
        .setDisplayName(displayName)
        .setExtras(extras == null ? Bundle.EMPTY : extras)
        .setEnabled(enabled)
        .setSlots(slots == null ? new int[] {SLOT_OVERFLOW} : slots)
        .build();
  }

  /**
   * Returns a drawable resource id for the given {@link Icon} constant.
   *
   * @param icon The {@link Icon}.
   * @return The drawable resource if for the {@code icon}, or 0 if not found.
   */
  @UnstableApi
  @DrawableRes
  public static int getIconResIdForIconConstant(@Icon int icon) {
    switch (icon) {
      case ICON_PLAY:
        return R.drawable.media3_icon_play;
      case ICON_PAUSE:
        return R.drawable.media3_icon_pause;
      case ICON_STOP:
        return R.drawable.media3_icon_stop;
      case ICON_NEXT:
        return R.drawable.media3_icon_next;
      case ICON_PREVIOUS:
        return R.drawable.media3_icon_previous;
      case ICON_SKIP_FORWARD:
        return R.drawable.media3_icon_skip_forward;
      case ICON_SKIP_FORWARD_5:
        return R.drawable.media3_icon_skip_forward_5;
      case ICON_SKIP_FORWARD_10:
        return R.drawable.media3_icon_skip_forward_10;
      case ICON_SKIP_FORWARD_15:
        return R.drawable.media3_icon_skip_forward_15;
      case ICON_SKIP_FORWARD_30:
        return R.drawable.media3_icon_skip_forward_30;
      case ICON_SKIP_BACK:
        return R.drawable.media3_icon_skip_back;
      case ICON_SKIP_BACK_5:
        return R.drawable.media3_icon_skip_back_5;
      case ICON_SKIP_BACK_10:
        return R.drawable.media3_icon_skip_back_10;
      case ICON_SKIP_BACK_15:
        return R.drawable.media3_icon_skip_back_15;
      case ICON_SKIP_BACK_30:
        return R.drawable.media3_icon_skip_back_30;
      case ICON_FAST_FORWARD:
        return R.drawable.media3_icon_fast_forward;
      case ICON_REWIND:
        return R.drawable.media3_icon_rewind;
      case ICON_REPEAT_ALL:
        return R.drawable.media3_icon_repeat_all;
      case ICON_REPEAT_ONE:
        return R.drawable.media3_icon_repeat_one;
      case ICON_REPEAT_OFF:
        return R.drawable.media3_icon_repeat_off;
      case ICON_SHUFFLE_ON:
        return R.drawable.media3_icon_shuffle_on;
      case ICON_SHUFFLE_OFF:
        return R.drawable.media3_icon_shuffle_off;
      case ICON_SHUFFLE_STAR:
        return R.drawable.media3_icon_shuffle_star;
      case ICON_HEART_FILLED:
        return R.drawable.media3_icon_heart_filled;
      case ICON_HEART_UNFILLED:
        return R.drawable.media3_icon_heart_unfilled;
      case ICON_STAR_FILLED:
        return R.drawable.media3_icon_star_filled;
      case ICON_STAR_UNFILLED:
        return R.drawable.media3_icon_star_unfilled;
      case ICON_BOOKMARK_FILLED:
        return R.drawable.media3_icon_bookmark_filled;
      case ICON_BOOKMARK_UNFILLED:
        return R.drawable.media3_icon_bookmark_unfilled;
      case ICON_THUMB_UP_FILLED:
        return R.drawable.media3_icon_thumb_up_filled;
      case ICON_THUMB_UP_UNFILLED:
        return R.drawable.media3_icon_thumb_up_unfilled;
      case ICON_THUMB_DOWN_FILLED:
        return R.drawable.media3_icon_thumb_down_filled;
      case ICON_THUMB_DOWN_UNFILLED:
        return R.drawable.media3_icon_thumb_down_unfilled;
      case ICON_FLAG_FILLED:
        return R.drawable.media3_icon_flag_filled;
      case ICON_FLAG_UNFILLED:
        return R.drawable.media3_icon_flag_unfilled;
      case ICON_PLUS:
        return R.drawable.media3_icon_plus;
      case ICON_MINUS:
        return R.drawable.media3_icon_minus;
      case ICON_PLAYLIST_ADD:
        return R.drawable.media3_icon_playlist_add;
      case ICON_PLAYLIST_REMOVE:
        return R.drawable.media3_icon_playlist_remove;
      case ICON_QUEUE_ADD:
        return R.drawable.media3_icon_queue_add;
      case ICON_QUEUE_NEXT:
        return R.drawable.media3_icon_queue_next;
      case ICON_QUEUE_REMOVE:
        return R.drawable.media3_icon_queue_remove;
      case ICON_BLOCK:
        return R.drawable.media3_icon_block;
      case ICON_PLUS_CIRCLE_FILLED:
        return R.drawable.media3_icon_plus_circle_filled;
      case ICON_PLUS_CIRCLE_UNFILLED:
        return R.drawable.media3_icon_plus_circle_unfilled;
      case ICON_MINUS_CIRCLE_FILLED:
        return R.drawable.media3_icon_minus_circle_filled;
      case ICON_MINUS_CIRCLE_UNFILLED:
        return R.drawable.media3_icon_minus_circle_unfilled;
      case ICON_CHECK_CIRCLE_FILLED:
        return R.drawable.media3_icon_check_circle_filled;
      case ICON_CHECK_CIRCLE_UNFILLED:
        return R.drawable.media3_icon_check_circle_unfilled;
      case ICON_PLAYBACK_SPEED:
        return R.drawable.media3_icon_playback_speed;
      case ICON_PLAYBACK_SPEED_0_5:
        return R.drawable.media3_icon_playback_speed_0_5;
      case ICON_PLAYBACK_SPEED_0_8:
        return R.drawable.media3_icon_playback_speed_0_8;
      case ICON_PLAYBACK_SPEED_1_0:
        return R.drawable.media3_icon_playback_speed_1_0;
      case ICON_PLAYBACK_SPEED_1_2:
        return R.drawable.media3_icon_playback_speed_1_2;
      case ICON_PLAYBACK_SPEED_1_5:
        return R.drawable.media3_icon_playback_speed_1_5;
      case ICON_PLAYBACK_SPEED_1_8:
        return R.drawable.media3_icon_playback_speed_1_8;
      case ICON_PLAYBACK_SPEED_2_0:
        return R.drawable.media3_icon_playback_speed_2_0;
      case ICON_SETTINGS:
        return R.drawable.media3_icon_settings;
      case ICON_QUALITY:
        return R.drawable.media3_icon_quality;
      case ICON_SUBTITLES:
        return R.drawable.media3_icon_subtitles;
      case ICON_SUBTITLES_OFF:
        return R.drawable.media3_icon_subtitles_off;
      case ICON_CLOSED_CAPTIONS:
        return R.drawable.media3_icon_closed_captions;
      case ICON_CLOSED_CAPTIONS_OFF:
        return R.drawable.media3_icon_closed_captions_off;
      case ICON_SYNC:
        return R.drawable.media3_icon_sync;
      case ICON_SHARE:
        return R.drawable.media3_icon_share;
      case ICON_VOLUME_UP:
        return R.drawable.media3_icon_volume_up;
      case ICON_VOLUME_DOWN:
        return R.drawable.media3_icon_volume_down;
      case ICON_VOLUME_OFF:
        return R.drawable.media3_icon_volume_off;
      case ICON_ARTIST:
        return R.drawable.media3_icon_artist;
      case ICON_ALBUM:
        return R.drawable.media3_icon_album;
      case ICON_RADIO:
        return R.drawable.media3_icon_radio;
      case ICON_SIGNAL:
        return R.drawable.media3_icon_signal;
      case ICON_FEED:
        return R.drawable.media3_icon_feed;
      default:
        return 0;
    }
  }

  /**
   * Returns the default {@link Slot} for a button.
   *
   * @param playerCommand The {@link Player.Command} associated with this button.
   * @param icon The {@link Icon} of this button.
   * @return The default {@link Slot} for this button.
   */
  @UnstableApi
  public static @Slot int getDefaultSlot(@Player.Command int playerCommand, @Icon int icon) {
    if (playerCommand == Player.COMMAND_PLAY_PAUSE || icon == ICON_PLAY || icon == ICON_PAUSE) {
      return SLOT_CENTRAL;
    } else if (playerCommand == Player.COMMAND_SEEK_BACK
        || playerCommand == Player.COMMAND_SEEK_TO_PREVIOUS
        || playerCommand == Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM
        || icon == ICON_PREVIOUS
        || icon == ICON_REWIND
        || icon == ICON_SKIP_BACK
        || icon == ICON_SKIP_BACK_5
        || icon == ICON_SKIP_BACK_10
        || icon == ICON_SKIP_BACK_15
        || icon == ICON_SKIP_BACK_30) {
      return SLOT_BACK;
    } else if (playerCommand == Player.COMMAND_SEEK_FORWARD
        || playerCommand == Player.COMMAND_SEEK_TO_NEXT
        || playerCommand == Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM
        || icon == ICON_NEXT
        || icon == ICON_FAST_FORWARD
        || icon == ICON_SKIP_FORWARD
        || icon == ICON_SKIP_FORWARD_5
        || icon == ICON_SKIP_FORWARD_10
        || icon == ICON_SKIP_FORWARD_15
        || icon == ICON_SKIP_FORWARD_30) {
      return SLOT_FORWARD;
    } else {
      return SLOT_OVERFLOW;
    }
  }
}
