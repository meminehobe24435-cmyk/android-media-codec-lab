# Android MediaCodec Lab

2026-10-13 新建的 **AI 辅助个人工程实践**：把 Android 平台 AAC 编解码、MP4 封装、C++/JNI WAV 输入校验和 Python 回归验证组成可复现的小项目。面试前应本人亲自运行、阅读代码并解释状态机；本仓库不是既有企业经历，也不是 Android Framework 修改成果。

## 实现范围

`合成 PCM16/WAV → C++ RIFF 校验（JNI） → AAC-LC MediaCodec → MediaMuxer MP4 → MediaExtractor → MediaCodec PCM → 帧数/RMS/PTS/EOS 检查`

- Java Android 应用，工作线程运行同步输入/输出队列，20 秒循环截止时间、取消检查、缓冲释放和 codec/muxer/extractor 生命周期管理。
- 编码器输出格式确定后添加轨道，排除 CODEC_CONFIG 数据，按编码器 PTS 写入 MP4，发送明确的封装 EOS 时长。AAC 由 Android 平台提供，不是自研算法。
- C++17 little-endian RIFF/WAVE 解析，限定 PCM16 单/双声道、8–96 kHz；检查 chunk 边界、奇数长度 padding、重复块、字节率及帧对齐。JNI 输入限制 16 MiB。
- Python 结构检查器递归检查 MP4 box 长度/深度和 `ftyp/moov/mdat/mp4a/stts/stsz`；不是全功能 MPEG-4 验证器。
- 生成 48 kHz 单声道、44.1 kHz 双声道的两秒正弦/静音；不申请录音、网络或外部存储权限，不处理用户录音。

## 验证与边界

验证结果以 `evidence/` 原始 JSON、测试日志及合成媒体为准。主机单测 38 项；Android 11 x86_64 独立模拟器每轮 6 个场景（4 个真实 AAC/MP4 编解码 + 损坏 WAV/MP4），连续 3 轮，并检查 4 个实际 MP4 的 box。不是 18 个不同场景，也不是长稳/真机认证。

初次测试保留了尾帧缺失问题：48000 Hz 输入 96000 帧，Codec2 实际输出 93 个 AAC access unit / 95232 帧。处理不满 1024 帧的尾输入时显式补零，并记录原长与补零数量；最终断言解码帧数等于补齐后的长度，未放宽为“差不多”。见 `docs/debugging.md`。此结论只约束本次 Codec2 模拟器，不宣称所有厂商实现相同。

**未实现/未验证**：Framework/core service 修改、系统开机性能优化、真机厂商 codec、视频 H.264/H.265、Surface 渲染、MediaCodec 异步回调、音画同步、FFmpeg、生产长稳、AAC 感知质量认证。源码分析见 `docs/android-boundaries.md`，不能把分析笔记写成系统服务开发。

## 构建与运行

Windows 必须放到 ASCII 路径（如 `D:/dev/android-media-codec-lab`）；本次在独立 ASCII 验证副本构建，不改 SDK 或现有工程配置。

安装 JDK17、Android SDK platform35 / build-tools36.0.0、NDK28.2.13676358、CMake3.22.1；设置 `JAVA_HOME` 和 `ANDROID_HOME`，或写仅本地使用且已忽略的 `local.properties`。

```powershell
./gradlew.bat assembleDebug
python tools/verify_host.py --compiler g++
python tools/collect_emulator.py --serial emulator-5580 --apk app/build/outputs/apk/debug/app-debug.apk --output evidence/local --runs 3
```

Linux 主机回归：`python3 tools/verify_host.py`。Windows MinGW 编译器所在目录需在 PATH。采集器拒绝物理手机；会清除**本 demo 包**的数据后运行，不改默认桌面、不接触其他应用。需要已启动的 Android 11/API30 模拟器与 adb，可用 `--adb` 指定路径。

## 代码入口

- `AudioPipeline.java`：PCM 输入、AAC 编码/封装、抽轨/解码、量化验证。
- `MainActivity.java`：后台运行 6 场景，写原子 JSON 报告，显示结果。
- `native/`：同一份解析器分别构建 JNI 和主机 CLI。
- `tests/`：WAV 异常/边界和 Python 标准库 oracle，MP4 长度/深度/样本描述检查。
- `tools/collect_emulator.py`：重复运行、导出合成媒体、实际 MP4 结构核对。

参考 [MediaCodec](https://developer.android.com/reference/android/media/MediaCodec)、[MediaMuxer](https://developer.android.com/reference/android/media/MediaMuxer)、[WAVE 格式](https://www-mmsp.ece.mcgill.ca/Documents/AudioFormats/WAVE/WAVE.html)。许可证与第三方来源见 LICENSE、THIRD_PARTY.md。
