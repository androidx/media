# GPU 着色器里做 HDR→SDR 的实现思路

目标：杜比 P5 等 HDR（BT.2020 + PQ）经 FFmpeg 软解后，在 GPU 上做 tone mapping，正确显示在 SDR 屏上。

---

## 1. 现有管线简要

- **当前**：`DecoderVideoRenderer` 根据 `setVideoSurface()` 传入的是 **Surface** 还是 **VideoDecoderOutputBufferRenderer** 选模式：
  - **Surface** → `VIDEO_OUTPUT_MODE_SURFACE_YUV` → 直接 `ffmpegRenderFrame` 把 YUV 拷到 ANativeWindow，**无 tone mapping**。
  - **VideoDecoderOutputBufferRenderer**（如 `VideoDecoderGLSurfaceView`）→ `VIDEO_OUTPUT_MODE_YUV` → 每帧调用 `setOutputBuffer(outputBuffer)`，在 **OpenGL 里** 用 shader 做 YUV→RGB 再画到 Surface。
- `VideoDecoderGLSurfaceView` 已有 BT.601 / BT.709 / **BT.2020** 的 YUV→RGB 矩阵，但**没有**做 PQ→SDR 的 tone mapping，所以 P5 会发紫/发灰。

---

## 2. 总体思路（推荐）

- **不改 ExoPlayer/FFmpeg 解码**，只在「谁来接 YUV 并画到屏幕」这一环做文章。
- 增加一条 **YUV + HDR tone mapping** 的渲染路径：
  - 使用 **自定义 View**，实现 `VideoDecoderOutputBufferRenderer`，内部用 **OpenGL ES** 画 YUV。
  - 在 **片段着色器** 里：先按现有方式 YUV→RGB（BT.2020），再按 **transfer** 做 **PQ→linear→SDR**，最后输出到屏幕。
- 播放时：对「可能 HDR / 杜比」的源，把 **这个自定义 View** 设为 ExoPlayer 的 video output（而不是普通 Surface），这样解码器会走 `VIDEO_OUTPUT_MODE_YUV`，每帧都会经过你的 shader，性能最佳（GPU 做 tone mapping）。

---

## 3. 实现步骤概要

### 3.1 自定义 View（实现 VideoDecoderOutputBufferRenderer）

- 参考 `VideoDecoderGLSurfaceView`：
  - 继承 `GLSurfaceView`，实现 `VideoDecoderOutputBufferRenderer`。
  - 在 `setOutputBuffer(VideoDecoderOutputBuffer outputBuffer)` 里接收每帧；把 `outputBuffer`（含 `yuvPlanes`、`yuvStrides`、`colorspace`）以及 **format**（含 `colorTransfer`）交给 GL 渲染线程。
- 渲染线程：
  - 把 Y/U/V 上传为 3 个 GL 纹理（同现有实现）。
  - 用自定义 shader 画全屏四边形；shader 内做 YUV→RGB + 按需 tone mapping。

### 3.2 判断是否需要 tone mapping

- 使用 `outputBuffer.format`（若为 null 可暂用上一帧的 format）：
  - `format.colorTransfer == C.COLOR_TRANSFER_ST2084` → PQ（杜比/常见 HDR），需要 tone mapping。
  - `format.colorTransfer == C.COLOR_TRANSFER_HLG` → HLG，也可做类似映射。
  - 仅当为 HDR transfer 时在 shader 里走 tone mapping 分支；SDR 保持原有 YUV→RGB 即可。

### 3.3 片段着色器里做什么

1. **YUV → RGB（与现有一致）**  
   用 3 个 sampler 采样 Y/U/V，用 `colorspace` 选矩阵（BT.601/709/2020），得到 `rgb_bt2020`（或对应色彩空间）。

2. **HDR → linear（仅当 PQ/HLG 时）**  
   - PQ (ST.2084)：  
     - 公式：`L = max(0, (c1 + c2 * Y^m1) / (1 + c3 * Y^m1))^m2`，其中 Y 为归一化亮度，常数 m1,m2,c1,c2,c3 查 ST 2084 标准。  
     - 或使用简化近似：`linear = pow(max(Y, 0), 1/2.4)` 之类的近似（实际建议用标准系数）。
   - 对 RGB 三个通道用同一套 EOTF（通常亮度在 Y，可先 YUV→RGB 再对 R,G,B 用 PQ inverse；若 FFmpeg 输出已是 RGB 则直接对 R,G,B 做）。

3. **linear → SDR（显示用）**  
   - 可选简单做法：`sdr = pow(linear, 1/2.2)`（gamma 2.2）。  
   - 更好：做简单 Reinhard 或 max-based tone map 再 gamma，例如：  
     `L_linear = dot(rgb, vec3(0.2126, 0.7152, 0.0722)); L_mapped = L_linear / (1.0 + L_linear); rgb_sdr = rgb_linear * (L_mapped / L_linear);` 再 `pow(..., 1/2.2)`。

4. **输出**  
   `gl_FragColor = vec4(rgb_sdr, 1.0);`

（上面仅为思路；完整 PQ 系数和 tone map 曲线可按 libplacebo/mpv 或 BT.2100 文档微调。）

### 3.4 在 App 里接上

- 对使用 FFmpeg 视频解码、且可能播杜比/HDR 的 Player：
  - 不用 `player.setVideoSurface(surface)`；
  - 用 `player.setVideoSurfaceView(hdrToneMappingView)` 或等价 API（即把 **你的自定义 VideoDecoderOutputBufferRenderer View** 设成 video output）。
- 这样：
  - 解码仍由 FFmpeg 做，输出 YUV；
  - 渲染全部走你的 View → OpenGL → 带 tone mapping 的 shader → 屏幕，性能最佳。

### 3.5 可选：仅 HDR 时走 YUV 路径

- 若希望「只有检测到 HDR 才用自定义 View，其余仍用 Surface」：
  - 可以在 `onRenderedFirstFrame` 或 format 变化时，根据 `format.colorTransfer` 动态切换 output（Surface vs 自定义 View），逻辑会稍复杂；
  - 或简单做法：只要有可能播 P5，就统一用自定义 View 输出；SDR 内容在 shader 里不走 tone mapping 分支，几乎无额外开销。

---

## 4. 和现有组件的对应关系

| 组件 | 作用 |
|------|------|
| `DecoderVideoRenderer` | 根据 output 类型选 SURFACE_YUV 或 YUV；不修改。 |
| `ExperimentalFfmpegVideoDecoder` | 只负责解码出 YUV；不修改。 |
| `VideoDecoderOutputBuffer` | 带 `yuvPlanes`、`colorspace`、`format`；shader 里用 format.colorTransfer 判断是否做 tone mapping。 |
| 自定义 View | 实现 `VideoDecoderOutputBufferRenderer`，内建 OpenGL + 带 tone mapping 的 fragment shader。 |
| ExoPlayer | 通过 `setVideoSurfaceView(你的View)` 把输出切到 YUV 路径。 |

---

## 5. 参考

- PQ (ST 2084) 反 EOTF 系数可查：ITU-R BT.2100、BT.2087。
- mpv/libplacebo 的 tone mapping 与 PQ 处理可作参考（你提到的 gpu-next + sw 就是这类思路）。
- media3 已有：`VideoDecoderGLSurfaceView`（YUV→RGB 矩阵）、`GlUtil`、`GlProgram` 等，可直接参考或复用其 GL 与纹理上传逻辑，只改 fragment shader 和是否走 tone mapping 的分支。

这样实现后，杜比 P5 等 HDR 在「FFmpeg 软解 + 直接输出」不变的前提下，通过 GPU 着色器做 HDR→SDR，即可在 SDR 屏上颜色正确、且性能最佳。
