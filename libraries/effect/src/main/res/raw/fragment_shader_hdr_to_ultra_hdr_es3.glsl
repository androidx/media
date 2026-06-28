#version 300 es
// Copyright 2026 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

// ES 3 fragment shader that:
// 1. Samples optical linear BT.2020 RGB.
// 2. Applies HLG OOTF (system gamma) and maps from BT.2020 to sRGB gamut via
// XYZ.
// 3. Clamps result to [0, 1] to create the SDR base image.
// 4. Applies sRGB OETF to the SDR base (RGB channels).
// 5. Computes gain map by comparing scene-linear HDR nits (scaled to 1000)
//    vs. SDR base nits (scaled to 203).
// 6. Normalizes log2 gain to [0, 1] based on uMaxBoost (Alpha channel).

precision mediump float;
uniform sampler2D uTexSampler;
uniform highp float
    uMaxBoost;  // Max content boost, matches ULTRA_HDR_MAX_BOOST in Java.
uniform highp float uSdrReferenceWhiteNits;
uniform highp float uHdrPeakNits;

in vec2 vTexSamplingCoord;
layout(location = 0) out vec4 outSdr;
layout(location = 1) out float outGainmap;

// Matrix to convert from BT.2020 RGB to CIE XYZ.
// Derived from BT.2020 primaries: Red(0.708, 0.292), Green(0.170, 0.797),
// Blue(0.131, 0.046) and D65 white point (x=0.3127, y=0.3290).
const mat3 RGB_BT2020_TO_XYZ =
    mat3(0.63695805f, 0.26270021f, 0.00000000f, 0.14461690f, 0.67799807f,
         0.02807269f, 0.16888098f, 0.05930172f, 1.06098506f);

// Matrix to convert from CIE XYZ to sRGB.
// Derived from sRGB primaries: Red(0.64, 0.33), Green(0.30, 0.60), Blue(0.15,
// 0.06) and D65 white point (x=0.3127, y=0.3290).
const mat3 XYZ_TO_RGB_BT709 =
    mat3(3.24096994f, -0.96924364f, 0.05563008f, -1.53738318f, 1.87596750f,
         -0.20397696f, -0.49861076f, 0.04155506f, 1.05697151f);

// Transforms a single channel from optical sRGB to electrical SDR using the
// sRGB OETF.
highp float srgbOetfSingleChannel(highp float linearChannel) {
  return linearChannel <= 0.0031308
             ? linearChannel * 12.92
             : 1.055 * pow(linearChannel, 1.0 / 2.4) - 0.055;
}

// Transforms optical sRGB to electrical SDR using the sRGB OETF.
highp vec3 srgbOetf(const highp vec3 linearColor) {
  return vec3(srgbOetfSingleChannel(linearColor.r),
              srgbOetfSingleChannel(linearColor.g),
              srgbOetfSingleChannel(linearColor.b));
}

void main() {
  highp vec4 linearColor = texture(uTexSampler, vTexSamplingCoord);

  // The input texture color is in optical linear BT.2020 space (scene-linear
  // for HLG)
  highp vec3 r2020 = linearColor.rgb;

  // Reference ("HLG Reference OOTF" section):
  // https://www.itu.int/dms_pubrec/itu-r/rec/bt/R-REC-BT.2100-2-201807-S!!PDF-E.pdf
  highp float hlgGamma =
      1.2 + 0.42 * (log2(uHdrPeakNits / 1000.0) / log2(10.0));

  highp vec3 linearXyz = RGB_BT2020_TO_XYZ * r2020;
  highp float yHdr = max(linearXyz[1], 0.0);
  linearXyz = linearXyz * (yHdr > 0.0 ? pow(yHdr, hlgGamma - 1.0) : 0.0);
  highp vec3 srgbLin = XYZ_TO_RGB_BT709 * linearXyz;
  srgbLin = clamp(srgbLin, 0.0, 1.0);

  // 2. Compute Gain in log2 space using max component (matching libultrahdr
  // default)
  highp float sdrYNits =
      max(srgbLin.r, max(srgbLin.g, srgbLin.b)) * uSdrReferenceWhiteNits;
  highp float hdrYNits = max(r2020.r, max(r2020.g, r2020.b)) * uHdrPeakNits;

  // Clamp ratio to [1.0, uMaxBoost] for max boost.
  highp float ratio = clamp(hdrYNits / max(sdrYNits, 0.01), 1.0, uMaxBoost);
  highp float gain = log2(ratio);

  // Normalize to [0, 1] (log2(uMaxBoost) is the max gain in log2 space)
  highp float maxLog2Boost = log2(uMaxBoost);
  highp float normalizedGain =
      (maxLog2Boost > 0.0) ? (gain / maxLog2Boost) : 0.0;

  // 3. Output electrical sRGB in RGB to attachment 0, and normalized Gainmap to
  // attachment 1
  outSdr = vec4(srgbOetf(srgbLin), 1.0);
  outGainmap = normalizedGain;
}
