package androidx.media3.extractor;

import static androidx.media3.test.utils.TestUtil.assertForwardingClassForwardsAllMethods;
import static androidx.media3.test.utils.TestUtil.assertForwardingClassOverridesAllMethods;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class ForwardingExtractorOutputTest {

  @Test
  public void overridesAllMethods() throws Exception {
    assertForwardingClassOverridesAllMethods(
        ExtractorOutput.class, ForwardingExtractorOutput.class);
  }

  @Test
  public void forwardsAllMethods() throws Exception {
    assertForwardingClassForwardsAllMethods(ExtractorOutput.class, ForwardingExtractorOutput::new);
  }
}
