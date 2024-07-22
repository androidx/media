/*
 * Copyright 2021 The Android Open Source Project
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
package androidx.media3.common;

import static com.google.common.truth.Truth.assertThat;

import android.os.Bundle;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.ext.truth.os.BundleSubject;
import com.google.common.collect.ImmutableList;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Tests for {@link BundleListRetriever}. */
@RunWith(AndroidJUnit4.class)
public class BundleListRetrieverTest {

  @Test
  public void getList_inProcess_returnsOriginalImmutableList() {
    int count = 100_000;
    ImmutableList.Builder<Bundle> listBuilder = ImmutableList.builder();
    for (int i = 0; i < count; i++) {
      Bundle bundle = new Bundle();
      bundle.putInt("i", i);
      listBuilder.add(bundle);
    }
    ImmutableList<Bundle> listBefore = listBuilder.build();

    ImmutableList<Bundle> listAfter =
        BundleListRetriever.getList(new BundleListRetriever(listBefore));

    assertThat(listAfter).isSameInstanceAs(listBefore);
  }

  @Test
  public void getList_fromRemoteBinder_preservedLargeList() {
    int count = 100_000;
    ImmutableList.Builder<Bundle> listBuilder = ImmutableList.builder();
    for (int i = 0; i < count; i++) {
      Bundle bundle = new Bundle();
      bundle.putInt("i", i);
      listBuilder.add(bundle);
    }
    ImmutableList<Bundle> listBefore = listBuilder.build();

    ImmutableList<Bundle> listAfter =
        BundleListRetriever.getListFromRemoteBinder(new BundleListRetriever(listBefore));

    for (int i = 0; i < count; i++) {
      Bundle bundle = listAfter.get(i);
      BundleSubject.assertThat(bundle).integer("i").isEqualTo(i);
    }
  }
}
