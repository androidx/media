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

// ES 2 fragment shader that samples semi-planar YUV 4:2:0 (NV12 or NV21)
// textures and converts to RGB in the target working color space.
//
// This is not a well-formed GLSL shader on its own and must be compiled
// together with color_conversions_es2.glsl (which provides #version, precision,
// and color conversion functions).

#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#endif

varying vec2 vTexSamplingCoord;
uniform sampler2D uTexY;
// The interleaved chroma, with each pair of U and V samples in one luminance
// alpha texel, so that the first sample of each pair is sampled as the r
// component and the second as the a component.
uniform sampler2D uTexChroma;
// Whether the first sample of each chroma pair is V (NV21), rather than U
// (NV12).
uniform int uIsNv21;
uniform int uInputColorGamut;
uniform int uOutputColorGamut;
uniform int uIsInputColorRangeFull;
uniform int uOutputColorTransfer;
// The fraction of each plane texture's width that holds visible pixels, in
// luma, chroma order. Planes are uploaded at their full row stride, so this
// crops out the row padding when sampling.
uniform vec2 uPlaneWidthRatios;

void main() {
  vec2 yCoord =
      vec2(vTexSamplingCoord.x * uPlaneWidthRatios.x, vTexSamplingCoord.y);
  vec2 chromaCoord =
      vec2(vTexSamplingCoord.x * uPlaneWidthRatios.y, vTexSamplingCoord.y);
  float y = texture2D(uTexY, yCoord).r;
  vec2 chroma = texture2D(uTexChroma, chromaCoord).ra;
  vec2 uv = (uIsNv21 == 1) ? chroma.yx : chroma;
  vec3 rgbElectrical = yuvElectricalToRgbElectrical(
      vec3(y, uv), uInputColorGamut, uIsInputColorRangeFull);
  gl_FragColor = vec4(processColor(rgbElectrical, uInputColorGamut,
                                   uOutputColorGamut, uOutputColorTransfer),
                      1.0);
}
