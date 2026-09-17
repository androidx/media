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

// ITU-R BT.2408 diffuse white reference, expressed in scene-referred light.
//
// BT.2408 defines diffuse white as 203 nits of display light, which for HLG is the 75% signal
// level. This pipeline works in scene-referred light: hlgEotf is the inverse OETF, and the SDR and
// PQ ingress paths apply an inverse OOTF to reach scene light. The anchor is therefore expressed
// in scene light as well, as hlgEotf(0.75) = 0.2649626. The HLG scene-to-display OOTF maps that
// to 1000.0 * pow(0.2649626, 1.2) = 203 nits on a nominal 1000-nit display.
//
// Scaling scene light by HLG_DIFFUSE_WHITE_SCALE_UP makes 1.0 represent diffuse white, and peak
// scene light evaluate to ~3.7741.
//
// The two factors are exact inverses, so scaling up on ingress and down on egress round-trips
// without loss.
// References:
// - ITU-R Report BT.2408: Guidance for operational practices in HDR television production.
const highp float HLG_DIFFUSE_WHITE_SCALE_DOWN = 0.2649626;  // hlgEotf(0.75)
const highp float HLG_DIFFUSE_WHITE_SCALE_UP = 1.0 / HLG_DIFFUSE_WHITE_SCALE_DOWN;  // ~3.7741

// BT.2100 / BT.2020 HLG EOTF for 3-channel RGB.
// Converts scene-referred non-linear electrical values [0.0, 1.0] to linear optical scene light
// in BT.2020 color space.
//
// BT.2100 technically defines the HLG EOTF as OOTF(OETF^-1(E)), which lands in display light.
// This function applies only OETF^-1, so that all processing is done in scene light.
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
highp vec3 hlgEotf(highp vec3 hlgElectrical) {
  const highp float a = 0.17883277;
  const highp float b = 0.28466892;
  const highp float c = 0.55991073;
  highp vec3 lowBranch = (hlgElectrical * hlgElectrical) / 3.0;
  highp vec3 highBranch = (vec3(b) + exp((hlgElectrical - vec3(c)) / a)) / 12.0;
  return mix(lowBranch, highBranch, step(0.5, hlgElectrical));
}

// BT.2100 / BT.2020 HLG OETF for 3-channel RGB.
// Converts linear optical scene light in BT.2020 color space to electrical HLG values [0.0, 1.0].
//
// Expects optical scene light normalized to [0.0, 1.0]. Callers converting from the linear HDR
// optical working space (where 1.0 represents the BT.2408 203-nit diffuse white reference) must
// scale the optical signal down by HLG_DIFFUSE_WHITE_SCALE_DOWN first, to normalize it back to
// [0.0, 1.0].
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

// Transforms linear optical BT.2020 scene light to linear optical BT.709 display light
// applying the HLG OOTF with luminance scaling (ITU-R BT.2100 Table 5).
//
// Background:
// HLG is scene-referred, while the SDR video ecosystem (sRGB/BT.709) is
// display-referred (expecting display-adapted electrical values for a standard monitor). This
// method applies HLG scene-light to display-light OOTF before converting color gamut.
//
// Expects optical scene light normalized to [0.0, 1.0]. Callers converting from the linear HDR
// optical working space (where 1.0 represents the BT.2408 203-nit diffuse white reference) must
// scale the optical signal down by HLG_DIFFUSE_WHITE_SCALE_DOWN first, to normalize it back to
// [0.0, 1.0].
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
highp vec3 transformBt2020SceneToBt709Display(highp vec3 linearRgbBt2020) {
  const highp float hlgGamma = 1.0735674018211279;
  highp vec3 linearXyz = BT2020_TO_XYZ * linearRgbBt2020;
  // In GLSL, pow(x, y) is undefined for x <= 0.0 with fractional exponents. Guard against
  // zero or negative luminance to prevent NaN output on mobile GPUs.
  highp float luminanceY = max(linearXyz[1], 1e-6);
  linearXyz *= pow(luminanceY, hlgGamma - 1.0);
  return clamp((XYZ_TO_BT709 * linearXyz), 0.0, 1.0);
}

// Transforms linear optical BT.709 display light to linear optical BT.2020 scene light
// applying the inverse HLG OOTF with luminance scaling (ITU-R BT.2100 Table 5 / BT.2408 §5.1.1).
//
// System Gamma Formula:
//   Inverse HLG OOTF exponent: (1.0 / 1.2) - 1.0 = -1.0 / 6.0 = -0.16666667
// for a 1000-nit HLG reference display (ITU-R BT.2100-2 Table 5).
highp vec3 transformBt709DisplayToBt2020Scene(highp vec3 linearBt709Display) {
  const highp float hlgInverseOotfExponent = -0.16666667;
  linearBt709Display = clamp(linearBt709Display, 0.0, 1.0);
  highp vec3 linearXyz = BT709_TO_XYZ * linearBt709Display;
  // Guard against near-zero luminance to avoid NaN/Inf on mobile GPUs with negative exponent.
  highp float luminanceY = max(linearXyz[1], 1e-6);
  linearXyz *= pow(luminanceY, hlgInverseOotfExponent);
  return clamp((XYZ_TO_BT2020 * linearXyz), 0.0, 1.0);
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

// Converts SDR electrical BT.709 to linear optical BT.2020 scene light.
highp vec3 sdrElectricalToHdrSceneLinear(highp vec3 sdrElectricalColor) {
  return transformBt709DisplayToBt2020Scene(srgbEotf(sdrElectricalColor));
}

// Converts HDR electrical BT.2020 to linear optical BT.709 display light.
// TODO(b/545591397): Support PQ (ST 2084) tone mapping.
highp vec3 hdrElectricalToSdrDisplayLinear(highp vec3 hdrElectricalColor) {
  return transformBt2020SceneToBt709Display(hlgEotf(hdrElectricalColor));
}

// Processes input electrical color (SDR BT.709 or HDR BT.2020), applying EOTF/OETF
// transformations and tone mapping based on the requested output color gamut and transfer.
// HDR outputs are always in scene-referred light.
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

  // 2. SDR -> HDR (Gamut expansion + display to scene OOTF)
  // sdrElectricalToHdrSceneLinear anchors SDR reference white on diffuse white, so its output is
  // already in the linear HDR optical working space and needs no ingress scaling. Egress to an
  // electrical transfer scales down by HLG_DIFFUSE_WHITE_SCALE_DOWN, mapping SDR reference white
  // to the BT.2408 75% HLG signal level (203 nits) rather than to HLG peak.
  // TODO(b/545590806): Support PQ (ST 2084) HDR output processing.
  if (!isInputHdr && isOutputHdr) {
    highp vec3 hdrSceneLinear = sdrElectricalToHdrSceneLinear(inputRgbElectricalColor);
    return (outputColorTransfer == COLOR_TRANSFER_LINEAR)
        ? hdrSceneLinear
        : hlgOetf(hdrSceneLinear * HLG_DIFFUSE_WHITE_SCALE_DOWN);
  }

  // 3. HDR -> HDR (Same gamut, pass-through or linearize)
  // TODO(b/545590806): Support PQ (ST 2084) HDR output processing.
  if (isInputHdr && isOutputHdr) {
    // Scales optical scene light up by HLG_DIFFUSE_WHITE_SCALE_UP, so that 1.0 represents the
    // BT.2408 203-nit diffuse white reference and peak optical scene light evaluates to ~3.7741.
    // Egress back to an electrical transfer scales by HLG_DIFFUSE_WHITE_SCALE_DOWN, inverting this
    // exactly.
    return (outputColorTransfer == COLOR_TRANSFER_LINEAR)
        ? hlgEotf(inputRgbElectricalColor) * HLG_DIFFUSE_WHITE_SCALE_UP
        : inputRgbElectricalColor;
  }

  // 4. HDR -> SDR (Tone-mapping + Gamut reduction)
  // This path never enters the linear working space, so the BT.2408 ingress scale-up and egress
  // scale-down cancel out and no scaling is applied.
  // TODO(b/545591397): Support PQ (ST 2084) tone mapping.
  highp vec3 sdrDisplayLinear = hdrElectricalToSdrDisplayLinear(inputRgbElectricalColor);
  return (outputColorTransfer == COLOR_TRANSFER_LINEAR)
      ? sdrDisplayLinear
      : srgbOetf(sdrDisplayLinear);
}
