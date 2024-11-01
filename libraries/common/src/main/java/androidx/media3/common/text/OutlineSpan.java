package androidx.media3.common.text;

import android.os.Bundle;
import android.text.TextPaint;
import android.text.style.CharacterStyle;
import androidx.annotation.Px;
import androidx.media3.common.util.Util;

/**
 * A styling span for outline width on subtitle text.
 */
public class OutlineSpan extends CharacterStyle {

  /**
   * The outline size in pixels.
   */
  @Px
  public final float outlineWidth;
  private static final String FIELD_OUTLINE_WIDTH = Util.intToStringMaxRadix(0);

  public OutlineSpan(@Px float outlineWidth) {
    this.outlineWidth = outlineWidth;
  }

  public Bundle toBundle() {
    Bundle bundle = new Bundle();
    bundle.putFloat(FIELD_OUTLINE_WIDTH, outlineWidth);
    return bundle;
  }

  public static OutlineSpan fromBundle(Bundle bundle) {
    return new OutlineSpan(
        /* outlineWidth= */ bundle.getFloat(FIELD_OUTLINE_WIDTH)
    );
  }

  @Override
  public void updateDrawState(TextPaint tp) {
    tp.setStrokeWidth(outlineWidth);
  }
}
