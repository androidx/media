/*
 * Copyright 2026 The Android Open Source Project
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
package androidx.media3.decoder.mpegh;

import static com.google.common.base.Preconditions.checkNotNull;

import androidx.annotation.Nullable;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.audio.AudioRendererEventListener;
import com.google.common.collect.ImmutableList;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Helper class for MPEG-H UI handling.
 *
 * <p>It differentiates between MPEG-H UI system settings and MPEG-H UI commands and caches them
 * locally. Furthermore, it provides an interface for the parameter communication between the Player
 * and the {@link MpeghDecoder} (i.e. for MPEG-H UI Manager).
 */
@UnstableApi
public final class MpeghUiCommandHelper {

  private static final int ACTION_TYPE_DRC_SELECTED = 10;
  private static final int ACTION_TYPE_DRC_BOOST = 11;
  private static final int ACTION_TYPE_DRC_COMPRESS = 12;
  private static final int ACTION_TYPE_TARGET_LOUDNESS = 20;
  private static final int ACTION_TYPE_ALBUM_MODE = 21;
  private static final int ACTION_TYPE_ACCESSIBILITY_PREFERENCE = 31;
  private static final int ACTION_TYPE_AUDIO_LANGUAGE_SELECTED = 70;
  private static final int ACTION_TYPE_INTERFACE_LANGUAGE_SELECTED = 71;

  private static final List<Integer> SYSTEM_ACTION_TYPES =
      ImmutableList.of(
          ACTION_TYPE_DRC_SELECTED,
          ACTION_TYPE_DRC_BOOST,
          ACTION_TYPE_DRC_COMPRESS,
          ACTION_TYPE_TARGET_LOUDNESS,
          ACTION_TYPE_ALBUM_MODE,
          ACTION_TYPE_ACCESSIBILITY_PREFERENCE,
          ACTION_TYPE_AUDIO_LANGUAGE_SELECTED,
          ACTION_TYPE_INTERFACE_LANGUAGE_SELECTED);

  private static final Pattern ACTION_TYPE_PATTERN =
      Pattern.compile("actionType\\s*=\\s*[\"'](\\d+)[\"']");

  private final Object lock;
  private final List<String> commands;
  private final Map<Integer, String> systemCommands;

  @Nullable private Set<String> subscribedCodecParameterKeys;
  @Nullable private AudioRendererEventListener.EventDispatcher eventDispatcher;
  @Nullable private ByteBuffer persistenceStorage;
  private boolean forceUiUpdate;

  /** Creates a new instance. */
  public MpeghUiCommandHelper() {
    persistenceStorage = null;
    forceUiUpdate = false;
    lock = new Object();
    commands = new ArrayList<>();
    systemCommands = new HashMap<>();
  }

  /**
   * Adds an MPEG-H UI command to the list of commands to be applied in the MPEG-H UI manager.
   *
   * <p>MPEG-H UI system settings will also be added to a separate map to be able to apply them if
   * the decoder instance gets reset.
   *
   * @param command The MPEG-H UI command to be stored.
   */
  public void addCommand(String command) {
    synchronized (lock) {
      Matcher matcher = ACTION_TYPE_PATTERN.matcher(command);
      if (matcher.find()) {
        try {
          int actionType = Integer.parseInt(checkNotNull(matcher.group(1)));
          if (SYSTEM_ACTION_TYPES.contains(actionType)) {
            systemCommands.put(actionType, command);
            // System commands are also added to the immediate command list if valid.
            if (!commands.contains(command)) {
              commands.add(command);
            }
            return;
          }
        } catch (NumberFormatException e) {
          // Ignore malformed action types and treat as a regular command.
        }
      }
      commands.add(command);
    }
  }

  /**
   * Returns a list of MPEG-H UI commands to be applied in the MPEG-H UI manager.
   *
   * <p>The obtained MPEG-H UI commands will also be removed from the stored list of commands. Only
   * MPEG-H UI system settings will be kept.
   *
   * @param init Whether to include MPEG-H UI system settings in the returned list.
   * @return The list of MPEG-H UI commands to be applied.
   */
  public List<String> getCommands(boolean init) {
    List<String> result = new ArrayList<>();
    synchronized (lock) {
      if (init) {
        result.addAll(systemCommands.values());
      }
      // Remove duplicate entries that are already in the system commands list.
      Iterator<String> iterator = commands.iterator();
      while (iterator.hasNext()) {
        if (result.contains(iterator.next())) {
          iterator.remove();
        }
      }

      if (!init) {
        result.addAll(commands);
        commands.clear();
      }
    }
    return result;
  }

  /**
   * Sets the subscribed codec parameter keys.
   *
   * @param keys The {@link Set} of subscribed codec parameter keys.
   */
  public void setSubscribedCodecParameterKeys(@Nullable Set<String> keys) {
    synchronized (lock) {
      subscribedCodecParameterKeys = keys;
    }
  }

  /**
   * Returns the subscribed codec parameter keys.
   *
   * @return The {@link Set} of subscribed codec parameter keys, or {@code null} if not set.
   */
  @Nullable
  public Set<String> getSubscribedCodecParameterKeys() {
    synchronized (lock) {
      return subscribedCodecParameterKeys;
    }
  }

  /**
   * Sets the event dispatcher.
   *
   * @param dispatcher The {@link AudioRendererEventListener.EventDispatcher}.
   */
  public void setEventDispatcher(@Nullable AudioRendererEventListener.EventDispatcher dispatcher) {
    synchronized (lock) {
      eventDispatcher = dispatcher;
    }
  }

  /**
   * Returns the event dispatcher.
   *
   * @return The {@link AudioRendererEventListener.EventDispatcher}, or {@code null} if not set.
   */
  @Nullable
  public AudioRendererEventListener.EventDispatcher getEventDispatcher() {
    synchronized (lock) {
      return eventDispatcher;
    }
  }

  /**
   * Sets a {@link ByteBuffer} holding the MPEG-H UI persistence data.
   *
   * @param buffer The {@link ByteBuffer} containing the persistence data.
   */
  public void setPersistenceStorage(@Nullable ByteBuffer buffer) {
    synchronized (lock) {
      if (buffer == null) {
        return;
      }
      ByteBuffer clone = ByteBuffer.allocateDirect(buffer.remaining());
      clone.put(buffer.duplicate());
      clone.flip();
      persistenceStorage = clone;
    }
  }

  /**
   * Returns the {@link ByteBuffer} holding the MPEG-H UI persistence data.
   *
   * @return The {@link ByteBuffer} containing the persistence data, or {@code null} if not set.
   */
  @Nullable
  public ByteBuffer getPersistenceStorage() {
    synchronized (lock) {
      return persistenceStorage;
    }
  }

  /**
   * Sets a flag to signal to the MPEG-H UI manager that an MPEG-H UI update should be forced.
   *
   * @param force Whether to force an MPEG-H UI update.
   */
  public void setForceUiUpdate(boolean force) {
    synchronized (lock) {
      forceUiUpdate = force;
    }
  }

  /**
   * Returns a flag to signal to the MPEG-H UI manager that an MPEG-H UI update should be forced.
   *
   * @return Whether an MPEG-H UI update should be forced.
   */
  public boolean getForceUiUpdate() {
    synchronized (lock) {
      return forceUiUpdate;
    }
  }
}
