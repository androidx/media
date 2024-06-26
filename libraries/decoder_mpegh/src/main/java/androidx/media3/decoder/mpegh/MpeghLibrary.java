package androidx.media3.decoder.mpegh;

import androidx.media3.common.MediaLibraryInfo;
import androidx.media3.common.util.LibraryLoader;
import androidx.media3.common.util.UnstableApi;

/**
 * Configures and queries the underlying native library.
 */
@UnstableApi
public final class MpeghLibrary {

  static {
    MediaLibraryInfo.registerModule("media3.decoder.mpegh");
  }

  private static final LibraryLoader LOADER = new LibraryLoader("mpeghdecJNI") {
    @Override
    protected void loadLibrary(String name) {
      System.loadLibrary(name);
    }
  };

  private MpeghLibrary() {}

  /**
   * Override the names of the MPEG-H native libraries. If an application wishes to call this
   * method, it must do so before calling any other method defined by this class, and before
   * instantiating a {@link MpeghAudioRenderer} instance.
   * @param libraries The names of the MPEG-H native libraries.
   */
  public static void setLibraries(String... libraries) {
    LOADER.setLibraries(libraries);
  }

  /**
   * Returns whether the underlying library is available, loading it if necessary.
   */
  public static boolean isAvailable() {
    return LOADER.isAvailable();
  }
}
