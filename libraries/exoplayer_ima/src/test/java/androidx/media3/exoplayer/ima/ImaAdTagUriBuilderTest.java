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
package androidx.media3.exoplayer.ima;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Mockito.when;

import android.net.Uri;
import androidx.media3.exoplayer.ima.ImaUtil.ImaFactory;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.ads.interactivemedia.v3.api.AdsRequest;
import com.google.ads.interactivemedia.v3.api.ImaSdkFactory;
import com.google.ads.interactivemedia.v3.api.VideoOrientation;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

@RunWith(AndroidJUnit4.class)
public final class ImaAdTagUriBuilderTest {

  private static final Uri TEST_URI = Uri.parse("https://www.google.com");

  @Rule public final MockitoRule mockito = MockitoJUnit.rule();

  @Mock private ImaFactory mockImaFactory;

  @Before
  public void setUp() {
    when(mockImaFactory.createAdsRequest())
        .thenReturn(ImaSdkFactory.getInstance().createAdsRequest());
  }

  @Test
  public void createAdsRequest_withUri_correctAdsRequest() {
    ImaAdTagUriBuilder builder = new ImaAdTagUriBuilder(TEST_URI);

    Uri uri = builder.build();
    AdsRequest createdAdsRequest = ImaAdTagUriBuilder.createAdsRequest(mockImaFactory, uri);

    assertThat(createdAdsRequest.getAdTagUrl()).isEqualTo(TEST_URI.toString());
    assertThat(createdAdsRequest.getPreferredLinearOrientation()).isEqualTo(VideoOrientation.UNSET);
  }

  @Test
  public void createAdsRequest_withUriAndVideoOrientation_correctAdsRequest() {
    ImaAdTagUriBuilder builder = new ImaAdTagUriBuilder(TEST_URI);
    builder.setPreferredLinearOrientation(ImaAdTagUriBuilder.IMA_ORIENTATION_PORTRAIT);

    Uri uri = builder.build();
    AdsRequest createdAdsRequest = ImaAdTagUriBuilder.createAdsRequest(mockImaFactory, uri);

    assertThat(createdAdsRequest.getAdTagUrl()).isEqualTo(TEST_URI.toString());
    assertThat(createdAdsRequest.getPreferredLinearOrientation())
        .isEqualTo(VideoOrientation.PORTRAIT);
  }
}
