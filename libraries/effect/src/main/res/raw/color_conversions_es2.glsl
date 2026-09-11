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
