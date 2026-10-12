# Android 系统链路：分析与实作分开

参考固定版本 AOSP `android-11.0.0_r1`。下述为源码阅读/模拟器观察，不是本仓库实现的系统服务。

## 开机与一个核心服务

- init 启动配置中的 zygote；ZygoteInit 解析 `start-system-server`，调用 `forkSystemServer`，子进程入口为 `com.android.server.SystemServer`。
- SystemServer 的 bootstrap 阶段调用 PackageManagerService.main；后者将 `package` 与 `package_native` 注册到 ServiceManager。
- 应用层的 PackageManager 查询通过公开 API 请求服务，不意味着应用实现/修改了 PackageManagerService。已有 DP-Launcher 的 MAIN/LAUNCHER 查询及包变更广播属于这一层。
- 这次模拟器 `ps -A` 中能观察到 zygote64、system_server、media.codec、media.swcodec；`service list` 中有 package、media.extractor、media.player、media.resource_manager。服务存在不是本人的系统服务开发或性能优化成果。

## 媒体层边界

本项目调用 app 侧 MediaCodec、MediaMuxer、MediaExtractor。报告记录实际使用的 codec 名称（本次为 c2.android.aac.encoder/decoder），不是编写 Codec2/OMX 服务。进程表只能证实进程存在，不独立证明每一个调用的 Binder 路由；完整 native 调用栈需要另外追踪。

排查顺序：输入格式/帧对齐 → configure/start → input/output buffer 与 format change → CODEC_CONFIG/PTS/EOS → mux 生命周期 → extractor 轨道及 PCM 输出格式 → logcat 中服务错误。应用修复与 framework/vendor codec 修复分层记录。

## 一手源码入口

- [ZygoteInit](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-11.0.0_r1/core/java/com/android/internal/os/ZygoteInit.java)
- [SystemServer](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-11.0.0_r1/services/java/com/android/server/SystemServer.java)
- [PackageManagerService](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-11.0.0_r1/services/core/java/com/android/server/pm/PackageManagerService.java)
- [init 原理](https://android.googlesource.com/platform/system/core/+/refs/heads/android11-release/init/README.md)

没有编译/替换 AOSP，没有测试开机耗时，也没有实现系统服务 patch。
