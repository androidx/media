package androidx.media3.exoplayer.audio;

import static com.google.common.truth.Truth.assertThat;

import androidx.media3.exoplayer.ForwardingRenderer;
import androidx.media3.exoplayer.Renderer;
import androidx.media3.test.utils.TestUtil;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.Test;

public class ForwardingRendererTest {
  @Test
  public void forwardingRenderer_overridesAllMethods() throws NoSuchMethodException {
    // Check with reflection that ForwardingRenderer overrides all Renderer methods.
    List<Method> methods = TestUtil.getPublicMethods(Renderer.class);
    for (Method method : methods) {
      if (!method.isDefault()) {
        assertThat(
            ForwardingRenderer.class
                .getDeclaredMethod(method.getName(), method.getParameterTypes())
                .getDeclaringClass())
            .isEqualTo(ForwardingRenderer.class);
      }
    }
  }
}
