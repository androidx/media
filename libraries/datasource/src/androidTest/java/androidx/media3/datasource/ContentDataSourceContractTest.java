/*
 * Copyright (C) 2020 The Android Open Source Project
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
package androidx.media3.datasource;

import android.net.Uri;
import androidx.media3.test.utils.AssetContentProvider;
import androidx.media3.test.utils.DataSourceContractTest;
import androidx.media3.test.utils.TestUtil;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import org.junit.runner.RunWith;

/** {@link DataSource} contract tests for {@link ContentDataSource}. */
@RunWith(AndroidJUnit4.class)
public final class ContentDataSourceContractTest extends DataSourceContractTest {

  private static final String AUTHORITY = "androidx.media3.datasource.test.AssetContentProvider";
  private static final String DATA_PATH = "media/mp3/1024_incrementing_bytes.mp3";

  @Override
  protected DataSource createDataSource() {
    return new ContentDataSource(ApplicationProvider.getApplicationContext());
  }

  @Override
  protected ImmutableList<TestResource> getTestResources() throws Exception {
    byte[] completeData =
        TestUtil.getByteArray(ApplicationProvider.getApplicationContext(), DATA_PATH);
    return ImmutableList.of(
        new TestResource.Builder()
            .setName("simple (pipe=false)")
            .setUri(AssetContentProvider.buildUri(AUTHORITY, DATA_PATH, /* pipeMode= */ false))
            .setExpectedBytes(completeData)
            .build(),
        new TestResource.Builder()
            .setName("simple (pipe=true)")
            .setUri(AssetContentProvider.buildUri(AUTHORITY, DATA_PATH, /* pipeMode= */ true))
            .setExpectedBytes(completeData)
            .build());
  }

  @Override
  protected Uri getNotFoundUri() {
    return AssetContentProvider.buildUri(AUTHORITY, "not/a/real/path", /* pipeMode= */ false);
  }
}
