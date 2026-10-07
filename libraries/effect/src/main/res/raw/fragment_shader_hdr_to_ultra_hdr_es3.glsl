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
// 3. Softly compresses out-of-gamut colors to [0, 1] to create the SDR base image.
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
    mat3(0.63695805, 0.26270021, 0.00000000, 0.14461690, 0.67799807,
         0.02807269, 0.16888098, 0.05930172, 1.06098506);

// Matrix to convert from CIE XYZ to sRGB.
// Derived from sRGB primaries: Red(0.64, 0.33), Green(0.30, 0.60), Blue(0.15,
// 0.06) and D65 white point (x=0.3127, y=0.3290).
const mat3 XYZ_TO_RGB_BT709 =
    mat3(3.24096994, -0.96924364, 0.05563008, -1.53738318, 1.87596750,
         -0.20397696, -0.49861076, 0.04155506, 1.05697151);

// Compresses each channel's distance from the achromatic axis max(R, G, B) into
// the BT.709 gamut along lines of constant hue (ACES 1.3 Reference Gamut
// Compression architecture, Academy TB-2021-001), and normalizes by
// max(1.0, ach) to preserve chromaticity when XYZ_TO_RGB_BT709 inflates
// max(R, G, B) above 1.0.
highp vec3 softCompressGamutBt2020ToBt709(highp vec3 rgbBt709) {
  // Knee threshold, colors within 95% of the BT.709 boundary pass through
  // untouched.
  const highp float t0 = 0.95;
  // Headroom between the knee and the BT.709 boundary (1.0 - t0).
  const highp float h = 0.05;
  // Maximum excess distance (d_max - t0) across all BT.2020 colors, where d_max
  // occurs on the BT.2020 cyan edge (G = B in BT.709).
  const highp float eMax = 0.6438584;
  // Curvature parameter (1.0 - h / eMax) ensuring unit slope at the knee
  // (smooth transition with no kink) and mapping eMax exactly to headroom h.
  const highp float a = 0.9223432;

  // Max achromatic light
  highp float ach = max(rgbBt709.r, max(rgbBt709.g, rgbBt709.b));
  if (ach <= 0.0) {
    return vec3(0.0);
  }

  // Use a C1 smooth, simple tone curve to avoid executing the heavy ACES /
  // BT.2407 math.
  highp vec3 d = (vec3(ach) - rgbBt709) / ach;
  highp vec3 e = clamp(d - vec3(t0), vec3(0.0), vec3(eMax));
  highp vec3 dCompressed = vec3(t0) + (h * e) / (a * e + vec3(h));
  highp vec3 dOut = mix(d, dCompressed, step(vec3(t0), d));
  highp vec3 compressed = vec3(ach) - dOut * ach;
  return clamp(compressed / max(1.0, ach), 0.0, 1.0);
}

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
  srgbLin = softCompressGamutBt2020ToBt709(srgbLin);

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
