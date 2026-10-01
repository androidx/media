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

// ES 3 fragment shader that converts frames from the working color space, which can be linear
// optical or electrical, to an electrical output color space.
//
// The output gamut is implied by the output transfer: BT.2020 for HLG, BT.709 for sRGB. Converting
// between SDR and HDR therefore also converts the gamut, and tone maps HDR to SDR with soft gamut
// compression and ITU-R BT.2446 Method C, or maps SDR to HDR via the BT.2408 diffuse white anchor.
//
// This is not a well-formed GLSL shader on its own and must be compiled together with
// color_conversions_es3.glsl (which provides #version, precision, and color conversion functions).

uniform sampler2D uTexSampler;
// C.java#ColorSpace value of the input working color space. Only used to decode the input: the
// input gamut is not preserved, because the output gamut is implied by uOutputColorTransfer.
uniform int uInputColorGamut;
// C.java#ColorTransfer value of the input working color space. COLOR_TRANSFER_LINEAR denotes
// display-referred optical light: BT.2020 anchored on the BT.2408 diffuse white reference, or
// BT.709 anchored on SDR reference white.
uniform int uInputColorTransfer;
// C.java#ColorTransfer value of the electrical output. Only COLOR_TRANSFER_HLG and
// COLOR_TRANSFER_SRGB are supported; any other transfer outputs magenta. The output gamut is
// implied by the transfer: BT.2020 for HLG, BT.709 for sRGB.
uniform int uOutputColorTransfer;
in vec2 vTexSamplingCoord;
out vec4 outColor;

// Decodes the input working color space to linear optical display light, in the input gamut.
highp vec3 toOpticalColor(highp vec3 inputColor) {
  if (uInputColorTransfer == COLOR_TRANSFER_LINEAR) {
    return inputColor;
  }
  if (isTransferHdr(uInputColorTransfer)) {
    return hlgElectricalToHdrDisplayLinear(inputColor);
  }
  return srgbEotf(inputColor);
}

void main() {
  highp vec4 inputColor = texture(uTexSampler, vTexSamplingCoord);
  highp vec3 opticalColor = toOpticalColor(inputColor.rgb);
  highp vec3 electricalColor;
  if (uOutputColorTransfer == COLOR_TRANSFER_HLG) {
    // BT.2020 HLG output. SDR reference white maps onto the BT.2408 diffuse white anchor.
    highp vec3 hdrDisplayLinear = isGamutHdr(uInputColorGamut)
        ? opticalColor
        : clamp(BT709_TO_BT2020 * opticalColor, 0.0, 1.0);
    electricalColor = hdrDisplayLinearToHlgElectrical(hdrDisplayLinear);
  } else if (uOutputColorTransfer == COLOR_TRANSFER_SRGB) {
    // BT.709 sRGB output. HDR display light is scaled from its BT.2408 diffuse white anchor
    // (1.0 = 203.1521 nits) to nits, which bt2020DisplayLinearNitsToBt709DisplayLinear expects.
    highp vec3 sdrDisplayLinear = isGamutHdr(uInputColorGamut)
        ? bt2020DisplayLinearNitsToBt709DisplayLinear(
            opticalColor * HDR_DIFFUSE_WHITE_SCALE_DOWN * 1000.0)
        : opticalColor;
    electricalColor = srgbOetf(sdrDisplayLinear);
  } else {
    // Unsupported output transfer. Output magenta to make the error visible.
    outColor = vec4(1.0, 0.0, 1.0, 1.0);
    return;
  }
  outColor = vec4(electricalColor, inputColor.a);
}
