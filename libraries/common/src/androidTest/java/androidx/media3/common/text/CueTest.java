package androidx.media3.common.text;

import static com.google.common.truth.Truth.assertThat;
import android.graphics.Bitmap;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Tests for {@link Cue}. */
@RunWith(AndroidJUnit4.class)
public class CueTest {

   @Test
   public void roundTripViaSerializableBundle_withBitmapAlpha8_yieldsEqualInstance() {
      Bitmap bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ALPHA_8);
      byte[] bytes = new byte[bitmap.getWidth() * bitmap.getHeight()];
      for (byte i = 0; i < bytes.length; i++) {
         bytes[i] = i;
      }
      Buffer buffer = ByteBuffer.wrap(bytes);
      bitmap.copyPixelsFromBuffer(buffer);
      Cue cue =
          new Cue.Builder().setBitmap(bitmap).build();
      Cue modifiedCue = Cue.fromBundle(cue.toSerializableBundle());

      assertThat(modifiedCue).isEqualTo(cue);
   }

}
