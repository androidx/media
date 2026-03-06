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
package androidx.media3.docsamples.cast;

import android.content.Context;
import androidx.annotation.Nullable;
import com.google.android.gms.cast.framework.CastOptions;
import com.google.android.gms.cast.framework.OptionsProvider;
import com.google.android.gms.cast.framework.SessionProvider;
import java.util.List;

/** Snippets for customize-castoptions.md. */
@SuppressWarnings("unused")
public class CustomizeCastOptions {

  private CustomizeCastOptions() {}

  /** Example class */
  // [START custom_options]
  public static final class MyCustomCastOptionsProvider implements OptionsProvider {

    // Add your receiver app ID in <APP_ID>.
    public static final String APP_ID = "<APP_ID>";

    @Override
    public CastOptions getCastOptions(Context context) {
      return new CastOptions.Builder()
          .setReceiverApplicationId(APP_ID)
          .setRemoteToLocalEnabled(true)
          .build();
    }

    @Override
    @Nullable
    public List<SessionProvider> getAdditionalSessionProviders(Context context) {
      return null;
    }
  }
  // [END custom_options]
}
