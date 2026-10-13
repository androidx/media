#version 100
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

// Shared SDR color science conversion functions for OpenGL ES 2.0.
// This file is included as the base fragment shader resource and provides
// #version, #extension, and precision declarations.

#extension GL_OES_EGL_image_external : enable
precision mediump float;

// LINT.IfChange(color_space)
const int COLOR_SPACE_BT709 = 1;
const int COLOR_SPACE_BT601 = 2;
// LINT.ThenChange(../../../../../common/src/main/java/androidx/media3/common/C.java:color_space)

// LINT.IfChange(color_transfer)
const int COLOR_TRANSFER_LINEAR = 1;
const int COLOR_TRANSFER_SRGB = 2;
// LINT.ThenChange(../../../../../common/src/main/java/androidx/media3/common/C.java:color_transfer)

// Matrix values based on computeXYZMatrix(BT601_525Primaries, D65WhitePoint) and
// computeXYZMatrix(BT709Primaries, D65WhitePoint) inverted.
// Direct BT.601 (SMPTE 170M 525) to BT.709 linear optical conversion matrix.
// Column-major representation:
const mat3 BT601_TO_BT709 =
    mat3( 0.93954206,  0.01777222, -0.00162160,
          0.05018136,  0.96579286, -0.00436975,
          0.01027660,  0.01643490,  1.00599135);

// YUV to RGB transform matrices (column-major format for OpenGL)
const mat3 BT601_LIMITED_RANGE_YUV_TO_RGB =
    mat3(1.1644,  1.1644, 1.1644,
         0.0000, -0.3918, 2.0172,
         1.5960, -0.8130, 0.0000);
const mat3 BT601_FULL_RANGE_YUV_TO_RGB =
    mat3(1.0000,  1.0000, 1.0000,
         0.0000, -0.3441, 1.7720,
         1.4020, -0.7141, 0.0000);
const mat3 BT709_LIMITED_RANGE_YUV_TO_RGB =
    mat3(1.1644,  1.1644, 1.1644,
         0.0000, -0.2132, 2.1124,
         1.7927, -0.5329, 0.0000);
const mat3 BT709_FULL_RANGE_YUV_TO_RGB =
    mat3(1.0000,  1.0000, 1.0000,
         0.0000, -0.1873, 1.8556,
         1.5748, -0.4681, 0.0000);

// YUV to RGB offset vectors (subtracted from YUV values before multiplying them
// by the YUV-to-RGB matrix):
// - For Y, limited-range values are in [16, 235] (offset by 16), while
//   full-range values are in [0, 255] (offset by 0).
// - For U and V, values in both limited and full range are in [-128, +127]
//   (biased by +128 into unsigned bytes, so both offset by 128).
const vec3 LIMITED_RANGE_YUV_OFFSET =
    vec3(16.0 / 255.0, 128.0 / 255.0, 128.0 / 255.0);
const vec3 FULL_RANGE_YUV_OFFSET = vec3(0.0, 128.0 / 255.0, 128.0 / 255.0);

// Transforms YUV electrical values to RGB electrical values in [0.0, 1.0].
vec3 yuvElectricalToRgbElectrical(
    vec3 yuv, int inputColorGamut, int isInputColorRangeFull) {
  vec3 yuvOffset = (isInputColorRangeFull == 1) ? FULL_RANGE_YUV_OFFSET
                                                : LIMITED_RANGE_YUV_OFFSET;
  mat3 yuvToRgbMatrix;
  if (inputColorGamut == COLOR_SPACE_BT601) {
    yuvToRgbMatrix = (isInputColorRangeFull == 1)
                         ? BT601_FULL_RANGE_YUV_TO_RGB
                         : BT601_LIMITED_RANGE_YUV_TO_RGB;
  } else {
    // Default to BT.709 for SDR
    yuvToRgbMatrix = (isInputColorRangeFull == 1)
                         ? BT709_FULL_RANGE_YUV_TO_RGB
                         : BT709_LIMITED_RANGE_YUV_TO_RGB;
  }
  return clamp(yuvToRgbMatrix * (yuv - yuvOffset), 0.0, 1.0);
}

// Transforms electrical SDR to linear optical SDR using the vectorized sRGB EOTF.
// References:
// - IEC 61966-2-1: Multimedia systems and equipment - Colour measurement and management - Part 2-1: Colour management - Default RGB colour space - sRGB
// - Khronos Data Format Specification 1.3 (TRANSFER_SRGB):
//   https://registry.khronos.org/DataFormat/specs/1.3/dataformat.1.3.inline.html#TRANSFER_SRGB
vec3 srgbEotf(vec3 electricalColor) {
  vec3 linearLow = electricalColor / 12.92;
  vec3 linearHigh = pow((max(electricalColor, vec3(0.0)) + vec3(0.055)) / 1.055, vec3(2.4));
  return mix(linearLow, linearHigh, step(vec3(0.04045), electricalColor));
}

// Transforms linear optical light to electrical SDR using the vectorized sRGB OETF.
// References:
// - IEC 61966-2-1: Multimedia systems and equipment - Colour measurement and management - Part 2-1: Colour management - Default RGB colour space - sRGB
// - Khronos Data Format Specification 1.3 (TRANSFER_SRGB):
//   https://registry.khronos.org/DataFormat/specs/1.3/dataformat.1.3.inline.html#TRANSFER_SRGB
vec3 srgbOetf(vec3 opticalColor) {
  vec3 clampedOpticalColor = max(opticalColor, vec3(0.0));
  vec3 electricalLow = 12.92 * clampedOpticalColor;
  vec3 electricalHigh = 1.055 * pow(clampedOpticalColor, vec3(1.0 / 2.4)) - vec3(0.055);
  return mix(electricalLow, electricalHigh, step(vec3(0.0031308), clampedOpticalColor));
}

// Converts linear optical light from BT.601 gamut to BT.709 gamut.
vec3 convertBt601ToBt709(vec3 linearBt601) {
  return clamp(BT601_TO_BT709 * linearBt601, 0.0, 1.0);
}

// Processes SDR electrical color, assuming sRGB input transfer, applying optional BT.601 to
// BT.709 gamut transformation, and converting to the requested output transfer (linear or sRGB electrical).
vec3 processColor(
    vec3 inputRgbElectricalColor,
    int inputColorGamut,
    int outputColorGamut,
    int outputColorTransfer) {
  bool needsGamutConversion =
      (inputColorGamut == COLOR_SPACE_BT601 && outputColorGamut == COLOR_SPACE_BT709);

  // Pass through when neither gamut conversion nor linear output is requested.
  if (outputColorTransfer != COLOR_TRANSFER_LINEAR && !needsGamutConversion) {
    return inputRgbElectricalColor;
  }

  vec3 linearOpticalColor = srgbEotf(inputRgbElectricalColor);
  if (needsGamutConversion) {
    linearOpticalColor = convertBt601ToBt709(linearOpticalColor);
  }

  if (outputColorTransfer == COLOR_TRANSFER_LINEAR) {
    return linearOpticalColor;
  }
  return srgbOetf(linearOpticalColor);
}
