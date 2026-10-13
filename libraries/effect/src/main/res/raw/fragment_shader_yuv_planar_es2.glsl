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

// ES 2 fragment shader that samples planar YUV 4:2:0 (I420) textures and
// converts to RGB in the target working color space.
//
// This is not a well-formed GLSL shader on its own and must be compiled
// together with color_conversions_es2.glsl (which provides #version, precision,
// and color conversion functions).

#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#endif

varying vec2 vTexSamplingCoord;
uniform sampler2D uTexY;
uniform sampler2D uTexU;
uniform sampler2D uTexV;
uniform int uInputColorGamut;
uniform int uOutputColorGamut;
uniform int uIsInputColorRangeFull;
uniform int uOutputColorTransfer;
// The fraction of each plane texture's width that holds visible pixels, in Y,
// U, V order. Planes are uploaded at their full row stride, so this crops out
// the row padding when sampling.
uniform vec3 uPlaneWidthRatios;

void main() {
  vec3 samplingX = vTexSamplingCoord.x * uPlaneWidthRatios;
  vec3 yuvElectrical =
      vec3(texture2D(uTexY, vec2(samplingX.x, vTexSamplingCoord.y)).r,
           texture2D(uTexU, vec2(samplingX.y, vTexSamplingCoord.y)).r,
           texture2D(uTexV, vec2(samplingX.z, vTexSamplingCoord.y)).r);
  vec3 rgbElectrical = yuvElectricalToRgbElectrical(
      yuvElectrical, uInputColorGamut, uIsInputColorRangeFull);
  gl_FragColor = vec4(processColor(rgbElectrical, uInputColorGamut,
                                   uOutputColorGamut, uOutputColorTransfer),
                      1.0);
}
