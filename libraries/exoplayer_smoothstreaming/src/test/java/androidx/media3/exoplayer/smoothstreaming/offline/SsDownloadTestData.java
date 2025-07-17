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
package androidx.media3.exoplayer.smoothstreaming.offline;

import static java.nio.charset.StandardCharsets.UTF_8;

/* package */ final class SsDownloadTestData {

  public static final String TEST_ISM_BASE = "test.ism";
  public static final String TEST_ISM_MANIFEST_URI = TEST_ISM_BASE + "/Manifest";
  public static final String TEST_ISM_QUALITY_LEVEL_DIR_1 =
      TEST_ISM_BASE + "/QualityLevels(1536000)";
  public static final String TEST_ISM_QUALITY_LEVEL_DIR_2 =
      TEST_ISM_BASE + "/QualityLevels(307200)";
  public static final String TEST_ISM_FRAGMENT_URI_1 = "/Fragments(video=0)";
  public static final String TEST_ISM_FRAGMENT_URI_2 = "/Fragments(video=19680000)";
  public static final String TEST_ISM_FRAGMENT_URI_3 = "/Fragments(video=28660000)";

  public static final byte[] TEST_ISM_MANIFEST_DATA =
      ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
              + "<SmoothStreamingMedia MajorVersion=\"2\" MinorVersion=\"0\"\n"
              + "    Duration=\"2300000000\" TimeScale=\"1000000\">\n"
              + "\n"
              + "    <StreamIndex\n"
              + "        Type = \"video\"\n"
              + "        Chunks = \"115\"\n"
              + "        QualityLevels = \"2\"\n"
              + "        MaxWidth = \"720\"\n"
              + "        MaxHeight = \"480\"\n"
              + "        TimeScale=\"1000000\"\n"
              + "        Url =\n"
              + "            \"QualityLevels({bitrate})/Fragments(video={start_time})\"\n"
              + "        Name = \"video\"\n"
              + "        >\n"
              + "        <QualityLevel Index=\"0\" Bitrate=\"1536000\" FourCC=\"WVC1\"\n"
              + "            MaxWidth=\"720\" MaxHeight=\"480\"\n"
              + "            CodecPrivateData ="
              + " \"270000010FCBEE1670EF8A16783BF180C9089CC4AFA11C0000010E1207F840\"\n"
              + "            >\n"
              + "            <CustomAttributes>\n"
              + "                <Attribute Name = \"Compatibility\" Value = \"Desktop\" />\n"
              + "            </CustomAttributes>\n"
              + "        </QualityLevel>\n"
              + "\n"
              + "\n"
              + "        <QualityLevel Index=\"5\" Bitrate=\"307200\" FourCC=\"WVC1\"\n"
              + "            MaxWidth=\"720\" MaxHeight=\"480\"\n"
              + "            CodecPrivateData ="
              + " \"270000010FCBEE1670EF8A16783BF180C9089CC4AFA11C0000010E1207F840\">\n"
              + "            <CustomAttributes>\n"
              + "                <Attribute Name = \"Compatibility\" Value = \"Handheld\" />\n"
              + "            </CustomAttributes>\n"
              + "        </QualityLevel>\n"
              + "\n"
              + "\n"
              + "        <c t = \"0\" d = \"19680000\" />\n"
              + "        <c n = \"1\" t = \"19680000\" d=\"8980000\" />\n"
              + "        <c n = \"2\" t = \"28660000\" d=\"8980000\" />\n"
              + "\n"
              + "    </StreamIndex>\n"
              + "</SmoothStreamingMedia>")
          .getBytes(UTF_8);

  private SsDownloadTestData() {}
}
