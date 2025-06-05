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
package androidx.media3.datasource;

import static androidx.media3.datasource.HttpUtil.buildRangeRequestHeader;
import static androidx.media3.datasource.HttpUtil.getContentLength;
import static androidx.media3.datasource.HttpUtil.getDocumentSize;
import static com.google.common.truth.Truth.assertThat;

import androidx.media3.common.C;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.URI;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit tests for {@link HttpUtil}. */
@RunWith(AndroidJUnit4.class)
public class HttpUtilTest {

  private static final String TEST_URL = "http://google.com/video/";
  private static final String TEST_REQUEST_COOKIE = "foo=bar";
  private static final String TEST_REQUEST_COOKIE_2 = "baz=qux";
  private static final String TEST_RESPONSE_SET_COOKIE =
      TEST_REQUEST_COOKIE + ";path=/video; expires 31-12-2099 23:59:59 GMT";
  private static final String TEST_RESPONSE_SET_COOKIE_2 =
      TEST_REQUEST_COOKIE_2 + ";path=/; expires 31-12-2099 23:59:59 GMT";

  @Test
  public void buildRangeRequestHeader_buildsHeader() {
    assertThat(buildRangeRequestHeader(0, C.LENGTH_UNSET)).isNull();
    assertThat(buildRangeRequestHeader(1, C.LENGTH_UNSET)).isEqualTo("bytes=1-");
    assertThat(buildRangeRequestHeader(0, 5)).isEqualTo("bytes=0-4");
    assertThat(buildRangeRequestHeader(5, 15)).isEqualTo("bytes=5-19");
  }

  @Test
  public void getContentLength_bothHeadersMissing_returnsUnset() {
    assertThat(getContentLength(null, null)).isEqualTo(C.LENGTH_UNSET);
    assertThat(getContentLength("", "")).isEqualTo(C.LENGTH_UNSET);
  }

  @Test
  public void getContentLength_onlyContentLengthHeaderSet_returnsCorrectValue() {
    assertThat(getContentLength("5", null)).isEqualTo(5);
    assertThat(getContentLength("5", "")).isEqualTo(5);
  }

  @Test
  public void getContentLength_onlyContentRangeHeaderSet_returnsCorrectValue() {
    assertThat(getContentLength(null, "bytes 5-9/100")).isEqualTo(5);
    assertThat(getContentLength("", "bytes 5-9/100")).isEqualTo(5);
    assertThat(getContentLength("", "bytes 5-9/*")).isEqualTo(5);
  }

  @Test
  public void getContentLength_bothHeadersSet_returnsCorrectValue() {
    assertThat(getContentLength("5", "bytes 5-9/100")).isEqualTo(5);
  }

  @Test
  public void getContentLength_headersInconsistent_returnsLargerValue() {
    assertThat(getContentLength("10", "bytes 0-4/100")).isEqualTo(10);
    assertThat(getContentLength("5", "bytes 0-9/100")).isEqualTo(10);
  }

  @Test
  public void getContentLength_ignoresInvalidValues() {
    assertThat(getContentLength("Invalid", "Invalid")).isEqualTo(C.LENGTH_UNSET);
    assertThat(getContentLength("Invalid", "bytes 5-9/100")).isEqualTo(5);
    assertThat(getContentLength("5", "Invalid")).isEqualTo(5);
  }

  @Test
  public void getContentLength_ignoresUnhandledRangeUnits() {
    assertThat(getContentLength(null, "unhandled 5-9/100")).isEqualTo(C.LENGTH_UNSET);
    assertThat(getContentLength("10", "unhandled 0-4/100")).isEqualTo(10);
  }

  @Test
  public void getDocumentSize_noHeader_returnsUnset() {
    assertThat(getDocumentSize(null)).isEqualTo(C.LENGTH_UNSET);
    assertThat(getDocumentSize("")).isEqualTo(C.LENGTH_UNSET);
  }

  @Test
  public void getDocumentSize_returnsSize() {
    assertThat(getDocumentSize("bytes */20")).isEqualTo(20);
    assertThat(getDocumentSize("bytes 0-4/20")).isEqualTo(20);
  }

  @Test
  public void getDocumentSize_ignoresUnhandledRangeUnits() {
    assertThat(getDocumentSize("unhandled */20")).isEqualTo(C.LENGTH_UNSET);
    assertThat(getDocumentSize("unhandled 0-4/20")).isEqualTo(C.LENGTH_UNSET);
  }

  @Test
  public void getCookieHeader_noCookieHandler() {
    CookieHandler.setDefault(null);

    assertThat(
            HttpUtil.getCookieHeader(
                TEST_URL, /* headers= */ ImmutableMap.of(), CookieHandler.getDefault()))
        .isEmpty();
    assertThat(CookieHandler.getDefault()).isNull();
  }

  @Test
  public void getCookieHeader_emptyCookieHandler() {
    CookieHandler.setDefault(new CookieManager());

    assertThat(
            HttpUtil.getCookieHeader(
                TEST_URL, /* headers= */ ImmutableMap.of(), CookieHandler.getDefault()))
        .isEmpty();
  }

  @Test
  public void getCookieHeader_cookieHandler() throws Exception {
    CookieManager cm = new CookieManager();
    cm.put(
        new URI(TEST_URL),
        ImmutableMap.of(
            "Set-Cookie", ImmutableList.of(TEST_RESPONSE_SET_COOKIE, TEST_RESPONSE_SET_COOKIE_2)));
    CookieHandler.setDefault(cm);

    assertThat(
            HttpUtil.getCookieHeader(
                TEST_URL, /* headers= */ ImmutableMap.of(), CookieHandler.getDefault()))
        .isEqualTo(TEST_REQUEST_COOKIE + "; " + TEST_REQUEST_COOKIE_2 + ";");
  }

  @Test
  public void getCookieHeader_cookieHandlerCustomHandler() throws Exception {
    CookieManager cm = new CookieManager();
    cm.put(
        new URI(TEST_URL),
        ImmutableMap.of(
            "Set-Cookie", ImmutableList.of(TEST_RESPONSE_SET_COOKIE, TEST_RESPONSE_SET_COOKIE_2)));

    assertThat(HttpUtil.getCookieHeader(TEST_URL, /* headers= */ ImmutableMap.of(), cm))
        .isEqualTo(TEST_REQUEST_COOKIE + "; " + TEST_REQUEST_COOKIE_2 + ";");
  }

  @Test
  public void getCookieHeader_cookieHandlerCookie2() throws Exception {
    CookieManager cm = new CookieManager();
    cm.put(
        new URI(TEST_URL),
        ImmutableMap.of(
            "Set-Cookie2", ImmutableList.of(TEST_RESPONSE_SET_COOKIE, TEST_RESPONSE_SET_COOKIE_2)));
    CookieHandler.setDefault(cm);

    // This asserts the surprising behavior of CookieManager - Set-Cookie2 is translated to Cookie,
    // not Cookie2.
    assertThat(cm.get(new URI(TEST_URL), ImmutableMap.of("", ImmutableList.of()))).isNotEmpty();
    assertThat(cm.get(new URI(TEST_URL), ImmutableMap.of("", ImmutableList.of())).get("Cookie"))
        .containsExactly(TEST_REQUEST_COOKIE, TEST_REQUEST_COOKIE_2);
    assertThat(cm.get(new URI(TEST_URL), ImmutableMap.of("", ImmutableList.of())))
        .doesNotContainKey("Cookie2");

    assertThat(
            HttpUtil.getCookieHeader(
                TEST_URL, /* headers= */ ImmutableMap.of(), CookieHandler.getDefault()))
        .isEqualTo(TEST_REQUEST_COOKIE + "; " + TEST_REQUEST_COOKIE_2 + ";");
  }
}
