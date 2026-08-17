/*
 * Copyright (C) 2021 The Android Open Source Project
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
package androidx.media3.datasource.cache;

import android.net.Uri;
import androidx.annotation.Nullable;
import androidx.media3.datasource.DataSource;
import androidx.media3.test.utils.DataSourceContractTest;
import androidx.media3.test.utils.FakeDataSet;
import androidx.media3.test.utils.FakeDataSource;
import androidx.media3.test.utils.InMemoryDatabaseRule;
import androidx.media3.test.utils.TestUtil;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import org.junit.Before;
import org.junit.Rule;
import org.junit.runner.RunWith;

/** {@link DataSource} contract tests for {@link CacheDataSource}. */
@RunWith(AndroidJUnit4.class)
public class CacheDataSourceContractTest extends DataSourceContractTest {

  @Rule public final InMemoryDatabaseRule inMemoryDatabaseRule = InMemoryDatabaseRule.create();

  private Uri simpleUri;
  private Uri unknownLengthUri;
  private byte[] simpleData;
  private byte[] unknownLengthData;
  private FakeDataSet fakeDataSet;

  private FakeDataSource upstreamDataSource;

  @Before
  public void setUp() throws IOException {
    simpleUri = Uri.parse("test://simple.test");
    unknownLengthUri = Uri.parse("test://unknown-length.test");
    simpleData = TestUtil.buildTestData(/* length= */ 20);
    unknownLengthData = TestUtil.buildTestData(/* length= */ 40);
    fakeDataSet =
        new FakeDataSet()
            .newData(simpleUri)
            .appendReadData(simpleData)
            .endData()
            .newData(unknownLengthUri)
            .setSimulateUnknownLength(true)
            .appendReadData(unknownLengthData)
            .endData();
  }

  @Override
  protected ImmutableList<TestResource> getTestResources() {
    return ImmutableList.of(
        new TestResource.Builder()
            .setName("simple")
            .setUri(simpleUri)
            .setExpectedBytes(simpleData)
            .build(),
        new TestResource.Builder()
            .setName("unknown length")
            .setUri(unknownLengthUri)
            .setExpectedBytes(unknownLengthData)
            .setMayResolveToUnknownLength(true)
            .build());
  }

  @Override
  protected Uri getNotFoundUri() {
    return Uri.parse("test://not-found.test");
  }

  @Override
  protected DataSource createDataSource() throws IOException {
    upstreamDataSource = new FakeDataSource(fakeDataSet);
    return new CacheDataSource(inMemoryDatabaseRule.createSimpleCache(), upstreamDataSource);
  }

  @Override
  @Nullable
  protected DataSource getTransferListenerDataSource() {
    return upstreamDataSource;
  }
}
