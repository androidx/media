/*
 * Copyright (C) 2017 The Android Open Source Project
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
package androidx.media3.test.utils;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import android.net.Uri;
import androidx.media3.common.util.Assertions;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSourceInputStream;
import androidx.media3.datasource.DataSourceUtil;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.PlaceholderDataSource;
import androidx.media3.datasource.cache.Cache;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.test.utils.FakeDataSet.FakeData;
import com.google.common.io.ByteStreams;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.io.IOException;
import java.util.ArrayList;

/** Assertion methods for {@link Cache}. */
@UnstableApi
public final class CacheAsserts {

  /** Defines a set of data requests. */
  public static final class RequestSet {

    private final FakeDataSet fakeDataSet;
    private DataSpec[] dataSpecs;

    public RequestSet(FakeDataSet fakeDataSet) {
      this.fakeDataSet = fakeDataSet;
      ArrayList<FakeData> allData = fakeDataSet.getAllData();
      dataSpecs = new DataSpec[allData.size()];
      for (int i = 0; i < dataSpecs.length; i++) {
        dataSpecs[i] = new DataSpec(Assertions.checkNotNull(allData.get(i).uri));
      }
    }

    @CanIgnoreReturnValue
    public RequestSet subset(String... uriStrings) {
      dataSpecs = new DataSpec[uriStrings.length];
      for (int i = 0; i < dataSpecs.length; i++) {
        dataSpecs[i] = new DataSpec(Uri.parse(uriStrings[i]));
      }
      return this;
    }

    @CanIgnoreReturnValue
    public RequestSet subset(Uri... uris) {
      dataSpecs = new DataSpec[uris.length];
      for (int i = 0; i < dataSpecs.length; i++) {
        dataSpecs[i] = new DataSpec(uris[i]);
      }
      return this;
    }

    @CanIgnoreReturnValue
    public RequestSet subset(DataSpec... dataSpecs) {
      this.dataSpecs = dataSpecs;
      return this;
    }

    public int getCount() {
      return dataSpecs.length;
    }

    public byte[] getData(int i) {
      return Assertions.checkNotNull(fakeDataSet.getData(dataSpecs[i].uri)).getData();
    }

    public DataSpec getDataSpec(int i) {
      return dataSpecs[i];
    }

    @CanIgnoreReturnValue
    public RequestSet useBoundedDataSpecFor(String uriString) {
      FakeData data = Assertions.checkStateNotNull(fakeDataSet.getData(uriString));
      for (int i = 0; i < dataSpecs.length; i++) {
        DataSpec spec = dataSpecs[i];
        if (Assertions.checkNotNull(spec.uri.getPath()).equals(uriString)) {
          dataSpecs[i] = spec.subrange(0, data.getData().length);
          return this;
        }
      }
      throw new IllegalStateException();
    }
  }

  /**
   * Asserts that the cache contains necessary data for the {@code requestSet}.
   *
   * @throws IOException If an error occurred reading from the Cache.
   */
  public static void assertCachedData(Cache cache, RequestSet requestSet) throws IOException {
    int totalLength = 0;
    for (int i = 0; i < requestSet.getCount(); i++) {
      byte[] data = requestSet.getData(i);
      assertDataCached(cache, requestSet.getDataSpec(i), data);
      totalLength += data.length;
    }
    assertThat(cache.getCacheSpace()).isEqualTo(totalLength);
  }

  /**
   * Asserts that the cache content is equal to the data in the {@code fakeDataSet}.
   *
   * @throws IOException If an error occurred reading from the Cache.
   */
  public static void assertCachedData(Cache cache, FakeDataSet fakeDataSet) throws IOException {
    assertCachedData(cache, new RequestSet(fakeDataSet));
  }

  /**
   * Asserts that the cache contains the given data for {@code dataSpec}.
   *
   * @throws IOException If an error occurred reading from the Cache.
   */
  public static void assertDataCached(Cache cache, DataSpec dataSpec, byte[] expected)
      throws IOException {
    DataSource dataSource = new CacheDataSource(cache, PlaceholderDataSource.INSTANCE, 0);
    byte[] bytes;
    try {
      dataSource.open(dataSpec);
      bytes = DataSourceUtil.readToEnd(dataSource);
    } catch (IOException e) {
      throw new IOException("Opening/reading cache failed: " + dataSpec, e);
    } finally {
      dataSource.close();
    }
    assertWithMessage("Cached data doesn't match expected for '" + dataSpec.uri + "',")
        .that(bytes)
        .isEqualTo(expected);
  }

  /**
   * Asserts that the read data from {@code dataSource} specified by {@code dataSpec} is equal to
   * {@code expected} or not.
   *
   * @throws IOException If an error occurred reading from the Cache.
   */
  public static void assertReadData(DataSource dataSource, DataSpec dataSpec, byte[] expected)
      throws IOException {
    try (DataSourceInputStream inputStream = new DataSourceInputStream(dataSource, dataSpec)) {
      byte[] bytes = ByteStreams.toByteArray(inputStream);
      assertThat(bytes).isEqualTo(expected);
    }
  }

  /** Asserts that the cache is empty. */
  public static void assertCacheEmpty(Cache cache) {
    assertThat(cache.getCacheSpace()).isEqualTo(0);
    assertThat(cache.getKeys()).isEmpty();
  }

  private CacheAsserts() {}
}
