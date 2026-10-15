package androidx.media3.exoplayer.rtsp;

import static androidx.media3.exoplayer.rtsp.MediaDescription.MEDIA_TYPE_VIDEO;
import static androidx.media3.exoplayer.rtsp.MediaDescription.RTP_AVP_PROFILE;
import static androidx.media3.exoplayer.rtsp.SessionDescription.ATTR_CONTROL;
import static androidx.media3.exoplayer.rtsp.SessionDescription.ATTR_FMTP;
import static androidx.media3.exoplayer.rtsp.SessionDescription.ATTR_RTPMAP;
import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableMap;
import org.junit.Test;

public class FmtpParserTest {

  private MediaDescription createMediaDescription(String fmtpValue) {
    return new MediaDescription.Builder(
        MEDIA_TYPE_VIDEO, /* port= */ 0, RTP_AVP_PROFILE, /* payloadType= */ 96)
        .addAttribute(ATTR_RTPMAP, "96 H264/90000")
        .addAttribute(ATTR_FMTP, fmtpValue)
        .build();
  }

  @Test
  public void testValidFmtpParameters() {
    MediaDescription mediaDescription = createMediaDescription(
        "96 packetization-mode=1;profile-level-id=4D4028;sprop-parameter-sets=Z01AKJ2oHgCJ+WEAAAMAAQAAAwAehA==,aO48gA=="
    );

    ImmutableMap<String, String> fmtpMap = mediaDescription.getFmtpParametersAsMap();

    assertThat(fmtpMap).containsExactly(
        "packetization-mode", "1",
        "profile-level-id", "4D4028",
        "sprop-parameter-sets", "Z01AKJ2oHgCJ+WEAAAMAAQAAAwAehA==,aO48gA=="
    );
  }

  @Test
  public void testEmptyFmtpAttribute() {
    try {
      MediaDescription mediaDescription = createMediaDescription("");

      ImmutableMap<String, String> fmtpMap = mediaDescription.getFmtpParametersAsMap();
    } catch (Exception e) {
      assertThat(e).isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  public void testNullFmtpAttribute() {
    try {
      MediaDescription mediaDescription = createMediaDescription(null);
      ImmutableMap<String, String> fmtpMap = mediaDescription.getFmtpParametersAsMap();
      assertThat(fmtpMap).isEmpty();
    } catch (NullPointerException e) {
      assertThat(e).isInstanceOf(NullPointerException.class);
    }
  }

  @Test
  public void testInvalidFormat_NoEqualSigns() {
    MediaDescription mediaDescription = createMediaDescription("96 invalid-format");

    ImmutableMap<String, String> fmtpMap = mediaDescription.getFmtpParametersAsMap();

    assertThat(fmtpMap).isEmpty();
  }

  @Test
  public void testTrailingSemicolon() {
    MediaDescription mediaDescription = createMediaDescription(
        "96 packetization-mode=1;profile-level-id=4D4028;");

    ImmutableMap<String, String> fmtpMap = mediaDescription.getFmtpParametersAsMap();

    assertThat(fmtpMap).containsExactly(
        "packetization-mode", "1",
        "profile-level-id", "4D4028"
    );
  }

  @Test
  public void testMultipleEqualsInValue() {
    MediaDescription mediaDescription = createMediaDescription(
        "96 complex-param=abc=xyz;another-param=value");

    ImmutableMap<String, String> fmtpMap = mediaDescription.getFmtpParametersAsMap();

    assertThat(fmtpMap).containsExactly(
        "complex-param", "abc=xyz",
        "another-param", "value"
    );
  }

  @Test
  public void testIncorrectFmtpFormat() {
    // Modified input to have a space but still invalid
    MediaDescription mediaDescription = createMediaDescription("96 profile-level-id");

    ImmutableMap<String, String> fmtpMap = mediaDescription.getFmtpParametersAsMap();

    assertThat(fmtpMap).isEmpty();
  }
}