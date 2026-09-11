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

// ES 3 fragment shader that samples from an internal 2D HDR texture and applies
// color transformations and tone mapping.
//
// This is not a well-formed GLSL shader on its own and must be compiled together
// with color_conversions_es3.glsl (which provides #version, precision, and color conversion functions).

uniform sampler2D uTexSampler;
uniform int uOutputColorGamut;
uniform int uInputColorTransfer;
uniform int uOutputColorTransfer;
in vec2 vTexSamplingCoord;
out vec4 outColor;

void main() {
  highp vec4 inputColor = texture(uTexSampler, vTexSamplingCoord);
  highp vec3 processedRgb = processColor(
      inputColor.rgb,
      uOutputColorGamut,
      uInputColorTransfer,
      uOutputColorTransfer);
  outColor = vec4(processedRgb, inputColor.a);
}
