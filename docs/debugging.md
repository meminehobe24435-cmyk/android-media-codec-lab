# 本次失败与修复记录

1. Gradle8.13 搭配本机 Android Studio JBR25 的初次构建失败（major version69）。改为已有 JDK17，不改全局环境。Windows 非 ASCII 路径检查失败后使用独立 ASCII 构建副本；未关闭路径检查掩盖潜在 NDK 路径问题。
2. 采集器初版把 `adb exec-out` 的退出0当成远端 `cat` 成功，报告尚不存在时产生 JSONDecodeError。修为还需实际输出 JSON 起始符，并设置总轮询期限；应用以原子替换方式输出完整报告。
3. 首轮编码 96000 个 PCM 帧，c2.android.aac.encoder 输出93个 AAC access unit，解码95232帧，严格时长断言失败。编码器输出格式没有 encoder-delay/encoder-padding 字段。补充 MP4 明确 EOS 最后一帧时长后，同样得到93个输出包：封装时长不是缺失音频样本的修复。
4. 输入显式补齐 AAC-LC 的1024帧边界：48k单声道补256帧；44.1k双声道补888帧。JSON 同时记录 inputFrames 和 paddingFrames，解码必须精确等于二者之和，同时核对 RMS 和 EOS。这是本次应用侧尾输入处理，不宣称修复平台源码或证明任意 AAC 编码器行为。

官方 [MediaMuxer writeSampleData](https://developer.android.com/reference/android/media/MediaMuxer#writeSampleData(int,%20java.nio.ByteBuffer,%20android.media.MediaCodec.BufferInfo)) 描述了空 EOS 样本的最后一帧时长规则。AAC 有损压缩，本项目不做字节一致性断言；RMS 是基本信号检查，不是 PESQ/感知音质评价。
