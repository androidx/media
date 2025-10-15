/*
 * Copyright 2025 The Android Open Source Project
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
package androidx.media3.test.proguard;

import android.content.Context;
import android.net.Uri;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.RawResourceDataSource;
import androidx.media3.datasource.TransferListener;
import java.io.IOException;

/**
 * Class exercising reflection code in the DataSource module that relies on a correct proguard
 * config.
 *
 * <p>Note on adding tests: Verify that tests fail without the relevant proguard config. Be careful
 * with adding new direct class references that may let other tests pass without proguard config.
 */
public final class DataSourceModuleProguard {

  private DataSourceModuleProguard() {}

  /** Builds a raw resource URI with {@link RawResourceDataSource#buildRawResourceUri(int)}. */
  @SuppressWarnings("deprecation") // Testing deprecated API
  public static void buildRawResourceUri() {
    RawResourceDataSource.buildRawResourceUri(R.raw.raw_resource);
  }

  /** Creates an RTMP data source with {@link DefaultDataSource}. */
  public static void createRtmpDataSourceWithDefaultDataSource(Context context) {
    // Use base source that throws IllegalStateExceptions to ensure it isn't used as a fallback if
    // RTMP is unavailable.
    DataSource baseDataSource = createIllegalStateExceptionDataSource();
    DataSource dataSource = new DefaultDataSource(context, baseDataSource);
    try {
      dataSource.open(new DataSpec(Uri.parse("rtmp://media")));
    } catch (IOException | UnsupportedOperationException e) {
      // Expected. Not a real RTMP stream.
    }
  }

  private static DataSource createIllegalStateExceptionDataSource() {
    return new DataSource() {
      @Override
      public void addTransferListener(TransferListener transferListener) {}

      @Override
      public long open(DataSpec dataSpec) {
        throw new IllegalStateException();
      }

      @Override
      public Uri getUri() {
        throw new IllegalStateException();
      }

      @Override
      public void close() {
        throw new IllegalStateException();
      }

      @Override
      public int read(byte[] buffer, int offset, int length) {
        throw new IllegalStateException();
      }
    };
  }
}
