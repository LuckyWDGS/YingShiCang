# 播放器内核与扩展核查（2026-10-19）

当前为本地 v65 开发验证，不是正式发布。源码更新、原生编译、单元测试和设备长播是分别验证的阶段。

## 版本与路径

| 组件 | 本次锁定版本或提交 | 说明 |
| --- | --- | --- |
| AndroidX Media3 / ExoPlayer | 1.11.1，`8c6678b` | 官方源码合入现有 FongMi 扩展及本次修复 |
| FongMi Media3 扩展基线 | `3c2cbe8` | `release-1.11.0-fongmi` 分支；其扩展与官方 1.11.1 合并 |
| MPV Android / JNI | `c75c5a7` | FongMi Android 集成 |
| MPV | `7f0bd1f` | FongMi 分支 |
| FFmpeg | `e6d1f51` | `release-9.0-fongmi`；由分拆动态库迁移至单一 `libffmpeg.so` |
| libplacebo | `558b035` | FongMi 渲染分支 |
| uavs3d | `0e20d2c` | 最新源增加 Android ARMv7 softfp ABI 适配 |
| dav1d | `d7a83f6` | AV1 软件解码依赖 |
| libass | `f61db56` | MPV ASS 字幕依赖 |
| SMBJ | 0.15.0 | Maven 官方元数据确认，实际依赖解析通过 |
| Android NDK | 30.0.16248370 | 双 ARM ABI；16 KiB 对齐验证 |

完整源锁和补丁位于 `scripts/native/` 与 `scripts/media3/`。`prepare_media.py`
使用校验过的官方源码包，应用本地补丁并校验每个覆盖文件；不再默默准备旧版本。
原生同步前检查两种 ABI、SONAME、依赖闭合、JNI 方法和段对齐，并备份旧产物。

实际本机 Media3 目录是 `E:/AIProject/_media_extract/media-release-1.11.1-fongmi`，
它是合成源码目录，不能在其中用普通 `git pull` 更新。不要直接覆盖本地定制扩展。

## 已落实的扩展修复

- SMB：正确区分编码后的用户名/密码分隔符，支持域账号；凭证改变不能复用旧身份；连接中途失败、越界 seek、单项关闭异常都释放剩余资源。
- 弹幕搜索：2 MiB 响应上限、空值处理、URL 去重和最多 128 个候选；不再依赖 App 生命周期初始化 Gson。
- 弹幕文件/HTTP 分段/压缩内容：32 MiB 读取及解压后大小上限；取消保持线程 interrupt 状态。
- 剧集网格：32dp 最小高度，自适应 6/4/3/2 列，保留完整文本及焦点/悬停滚动。

单元测试：主应用 370 项 / 52 suites，MPV 47 项 / 14 suites，SMB 8 项、
弹幕受限输入 4 项通过。源码准备已在新目录完整执行，471 个覆盖文件 hash 一致。

## 主仓库与上游更新

`LuckyWDGS/YingShiCang` 的 GitHub 元数据 `fork=false`，源码基于 FongMi/TV。
核查时本地和远端 `main` 都是 `c94e24a`，远端没有额外提交。

FongMi/TV 相对记录的 `fe6a5b2` 基线新增 23 个提交、涉及 102 个文件，
最新为 `0af3b31`。这不是本地缺失提交的精确计数：部分修复已被单独采纳，
例如 `AndroidPlatform.ABI` 的 Chaquopy 架构同步已经存在。

值得继续评估的更新：

1. Exo 的高级 libass 字幕与 PlayerView 叠层绑定：改善复杂 ASS 字幕、样式、字体和画面缩放时的对齐；需要与现有全屏/小窗及字幕设置共同验证。
2. HTTP 重定向后的代理策略：上游按跳转链保留或重选代理，处理代理认证边界；需要可控重定向测试，不能直接替换项目所有网络行为。
3. mpv.conf 配置优先级与编辑器：让用户自定义参数和应用设置的覆盖关系更清晰，保留用户原有配置。
4. 高码率/HDR/杜比视界、音轨切换和 SMB 真实 NAS 长播：需要具体硬件与媒体样本，不能由模拟器的普通 SDR 素材代替。

## 播放验证边界

独立 benchmark 使用与主工程相同的 Media3 模块，分别请求 Exo 硬解、Exo 软解、
MPV 硬解和 MPV 软解，记录实际解码器、首帧、播放推进、掉帧、CPU 和 PSS。
素材为可复现的 AVC 1080p、HEVC 1080p 和 HEVC 4K，均为 60 秒循环视频。

测试设备仅限已授权的雷电 `127.0.0.2:5555`，它是 x86_64/Houdini 环境。
选择硬解选项不等于硬件解码实际生效；无法启用的路径单独记录，不计为通过。
模拟器成绩不能证明所有 ARM 电视的长期稳定性。有效 HEVC1080 矩阵中，Exo 硬解首帧
473ms、CPU 10.8%、PSS 峰值 141.3MiB，0 解码/输出错误；MPV 硬解首帧 823ms、CPU
9.1%、PSS 峰值 218.2MiB，0 错误。Exo 硬解因此作为本地 v65 的默认内核，MPV 保留
为手动兼容选项。MPV gpu-next 没有收益（859ms），Vulkan 明显变慢（1453–1760ms），
均不作为默认设置。4K Exo 样本 60 秒无错误，但当前 fixture 走 FFmpeg 软件解码，
CPU 66–75%、PSS 峰值 485–497MiB；这只能证明能播放该样本，不能作为 4K 长播稳定性
或硬解能力结论。AVC Exo-hard 的旧失败是 benchmark 外部文件权限（EACCES），已修正
隔离 fixture 权限后再复测，不能把旧记录算作解码器失败。

饭太硬真实点播的慢和失败需要与内核证据分开看：入口 HTTP 响应约 1.26–1.77 秒，
曾有约 2.23 秒；雷电日志出现 TLS handshake failed、connection reset/refused，且
点播解析还要访问网盘/解析服务。4K MKV 首段体积约 5.3–5.98GB，首帧前需要更多数据。
因此当前加载慢/失败主要是入口、DNS/TLS、解析鉴权和首段网络等待，现有日志没有显示
Exo/MPV 的实际解码错误。直播不在本轮结论范围内。

## 旧版 Release 65 回归发现与修复

在安装旧版 Release 65 后，用真实饭太硬源的 4K HEVC 片段触发了 Exo 软件解码到 MPV 的自动回退；雷电的 x86_64/Houdini 在 `libmpv.so` 的 glslang/shader 编译路径发生 SIGSEGV，导致 VideoActivity 退出。证据保存在 `.codex/session/qa/real-source-20261019/final-run/release65-4k-logcat-18s.txt`。

当前修复在回退前先检查 `Build.SUPPORTED_ABIS`：只有 ARM 运行时才允许自动 Exo→MPV，x86/Houdini 直接保持 Exo 软件解码并且不探测 MPV native；手动选择 MPV 仍可用。该修复已加入 `SoftwareHevcFallbackTest`，并在新包安装后重新验证。

## 1.0.10 / code66 收尾核验（2026-10-20）

- 默认内核仍为 Exo/Media3 硬解（`player_engine=0`）。独立雷电矩阵的有效 HEVC 1080p 窗口中，Exo 硬解使用 `OMX.qcom.video.decoder.hevc`，60 秒播放无错误、无解码丢帧，CPU 约 10.2%；Exo 软解虽能推进但 CPU 约 28%、PSS 峰值约 218.7 MiB。MPV 硬解也能推进但首帧约 1282 ms，启动慢于 Exo。故 Exo 硬解继续作为默认，MPV 只作为手动兼容回退。该证据限定于雷电 x86_64/Houdini，不代表所有 ARM 设备或直播场景。
- 最终 TV ARM64 包在同一雷电上完成真实饭太硬搜索、详情、4K《战狼2》条目、弹幕/播放控件、暂停恢复/seek/换集换源回归，未发现本轮应用崩溃。搜索失败仍主要来自源端 EOF、TLS、DNS/CDN、解析鉴权或首段网络等待，不能归因于解码器。
- 剧集卡片最终收窄至 28dp（雷电约 2x 密度实测 56px，高度间距 64px），《嫁金钗》首屏显示 10 集且 DPAD 焦点稳定。真实条目本轮未暴露 `size+name` 双行网盘元数据，因此双行自然增高逻辑保留但未作真实数据断言。
- 正式发布清单只包含 TV ARM64/ARMv7 两个包，以避免手机版 ABI 与 TV 更新器选择冲突。四 ABI 本地构建包均已完成签名、V2、非 debuggable 和 16 KiB 对齐检查；GitHub Release 上传附件应使用 TV 两包及 `release-manifest.json`。
