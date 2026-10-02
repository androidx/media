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

// ES 3 fragment shader that samples from an internal 2D SDR base texture and a
// 2D Ultra HDR gainmap texture, reconstructs the HDR image, and converts to the
// requested HDR output color transfer (BT.2020 linear or HLG).
//
// This is not a well-formed GLSL shader on its own and must be compiled together
// with color_conversions_es3.glsl (which provides #version, precision, and color
// conversion functions).

uniform sampler2D uTexSampler;
uniform sampler2D uGainmapTexSampler;
uniform int uOutputColorTransfer;

// Gainmap uniforms set by GainmapUtil.setGainmapUniforms (see android.graphics.Gainmap).
// 1 (GL_TRUE) if the gainmap texture is single-channel ALPHA_8 (stored in .a), 0 (GL_FALSE) if RGB.
uniform int uGainmapIsAlpha;
// 1 (GL_TRUE) if uGainmapGamma is (1.0, 1.0, 1.0) so gamma decoding can be skipped, 0 otherwise.
uniform int uNoGamma;
// 1 (GL_TRUE) if gamma, ratioMin, and ratioMax are identical across R, G, and B channels.
uniform int uSingleChannel;
// Natural logarithm of the minimum gainmap boost ratio per channel: ln(Gainmap.getRatioMin()).
uniform highp vec3 uLogRatioMin;
// Natural logarithm of the maximum gainmap boost ratio per channel: ln(Gainmap.getRatioMax()).
uniform highp vec3 uLogRatioMax;
// Offset added to linear SDR values before applying gain to avoid division by zero near black.
uniform highp vec3 uEpsilonSdr;
// Offset subtracted from reconstructed linear HDR values after applying gain.
uniform highp vec3 uEpsilonHdr;
// Gamma exponent applied to decode the normalized gainmap texture values: Gainmap.getGamma().
uniform highp vec3 uGainmapGamma;
// Display HDR/SDR ratio at which the full gainmap boost is applied:
// Gainmap.getDisplayRatioForFullHdr().
uniform highp float uDisplayRatioHdr;
// Minimum display HDR/SDR ratio at which gainmap boost begins:
// Gainmap.getMinDisplayRatioForHdrTransition().
uniform highp float uDisplayRatioSdr;

in vec2 vTexSamplingCoord;
out vec4 outColor;

// Applies the UltraHDR gainmap to the SDR electrical color to reconstruct the linear HDR image.
// The resulting linear optical BT.709 display light has 1.0 anchored at 203.1521-nit diffuse white
// and highlights boosted up to HDR_DIFFUSE_WHITE_SCALE_UP (~4.9224 = 1,000 nits).
// References:
// - Android Gainmap specification:
//   https://developer.android.com/reference/android/graphics/Gainmap#applying-a-gainmap-manually
// - ISO 21496-1 / libultrahdr gainmapmath.
highp vec3 applyGainmap(highp vec3 sdrElectricalColor, highp vec4 gainmapPixel) {
  // 1. Linearize the electrical sRGB base image to optical BT.709 display light (1.0 = 203 nits).
  highp vec3 sdrDisplayLinear = srgbEotf(sdrElectricalColor);

  // 2. Unpack the normalized [0, 1] gainmap value and decode its gamma curve if needed.
  highp vec3 gainmapValue = (uGainmapIsAlpha == 1) ? vec3(gainmapPixel.a) : gainmapPixel.rgb;
  if (uSingleChannel == 1) {
    gainmapValue = vec3(gainmapValue.r);
  }
  if (uNoGamma != 1) {
    gainmapValue = pow(max(gainmapValue, vec3(0.0)), uGainmapGamma);
  }

  // 3. Interpolate the logarithmic gain factor between ln(ratioMin) and ln(ratioMax).
  highp vec3 logBoost = mix(uLogRatioMin, uLogRatioMax, gainmapValue);

  // 4. Compute the display headroom weight in [0, 1] by mapping our target HDR/SDR ratio
  // (1000 nits / 203 nits = HDR_DIFFUSE_WHITE_SCALE_UP) into [uDisplayRatioSdr, uDisplayRatioHdr].
  highp float headroomWeight = clamp(
      (log(HDR_DIFFUSE_WHITE_SCALE_UP) - log(uDisplayRatioSdr)) /
          max(log(uDisplayRatioHdr) - log(uDisplayRatioSdr), 1e-6),
      0.0, 1.0);

  // 5. Convert the weighted log boost back to a linear multiplier and scale the SDR base color.
  highp vec3 linearBoost = exp(logBoost * headroomWeight);
  highp vec3 boostedBt709DisplayLinear =
      (sdrDisplayLinear + uEpsilonSdr) * linearBoost - uEpsilonHdr;
  return max(boostedBt709DisplayLinear, vec3(0.0));
}

void main() {
  highp vec4 inputColor = texture(uTexSampler, vTexSamplingCoord);
  highp vec4 gainmapPixel = texture(uGainmapTexSampler, vTexSamplingCoord);

  highp vec3 boostedBt709DisplayLinear = applyGainmap(inputColor.rgb, gainmapPixel);
  highp vec3 bt2020DisplayLinear = max(BT709_TO_BT2020 * boostedBt709DisplayLinear, vec3(0.0));

  // This code path only outputs HDR (BT.2020 linear or HLG). When the target output is SDR,
  // HardwareBufferToGlTextureConverter copies the base SDR image directly without gainmap
  // application.
  highp vec3 processedRgb =
      (uOutputColorTransfer == COLOR_TRANSFER_LINEAR)
          ? bt2020DisplayLinear
          : hdrDisplayLinearToHlgElectrical(bt2020DisplayLinear);

  outColor = vec4(processedRgb, inputColor.a);
}
