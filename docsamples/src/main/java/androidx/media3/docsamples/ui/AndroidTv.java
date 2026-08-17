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
package androidx.media3.docsamples.ui;

import android.app.Activity;
import android.os.Bundle;
import android.view.KeyEvent;
import androidx.annotation.Nullable;
import androidx.media3.ui.PlayerView;

/** Snippets for androidtv.md. */
@SuppressWarnings({"unused", "InstantiationOfAbstractClass"})
public final class AndroidTv {

  private abstract static class DispatchKeyEventExample extends Activity {
    private final PlayerView playerView = null;

    // [START dispatch_key_event]
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
      return playerView.dispatchKeyEvent(event) || super.dispatchKeyEvent(event);
    }
    // [END dispatch_key_event]
  }

  private abstract static class RequestFocusExample extends Activity {
    private final PlayerView playerView = null;

    // [START request_focus]
    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
      super.onCreate(savedInstanceState);
      // ...
      playerView.requestFocus();
      // ...
    }
    // [END request_focus]
  }

  private AndroidTv() {}
}
