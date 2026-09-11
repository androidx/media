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

// Matrix values based on computeXYZMatrix(BT709Primaries, D65WhitePoint) inverted
// References:
// - ITU-R BT.709-6: Parameter values for the HDTV standard for production and international programme exchange.
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

// BT.2100 / BT.2020 HLG EOTF (Electro-Optical Transfer Function) for one channel.
// Converts scene-referred non-linear electrical values [0.0, 1.0] to linear optical values [0.0, 1.0].
// References:
// - ITU-R Recommendation BT.2100-2 (Table 5: "Hybrid Log-Gamma reference OETF / EOTF"):
//   https://www.itu.int/dms_pubrec/itu-r/rec/bt/R-REC-BT.2100-2-201807-I!!PDF-E.pdf
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
highp float hlgEotfSingleChannel(highp float hlgChannelElectrical) {
  const highp float a = 0.17883277;
  const highp float b = 0.28466892;
  const highp float c = 0.55991073;
  return hlgChannelElectrical <= 0.5
      ? (hlgChannelElectrical * hlgChannelElectrical) / 3.0
      : (b + exp((hlgChannelElectrical - c) / a)) / 12.0;
}

// BT.2100 / BT.2020 HLG EOTF for 3-channel RGB.
// Converts HLG electrical RGB to linear optical scene light in BT.2020 color space.
highp vec3 hlgEotf(highp vec3 hlgElectrical) {
  return vec3(hlgEotfSingleChannel(hlgElectrical.r),
              hlgEotfSingleChannel(hlgElectrical.g),
              hlgEotfSingleChannel(hlgElectrical.b));
}

// Applies the HLG BT.2020 to BT.709 OOTF (Opto-Optical Transfer Function) for tone mapping.
//
// Background:
// HLG is scene-referred, while the SDR video ecosystem (sRGB/BT.709) is
// display-referred (expecting display-adapted electrical values for a standard monitor). This
// method applies HLG scene-light to display-light OOTF before converting color gamut.
//
// System Gamma Formula (ITU-R BT.2100-2 Table 5, Note 5b):
//   gamma = 1.2 + 0.42 * log10(L_W / 1000.0)
// where L_W is the nominal peak display luminance in nits (cd/m^2).
// For standard SDR display rendering, L_W is selected as 500 nits (typical mobile/desktop display):
//   gamma = 1.2 + 0.42 * log10(500 / 1000) = 1.2 + 0.42 * log10(0.5) = 1.0735674018211279
//
// Chromaticity Preservation:
// In CIE XYZ space, Y (linearXyz[1]) is perceptual luminance, while X and Z carry color ratios.
// Multiplying the entire XYZ vector by (Y)^(gamma - 1.0) scales the luminance to Y * Y^(gamma-1) = Y^gamma
// while perfectly preserving the X/Y and Z/Y ratios, resulting in zero hue shift or color distortion.
//
// References:
// - ITU-R BT.2100-2 ("HLG Reference OOTF"):
//   https://www.itu.int/dms_pubrec/itu-r/rec/bt/R-REC-BT.2100-2-201807-I!!PDF-E.pdf
// - Android native tone mapping (libtonemap):
//   https://cs.android.com/android/platform/superproject/+/master:frameworks/native/libs/tonemap/tonemap.cpp;drc=7a577450e536aa1e99f229a0cb3d3531c82e8a8d;l=62
highp vec3 applyHlgBt2020ToBt709Ootf(highp vec3 linearRgbBt2020) {
  const highp float hlgGamma = 1.0735674018211279;
  highp vec3 linearXyz = BT2020_TO_XYZ * linearRgbBt2020;
  // In GLSL, pow(x, y) is undefined for x <= 0.0 with fractional exponents. Guard against
  // zero or negative luminance to prevent NaN output on mobile GPUs.
  highp float luminanceY = max(linearXyz[1], 0.0);
  if (luminanceY > 0.0) {
    linearXyz = linearXyz * pow(luminanceY, hlgGamma - 1.0);
  }
  return clamp((XYZ_TO_BT709 * linearXyz), 0.0, 1.0);
}

// Transforms a single channel from electrical SDR to linear optical SDR using the sRGB EOTF.
// References:
// - IEC 61966-2-1: Multimedia systems and equipment - Colour measurement and management - Part 2-1: Colour management - Default RGB colour space - sRGB
// - Khronos Data Format Specification 1.3 (TRANSFER_SRGB):
//   https://registry.khronos.org/DataFormat/specs/1.3/dataformat.1.3.inline.html#TRANSFER_SRGB
// - Android RenderEngine ProgramCache:
//   https://cs.android.com/android/platform/superproject/+/master:frameworks/native/libs/renderengine/gl/ProgramCache.cpp;l=265-279;drc=de09f10aa504fd8066370591a00c9ff1cafbb7fa
highp float srgbEotfSingleChannel(highp float electricalChannel) {
  return electricalChannel <= 0.04045
      ? electricalChannel / 12.92
      : pow((electricalChannel + 0.055) / 1.055, 2.4);
}

// Transforms electrical SDR to linear optical SDR using the sRGB EOTF.
highp vec3 srgbEotf(highp vec3 electricalColor) {
  return vec3(srgbEotfSingleChannel(electricalColor.r),
              srgbEotfSingleChannel(electricalColor.g),
              srgbEotfSingleChannel(electricalColor.b));
}

// Transforms a single channel from linear optical to electrical SDR using the sRGB OETF.
// References:
// - IEC 61966-2-1: Multimedia systems and equipment - Colour measurement and management - Part 2-1: Colour management - Default RGB colour space - sRGB
// - Khronos Data Format Specification 1.3 (TRANSFER_SRGB):
//   https://registry.khronos.org/DataFormat/specs/1.3/dataformat.1.3.inline.html#TRANSFER_SRGB
// - Android RenderEngine ProgramCache:
//   https://cs.android.com/android/platform/superproject/+/master:frameworks/native/libs/renderengine/gl/ProgramCache.cpp;l=281-285;drc=de09f10aa504fd8066370591a00c9ff1cafbb7fa
highp float srgbOetfSingleChannel(highp float opticalColor) {
  return opticalColor <= 0.0031308
      ? 12.92 * opticalColor
      : 1.055 * pow(opticalColor, 1.0 / 2.4) - 0.055;
}

// Transforms linear optical light to electrical SDR using the sRGB OETF.
highp vec3 srgbOetf(highp vec3 opticalColor) {
  return vec3(srgbOetfSingleChannel(opticalColor.r),
              srgbOetfSingleChannel(opticalColor.g),
              srgbOetfSingleChannel(opticalColor.b));
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

// Processes SDR electrical color to the requested output transfer (linear optical or sRGB electrical).
highp vec3 processSdrColor(highp vec3 sdrElectricalColor, int outputColorTransfer) {
  if (outputColorTransfer == COLOR_TRANSFER_LINEAR) {
    return srgbEotf(sdrElectricalColor);
  }
  // Pass through electrical SDR (sRGB)
  return sdrElectricalColor;
}

// Processes HDR electrical color to the requested HDR output transfer (linear optical or HLG electrical).
// TODO(b/545591397): Support PQ (ST 2084) HDR output processing.
highp vec3 processHdrColor(highp vec3 hdrElectricalColor, int outputColorTransfer) {
  if (outputColorTransfer == COLOR_TRANSFER_LINEAR) {
    return hlgEotf(hdrElectricalColor);
  }
  // Pass through electrical HDR (HLG)
  return hdrElectricalColor;
}

// Tone maps HDR electrical BT.2020 to SDR optical / electrical based on output transfer.
// TODO(b/545591397): Support PQ (ST 2084) tone mapping.
highp vec3 toneMapHdrToSdr(highp vec3 hdrElectricalColor, int outputColorTransfer) {
  highp vec3 linearBt2020 = hlgEotf(hdrElectricalColor);
  highp vec3 toneMappedLinearBt709 = applyHlgBt2020ToBt709Ootf(linearBt2020);
  if (outputColorTransfer == COLOR_TRANSFER_LINEAR) {
    return toneMappedLinearBt709;
  }
  return srgbOetf(toneMappedLinearBt709);
}

// Processes input electrical color (SDR BT.709 or HDR BT.2020), applying EOTF/OETF
// transformations and tone mapping based on the requested output color gamut and transfer.
highp vec3 processColor(
    highp vec3 inputRgbElectricalColor,
    int outputColorGamut,
    int inputColorTransfer,
    int outputColorTransfer) {
  bool isInputHdr = isTransferHdr(inputColorTransfer);
  bool isOutputHdr = isGamutHdr(outputColorGamut);

  if (!isInputHdr) {
    // TODO(b/545591199): Support SDR to HDR upsampling when isOutputHdr is true.
    return processSdrColor(inputRgbElectricalColor, outputColorTransfer);
  }

  // HDR Input (BT.2020)
  if (isOutputHdr) {
    // HDR Output (Branch reserved for future HDR preservation / egress)
    return processHdrColor(inputRgbElectricalColor, outputColorTransfer);
  }

  // Tone map HDR BT.2020 to SDR BT.709
  return toneMapHdrToSdr(inputRgbElectricalColor, outputColorTransfer);
}
