/*
 * Copyright (C) 2025 The Android Open Source Project
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
package androidx.media3.demo.cast;

import android.app.Application;
import androidx.media3.cast.Cast;

/** The main application class for the Cast demo app. */
public class CastDemoApplication extends Application {

  @Override
  public void onCreate() {
    super.onCreate();
    Cast.getSingletonInstance(this).initialize();
  }
}
