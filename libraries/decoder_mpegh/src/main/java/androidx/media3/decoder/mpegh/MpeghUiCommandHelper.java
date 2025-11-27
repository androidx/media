package androidx.media3.decoder.mpegh;

import androidx.annotation.Nullable;
import androidx.media3.exoplayer.audio.AudioRendererEventListener;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Provides a collection of helper functionality for MPEG-H UI handling.
 * It differentiates between MPEG-H UI system settings and MPEG-H UI commands and caches them
 * locally.
 * Furthermore, it provides an interface for the parameter communication between the Player and
 * the MpeghDecoder (i.e. for MPEG-H UI Manager).
 * */
public class MpeghUiCommandHelper {

  private final Object syncObj;

  private final List<String> commands;
  private final Map<Integer, String> systemCommands;

  @Nullable private Set<String> subscribedCodecParameterKeys;

  @Nullable private AudioRendererEventListener.EventDispatcher eventDispatcher;
  @Nullable private ByteBuffer persistenceStorage;
  private boolean forceUiUpdate;

  private final List<Integer> systemActionTypes = new ArrayList<Integer>() {
    {
      add(10); // DRC_SELECTED
      add(11); // DRC_BOOST
      add(12); // DRC_COMPRESS
      add(20); // TARGET_LOUDNESS
      add(21); // ALBUM_MODE
      add(31); // ACCESSIBILITY_PREFERENCE
      add(70); // AUDIO_LANGUAGE_SELECTED
      add(71); // INTERFACE_LANGUAGE_SELECTED
    }
  };

  /**
   * Creates an MPEG-H UI command helper.
   */
  public MpeghUiCommandHelper() {
    persistenceStorage = null;
    forceUiUpdate = false;
    syncObj = new Object();
    commands = new ArrayList<>();
    systemCommands = new HashMap<>();
  }

  /**
   * Adds an MPEG-H UI command to the list of commands to be applied in the MPEG-H UI manager.
   * MPEG-H UI system settings will also be added to a separate hashmap to be able to apply them
   * if the decoder instance gets reset.
   *
   * @param command The MPEG-H UI command to be stored.
   */
  public void addCommand(String command) {
    synchronized (syncObj) {

      // keep MPEG-H system settings commands
      for (int type : systemActionTypes) {
        boolean addToCommands = false;
        if (command.contains("actionType=\"" + type + "\"")) {
          if (systemCommands.containsKey(type)) {
            addToCommands = true;
          }
          systemCommands.put(type, command);
          if (addToCommands) {
            commands.add(command);
          }
          return;
        }
      }

      commands.add(command);
    }
  }

  /**
   * Obtains a list of MPEG-H UI commands to be applied in the MPEG-H UI manager.
   * The obtained MPEG-H UI commands will also be removed from the stored list of commands.
   * Only MPEG-H UI system settings will be kept.
   *
   * @param init Boolean value to signal that also MPEG-H UI system settings need to be obtained.
   * @return List<String> List of MPEG-H UI commands to be applied in the MPEG-H UI manager.
   */
  public List<String> getCommands(boolean init) {
    List<String> tmpList = new ArrayList<>();
    synchronized (syncObj) {
      if (init) {
        tmpList.addAll(systemCommands.values());
      }
      // remove duplicate entries
      commands.removeIf(tmpList::contains);

      tmpList.addAll(commands);
      commands.clear();
    }
    return tmpList;
  }

  /**
   * Sets the subscribed codec parameter keys
   *
   * @param keys Set<String>
   */
  public void setSubscribedCodecParameterKeys(@Nullable Set<String> keys) {
    subscribedCodecParameterKeys = keys;
  }

  /**
   * Gets the subscribed codec parameter keys
   *
   * @return Set<String>
   */
  public @Nullable Set<String> getSubscribedCodecParameterKeys() {
    return subscribedCodecParameterKeys;
  }

  /**
   * Sets the EventDispatcher
   *
   * @param dispatcher AudioRendererEventListener.EventDispatcher
   */
  public void setEventDispatcher(@Nullable AudioRendererEventListener.EventDispatcher dispatcher) {
    eventDispatcher = dispatcher;
  }

  /**
   * Gets the EventDispatcher
   *
   * @return AudioRendererEventListener.EventDispatcher
   */
  public @Nullable AudioRendererEventListener.EventDispatcher getEventDispatcher() {
    return eventDispatcher;
  }

  /**
   * Sets a ByteBuffer holding the MPEG-H UI persistence data.
   *
   * @param buffer ByteBuffer containing the MPEG-H UI persistence data
   */
  public void setPersistenceStorage(@Nullable ByteBuffer buffer) {
    if (buffer == null) {
      return;
    }
    ByteBuffer clone = ByteBuffer.allocateDirect(buffer.remaining());
    clone.put(buffer.duplicate());
    clone.flip();
    persistenceStorage = clone;
  }

  /**
   * Gets a ByteBuffer holding the MPEG-H UI persistence data.
   *
   * @return ByteBuffer containing the MPEG-H UI persistence data
   */
  public @Nullable ByteBuffer getPersistenceStorage() {
    return persistenceStorage;
  }

  /**
   * Sets a flag to signal to the MPEG-H UI manager that an MPEG-H UI update should be forced.
   *
   * @param force flag to signal to the MPEG-H UI manager that an MPEG-H UI update should be forced
   */
  public void setForceUiUpdate(boolean force) {
    synchronized (syncObj) {
      forceUiUpdate = force;
    }
  }

  /**
   * Gets a flag to signal to the MPEG-H UI manager that an MPEG-H UI update should be forced.
   *
   * @return Boolean flag to signal to the MPEG-H UI manager that an MPEG-H UI update should be forced
   */
  public boolean getForceUiUpdate() {
    boolean force;
    synchronized (syncObj) {
      force = forceUiUpdate;
    }
    return force;
  }
}
