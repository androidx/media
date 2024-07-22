/*
 * Copyright (C) 2016 The Android Open Source Project
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
package androidx.media3.ui;

import static androidx.media3.common.Player.COMMAND_SEEK_BACK;
import static androidx.media3.common.Player.COMMAND_SEEK_FORWARD;
import static androidx.media3.common.Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM;
import static androidx.media3.common.Player.COMMAND_SEEK_TO_NEXT;
import static androidx.media3.common.Player.COMMAND_SEEK_TO_PREVIOUS;
import static androidx.media3.common.Player.EVENT_AVAILABLE_COMMANDS_CHANGED;
import static androidx.media3.common.Player.EVENT_IS_PLAYING_CHANGED;
import static androidx.media3.common.Player.EVENT_PLAYBACK_STATE_CHANGED;
import static androidx.media3.common.Player.EVENT_PLAY_WHEN_READY_CHANGED;
import static androidx.media3.common.Player.EVENT_POSITION_DISCONTINUITY;
import static androidx.media3.common.Player.EVENT_REPEAT_MODE_CHANGED;
import static androidx.media3.common.Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED;
import static androidx.media3.common.Player.EVENT_TIMELINE_CHANGED;
import static androidx.media3.common.util.Util.getDrawable;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.drawable.Drawable;
import android.os.Looper;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityEvent;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.DoNotInline;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.media3.common.C;
import androidx.media3.common.MediaLibraryInfo;
import androidx.media3.common.Player;
import androidx.media3.common.Player.Events;
import androidx.media3.common.Timeline;
import androidx.media3.common.util.Assertions;
import androidx.media3.common.util.RepeatModeUtil;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import java.util.Arrays;
import java.util.Formatter;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A view for controlling {@link Player} instances.
 *
 * <p>A LegacyPlayerControlView can be customized by setting attributes (or calling corresponding
 * methods), overriding drawables, overriding the view's layout file, or by specifying a custom view
 * layout file.
 *
 * <h2>Attributes</h2>
 *
 * The following attributes can be set on a LegacyPlayerControlView when used in a layout XML file:
 *
 * <ul>
 *   <li><b>{@code show_timeout}</b> - The time between the last user interaction and the controls
 *       being automatically hidden, in milliseconds. Use zero if the controls should not
 *       automatically timeout.
 *       <ul>
 *         <li>Corresponding method: {@link #setShowTimeoutMs(int)}
 *         <li>Default: {@link #DEFAULT_SHOW_TIMEOUT_MS}
 *       </ul>
 *   <li><b>{@code show_rewind_button}</b> - Whether the rewind button is shown.
 *       <ul>
 *         <li>Corresponding method: {@link #setShowRewindButton(boolean)}
 *         <li>Default: true
 *       </ul>
 *   <li><b>{@code show_fastforward_button}</b> - Whether the fast forward button is shown.
 *       <ul>
 *         <li>Corresponding method: {@link #setShowFastForwardButton(boolean)}
 *         <li>Default: true
 *       </ul>
 *   <li><b>{@code show_previous_button}</b> - Whether the previous button is shown.
 *       <ul>
 *         <li>Corresponding method: {@link #setShowPreviousButton(boolean)}
 *         <li>Default: true
 *       </ul>
 *   <li><b>{@code show_next_button}</b> - Whether the next button is shown.
 *       <ul>
 *         <li>Corresponding method: {@link #setShowNextButton(boolean)}
 *         <li>Default: true
 *       </ul>
 *   <li><b>{@code repeat_toggle_modes}</b> - A flagged enumeration value specifying which repeat
 *       mode toggle options are enabled. Valid values are: {@code none}, {@code one}, {@code all},
 *       or {@code one|all}.
 *       <ul>
 *         <li>Corresponding method: {@link #setRepeatToggleModes(int)}
 *         <li>Default: {@link LegacyPlayerControlView#DEFAULT_REPEAT_TOGGLE_MODES}
 *       </ul>
 *   <li><b>{@code show_shuffle_button}</b> - Whether the shuffle button is shown.
 *       <ul>
 *         <li>Corresponding method: {@link #setShowShuffleButton(boolean)}
 *         <li>Default: false
 *       </ul>
 *   <li><b>{@code time_bar_min_update_interval}</b> - Specifies the minimum interval between time
 *       bar position updates.
 *       <ul>
 *         <li>Corresponding method: {@link #setTimeBarMinUpdateInterval(int)}
 *         <li>Default: {@link #DEFAULT_TIME_BAR_MIN_UPDATE_INTERVAL_MS}
 *       </ul>
 *   <li><b>{@code controller_layout_id}</b> - Specifies the id of the layout to be inflated. See
 *       below for more details.
 *       <ul>
 *         <li>Corresponding method: None
 *         <li>Default: {@code R.layout.exo_legacy_player_control_view}
 *       </ul>
 *   <li>All attributes that can be set on {@link DefaultTimeBar} can also be set on a
 *       LegacyPlayerControlView, and will be propagated to the inflated {@link DefaultTimeBar}
 *       unless the layout is overridden to specify a custom {@code exo_progress} (see below).
 * </ul>
 *
 * <h2>Overriding drawables</h2>
 *
 * The drawables used by LegacyPlayerControlView (with its default layout file) can be overridden by
 * drawables with the same names defined in your application. The drawables that can be overridden
 * are:
 *
 * <ul>
 *   <li><b>{@code exo_legacy_controls_play}</b> - The play icon.
 *   <li><b>{@code exo_legacy_controls_pause}</b> - The pause icon.
 *   <li><b>{@code exo_legacy_controls_rewind}</b> - The rewind icon.
 *   <li><b>{@code exo_legacy_controls_fastforward}</b> - The fast forward icon.
 *   <li><b>{@code exo_legacy_controls_previous}</b> - The previous icon.
 *   <li><b>{@code exo_legacy_controls_next}</b> - The next icon.
 *   <li><b>{@code exo_legacy_controls_repeat_off}</b> - The repeat icon for {@link
 *       Player#REPEAT_MODE_OFF}.
 *   <li><b>{@code exo_legacy_controls_repeat_one}</b> - The repeat icon for {@link
 *       Player#REPEAT_MODE_ONE}.
 *   <li><b>{@code exo_legacy_controls_repeat_all}</b> - The repeat icon for {@link
 *       Player#REPEAT_MODE_ALL}.
 *   <li><b>{@code exo_legacy_controls_shuffle_off}</b> - The shuffle icon when shuffling is
 *       disabled.
 *   <li><b>{@code exo_legacy_controls_shuffle_on}</b> - The shuffle icon when shuffling is enabled.
 *   <li><b>{@code exo_legacy_controls_vr}</b> - The VR icon.
 * </ul>
 *
 * <h2>Overriding the layout file</h2>
 *
 * To customize the layout of LegacyPlayerControlView throughout your app, or just for certain
 * configurations, you can define {@code exo_legacy_player_control_view.xml} layout files in your
 * application {@code res/layout*} directories. These layouts will override the one provided by the
 * library, and will be inflated for use by LegacyPlayerControlView. The view identifies and binds
 * its children by looking for the following ids:
 *
 * <ul>
 *   <li><b>{@code exo_play}</b> - The play button.
 *       <ul>
 *         <li>Type: {@link View}
 *       </ul>
 *   <li><b>{@code exo_pause}</b> - The pause button.
 *       <ul>
 *         <li>Type: {@link View}
 *       </ul>
 *   <li><b>{@code exo_rew}</b> - The rewind button.
 *       <ul>
 *         <li>Type: {@link View}
 *       </ul>
 *   <li><b>{@code exo_ffwd}</b> - The fast forward button.
 *       <ul>
 *         <li>Type: {@link View}
 *       </ul>
 *   <li><b>{@code exo_prev}</b> - The previous button.
 *       <ul>
 *         <li>Type: {@link View}
 *       </ul>
 *   <li><b>{@code exo_next}</b> - The next button.
 *       <ul>
 *         <li>Type: {@link View}
 *       </ul>
 *   <li><b>{@code exo_repeat_toggle}</b> - The repeat toggle button.
 *       <ul>
 *         <li>Type: {@link ImageView}
 *         <li>Note: LegacyPlayerControlView will programmatically set the drawable on the repeat
 *             toggle button according to the player's current repeat mode. The drawables used are
 *             {@code exo_legacy_controls_repeat_off}, {@code exo_legacy_controls_repeat_one} and
 *             {@code exo_legacy_controls_repeat_all}. See the section above for information on
 *             overriding these drawables.
 *       </ul>
 *   <li><b>{@code exo_shuffle}</b> - The shuffle button.
 *       <ul>
 *         <li>Type: {@link ImageView}
 *         <li>Note: LegacyPlayerControlView will programmatically set the drawable on the shuffle
 *             button according to the player's current repeat mode. The drawables used are {@code
 *             exo_legacy_controls_shuffle_off} and {@code exo_legacy_controls_shuffle_on}. See the
 *             section above for information on overriding these drawables.
 *       </ul>
 *   <li><b>{@code exo_vr}</b> - The VR mode button.
 *       <ul>
 *         <li>Type: {@link View}
 *       </ul>
 *   <li><b>{@code exo_position}</b> - Text view displaying the current playback position.
 *       <ul>
 *         <li>Type: {@link TextView}
 *       </ul>
 *   <li><b>{@code exo_duration}</b> - Text view displaying the current media duration.
 *       <ul>
 *         <li>Type: {@link TextView}
 *       </ul>
 *   <li><b>{@code exo_progress_placeholder}</b> - A placeholder that's replaced with the inflated
 *       {@link DefaultTimeBar}. Ignored if an {@code exo_progress} view exists.
 *       <ul>
 *         <li>Type: {@link View}
 *       </ul>
 *   <li><b>{@code exo_progress}</b> - Time bar that's updated during playback and allows seeking.
 *       {@link DefaultTimeBar} attributes set on the LegacyPlayerControlView will not be
 *       automatically propagated through to this instance. If a view exists with this id, any
 *       {@code exo_progress_placeholder} view will be ignored.
 *       <ul>
 *         <li>Type: {@link TimeBar}
 *       </ul>
 * </ul>
 *
 * <p>All child views are optional and so can be omitted if not required, however where defined they
 * must be of the expected type.
 *
 * <h2>Specifying a custom layout file</h2>
 *
 * Defining your own {@code exo_legacy_player_control_view.xml} is useful to customize the layout of
 * LegacyPlayerControlView throughout your application. It's also possible to customize the layout
 * for a single instance in a layout file. This is achieved by setting the {@code
 * controller_layout_id} attribute on a LegacyPlayerControlView. This will cause the specified
 * layout to be inflated instead of {@code exo_legacy_player_control_view.xml} for only the instance
 * on which the attribute is set.
 */
@UnstableApi
public class LegacyPlayerControlView extends FrameLayout {

  static {
    MediaLibraryInfo.registerModule("media3.ui");
  }

  /** Listener to be notified about changes of the visibility of the UI control. */
  public interface VisibilityListener {

    /**
     * Called when the visibility changes.
     *
     * @param visibility The new visibility. Either {@link View#VISIBLE} or {@link View#GONE}.
     */
    void onVisibilityChange(int visibility);
  }

  /** Listener to be notified when progress has been updated. */
  public interface ProgressUpdateListener {

    /**
     * Called when progress needs to be updated.
     *
     * @param position The current position.
     * @param bufferedPosition The current buffered position.
     */
    void onProgressUpdate(long position, long bufferedPosition);
  }

  /** The default show timeout, in milliseconds. */
  public static final int DEFAULT_SHOW_TIMEOUT_MS = 5000;

  /** The default repeat toggle modes. */
  public static final @RepeatModeUtil.RepeatToggleModes int DEFAULT_REPEAT_TOGGLE_MODES =
      RepeatModeUtil.REPEAT_TOGGLE_MODE_NONE;

  /** The default minimum interval between time bar position updates. */
  public static final int DEFAULT_TIME_BAR_MIN_UPDATE_INTERVAL_MS = 200;

  /** The maximum number of windows that can be shown in a multi-window time bar. */
  public static final int MAX_WINDOWS_FOR_MULTI_WINDOW_TIME_BAR = 100;

  /** The maximum interval between time bar position updates. */
  private static final int MAX_UPDATE_INTERVAL_MS = 1000;

  private final ComponentListener componentListener;
  private final CopyOnWriteArrayList<VisibilityListener> visibilityListeners;
  @Nullable private final View previousButton;
  @Nullable private final View nextButton;
  @Nullable private final View playButton;
  @Nullable private final View pauseButton;
  @Nullable private final View fastForwardButton;
  @Nullable private final View rewindButton;
  @Nullable private final ImageView repeatToggleButton;
  @Nullable private final ImageView shuffleButton;
  @Nullable private final View vrButton;
  @Nullable private final TextView durationView;
  @Nullable private final TextView positionView;
  @Nullable private final TimeBar timeBar;
  private final StringBuilder formatBuilder;
  private final Formatter formatter;
  private final Timeline.Period period;
  private final Timeline.Window window;
  private final Runnable updateProgressAction;
  private final Runnable hideAction;

  private final Drawable repeatOffButtonDrawable;
  private final Drawable repeatOneButtonDrawable;
  private final Drawable repeatAllButtonDrawable;
  private final String repeatOffButtonContentDescription;
  private final String repeatOneButtonContentDescription;
  private final String repeatAllButtonContentDescription;
  private final Drawable shuffleOnButtonDrawable;
  private final Drawable shuffleOffButtonDrawable;
  private final float buttonAlphaEnabled;
  private final float buttonAlphaDisabled;
  private final String shuffleOnContentDescription;
  private final String shuffleOffContentDescription;

  @Nullable private Player player;
  @Nullable private ProgressUpdateListener progressUpdateListener;

  private boolean isAttachedToWindow;
  private boolean showMultiWindowTimeBar;
  private boolean showPlayButtonIfSuppressed;
  private boolean multiWindowTimeBar;
  private boolean scrubbing;
  private int showTimeoutMs;
  private int timeBarMinUpdateIntervalMs;
  private @RepeatModeUtil.RepeatToggleModes int repeatToggleModes;
  private boolean showRewindButton;
  private boolean showFastForwardButton;
  private boolean showPreviousButton;
  private boolean showNextButton;
  private boolean showShuffleButton;
  private long hideAtMs;
  private long[] adGroupTimesMs;
  private boolean[] playedAdGroups;
  private long[] extraAdGroupTimesMs;
  private boolean[] extraPlayedAdGroups;
  private long currentWindowOffset;
  private long currentPosition;
  private long currentBufferedPosition;

  public LegacyPlayerControlView(Context context) {
    this(context, /* attrs= */ null);
  }

  public LegacyPlayerControlView(Context context, @Nullable AttributeSet attrs) {
    this(context, attrs, /* defStyleAttr= */ 0);
  }

  public LegacyPlayerControlView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
    this(context, attrs, defStyleAttr, attrs);
  }

  @SuppressWarnings({
    "nullness:argument",
    "nullness:method.invocation",
    "nullness:methodref.receiver.bound"
  })
  public LegacyPlayerControlView(
      Context context,
      @Nullable AttributeSet attrs,
      int defStyleAttr,
      @Nullable AttributeSet playbackAttrs) {
    super(context, attrs, defStyleAttr);
    int controllerLayoutId = R.layout.exo_legacy_player_control_view;
    showPlayButtonIfSuppressed = true;
    showTimeoutMs = DEFAULT_SHOW_TIMEOUT_MS;
    repeatToggleModes = DEFAULT_REPEAT_TOGGLE_MODES;
    timeBarMinUpdateIntervalMs = DEFAULT_TIME_BAR_MIN_UPDATE_INTERVAL_MS;
    hideAtMs = C.TIME_UNSET;
    showRewindButton = true;
    showFastForwardButton = true;
    showPreviousButton = true;
    showNextButton = true;
    showShuffleButton = false;
    if (playbackAttrs != null) {
      TypedArray a =
          context
              .getTheme()
              .obtainStyledAttributes(
                  playbackAttrs,
                  R.styleable.LegacyPlayerControlView,
                  defStyleAttr,
                  /* defStyleRes= */ 0);
      try {
        showTimeoutMs = a.getInt(R.styleable.LegacyPlayerControlView_show_timeout, showTimeoutMs);
        controllerLayoutId =
            a.getResourceId(
                R.styleable.LegacyPlayerControlView_controller_layout_id, controllerLayoutId);
        repeatToggleModes = getRepeatToggleModes(a, repeatToggleModes);
        showRewindButton =
            a.getBoolean(R.styleable.LegacyPlayerControlView_show_rewind_button, showRewindButton);
        showFastForwardButton =
            a.getBoolean(
                R.styleable.LegacyPlayerControlView_show_fastforward_button, showFastForwardButton);
        showPreviousButton =
            a.getBoolean(
                R.styleable.LegacyPlayerControlView_show_previous_button, showPreviousButton);
        showNextButton =
            a.getBoolean(R.styleable.LegacyPlayerControlView_show_next_button, showNextButton);
        showShuffleButton =
            a.getBoolean(
                R.styleable.LegacyPlayerControlView_show_shuffle_button, showShuffleButton);
        setTimeBarMinUpdateInterval(
            a.getInt(
                R.styleable.LegacyPlayerControlView_time_bar_min_update_interval,
                timeBarMinUpdateIntervalMs));
      } finally {
        a.recycle();
      }
    }
    visibilityListeners = new CopyOnWriteArrayList<>();
    period = new Timeline.Period();
    window = new Timeline.Window();
    formatBuilder = new StringBuilder();
    formatter = new Formatter(formatBuilder, Locale.getDefault());
    adGroupTimesMs = new long[0];
    playedAdGroups = new boolean[0];
    extraAdGroupTimesMs = new long[0];
    extraPlayedAdGroups = new boolean[0];
    componentListener = new ComponentListener();
    updateProgressAction = this::updateProgress;
    hideAction = this::hide;

    LayoutInflater.from(context).inflate(controllerLayoutId, /* root= */ this);
    setDescendantFocusability(FOCUS_AFTER_DESCENDANTS);

    TimeBar customTimeBar = findViewById(R.id.exo_progress);
    View timeBarPlaceholder = findViewById(R.id.exo_progress_placeholder);
    if (customTimeBar != null) {
      timeBar = customTimeBar;
    } else if (timeBarPlaceholder != null) {
      // Propagate playbackAttrs as timebarAttrs so that DefaultTimeBar's custom attributes are
      // transferred, but standard attributes (e.g. background) are not.
      DefaultTimeBar defaultTimeBar = new DefaultTimeBar(context, null, 0, playbackAttrs);
      defaultTimeBar.setId(R.id.exo_progress);
      defaultTimeBar.setLayoutParams(timeBarPlaceholder.getLayoutParams());
      ViewGroup parent = ((ViewGroup) timeBarPlaceholder.getParent());
      int timeBarIndex = parent.indexOfChild(timeBarPlaceholder);
      parent.removeView(timeBarPlaceholder);
      parent.addView(defaultTimeBar, timeBarIndex);
      timeBar = defaultTimeBar;
    } else {
      timeBar = null;
    }
    durationView = findViewById(R.id.exo_duration);
    positionView = findViewById(R.id.exo_position);

    if (timeBar != null) {
      timeBar.addListener(componentListener);
    }
    playButton = findViewById(R.id.exo_play);
    if (playButton != null) {
      playButton.setOnClickListener(componentListener);
    }
    pauseButton = findViewById(R.id.exo_pause);
    if (pauseButton != null) {
      pauseButton.setOnClickListener(componentListener);
    }
    previousButton = findViewById(R.id.exo_prev);
    if (previousButton != null) {
      previousButton.setOnClickListener(componentListener);
    }
    nextButton = findViewById(R.id.exo_next);
    if (nextButton != null) {
      nextButton.setOnClickListener(componentListener);
    }
    rewindButton = findViewById(R.id.exo_rew);
    if (rewindButton != null) {
      rewindButton.setOnClickListener(componentListener);
    }
    fastForwardButton = findViewById(R.id.exo_ffwd);
    if (fastForwardButton != null) {
      fastForwardButton.setOnClickListener(componentListener);
    }
    repeatToggleButton = findViewById(R.id.exo_repeat_toggle);
    if (repeatToggleButton != null) {
      repeatToggleButton.setOnClickListener(componentListener);
    }
    shuffleButton = findViewById(R.id.exo_shuffle);
    if (shuffleButton != null) {
      shuffleButton.setOnClickListener(componentListener);
    }
    vrButton = findViewById(R.id.exo_vr);
    setShowVrButton(false);
    updateButton(false, false, vrButton);

    Resources resources = context.getResources();

    buttonAlphaEnabled =
        (float) resources.getInteger(R.integer.exo_media_button_opacity_percentage_enabled) / 100;
    buttonAlphaDisabled =
        (float) resources.getInteger(R.integer.exo_media_button_opacity_percentage_disabled) / 100;

    repeatOffButtonDrawable =
        getDrawable(context, resources, R.drawable.exo_legacy_controls_repeat_off);
    repeatOneButtonDrawable =
        getDrawable(context, resources, R.drawable.exo_legacy_controls_repeat_one);
    repeatAllButtonDrawable =
        getDrawable(context, resources, R.drawable.exo_legacy_controls_repeat_all);
    shuffleOnButtonDrawable =
        getDrawable(context, resources, R.drawable.exo_legacy_controls_shuffle_on);
    shuffleOffButtonDrawable =
        getDrawable(context, resources, R.drawable.exo_legacy_controls_shuffle_off);
    repeatOffButtonContentDescription =
        resources.getString(R.string.exo_controls_repeat_off_description);
    repeatOneButtonContentDescription =
        resources.getString(R.string.exo_controls_repeat_one_description);
    repeatAllButtonContentDescription =
        resources.getString(R.string.exo_controls_repeat_all_description);
    shuffleOnContentDescription = resources.getString(R.string.exo_controls_shuffle_on_description);
    shuffleOffContentDescription =
        resources.getString(R.string.exo_controls_shuffle_off_description);

    currentPosition = C.TIME_UNSET;
    currentBufferedPosition = C.TIME_UNSET;
  }

  /**
   * Returns the {@link Player} currently being controlled by this view, or null if no player is
   * set.
   */
  @Nullable
  public Player getPlayer() {
    return player;
  }

  /**
   * Sets the {@link Player} to control.
   *
   * @param player The {@link Player} to control, or {@code null} to detach the current player. Only
   *     players which are accessed on the main thread are supported ({@code
   *     player.getApplicationLooper() == Looper.getMainLooper()}).
   */
  public void setPlayer(@Nullable Player player) {
    Assertions.checkState(Looper.myLooper() == Looper.getMainLooper());
    Assertions.checkArgument(
        player == null || player.getApplicationLooper() == Looper.getMainLooper());
    if (this.player == player) {
      return;
    }
    if (this.player != null) {
      this.player.removeListener(componentListener);
    }
    this.player = player;
    if (player != null) {
      player.addListener(componentListener);
    }
    updateAll();
  }

  /**
   * @deprecated Replace multi-window time bar display by merging source windows together instead,
   *     for example using ExoPlayer's {@code ConcatenatingMediaSource2}.
   */
  @Deprecated
  public void setShowMultiWindowTimeBar(boolean showMultiWindowTimeBar) {
    this.showMultiWindowTimeBar = showMultiWindowTimeBar;
    updateTimeline();
  }

  /**
   * Sets whether a play button is shown if playback is {@linkplain
   * Player#getPlaybackSuppressionReason() suppressed}.
   *
   * <p>The default is {@code true}.
   *
   * @param showPlayButtonIfSuppressed Whether to show a play button if playback is {@linkplain
   *     Player#getPlaybackSuppressionReason() suppressed}.
   */
  public void setShowPlayButtonIfPlaybackIsSuppressed(boolean showPlayButtonIfSuppressed) {
    this.showPlayButtonIfSuppressed = showPlayButtonIfSuppressed;
    updatePlayPauseButton();
  }

  /**
   * Sets the millisecond positions of extra ad markers relative to the start of the window (or
   * timeline, if in multi-window mode) and whether each extra ad has been played or not. The
   * markers are shown in addition to any ad markers for ads in the player's timeline.
   *
   * @param extraAdGroupTimesMs The millisecond timestamps of the extra ad markers to show, or
   *     {@code null} to show no extra ad markers.
   * @param extraPlayedAdGroups Whether each ad has been played. Must be the same length as {@code
   *     extraAdGroupTimesMs}, or {@code null} if {@code extraAdGroupTimesMs} is {@code null}.
   */
  public void setExtraAdGroupMarkers(
      @Nullable long[] extraAdGroupTimesMs, @Nullable boolean[] extraPlayedAdGroups) {
    if (extraAdGroupTimesMs == null) {
      this.extraAdGroupTimesMs = new long[0];
      this.extraPlayedAdGroups = new boolean[0];
    } else {
      extraPlayedAdGroups = Assertions.checkNotNull(extraPlayedAdGroups);
      Assertions.checkArgument(extraAdGroupTimesMs.length == extraPlayedAdGroups.length);
      this.extraAdGroupTimesMs = extraAdGroupTimesMs;
      this.extraPlayedAdGroups = extraPlayedAdGroups;
    }
    updateTimeline();
  }

  /**
   * Adds a {@link VisibilityListener}.
   *
   * @param listener The listener to be notified about visibility changes.
   */
  public void addVisibilityListener(VisibilityListener listener) {
    Assertions.checkNotNull(listener);
    visibilityListeners.add(listener);
  }

  /**
   * Removes a {@link VisibilityListener}.
   *
   * @param listener The listener to be removed.
   */
  public void removeVisibilityListener(VisibilityListener listener) {
    visibilityListeners.remove(listener);
  }

  /**
   * Sets the {@link ProgressUpdateListener}.
   *
   * @param listener The listener to be notified about when progress is updated.
   */
  public void setProgressUpdateListener(@Nullable ProgressUpdateListener listener) {
    this.progressUpdateListener = listener;
  }

  /**
   * Sets whether the rewind button is shown.
   *
   * @param showRewindButton Whether the rewind button is shown.
   */
  public void setShowRewindButton(boolean showRewindButton) {
    this.showRewindButton = showRewindButton;
    updateNavigation();
  }

  /**
   * Sets whether the fast forward button is shown.
   *
   * @param showFastForwardButton Whether the fast forward button is shown.
   */
  public void setShowFastForwardButton(boolean showFastForwardButton) {
    this.showFastForwardButton = showFastForwardButton;
    updateNavigation();
  }

  /**
   * Sets whether the previous button is shown.
   *
   * @param showPreviousButton Whether the previous button is shown.
   */
  public void setShowPreviousButton(boolean showPreviousButton) {
    this.showPreviousButton = showPreviousButton;
    updateNavigation();
  }

  /**
   * Sets whether the next button is shown.
   *
   * @param showNextButton Whether the next button is shown.
   */
  public void setShowNextButton(boolean showNextButton) {
    this.showNextButton = showNextButton;
    updateNavigation();
  }

  /**
   * Returns the playback controls timeout. The playback controls are automatically hidden after
   * this duration of time has elapsed without user input.
   *
   * @return The duration in milliseconds. A non-positive value indicates that the controls will
   *     remain visible indefinitely.
   */
  public int getShowTimeoutMs() {
    return showTimeoutMs;
  }

  /**
   * Sets the playback controls timeout. The playback controls are automatically hidden after this
   * duration of time has elapsed without user input.
   *
   * @param showTimeoutMs The duration in milliseconds. A non-positive value will cause the controls
   *     to remain visible indefinitely.
   */
  public void setShowTimeoutMs(int showTimeoutMs) {
    this.showTimeoutMs = showTimeoutMs;
    if (isVisible()) {
      // Reset the timeout.
      hideAfterTimeout();
    }
  }

  /**
   * Returns which repeat toggle modes are enabled.
   *
   * @return The currently enabled {@link RepeatModeUtil.RepeatToggleModes}.
   */
  public @RepeatModeUtil.RepeatToggleModes int getRepeatToggleModes() {
    return repeatToggleModes;
  }

  /**
   * Sets which repeat toggle modes are enabled.
   *
   * @param repeatToggleModes A set of {@link RepeatModeUtil.RepeatToggleModes}.
   */
  public void setRepeatToggleModes(@RepeatModeUtil.RepeatToggleModes int repeatToggleModes) {
    this.repeatToggleModes = repeatToggleModes;
    if (player != null) {
      @Player.RepeatMode int currentMode = player.getRepeatMode();
      if (repeatToggleModes == RepeatModeUtil.REPEAT_TOGGLE_MODE_NONE
          && currentMode != Player.REPEAT_MODE_OFF) {
        player.setRepeatMode(Player.REPEAT_MODE_OFF);
      } else if (repeatToggleModes == RepeatModeUtil.REPEAT_TOGGLE_MODE_ONE
          && currentMode == Player.REPEAT_MODE_ALL) {
        player.setRepeatMode(Player.REPEAT_MODE_ONE);
      } else if (repeatToggleModes == RepeatModeUtil.REPEAT_TOGGLE_MODE_ALL
          && currentMode == Player.REPEAT_MODE_ONE) {
        player.setRepeatMode(Player.REPEAT_MODE_ALL);
      }
    }
    updateRepeatModeButton();
  }

  /** Returns whether the shuffle button is shown. */
  public boolean getShowShuffleButton() {
    return showShuffleButton;
  }

  /**
   * Sets whether the shuffle button is shown.
   *
   * @param showShuffleButton Whether the shuffle button is shown.
   */
  public void setShowShuffleButton(boolean showShuffleButton) {
    this.showShuffleButton = showShuffleButton;
    updateShuffleButton();
  }

  /** Returns whether the VR button is shown. */
  public boolean getShowVrButton() {
    return vrButton != null && vrButton.getVisibility() == VISIBLE;
  }

  /**
   * Sets whether the VR button is shown.
   *
   * @param showVrButton Whether the VR button is shown.
   */
  public void setShowVrButton(boolean showVrButton) {
    if (vrButton != null) {
      vrButton.setVisibility(showVrButton ? VISIBLE : GONE);
    }
  }

  /**
   * Sets listener for the VR button.
   *
   * @param onClickListener Listener for the VR button, or null to clear the listener.
   */
  public void setVrButtonListener(@Nullable OnClickListener onClickListener) {
    if (vrButton != null) {
      vrButton.setOnClickListener(onClickListener);
      updateButton(getShowVrButton(), onClickListener != null, vrButton);
    }
  }

  /**
   * Sets the minimum interval between time bar position updates.
   *
   * <p>Note that smaller intervals, e.g. 33ms, will result in a smooth movement but will use more
   * CPU resources while the time bar is visible, whereas larger intervals, e.g. 200ms, will result
   * in a step-wise update with less CPU usage.
   *
   * @param minUpdateIntervalMs The minimum interval between time bar position updates, in
   *     milliseconds.
   */
  public void setTimeBarMinUpdateInterval(int minUpdateIntervalMs) {
    // Do not accept values below 16ms (60fps) and larger than the maximum update interval.
    timeBarMinUpdateIntervalMs =
        Util.constrainValue(minUpdateIntervalMs, 16, MAX_UPDATE_INTERVAL_MS);
  }

  /**
   * Shows the playback controls. If {@link #getShowTimeoutMs()} is positive then the controls will
   * be automatically hidden after this duration of time has elapsed without user input.
   */
  public void show() {
    if (!isVisible()) {
      setVisibility(VISIBLE);
      for (VisibilityListener visibilityListener : visibilityListeners) {
        visibilityListener.onVisibilityChange(getVisibility());
      }
      updateAll();
      requestPlayPauseFocus();
      requestPlayPauseAccessibilityFocus();
    }
    // Call hideAfterTimeout even if already visible to reset the timeout.
    hideAfterTimeout();
  }

  /** Hides the controller. */
  public void hide() {
    if (isVisible()) {
      setVisibility(GONE);
      for (VisibilityListener visibilityListener : visibilityListeners) {
        visibilityListener.onVisibilityChange(getVisibility());
      }
      removeCallbacks(updateProgressAction);
      removeCallbacks(hideAction);
      hideAtMs = C.TIME_UNSET;
    }
  }

  /** Returns whether the controller is currently visible. */
  public boolean isVisible() {
    return getVisibility() == VISIBLE;
  }

  private void hideAfterTimeout() {
    removeCallbacks(hideAction);
    if (showTimeoutMs > 0) {
      hideAtMs = SystemClock.uptimeMillis() + showTimeoutMs;
      if (isAttachedToWindow) {
        postDelayed(hideAction, showTimeoutMs);
      }
    } else {
      hideAtMs = C.TIME_UNSET;
    }
  }

  private void updateAll() {
    updatePlayPauseButton();
    updateNavigation();
    updateRepeatModeButton();
    updateShuffleButton();
    updateTimeline();
  }

  private void updatePlayPauseButton() {
    if (!isVisible() || !isAttachedToWindow) {
      return;
    }
    boolean requestPlayPauseFocus = false;
    boolean requestPlayPauseAccessibilityFocus = false;
    boolean shouldShowPlayButton = Util.shouldShowPlayButton(player, showPlayButtonIfSuppressed);
    if (playButton != null) {
      requestPlayPauseFocus |= !shouldShowPlayButton && playButton.isFocused();
      requestPlayPauseAccessibilityFocus |=
          Util.SDK_INT < 21
              ? requestPlayPauseFocus
              : (!shouldShowPlayButton && Api21.isAccessibilityFocused(playButton));
      playButton.setVisibility(shouldShowPlayButton ? VISIBLE : GONE);
    }
    if (pauseButton != null) {
      requestPlayPauseFocus |= shouldShowPlayButton && pauseButton.isFocused();
      requestPlayPauseAccessibilityFocus |=
          Util.SDK_INT < 21
              ? requestPlayPauseFocus
              : (shouldShowPlayButton && Api21.isAccessibilityFocused(pauseButton));
      pauseButton.setVisibility(shouldShowPlayButton ? GONE : VISIBLE);
    }
    if (requestPlayPauseFocus) {
      requestPlayPauseFocus();
    }
    if (requestPlayPauseAccessibilityFocus) {
      requestPlayPauseAccessibilityFocus();
    }
  }

  private void updateNavigation() {
    if (!isVisible() || !isAttachedToWindow) {
      return;
    }

    @Nullable Player player = this.player;
    boolean enableSeeking = false;
    boolean enablePrevious = false;
    boolean enableRewind = false;
    boolean enableFastForward = false;
    boolean enableNext = false;
    if (player != null) {
      enableSeeking = player.isCommandAvailable(COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM);
      enablePrevious = player.isCommandAvailable(COMMAND_SEEK_TO_PREVIOUS);
      enableRewind = player.isCommandAvailable(COMMAND_SEEK_BACK);
      enableFastForward = player.isCommandAvailable(COMMAND_SEEK_FORWARD);
      enableNext = player.isCommandAvailable(COMMAND_SEEK_TO_NEXT);
    }

    updateButton(showPreviousButton, enablePrevious, previousButton);
    updateButton(showRewindButton, enableRewind, rewindButton);
    updateButton(showFastForwardButton, enableFastForward, fastForwardButton);
    updateButton(showNextButton, enableNext, nextButton);
    if (timeBar != null) {
      timeBar.setEnabled(enableSeeking);
    }
  }

  private void updateRepeatModeButton() {
    if (!isVisible() || !isAttachedToWindow || repeatToggleButton == null) {
      return;
    }

    if (repeatToggleModes == RepeatModeUtil.REPEAT_TOGGLE_MODE_NONE) {
      updateButton(/* visible= */ false, /* enabled= */ false, repeatToggleButton);
      return;
    }

    @Nullable Player player = this.player;
    if (player == null) {
      updateButton(/* visible= */ true, /* enabled= */ false, repeatToggleButton);
      repeatToggleButton.setImageDrawable(repeatOffButtonDrawable);
      repeatToggleButton.setContentDescription(repeatOffButtonContentDescription);
      return;
    }

    updateButton(/* visible= */ true, /* enabled= */ true, repeatToggleButton);
    switch (player.getRepeatMode()) {
      case Player.REPEAT_MODE_OFF:
        repeatToggleButton.setImageDrawable(repeatOffButtonDrawable);
        repeatToggleButton.setContentDescription(repeatOffButtonContentDescription);
        break;
      case Player.REPEAT_MODE_ONE:
        repeatToggleButton.setImageDrawable(repeatOneButtonDrawable);
        repeatToggleButton.setContentDescription(repeatOneButtonContentDescription);
        break;
      case Player.REPEAT_MODE_ALL:
        repeatToggleButton.setImageDrawable(repeatAllButtonDrawable);
        repeatToggleButton.setContentDescription(repeatAllButtonContentDescription);
        break;
      default:
        // Never happens.
    }
    repeatToggleButton.setVisibility(VISIBLE);
  }

  private void updateShuffleButton() {
    if (!isVisible() || !isAttachedToWindow || shuffleButton == null) {
      return;
    }

    @Nullable Player player = this.player;
    if (!showShuffleButton) {
      updateButton(/* visible= */ false, /* enabled= */ false, shuffleButton);
    } else if (player == null) {
      updateButton(/* visible= */ true, /* enabled= */ false, shuffleButton);
      shuffleButton.setImageDrawable(shuffleOffButtonDrawable);
      shuffleButton.setContentDescription(shuffleOffContentDescription);
    } else {
      updateButton(/* visible= */ true, /* enabled= */ true, shuffleButton);
      shuffleButton.setImageDrawable(
          player.getShuffleModeEnabled() ? shuffleOnButtonDrawable : shuffleOffButtonDrawable);
      shuffleButton.setContentDescription(
          player.getShuffleModeEnabled()
              ? shuffleOnContentDescription
              : shuffleOffContentDescription);
    }
  }

  private void updateTimeline() {
    @Nullable Player player = this.player;
    if (player == null) {
      return;
    }
    multiWindowTimeBar =
        showMultiWindowTimeBar && canShowMultiWindowTimeBar(player.getCurrentTimeline(), window);
    currentWindowOffset = 0;
    long durationUs = 0;
    int adGroupCount = 0;
    Timeline timeline = player.getCurrentTimeline();
    if (!timeline.isEmpty()) {
      int currentWindowIndex = player.getCurrentMediaItemIndex();
      int firstWindowIndex = multiWindowTimeBar ? 0 : currentWindowIndex;
      int lastWindowIndex = multiWindowTimeBar ? timeline.getWindowCount() - 1 : currentWindowIndex;
      for (int i = firstWindowIndex; i <= lastWindowIndex; i++) {
        if (i == currentWindowIndex) {
          currentWindowOffset = Util.usToMs(durationUs);
        }
        timeline.getWindow(i, window);
        if (window.durationUs == C.TIME_UNSET) {
          Assertions.checkState(!multiWindowTimeBar);
          break;
        }
        for (int j = window.firstPeriodIndex; j <= window.lastPeriodIndex; j++) {
          timeline.getPeriod(j, period);
          int removedGroups = period.getRemovedAdGroupCount();
          int totalGroups = period.getAdGroupCount();
          for (int adGroupIndex = removedGroups; adGroupIndex < totalGroups; adGroupIndex++) {
            long adGroupTimeInPeriodUs = period.getAdGroupTimeUs(adGroupIndex);
            if (adGroupTimeInPeriodUs == C.TIME_END_OF_SOURCE) {
              if (period.durationUs == C.TIME_UNSET) {
                // Don't show ad markers for postrolls in periods with unknown duration.
                continue;
              }
              adGroupTimeInPeriodUs = period.durationUs;
            }
            long adGroupTimeInWindowUs = adGroupTimeInPeriodUs + period.getPositionInWindowUs();
            if (adGroupTimeInWindowUs >= 0) {
              if (adGroupCount == adGroupTimesMs.length) {
                int newLength = adGroupTimesMs.length == 0 ? 1 : adGroupTimesMs.length * 2;
                adGroupTimesMs = Arrays.copyOf(adGroupTimesMs, newLength);
                playedAdGroups = Arrays.copyOf(playedAdGroups, newLength);
              }
              adGroupTimesMs[adGroupCount] = Util.usToMs(durationUs + adGroupTimeInWindowUs);
              playedAdGroups[adGroupCount] = period.hasPlayedAdGroup(adGroupIndex);
              adGroupCount++;
            }
          }
        }
        durationUs += window.durationUs;
      }
    }
    long durationMs = Util.usToMs(durationUs);
    if (durationView != null) {
      durationView.setText(Util.getStringForTime(formatBuilder, formatter, durationMs));
    }
    if (timeBar != null) {
      timeBar.setDuration(durationMs);
      int extraAdGroupCount = extraAdGroupTimesMs.length;
      int totalAdGroupCount = adGroupCount + extraAdGroupCount;
      if (totalAdGroupCount > adGroupTimesMs.length) {
        adGroupTimesMs = Arrays.copyOf(adGroupTimesMs, totalAdGroupCount);
        playedAdGroups = Arrays.copyOf(playedAdGroups, totalAdGroupCount);
      }
      System.arraycopy(extraAdGroupTimesMs, 0, adGroupTimesMs, adGroupCount, extraAdGroupCount);
      System.arraycopy(extraPlayedAdGroups, 0, playedAdGroups, adGroupCount, extraAdGroupCount);
      timeBar.setAdGroupTimesMs(adGroupTimesMs, playedAdGroups, totalAdGroupCount);
    }
    updateProgress();
  }

  private void updateProgress() {
    if (!isVisible() || !isAttachedToWindow) {
      return;
    }

    @Nullable Player player = this.player;
    long position = 0;
    long bufferedPosition = 0;
    if (player != null) {
      position = currentWindowOffset + player.getContentPosition();
      bufferedPosition = currentWindowOffset + player.getContentBufferedPosition();
    }
    boolean positionChanged = position != currentPosition;
    boolean bufferedPositionChanged = bufferedPosition != currentBufferedPosition;
    currentPosition = position;
    currentBufferedPosition = bufferedPosition;

    // Only update the TextView if the position has changed, else TalkBack will repeatedly read the
    // same position to the user.
    if (positionView != null && !scrubbing && positionChanged) {
      positionView.setText(Util.getStringForTime(formatBuilder, formatter, position));
    }
    if (timeBar != null) {
      timeBar.setPosition(position);
      timeBar.setBufferedPosition(bufferedPosition);
    }
    if (progressUpdateListener != null && (positionChanged || bufferedPositionChanged)) {
      progressUpdateListener.onProgressUpdate(position, bufferedPosition);
    }

    // Cancel any pending updates and schedule a new one if necessary.
    removeCallbacks(updateProgressAction);
    int playbackState = player == null ? Player.STATE_IDLE : player.getPlaybackState();
    if (player != null && player.isPlaying()) {
      long mediaTimeDelayMs =
          timeBar != null ? timeBar.getPreferredUpdateDelay() : MAX_UPDATE_INTERVAL_MS;

      // Limit delay to the start of the next full second to ensure position display is smooth.
      long mediaTimeUntilNextFullSecondMs = 1000 - position % 1000;
      mediaTimeDelayMs = Math.min(mediaTimeDelayMs, mediaTimeUntilNextFullSecondMs);

      // Calculate the delay until the next update in real time, taking playback speed into account.
      float playbackSpeed = player.getPlaybackParameters().speed;
      long delayMs =
          playbackSpeed > 0 ? (long) (mediaTimeDelayMs / playbackSpeed) : MAX_UPDATE_INTERVAL_MS;

      // Constrain the delay to avoid too frequent / infrequent updates.
      delayMs = Util.constrainValue(delayMs, timeBarMinUpdateIntervalMs, MAX_UPDATE_INTERVAL_MS);
      postDelayed(updateProgressAction, delayMs);
    } else if (playbackState != Player.STATE_ENDED && playbackState != Player.STATE_IDLE) {
      postDelayed(updateProgressAction, MAX_UPDATE_INTERVAL_MS);
    }
  }

  private void requestPlayPauseFocus() {
    boolean shouldShowPlayButton = Util.shouldShowPlayButton(player, showPlayButtonIfSuppressed);
    if (shouldShowPlayButton && playButton != null) {
      playButton.requestFocus();
    } else if (!shouldShowPlayButton && pauseButton != null) {
      pauseButton.requestFocus();
    }
  }

  private void requestPlayPauseAccessibilityFocus() {
    boolean shouldShowPlayButton = Util.shouldShowPlayButton(player, showPlayButtonIfSuppressed);
    if (shouldShowPlayButton && playButton != null) {
      playButton.sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_FOCUSED);
    } else if (!shouldShowPlayButton && pauseButton != null) {
      pauseButton.sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_FOCUSED);
    }
  }

  private void updateButton(boolean visible, boolean enabled, @Nullable View view) {
    if (view == null) {
      return;
    }
    view.setEnabled(enabled);
    view.setAlpha(enabled ? buttonAlphaEnabled : buttonAlphaDisabled);
    view.setVisibility(visible ? VISIBLE : GONE);
  }

  private void seekToTimeBarPosition(Player player, long positionMs) {
    int windowIndex;
    Timeline timeline = player.getCurrentTimeline();
    if (multiWindowTimeBar && !timeline.isEmpty()) {
      int windowCount = timeline.getWindowCount();
      windowIndex = 0;
      while (true) {
        long windowDurationMs = timeline.getWindow(windowIndex, window).getDurationMs();
        if (positionMs < windowDurationMs) {
          break;
        } else if (windowIndex == windowCount - 1) {
          // Seeking past the end of the last window should seek to the end of the timeline.
          positionMs = windowDurationMs;
          break;
        }
        positionMs -= windowDurationMs;
        windowIndex++;
      }
    } else {
      windowIndex = player.getCurrentMediaItemIndex();
    }
    seekTo(player, windowIndex, positionMs);
    updateProgress();
  }

  private void seekTo(Player player, int windowIndex, long positionMs) {
    player.seekTo(windowIndex, positionMs);
  }

  @Override
  public void onAttachedToWindow() {
    super.onAttachedToWindow();
    isAttachedToWindow = true;
    if (hideAtMs != C.TIME_UNSET) {
      long delayMs = hideAtMs - SystemClock.uptimeMillis();
      if (delayMs <= 0) {
        hide();
      } else {
        postDelayed(hideAction, delayMs);
      }
    } else if (isVisible()) {
      hideAfterTimeout();
    }
    updateAll();
  }

  @Override
  public void onDetachedFromWindow() {
    super.onDetachedFromWindow();
    isAttachedToWindow = false;
    removeCallbacks(updateProgressAction);
    removeCallbacks(hideAction);
  }

  @Override
  public final boolean dispatchTouchEvent(MotionEvent ev) {
    if (ev.getAction() == MotionEvent.ACTION_DOWN) {
      removeCallbacks(hideAction);
    } else if (ev.getAction() == MotionEvent.ACTION_UP) {
      hideAfterTimeout();
    }
    return super.dispatchTouchEvent(ev);
  }

  @Override
  public boolean dispatchKeyEvent(KeyEvent event) {
    return dispatchMediaKeyEvent(event) || super.dispatchKeyEvent(event);
  }

  /**
   * Called to process media key events. Any {@link KeyEvent} can be passed but only media key
   * events will be handled.
   *
   * @param event A key event.
   * @return Whether the key event was handled.
   */
  public boolean dispatchMediaKeyEvent(KeyEvent event) {
    int keyCode = event.getKeyCode();
    @Nullable Player player = this.player;
    if (player == null || !isHandledMediaKey(keyCode)) {
      return false;
    }
    if (event.getAction() == KeyEvent.ACTION_DOWN) {
      if (keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD) {
        if (player.getPlaybackState() != Player.STATE_ENDED) {
          player.seekForward();
        }
      } else if (keyCode == KeyEvent.KEYCODE_MEDIA_REWIND) {
        player.seekBack();
      } else if (event.getRepeatCount() == 0) {
        switch (keyCode) {
          case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
          case KeyEvent.KEYCODE_HEADSETHOOK:
            Util.handlePlayPauseButtonAction(player, showPlayButtonIfSuppressed);
            break;
          case KeyEvent.KEYCODE_MEDIA_PLAY:
            Util.handlePlayButtonAction(player);
            break;
          case KeyEvent.KEYCODE_MEDIA_PAUSE:
            Util.handlePauseButtonAction(player);
            break;
          case KeyEvent.KEYCODE_MEDIA_NEXT:
            player.seekToNext();
            break;
          case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
            player.seekToPrevious();
            break;
          default:
            break;
        }
      }
    }
    return true;
  }

  @SuppressLint("InlinedApi")
  private static boolean isHandledMediaKey(int keyCode) {
    return keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
        || keyCode == KeyEvent.KEYCODE_MEDIA_REWIND
        || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        || keyCode == KeyEvent.KEYCODE_HEADSETHOOK
        || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY
        || keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE
        || keyCode == KeyEvent.KEYCODE_MEDIA_NEXT
        || keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS;
  }

  /**
   * Returns whether the specified {@code timeline} can be shown on a multi-window time bar.
   *
   * @param timeline The {@link Timeline} to check.
   * @param window A scratch {@link Timeline.Window} instance.
   * @return Whether the specified timeline can be shown on a multi-window time bar.
   */
  private static boolean canShowMultiWindowTimeBar(Timeline timeline, Timeline.Window window) {
    if (timeline.getWindowCount() > MAX_WINDOWS_FOR_MULTI_WINDOW_TIME_BAR) {
      return false;
    }
    int windowCount = timeline.getWindowCount();
    for (int i = 0; i < windowCount; i++) {
      if (timeline.getWindow(i, window).durationUs == C.TIME_UNSET) {
        return false;
      }
    }
    return true;
  }

  @SuppressWarnings("ResourceType")
  private static @RepeatModeUtil.RepeatToggleModes int getRepeatToggleModes(
      TypedArray a, @RepeatModeUtil.RepeatToggleModes int defaultValue) {
    return a.getInt(R.styleable.LegacyPlayerControlView_repeat_toggle_modes, defaultValue);
  }

  private final class ComponentListener
      implements Player.Listener, TimeBar.OnScrubListener, OnClickListener {

    @Override
    public void onEvents(Player player, Events events) {
      if (events.containsAny(EVENT_PLAYBACK_STATE_CHANGED, EVENT_PLAY_WHEN_READY_CHANGED)) {
        updatePlayPauseButton();
      }
      if (events.containsAny(
          EVENT_PLAYBACK_STATE_CHANGED, EVENT_PLAY_WHEN_READY_CHANGED, EVENT_IS_PLAYING_CHANGED)) {
        updateProgress();
      }
      if (events.contains(EVENT_REPEAT_MODE_CHANGED)) {
        updateRepeatModeButton();
      }
      if (events.contains(EVENT_SHUFFLE_MODE_ENABLED_CHANGED)) {
        updateShuffleButton();
      }
      if (events.containsAny(
          EVENT_REPEAT_MODE_CHANGED,
          EVENT_SHUFFLE_MODE_ENABLED_CHANGED,
          EVENT_POSITION_DISCONTINUITY,
          EVENT_TIMELINE_CHANGED,
          EVENT_AVAILABLE_COMMANDS_CHANGED)) {
        updateNavigation();
      }
      if (events.containsAny(EVENT_POSITION_DISCONTINUITY, EVENT_TIMELINE_CHANGED)) {
        updateTimeline();
      }
    }

    @Override
    public void onScrubStart(TimeBar timeBar, long position) {
      scrubbing = true;
      if (positionView != null) {
        positionView.setText(Util.getStringForTime(formatBuilder, formatter, position));
      }
    }

    @Override
    public void onScrubMove(TimeBar timeBar, long position) {
      if (positionView != null) {
        positionView.setText(Util.getStringForTime(formatBuilder, formatter, position));
      }
    }

    @Override
    public void onScrubStop(TimeBar timeBar, long position, boolean canceled) {
      scrubbing = false;
      if (!canceled && player != null) {
        seekToTimeBarPosition(player, position);
      }
    }

    @Override
    public void onClick(View view) {
      Player player = LegacyPlayerControlView.this.player;
      if (player == null) {
        return;
      }
      if (nextButton == view) {
        player.seekToNext();
      } else if (previousButton == view) {
        player.seekToPrevious();
      } else if (fastForwardButton == view) {
        if (player.getPlaybackState() != Player.STATE_ENDED) {
          player.seekForward();
        }
      } else if (rewindButton == view) {
        player.seekBack();
      } else if (playButton == view) {
        Util.handlePlayButtonAction(player);
      } else if (pauseButton == view) {
        Util.handlePauseButtonAction(player);
      } else if (repeatToggleButton == view) {
        player.setRepeatMode(
            RepeatModeUtil.getNextRepeatMode(player.getRepeatMode(), repeatToggleModes));
      } else if (shuffleButton == view) {
        player.setShuffleModeEnabled(!player.getShuffleModeEnabled());
      }
    }
  }

  @RequiresApi(21)
  private static final class Api21 {
    @DoNotInline
    public static boolean isAccessibilityFocused(View view) {
      return view.isAccessibilityFocused();
    }
  }
}
