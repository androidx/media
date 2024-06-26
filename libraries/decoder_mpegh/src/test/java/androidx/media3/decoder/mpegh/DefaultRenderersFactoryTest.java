package androidx.media3.decoder.mpegh;

import androidx.media3.common.C;
import androidx.media3.test.utils.DefaultRenderersFactoryAsserts;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit test for {@link DefaultRenderersFactoryTest} with {@link MpeghAudioRenderer}. */
@RunWith(AndroidJUnit4.class)
public final class DefaultRenderersFactoryTest {

  @Test
  public void createRenderers_instantiatesMpeghAudioRenderer() {
    DefaultRenderersFactoryAsserts.assertExtensionRendererCreated(
        MpeghAudioRenderer.class, C.TRACK_TYPE_AUDIO);
  }
}
