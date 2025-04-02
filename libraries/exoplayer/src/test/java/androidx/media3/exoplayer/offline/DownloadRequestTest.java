/*
 * Copyright (C) 2018 The Android Open Source Project
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
package androidx.media3.exoplayer.offline;

import static com.google.common.truth.Truth.assertThat;
import static java.util.Arrays.asList;
import static org.junit.Assert.fail;

import android.net.Uri;
import android.os.Parcel;
import androidx.media3.common.StreamKey;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.util.ArrayList;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit tests for {@link DownloadRequest}. */
@RunWith(AndroidJUnit4.class)
public class DownloadRequestTest {

  private Uri progressiveUri1;
  private Uri progressiveUri2;
  private Uri adaptiveUri1;
  private Uri adaptiveUri2;

  @Before
  public void setUp() {
    progressiveUri1 = Uri.parse("http://test/1.uri");
    progressiveUri2 = Uri.parse("http://test/2.uri");
    adaptiveUri1 = Uri.parse("http://test/1.m3u8");
    adaptiveUri2 = Uri.parse("http://test/2.m3u8");
  }

  @Test
  public void createRequestForProgressiveStream_ignoreTimeRangeField() {
    DownloadRequest downloadRequest =
        new DownloadRequest.Builder(/* id= */ "id1", progressiveUri1)
            .setTimeRange(/* startPositionUs= */ 0, /* durationUs= */ 10_000)
            .build();

    assertThat(downloadRequest.timeRange).isNull();
  }

  @Test
  public void createRequestForAdaptiveStream_ignoreByteRangeField() {
    DownloadRequest downloadRequest =
        new DownloadRequest.Builder(/* id= */ "id1", adaptiveUri1)
            .setByteRange(/* offset= */ 0, /* length= */ 10)
            .build();

    assertThat(downloadRequest.byteRange).isNull();
  }

  @Test
  public void mergeRequests_withDifferentIds_fails() {
    DownloadRequest request1 =
        new DownloadRequest.Builder(/* id= */ "id1", progressiveUri1).build();
    DownloadRequest request2 =
        new DownloadRequest.Builder(/* id= */ "id2", progressiveUri2).build();

    try {
      request1.copyWithMergedRequest(request2);
      fail();
    } catch (IllegalArgumentException e) {
      // Expected.
    }
  }

  @Test
  public void mergeRequest_withSameRequest() {
    DownloadRequest request1 = createRequest(progressiveUri1, new StreamKey(0, 0, 0));

    DownloadRequest mergedRequest = request1.copyWithMergedRequest(request1);
    assertEqual(request1, mergedRequest);
  }

  @Test
  public void mergeRequests_withEmptyStreamKeys() {
    DownloadRequest request1 = createRequest(progressiveUri1, new StreamKey(0, 0, 0));
    DownloadRequest request2 = createRequest(progressiveUri1);

    // If either of the requests have empty streamKeys, the merge should have empty streamKeys.
    DownloadRequest mergedRequest = request1.copyWithMergedRequest(request2);
    assertThat(mergedRequest.streamKeys).isEmpty();

    mergedRequest = request2.copyWithMergedRequest(request1);
    assertThat(mergedRequest.streamKeys).isEmpty();
  }

  @Test
  public void mergeRequests_withOverlappingStreamKeys() {
    StreamKey streamKey1 = new StreamKey(0, 1, 2);
    StreamKey streamKey2 = new StreamKey(3, 4, 5);
    StreamKey streamKey3 = new StreamKey(6, 7, 8);
    DownloadRequest request1 = createRequest(progressiveUri1, streamKey1, streamKey2);
    DownloadRequest request2 = createRequest(progressiveUri1, streamKey2, streamKey3);

    // Merged streamKeys should be in their original order without duplicates.
    DownloadRequest mergedRequest = request1.copyWithMergedRequest(request2);
    assertThat(mergedRequest.streamKeys).containsExactly(streamKey1, streamKey2, streamKey3);

    mergedRequest = request2.copyWithMergedRequest(request1);
    assertThat(mergedRequest.streamKeys).containsExactly(streamKey2, streamKey3, streamKey1);
  }

  @Test
  public void mergeRequests_withDifferentFields() {
    byte[] keySetId1 = new byte[] {0, 1, 2};
    byte[] keySetId2 = new byte[] {3, 4, 5};
    byte[] data1 = new byte[] {6, 7, 8};
    byte[] data2 = new byte[] {9, 10, 11};

    DownloadRequest request1 =
        new DownloadRequest.Builder(/* id= */ "id1", progressiveUri1)
            .setKeySetId(keySetId1)
            .setCustomCacheKey("key1")
            .setData(data1)
            .setByteRange(/* offset= */ 0, /* length= */ 10)
            .build();
    DownloadRequest request2 =
        new DownloadRequest.Builder(/* id= */ "id1", progressiveUri2)
            .setKeySetId(keySetId2)
            .setCustomCacheKey("key2")
            .setData(data2)
            .setByteRange(/* offset= */ 10, /* length= */ 20)
            .build();

    // uri, keySetId, customCacheKey, data and byteRange should be from the request being merged.
    DownloadRequest mergedRequest = request1.copyWithMergedRequest(request2);
    assertThat(mergedRequest.uri).isEqualTo(progressiveUri2);
    assertThat(mergedRequest.keySetId).isEqualTo(keySetId2);
    assertThat(mergedRequest.customCacheKey).isEqualTo("key2");
    assertThat(mergedRequest.data).isEqualTo(data2);
    assertThat(mergedRequest.byteRange.offset).isEqualTo(10);
    assertThat(mergedRequest.byteRange.length).isEqualTo(20);

    mergedRequest = request2.copyWithMergedRequest(request1);
    assertThat(mergedRequest.uri).isEqualTo(progressiveUri1);
    assertThat(mergedRequest.keySetId).isEqualTo(keySetId1);
    assertThat(mergedRequest.customCacheKey).isEqualTo("key1");
    assertThat(mergedRequest.data).isEqualTo(data1);
    assertThat(mergedRequest.byteRange.offset).isEqualTo(0);
    assertThat(mergedRequest.byteRange.length).isEqualTo(10);

    DownloadRequest adaptiveRequest1 =
        new DownloadRequest.Builder(/* id= */ "id1", adaptiveUri1)
            .setKeySetId(keySetId1)
            .setData(data1)
            .setTimeRange(/* startPositionUs= */ 0, /* durationUs= */ 10_000)
            .build();
    DownloadRequest adaptiveRequest2 =
        new DownloadRequest.Builder(/* id= */ "id1", adaptiveUri2)
            .setKeySetId(keySetId2)
            .setData(data2)
            .setTimeRange(/* startPositionUs= */ 10_000, /* durationUs= */ 20_000)
            .build();

    // uri, keySetId, data and timeRange should be from the request being merged.
    mergedRequest = adaptiveRequest1.copyWithMergedRequest(adaptiveRequest2);
    assertThat(mergedRequest.uri).isEqualTo(adaptiveUri2);
    assertThat(mergedRequest.keySetId).isEqualTo(keySetId2);
    assertThat(mergedRequest.data).isEqualTo(data2);
    assertThat(mergedRequest.timeRange.startPositionUs).isEqualTo(10_000);
    assertThat(mergedRequest.timeRange.durationUs).isEqualTo(20_000);

    mergedRequest = adaptiveRequest2.copyWithMergedRequest(adaptiveRequest1);
    assertThat(mergedRequest.uri).isEqualTo(adaptiveUri1);
    assertThat(mergedRequest.keySetId).isEqualTo(keySetId1);
    assertThat(mergedRequest.data).isEqualTo(data1);
    assertThat(mergedRequest.timeRange.startPositionUs).isEqualTo(0);
    assertThat(mergedRequest.timeRange.durationUs).isEqualTo(10_000);
  }

  @Test
  public void parcelable() {
    ArrayList<StreamKey> streamKeys = new ArrayList<>();
    streamKeys.add(new StreamKey(1, 2, 3));
    streamKeys.add(new StreamKey(4, 5, 6));
    DownloadRequest requestToParcel =
        new DownloadRequest.Builder("id", Uri.parse("https://abc.def/ghi"))
            .setStreamKeys(streamKeys)
            .setKeySetId(new byte[] {1, 2, 3, 4, 5})
            .setCustomCacheKey("key")
            .setData(new byte[] {1, 2, 3, 4, 5})
            .setByteRange(/* offset= */ 0, /* length= */ 20)
            .build();
    Parcel parcel = Parcel.obtain();
    requestToParcel.writeToParcel(parcel, 0);
    parcel.setDataPosition(0);

    DownloadRequest requestFromParcel = DownloadRequest.CREATOR.createFromParcel(parcel);
    assertThat(requestFromParcel).isEqualTo(requestToParcel);

    parcel.recycle();
  }

  @SuppressWarnings("EqualsWithItself")
  @Test
  public void equals() {
    DownloadRequest request1 = createRequest(progressiveUri1);
    assertThat(request1.equals(request1)).isTrue();

    DownloadRequest request2 = createRequest(progressiveUri1);
    DownloadRequest request3 = createRequest(progressiveUri1);
    assertEqual(request2, request3);

    DownloadRequest request4 = createRequest(progressiveUri1);
    DownloadRequest request5 = createRequest(progressiveUri1, new StreamKey(0, 0, 0));
    assertNotEqual(request4, request5);

    DownloadRequest request6 = createRequest(progressiveUri1, new StreamKey(0, 1, 1));
    DownloadRequest request7 = createRequest(progressiveUri1, new StreamKey(0, 0, 0));
    assertNotEqual(request6, request7);

    DownloadRequest request8 = createRequest(progressiveUri1);
    DownloadRequest request9 = createRequest(progressiveUri2);
    assertNotEqual(request8, request9);

    DownloadRequest request10 =
        createRequest(progressiveUri1, new StreamKey(0, 0, 0), new StreamKey(0, 1, 1));
    DownloadRequest request11 =
        createRequest(progressiveUri1, new StreamKey(0, 1, 1), new StreamKey(0, 0, 0));
    assertEqual(request10, request11);

    DownloadRequest request12 = createRequest(progressiveUri1, new StreamKey(0, 0, 0));
    DownloadRequest request13 =
        createRequest(progressiveUri1, new StreamKey(0, 1, 1), new StreamKey(0, 0, 0));
    assertNotEqual(request12, request13);

    DownloadRequest request14 = createRequest(progressiveUri1);
    DownloadRequest request15 = createRequest(progressiveUri1);
    assertEqual(request14, request15);

    DownloadRequest request16 = createRequest(progressiveUri1);
    DownloadRequest request17 =
        createRequest(progressiveUri1, /* byteRangeOffset= */ 0, /* byteRangeLength= */ 20);
    assertNotEqual(request16, request17);

    DownloadRequest request18 =
        createRequest(progressiveUri1, /* byteRangeOffset= */ 0, /* byteRangeLength= */ 20);
    DownloadRequest request19 =
        createRequest(progressiveUri1, /* byteRangeOffset= */ 0, /* byteRangeLength= */ 20);
    assertEqual(request18, request19);

    DownloadRequest request20 =
        createRequest(progressiveUri1, /* byteRangeOffset= */ 0, /* byteRangeLength= */ 10);
    DownloadRequest request21 =
        createRequest(progressiveUri1, /* byteRangeOffset= */ 0, /* byteRangeLength= */ 20);
    assertNotEqual(request20, request21);

    DownloadRequest request22 = createRequest(adaptiveUri1);
    DownloadRequest request23 =
        createRequestWithTimeRange(
            adaptiveUri1, /* startPositionUs= */ 0, /* durationUs= */ 20_000);
    assertNotEqual(request22, request23);

    DownloadRequest request24 =
        createRequestWithTimeRange(
            adaptiveUri1, /* startPositionUs= */ 0, /* durationUs= */ 20_000);
    DownloadRequest request25 =
        createRequestWithTimeRange(
            adaptiveUri1, /* startPositionUs= */ 0, /* durationUs= */ 20_000);
    assertEqual(request24, request25);

    DownloadRequest request26 =
        createRequestWithTimeRange(
            adaptiveUri1, /* startPositionUs= */ 0, /* durationUs= */ 10_000);
    DownloadRequest request27 =
        createRequestWithTimeRange(
            adaptiveUri1, /* startPositionUs= */ 0, /* durationUs= */ 20_000);
    assertNotEqual(request26, request27);
  }

  private static void assertNotEqual(DownloadRequest request1, DownloadRequest request2) {
    assertThat(request1).isNotEqualTo(request2);
    assertThat(request2).isNotEqualTo(request1);
  }

  private static void assertEqual(DownloadRequest request1, DownloadRequest request2) {
    assertThat(request1).isEqualTo(request2);
    assertThat(request2).isEqualTo(request1);
    assertThat(request1.hashCode()).isEqualTo(request2.hashCode());
  }

  private static DownloadRequest createRequest(Uri uri, StreamKey... keys) {
    return new DownloadRequest.Builder(uri.toString(), uri).setStreamKeys(asList(keys)).build();
  }

  private static DownloadRequest createRequest(
      Uri uri, long byteRangeOffset, long byteRangeLength) {
    return new DownloadRequest.Builder(uri.toString(), uri)
        .setByteRange(byteRangeOffset, byteRangeLength)
        .build();
  }

  private static DownloadRequest createRequestWithTimeRange(
      Uri uri, long startPositionUs, long durationUs) {
    return new DownloadRequest.Builder(uri.toString(), uri)
        .setTimeRange(startPositionUs, durationUs)
        .build();
  }
}
