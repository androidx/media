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

// ES 2 fragment shader that samples a (non-external) RGBA texture with
// uTexSampler and packs each pair of horizontally adjacent input pixels into a
// single RGBA output texel in YUY2 (4:2:2) order (Y0, U, Y1, V): left luma,
// horizontally averaged U, right luma, and horizontally averaged V.
//
// This is not a well-formed GLSL shader on its own and must be compiled
// together with color_conversions_es2.glsl (which provides #version and color
// conversion functions).

// Override color_conversions_es2.glsl's mediump default when supported so
// sub-texel coordinate offsets remain accurate at high resolutions.
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#endif
varying vec2 vTexSamplingCoord;
uniform sampler2D uTexSampler;
// Half the width of an input texel, in normalized texture coordinates.
uniform float uHalfInputTexelWidth;

void main() {
  // Each output texel spans a horizontal pair of input pixels, so its center
  // (vTexSamplingCoord.x) sits on the boundary between them. Stepping +/- half
  // an input pixel width (uHalfInputTexelWidth) lands on the two pixel centers:
  //
  //   Input pair: |    left    |    right   |
  //                     ^      ^      ^
  //               leftCoord.x  |  rightCoord.x
  //                   vTexSamplingCoord.x
  //
  // When inputWidth is odd, rightCoord.x on the final column exceeds 1.0 and
  // clamps to the rightmost pixel via GL_CLAMP_TO_EDGE (set in
  // GlUtil.bindTexture).
  vec2 leftCoord =
      vec2(vTexSamplingCoord.x - uHalfInputTexelWidth, vTexSamplingCoord.y);
  vec2 rightCoord =
      vec2(vTexSamplingCoord.x + uHalfInputTexelWidth, vTexSamplingCoord.y);
  vec3 leftYuv =
      rgbElectricalToYuvElectrical(texture2D(uTexSampler, leftCoord).rgb);
  vec3 rightYuv =
      rgbElectricalToYuvElectrical(texture2D(uTexSampler, rightCoord).rgb);
  vec3 averageYuv = 0.5 * (leftYuv + rightYuv);
  gl_FragColor = vec4(leftYuv.x, averageYuv.y, rightYuv.x, averageYuv.z);
}
