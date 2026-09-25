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

// Shared color science conversion functions. This file is intended to be included as the first
// fragment shader resource and provides the #version, #extension, and precision declarations.

#extension GL_OES_EGL_image_external : enable
#extension GL_EXT_YUV_target : enable
precision highp float;

// LINT.IfChange(color_space)
const int COLOR_SPACE_BT709 = 1;
const int COLOR_SPACE_BT2020 = 6;
// LINT.ThenChange(../../../../../common/src/main/java/androidx/media3/common/C.java:color_space)

// LINT.IfChange(color_transfer)
const int COLOR_TRANSFER_LINEAR = 1;
const int COLOR_TRANSFER_SRGB = 2;
const int COLOR_TRANSFER_ST2084 = 6;
const int COLOR_TRANSFER_HLG = 7;
// LINT.ThenChange(../../../../../common/src/main/java/androidx/media3/common/C.java:color_transfer)

// Returns whether the color transfer is an HDR transfer (HLG or PQ).
bool isTransferHdr(int colorTransfer) {
  return colorTransfer == COLOR_TRANSFER_HLG || colorTransfer == COLOR_TRANSFER_ST2084;
}

// Returns whether the color gamut is an HDR wide gamut (BT.2020).
bool isGamutHdr(int colorGamut) {
  return colorGamut == COLOR_SPACE_BT2020;
}

// Matrix values based on computeXYZMatrix(BT2020Primaries, D65WhitePoint)
// References:
// - ITU-R BT.2020-2: Parameter values for ultra-high definition television systems.
// - Android HWUI HostColorSpace:
//   https://cs.android.com/android/platform/superproject/+/master:frameworks/base/libs/hwui/utils/HostColorSpace.cpp;l=200-232;drc=86bd214059cd6150304888a285941bf74af5b687
// Column-major representation:
const mat3 BT2020_TO_XYZ =
    mat3(0.63695805, 0.26270021, 0.00000000,
         0.14461690, 0.67799807, 0.02807269,
         0.16888098, 0.05930172, 1.06098506);

// Matrix values based on computeXYZMatrix(BT2020Primaries, D65WhitePoint) inverted
// References:
// - ITU-R BT.2020-2: Parameter values for ultra-high definition television systems.
// Column-major representation:
const mat3 XYZ_TO_BT2020 =
    mat3( 1.71665118, -0.66668434,  0.01763986,
         -0.35567077,  1.61648124, -0.04277061,
         -0.25336629,  0.01576854,  0.94210312);

// Matrix values based on computeXYZMatrix(BT709Primaries, D65WhitePoint)
// References:
// - ITU-R BT.709-6: Parameter values for the HDTV standard for production and international
//   programme exchange.
// Column-major representation:
const mat3 BT709_TO_XYZ =
    mat3(0.41239080, 0.21263901, 0.01933082,
         0.35758434, 0.71516868, 0.11919478,
         0.18048079, 0.07219231, 0.95053216);

// Matrix values based on computeXYZMatrix(BT709Primaries, D65WhitePoint) inverted
// References:
// - ITU-R BT.709-6: Parameter values for the HDTV standard for production and international
//   programme exchange.
// Column-major representation:
const mat3 XYZ_TO_BT709 =
    mat3(3.24096994, -0.96924364, 0.05563008,
        -1.53738318,  1.87596750, -0.20397696,
        -0.49861076,  0.04155506,  1.05697151);

// YUV to RGB transform matrices (column-major format for OpenGL)
// ITU-R BT.709
const highp mat3 BT709_FULL_RANGE_YUV_TO_RGB =
    mat3(1.0000,  1.0000, 1.0000,
        0.0000, -0.1873, 1.8556,
        1.5748, -0.4681, 0.0000);
const highp mat3 BT709_LIMITED_RANGE_YUV_TO_RGB =
    mat3(1.1644,  1.1644, 1.1644,
        0.0000, -0.2132, 2.1124,
        1.7927, -0.5329, 0.0000);
// ITU-R BT.2020
const highp mat3 BT2020_FULL_RANGE_YUV_TO_RGB =
    mat3(1.0000,  1.0000, 1.0000,
        0.0000, -0.1646, 1.8814,
        1.4746, -0.5714, 0.0000);
const highp mat3 BT2020_LIMITED_RANGE_YUV_TO_RGB =
    mat3(1.1689,  1.1689, 1.1689,
        0.0000, -0.1881, 2.1502,
        1.6853, -0.6530, 0.0000);

// Direct linear gamut conversion matrices (column-major):
const highp mat3 BT709_TO_BT2020 = XYZ_TO_BT2020 * BT709_TO_XYZ;
const highp mat3 BT2020_TO_BT709 = XYZ_TO_BT709 * BT2020_TO_XYZ;

// Relative luminance weights for BT.2020 primaries (second row of BT2020_TO_XYZ).
const highp vec3 BT2020_LUMINANCE_WEIGHTS = vec3(0.26270021, 0.67799807, 0.05930172);

// ITU-R BT.2408 diffuse white reference, expressed in display-referred light.
//
// BT.2408 defines diffuse white as 203 nits of display light, which for HLG is the 75% signal
// level: hlgEotf(0.75) = pow(hlgInverseOetf(0.75), 1.2) = pow(0.26496256, 1.2) = 0.2031521
// (203.1521 nits on a nominal 1000-nit reference display).
//
// Scaling display light by HDR_DIFFUSE_WHITE_SCALE_UP makes 1.0 represent 203-nit diffuse white:
// - 1.0 = 203.15 nits (SDR reference white / HLG 75% / PQ 0.5807)
// - ~4.9224 = 1,000 nits (nominal HLG / HDR10 peak)
// - ~49.2242 = 10,000 nits (PQ absolute peak)
//
// The two factors are exact inverses, so scaling up on ingress and down on egress round-trips
// without loss.
// References:
// - ITU-R Report BT.2408: Guidance for operational practices in HDR television production.
const highp float HDR_DIFFUSE_WHITE_SCALE_DOWN = 0.2031521;  // hlgEotf(0.75) = 203.1521 / 1000.0
const highp float HDR_DIFFUSE_WHITE_SCALE_UP = 1.0 / HDR_DIFFUSE_WHITE_SCALE_DOWN;  // ~4.9224197

// BT.2100 / BT.2020 HLG Inverse OETF for 3-channel RGB.
// Converts non-linear electrical HLG values [0.0, 1.0] to normalized linear optical scene light
// [0.0, 1.0] in BT.2020 color space.
//
// References:
// - ITU-R Recommendation BT.2100-2 (Table 5: "Hybrid Log-Gamma reference OETF / EOTF"):
//   https://www.itu.int/dms_pubrec/itu-r/rec/bt/R-REC-BT.2100-3-202502-I!!PDF-E.pdf
// - Khronos Data Format Specification 1.3 (TRANSFER_HLG):
//   https://www.khronos.org/registry/DataFormat/specs/1.3/dataformat.1.3.inline.html#TRANSFER_HLG
// - Android RenderEngine ProgramCache:
//   https://cs.android.com/android/platform/superproject/+/master:frameworks/native/libs/renderengine/gl/ProgramCache.cpp;l=265-279;drc=de09f10aa504fd8066370591a00c9ff1cafbb7fa
//
// The constants a, b, c are specified by ITU-R BT.2100 to ensure:
// 1. C0 value continuity: Both branches equal 1/12 at the transition point (channel = 0.5).
// 2. C1 derivative smoothness: First derivatives match at channel = 0.5 (no slope kink).
// 3. Peak normalization: Peak electrical signal 1.0 evaluates to 1.0.
// a = 0.17883277
// b = 1.0 - 4.0 * a = 0.28466892
// c = 0.5 - a * ln(4.0 * a) = 0.55991073
highp vec3 hlgInverseOetf(highp vec3 hlgElectrical) {
  const highp float a = 0.17883277;
  const highp float b = 0.28466892;
  const highp float c = 0.55991073;
  highp vec3 lowBranch = (hlgElectrical * hlgElectrical) / 3.0;
  highp vec3 highBranch = (vec3(b) + exp((hlgElectrical - vec3(c)) / a)) / 12.0;
  return mix(lowBranch, highBranch, step(0.5, hlgElectrical));
}

// BT.2100 / BT.2020 HLG EOTF for 3-channel RGB.
// Converts non-linear electrical HLG values [0.0, 1.0] to normalized linear optical display light
// [0.0, 1.0] (where 1.0 = 1,000 nits) by applying OOTF(OETF^-1(E)) with nominal system gamma = 1.2.
highp vec3 hlgEotf(highp vec3 hlgElectrical) {
  highp vec3 sceneLinear = hlgInverseOetf(hlgElectrical);
  highp float sceneLuminanceY = max(dot(sceneLinear, BT2020_LUMINANCE_WEIGHTS), 1e-6);
  return sceneLinear * pow(sceneLuminanceY, 0.2);
}

// BT.2100 / BT.2020 HLG Inverse OOTF for 3-channel RGB.
// Converts normalized linear optical display light [0.0, 1.0] (1.0 = 1,000 nits) in BT.2020 color
// space to normalized linear optical scene light [0.0, 1.0] using the inverse HLG OOTF (gamma = 1.2,
// exponent = 1/1.2 - 1 = -1/6).
//
// Scene light values exceeding 1.0 (either from display light above 1,000 nits or saturated
// primaries near 1,000 nits where Y_d < 1.0) are scaled down to [0.0, 1.0] while preserving
// chromaticity.
// TODO(b/564898615): Roll off with the BT.2408 Annex 5 EETF instead of a single ratio, once the
//  mastering peak is known.
highp vec3 hlgInverseOotf(highp vec3 displayLinear) {
  displayLinear = max(displayLinear, vec3(0.0));
  highp float displayLuminanceY = max(dot(displayLinear, BT2020_LUMINANCE_WEIGHTS), 1e-6);
  highp vec3 sceneLinear = displayLinear * pow(displayLuminanceY, -0.16666667);
  return sceneLinear / max(1.0, max(sceneLinear.r, max(sceneLinear.g, sceneLinear.b)));
}

// BT.2100 / BT.2020 HLG OETF for 3-channel RGB.
// Converts linear optical scene light [0.0, 1.0] in BT.2020 color space to electrical HLG values
// [0.0, 1.0].
//
// References:
// - ITU-R Recommendation BT.2100-2 (Table 5: "Hybrid Log-Gamma reference OETF / EOTF"):
//   https://www.itu.int/dms_pubrec/itu-r/rec/bt/R-REC-BT.2100-3-202502-I!!PDF-E.pdf
// - Khronos Data Format Specification 1.3 (TRANSFER_HLG):
//   https://www.khronos.org/registry/DataFormat/specs/1.3/dataformat.1.3.inline.html#TRANSFER_HLG
// - Android RenderEngine ProgramCache:
//   https://cs.android.com/android/platform/superproject/+/master:frameworks/native/libs/renderengine/gl/ProgramCache.cpp;l=529-543;drc=de09f10aa504fd8066370591a00c9ff1cafbb7fa
highp vec3 hlgOetf(highp vec3 linearColor) {
  const highp float a = 0.17883277;
  const highp float b = 0.28466892;
  const highp float c = 0.55991073;
  highp vec3 lowBranch = sqrt(3.0 * max(linearColor, vec3(0.0)));
  highp vec3 highBranch = a * log(max(12.0 * linearColor - vec3(b), vec3(1e-6))) + vec3(c);
  return mix(lowBranch, highBranch, step(1.0 / 12.0, linearColor));
}

// BT.2100 / BT.2020 PQ (SMPTE ST 2084) EOTF for 3-channel RGB.
// Converts electrical PQ values [0.0, 1.0] to normalized linear display values [0.0, 1.0] (1.0 = 10,000 nits).
// References:
// - SMPTE ST 2084 / ITU-R BT.2100-2
// - Khronos Data Format Specification 1.3 (TRANSFER_PQ):
//   https://registry.khronos.org/DataFormat/specs/1.3/dataformat.1.3.inline.html#TRANSFER_PQ
// - Android RenderEngine ProgramCache:
//   https://cs.android.com/android/platform/superproject/+/master:frameworks/native/libs/renderengine/gl/ProgramCache.cpp;l=250-263;drc=de09f10aa504fd8066370591a00c9ff1cafbb7fa
highp vec3 pqEotf(highp vec3 pqElectricalColor) {
  // SMPTE ST 2084 constants and reciprocal exponents (1.0 / m1, 1.0 / m2):
  // m1 = 2610.0 / 16384.0  => invM1 = 1.0 / m1 = 16384.0 / 2610.0
  // m2 = 2523.0 / 32.0     => invM2 = 1.0 / m2 = 32.0 / 2523.0
  const highp float invM1 = 6.27739464;
  const highp float invM2 = 0.0126833135;
  const highp float c1 = 0.8359375;  // 3424.0 / 4096.0
  const highp float c2 = 18.8515625; // 2413.0 / 128.0
  const highp float c3 = 18.6875;    // 2392.0 / 128.0

  highp vec3 temp = pow(clamp(pqElectricalColor, 0.0, 1.0), vec3(invM2));
  temp = max(temp - vec3(c1), vec3(0.0)) / (vec3(c2) - c3 * temp);
  return pow(temp, vec3(invM1));
}

// Transforms linear optical BT.2020 scene light to linear optical BT.709 display light
// applying the HLG OOTF with luminance scaling (ITU-R BT.2100 Table 5).
//
// Background:
// HLG is scene-referred, while the SDR video ecosystem (sRGB/BT.709) is
// display-referred (expecting display-adapted electrical values for a standard monitor). This
// method applies HLG scene-light to display-light OOTF before converting color gamut.
//
// Expects optical scene light normalized to [0.0, 1.0].
//
// System Gamma Formula (ITU-R BT.2100-2 Table 5, Note 5b):
//   gamma = 1.2 + 0.42 * log10(L_W / 1000.0)
// where L_W is the nominal peak display luminance in nits (cd/m^2).
// For standard SDR display rendering, L_W is selected as 500 nits (typical mobile/desktop display):
//   gamma = 1.2 + 0.42 * log10(500 / 1000) = 1.2 + 0.42 * log10(0.5) = 1.0735674018211279
//
// Chromaticity Preservation:
// In CIE XYZ space, Y (linearXyz[1]) is perceptual luminance, while X and Z carry color ratios.
// Multiplying the entire XYZ vector by (Y)^(gamma - 1.0) scales the luminance to
// Y * Y^(gamma-1) = Y^gamma while perfectly preserving the X/Y and Z/Y ratios, resulting in zero
// hue shift or color distortion.
//
// References:
// - ITU-R BT.2100-2 ("HLG Reference OOTF"):
//   https://www.itu.int/dms_pubrec/itu-r/rec/bt/R-REC-BT.2100-3-202502-I!!PDF-E.pdf
// - Android native tone mapping (libtonemap):
//   https://cs.android.com/android/platform/superproject/+/master:frameworks/native/libs/tonemap/tonemap.cpp;drc=7a577450e536aa1e99f229a0cb3d3531c82e8a8d;l=62
highp vec3 linearBt2020SceneToLinearBt709Display(highp vec3 linearRgbBt2020) {
  const highp float hlgGamma = 1.0735674018211279;
  highp vec3 linearXyz = BT2020_TO_XYZ * linearRgbBt2020;
  // In GLSL, pow(x, y) is undefined for x <= 0.0 with fractional exponents. Guard against
  // zero or negative luminance to prevent NaN output on mobile GPUs.
  highp float luminanceY = max(linearXyz[1], 1e-6);
  linearXyz *= pow(luminanceY, hlgGamma - 1.0);
  return clamp((XYZ_TO_BT709 * linearXyz), 0.0, 1.0);
}

// Transforms electrical SDR to linear optical SDR using the sRGB EOTF.
// References:
// - IEC 61966-2-1: Multimedia systems and equipment - Colour measurement and management -
//   Part 2-1: Colour management - Default RGB colour space - sRGB
// - Khronos Data Format Specification 1.3 (TRANSFER_SRGB):
//   https://registry.khronos.org/DataFormat/specs/1.3/dataformat.1.3.inline.html#TRANSFER_SRGB
// - Android RenderEngine ProgramCache:
//   https://cs.android.com/android/platform/superproject/+/master:frameworks/native/libs/renderengine/gl/ProgramCache.cpp;l=265-279;drc=de09f10aa504fd8066370591a00c9ff1cafbb7fa
highp vec3 srgbEotf(highp vec3 electricalColor) {
  highp vec3 lowBranch = electricalColor / 12.92;
  highp vec3 highBranch = pow((electricalColor + vec3(0.055)) / 1.055, vec3(2.4));
  return mix(lowBranch, highBranch, step(0.04045, electricalColor));
}

// Transforms linear optical light to electrical SDR using the sRGB OETF.
// References:
// - IEC 61966-2-1: Multimedia systems and equipment - Colour measurement and management -
//   Part 2-1: Colour management - Default RGB colour space - sRGB
// - Khronos Data Format Specification 1.3 (TRANSFER_SRGB):
//   https://registry.khronos.org/DataFormat/specs/1.3/dataformat.1.3.inline.html#TRANSFER_SRGB
// - Android RenderEngine ProgramCache:
//   https://cs.android.com/android/platform/superproject/+/master:frameworks/native/libs/renderengine/gl/ProgramCache.cpp;l=281-285;drc=de09f10aa504fd8066370591a00c9ff1cafbb7fa
highp vec3 srgbOetf(highp vec3 opticalColor) {
  highp vec3 lowBranch = 12.92 * opticalColor;
  highp vec3 highBranch = 1.055 * pow(max(opticalColor, vec3(0.0)), vec3(1.0 / 2.4)) - vec3(0.055);
  return mix(lowBranch, highBranch, step(0.0031308, opticalColor));
}

// Transforms YUV electrical values to RGB electrical values in [0.0, 1.0].
highp vec3 yuvToRgb(highp vec3 yuv, int inputColorTransfer, int isInputColorRangeFull) {
  highp vec3 yuvOffset =
      (isInputColorRangeFull == 1) ? vec3(0.0, 0.5, 0.5) : vec3(0.0625, 0.5, 0.5);
  highp mat3 yuvToRgbMatrix;
  if (isTransferHdr(inputColorTransfer)) {
    yuvToRgbMatrix = (isInputColorRangeFull == 1)
        ? BT2020_FULL_RANGE_YUV_TO_RGB
        : BT2020_LIMITED_RANGE_YUV_TO_RGB;
  } else {
    // Default to BT.709 for SDR
    yuvToRgbMatrix = (isInputColorRangeFull == 1)
        ? BT709_FULL_RANGE_YUV_TO_RGB
        : BT709_LIMITED_RANGE_YUV_TO_RGB;
  }
  return clamp(yuvToRgbMatrix * (yuv - yuvOffset), 0.0, 1.0);
}

// Converts SDR electrical BT.709 to linear optical BT.2020 display light, in the linear HDR
// optical working space where 1.0 represents both SDR reference white and BT.2408 203-nit diffuse
// white.
highp vec3 sdrElectricalToHdrDisplayLinear(highp vec3 sdrElectricalColor) {
  return clamp(BT709_TO_BT2020 * srgbEotf(sdrElectricalColor), 0.0, 1.0);
}

// Converts HDR HLG electrical BT.2020 to linear optical BT.709 display light.
highp vec3 hlgElectricalToSdrDisplayLinear(highp vec3 hlgElectricalColor) {
  return linearBt2020SceneToLinearBt709Display(hlgInverseOetf(hlgElectricalColor));
}

// Converts HDR HLG electrical BT.2020 to linear optical BT.2020 display light, in the linear HDR
// optical working space where 1.0 represents the BT.2408 203-nit diffuse white reference.
highp vec3 hlgElectricalToHdrDisplayLinear(highp vec3 hlgElectricalColor) {
  return hlgEotf(hlgElectricalColor) * HDR_DIFFUSE_WHITE_SCALE_UP;
}

// Converts PQ electrical BT.2020 to linear optical BT.2020 display light, in the linear HDR
// optical working space where 1.0 represents the BT.2408 203-nit diffuse white reference.
highp vec3 pqElectricalToHdrDisplayLinear(highp vec3 pqElectricalColor) {
  // PQ reference peak is 10,000 nits, normalize against 1,000 nits
  highp vec3 displayLinear = pqEotf(pqElectricalColor) * 10.0;
  return displayLinear * HDR_DIFFUSE_WHITE_SCALE_UP;
}

// Converts linear optical BT.2020 display light (anchored at 1.0 = 203-nit diffuse white) to HLG
// electrical BT.2020.
// Egress from the linear HDR optical working space scales down by HDR_DIFFUSE_WHITE_SCALE_DOWN
// (normalizing 1,000 nits to 1.0), applies the inverse HLG OOTF to obtain scene light, and encodes
// with the HLG OETF.
highp vec3 hdrDisplayLinearToHlgElectrical(highp vec3 hdrDisplayLinear) {
  // Display light is referenced at 1000 nits.
  highp vec3 displayLinear = hdrDisplayLinear * HDR_DIFFUSE_WHITE_SCALE_DOWN;
  return hlgOetf(hlgInverseOotf(displayLinear));
}

// Converts PQ electrical BT.2020 to linear BT.709 display light.
// Compresses PQ display light (assumed to peak at 1,000 nits) down to 0-500 nits of SDR display
// light using the parametric tone-mapping curve from Report ITU-R BT.2446-1 (Section 6.1.4,
// Method C), then converts the color gamut to BT.709. The tone curve is driven by max(R, G, B),
// and all three channels are scaled by the same ratio so that chromaticity is preserved in linear
// space.
//
// Report ITU-R BT.2446-1, Method C defines a two-segment piecewise curve:
//   Y_SDR = k1 * Y_HDR                                   for Y_HDR < Y_HDR_ip
//   Y_SDR = k2 * ln(Y_HDR / Y_HDR_ip - k3) + k4          for Y_HDR >= Y_HDR_ip
//
// Its published constants target a 100/120-nit SDR display and are re-derived here for
// maxInputNits = 1000.0 and maxOutputNits = 500.0:
// 1. Linear pass-through (k1 = 1.0): Leaves BT.2408 diffuse white (203 nits) and midtones untouched
//    at 1:1 nits below the inflection point.
// 2. Inflection point (Y_HDR_ip = 292.5 nits): Placed at 80% of the SDR electrical range
//    (0.80^2.4 = 58.5% optical luminance, 292.5 nits).
// 3. C1 derivative smoothness at Y_HDR_ip: k2 = k1 * (1.0 - k3) * Y_HDR_ip = 98.8682714.
// 4. C0 value continuity at Y_HDR_ip: k4 = k1 * Y_HDR_ip - k2 * ln(1.0 - k3) = 399.7400703.
// 5. Peak normalization Y_SDR(1000.0) = 500.0: Solves k3 = 0.6619888.
highp vec3 pqElectricalToBt709DisplayLinear(highp vec3 pqElectricalColor) {
  highp vec3 nitsIn = pqEotf(pqElectricalColor) * 10000.0;
  highp float maxColorIn = max(nitsIn.r, max(nitsIn.g, nitsIn.b));
  if (maxColorIn <= 0.0) {
    return vec3(0.0);
  }
  // TODO(b/314971953): Use max_display_mastering_luminance from ColorInfo.hdrStaticInfo in the bitstream instead.
  const highp float maxInputNits = 1000.0;
  const highp float maxOutputNits = 500.0;
  highp float nits = min(maxColorIn, maxInputNits);

  const highp float k1 = 1.0;
  const highp float k2 = 98.8682714;
  const highp float k3 = 0.6619888;
  const highp float k4 = 399.7400703;
  const highp float inflectionPointNits = 292.5;

  highp float lowBranch = k1 * nits;
  highp float highBranch = k2 * log(max(nits / inflectionPointNits - k3, 1e-6)) + k4;
  highp float maxColorOut = mix(lowBranch, highBranch, step(inflectionPointNits, nits));

  // Chromaticity preservation:
  // Scaling linear RGB by the tone mapping ratio (maxColorOut / maxColorIn) preserves original
  // chromaticity in linear space, and dividing by maxOutputNits normalizes to [0.0, 1.0].
  highp vec3 displayLinearBt2020 = nitsIn * (maxColorOut / (maxColorIn * maxOutputNits));

  // Direct color gamut conversion from BT.2020 display light to BT.709 display light.
  // Deliberately unclamped: BT.2020 colors outside the BT.709 gamut map to negative components,
  // and clamping them per-channel shifts hue rather than reducing saturation. The 8-bit output
  // path clamps in hardware and srgbOetf tolerates negatives, so out-of-gamut values only survive
  // on the high-precision linear output path.
  // TODO(b/545591397): Apply perceptual soft gamut compression instead.
  return BT2020_TO_BT709 * displayLinearBt2020;
}

// Processes input electrical color (SDR BT.709 or HDR BT.2020), applying EOTF/OETF
// transformations and tone mapping based on the requested output color gamut and transfer.
// Linear outputs (COLOR_TRANSFER_LINEAR) are always in display-referred light.
// TODO(b/549154420): Measure performance cost of branches in fragment shaders versus simpler
// specialized shader variants generated in Java.
highp vec3 processColor(
    highp vec3 inputRgbElectricalColor,
    int outputColorGamut,
    int inputColorTransfer,
    int outputColorTransfer) {
  bool isInputHdr = isTransferHdr(inputColorTransfer);
  bool isOutputHdr = isGamutHdr(outputColorGamut);

  // 1. SDR -> SDR (Same gamut, pass-through or linearize)
  if (!isInputHdr && !isOutputHdr) {
    return (outputColorTransfer == COLOR_TRANSFER_LINEAR)
        ? srgbEotf(inputRgbElectricalColor)
        : inputRgbElectricalColor;
  }

  // 2. SDR -> HDR (Gamut expansion to display-referred linear HDR; inverse OOTF + OETF if HLG output)
  // sdrElectricalToHdrDisplayLinear anchors SDR reference white (1.0) directly on the BT.2408
  // 203-nit diffuse white reference (1.0). Egress to HLG scales down to [0.0, 1.0], applies the
  // inverse HLG OOTF to obtain scene light, and encodes with the HLG OETF (mapping 1.0 to 0.75).
  // TODO(b/545590806): Support PQ (ST 2084) HDR output processing.
  if (!isInputHdr && isOutputHdr) {
    highp vec3 hdrDisplayLinear = sdrElectricalToHdrDisplayLinear(inputRgbElectricalColor);
    return (outputColorTransfer == COLOR_TRANSFER_LINEAR)
        ? hdrDisplayLinear
        : hdrDisplayLinearToHlgElectrical(hdrDisplayLinear);
  }

  // 3. HDR -> HDR (Same gamut, pass-through or linearize to display light)
  if (isInputHdr && isOutputHdr) {
    if (inputColorTransfer == COLOR_TRANSFER_ST2084) {
      highp vec3 hdrDisplayLinear = pqElectricalToHdrDisplayLinear(inputRgbElectricalColor);
      if (outputColorTransfer == COLOR_TRANSFER_LINEAR) {
        return hdrDisplayLinear;
      } else if (outputColorTransfer == COLOR_TRANSFER_HLG) {
        return hdrDisplayLinearToHlgElectrical(hdrDisplayLinear);
      }
      // Green for visible error.
      return vec3(0.0f, 1.0f, 0.0f);
    }
    // HLG input
    return (outputColorTransfer == COLOR_TRANSFER_LINEAR)
        ? hlgElectricalToHdrDisplayLinear(inputRgbElectricalColor)
        : inputRgbElectricalColor;
  }

  // 4. HDR -> SDR (Tone-mapping + Gamut reduction)
  // This path never enters the linear working space, so the BT.2408 ingress scale-up and egress
  // scale-down cancel out and no scaling is applied.
  highp vec3 sdrDisplayLinear = (inputColorTransfer == COLOR_TRANSFER_ST2084)
      ? pqElectricalToBt709DisplayLinear(inputRgbElectricalColor)
      : hlgElectricalToSdrDisplayLinear(inputRgbElectricalColor);
  return (outputColorTransfer == COLOR_TRANSFER_LINEAR)
      ? sdrDisplayLinear
      : srgbOetf(sdrDisplayLinear);
}
