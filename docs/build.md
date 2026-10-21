# 构建与模拟器验证

## 环境

- JDK 21、Python 3.10、Android SDK（platform android-37.0、build-tools 36.0.0）、网络可访问 Gradle／Maven／Chaquopy。
- 设置 `JAVA_HOME`、`ANDROID_HOME`、`PYTHON_BUILD`，或复制 `local.properties.example` 为未跟踪的 `local.properties` 填本机路径。
- 运行 `python scripts/prepare_media.py` 准备经过 SHA-256 校验的官方 Media3 1.11.1 源码及本项目 FongMi 合并补丁；不会覆盖已有目录。原生库另按 [native 构建说明](../scripts/native/README.md) 编译双 ARM ABI，并用 `scripts/sync_media3_native.py` 校验和导入。`MEDIA3_DIR` 或未跟踪的 `local.properties` 中 `media3.dir` 指向准备好的目录。
- 当前 MPV/FFmpeg 使用 NDK 30.0.16248370；其他原生模块还可能需要 NDK 28.2.13676358 和 CMake。不要把旧版分拆 FFmpeg 库与新版 `libffmpeg.so` 混装。

```powershell
.\gradlew.bat :app:testLeanbackDebugUnitTest :app:assembleLeanbackDebug :app:assembleMobileDebug --console=plain
python -m unittest discover -s service -v
```

输出在 `app/build/outputs/apk/{leanback,mobile}/debug/`。两个 UI 构建目前使用相同包名，不应同机同时安装；切换测试安装覆盖前保存所需测试数据。

模拟器原生 x86_64 回归：

```powershell
.\gradlew.bat -PtestAbi=x86_64 :app:assembleLeanbackDebug
python scripts/emulator.py install -r app/build/outputs/apk/leanback/debug/app-leanback-x86_64-debug.apk
python scripts/emulator.py shell am start -n com.yingshicang.player/com.fongmi.android.tv.ui.activity.HomeActivity
python scripts/ui.py
```

`scripts/emulator.py` 同时检查 serial 前缀与 `ro.kernel.qemu=1`，拒绝操作实体机。默认端口 5580。ARM-only Spider 插件不一定支持 x86_64，必须将这类兼容失败与应用错误分开记录。

## 签名与发版

Debug 仅用于测试。Release 从 `YSC_KEYSTORE/YSC_KEY_ALIAS/YSC_STORE_PASSWORD/YSC_KEY_PASSWORD` 环境变量或 `ysc.*` 本地配置读取签名；配置缺失时不能将未签名包当正式版本发布。

```powershell
.\gradlew.bat :app:assembleLeanbackRelease "-PappVersionCode=69" "-PappVersionName=1.2.0"
```

应用默认从 [GitHub 最新正式 Release](https://github.com/LuckyWDGS/YingShiCang/releases/latest) 检查更新，使用 Release 正文作为更新说明。设置里的自定义更新地址留空即可；已有自定义地址仍沿用单包清单协议。

以上命令对应当前本地源码 1.2.0 / code69；以后每次发版继续递增 `versionCode` 和 `versionName`，沿用相同正式签名。Gradle 构建不会生成 GitHub 聚合清单；将已签名的两个 TV APK 放进新的发布目录，再运行生成器：

```powershell
New-Item -ItemType Directory -Path Release/1.2.0
Copy-Item -LiteralPath app/build/outputs/apk/leanback/release/leanback-arm64_v8a.apk -Destination Release/1.2.0/YingShiCang-1.2.0-tv-arm64-v8a.apk
Copy-Item -LiteralPath app/build/outputs/apk/leanback/release/leanback-armeabi_v7a.apk -Destination Release/1.2.0/YingShiCang-1.2.0-tv-armeabi-v7a.apk
python scripts/github_release_manifest.py --arm64 Release/1.2.0/YingShiCang-1.2.0-tv-arm64-v8a.apk --armv7 Release/1.2.0/YingShiCang-1.2.0-tv-armeabi-v7a.apk --output Release/1.2.0/release-manifest.json
```

生成器需要 Python 3.10+、Java 和 Android SDK build-tools。默认从 `ANDROID_HOME` / `ANDROID_SDK_ROOT` 选择最新稳定 build-tools，从 `JAVA_HOME` / `PATH` 找 Java；可用 `--sdk` 或 `--aapt`、`--apksigner-jar`、`--java` 显式指定工具。它实际检查包名、两个 TV 架构、正式签名及非 debug、相同版本与最低系统要求，读取真实大小和 SHA-256，只生成本地清单。默认拒绝覆盖已有清单；仅重新生成尚未发布的本地清单时使用 `--overwrite`。APK 在生成清单后不得修改或改名。

GitHub 发版流程：

1. 新建草稿 Release，标签使用与 APK `versionName` 对应的 `v版本号`，例如 `v1.2.0`，不要添加 alpha 等后缀。Release 正文填写本版更新内容，App 直接使用正文显示更新说明。
2. 上传上述两个 APK 和 **release-manifest.json**。不能只上传 APK，也不能上传旧单包格式的清单。清单读取附件的实际文件名，不要求固定 APK 名称。
3. 等全部附件上传完成后，发布为正式版本，并标记 **Latest**。不要勾选预发布。已发布附件保持不变；修复安装包时递增版本重新发版。

所有附件名（包括额外说明文件）只能使用 ASCII 字母、数字、点、下划线、连字符，首字符必须是字母或数字，总长不超过200，且不能含 `..`。附件必须非空、上传完成，单文件不超过300MB；例如可用 `INSTALL.txt`，不要使用中文附件名。

提交代码或 `git push` 本身不会发布 APK。仓库没有自动发布流程；需要按上述流程在 [GitHub Releases](https://github.com/LuckyWDGS/YingShiCang/releases) 发布，也可以用 GitHub CLI：`gh release create v1.2.0 --verify-tag --title "影视仓 1.2.0" --notes-file Release/1.2.0/RELEASE_NOTES.md --latest` 后面加两个 APK 和 `release-manifest.json`。此聚合生成器面向 TV `leanback`，不能用作手机版更新清单。

首次需安装包含此更新功能的1.0.7或后续版本。设备按支持的 ABI 顺序选择附件；发现更高 `versionCode` 才提示更新。用户确认后显示下载进度，核验 APK 的版本、包名、大小、SHA-256、系统要求及当前签名，再由 Android 系统确认安装。Android 8 及以上首次更新可能需要允许此应用安装未知来源，返回应用后继续。

显式自定义服务的单包版本清单生成器（不用于 GitHub 的多包清单）：

```powershell
python scripts/publish_manifest.py path/to/signed.apk --aapt path/to/aapt.exe --url https://your-download-host/releases/tv.apk --flavor leanback --output release.json
```

先上传 APK，再发布清单；App 更新时还会匹配 APK 的版本、大小、SHA-256 和当前签名。仅改清单不能让另一个签名的应用覆盖安装。

## 短剧漫剧回归要点

1.0.8 的 AI 漫剧在原有 8 项网页题材之外，支持 14 项已验证的原生细题材能力：求生、修真、穿越、系统、重生、诡秘、异能、逆袭、异界、年代、搞笑、都市、古代、萌宝。实际显示名称和是否显示由当前源声明决定。原生数字题材筛选目前仅对 AI 漫剧生效；AI 短剧保留网页题材，不提供这些原生细题材。

原生元数据须覆盖全部 14 个已验证 ID 才算完整，空或缺项结果保留已取得选项并允许重试。完整成功元数据使用 30 分钟单调时钟缓存，读取和失败不续期；当前浏览会话保留完整元数据。原生元数据失败时，网页题材可部分展示和重试，部分结果不进入成功缓存。同源保存的非空选择无法由部分目录恢复时，应保留原选择并提示重试，不得覆盖为“全部”；完整元数据确认选项已移除后才恢复默认。元数据单独重试且查询不变时应保留卡片和分页。验证应覆盖这些行为、题材与最新互斥、切换类型和遥控焦点；JVM 通过不代表设备验证完成。

1.0.8 本地包已完成相关 4 套共 60 项 JVM 测试、TV 双架构 Release 构建、mobile ARM64 Release Java 编译，以及签名、版本、ABI 和 16KB 对齐检查。最终雷电实测覆盖求生 18→36 部分页、系统筛选、最右标签、最新及类型互斥、详情返回和重启恢复。测试前 1405 个内部文件与空外部目录完整恢复，临时权限和诊断工具已清理。具体证据和测试流程中的早期失败见[细题材报告](短剧漫剧细题材-1.0.8-2026-10-18.md)；未发布远程 Release。

## 本地日志／升级闭环测试

```powershell
python service/receiver.py --storage .codex/session/server-data --artifacts .codex/session/releases
```

模拟器调试包的日志接口可配置 `http://10.0.2.2:8765/logs`；正式日志接口要求 HTTPS。应用更新在所有构建中都要求受信任的 HTTPS，自定义更新测试需要先为接收器配置可由设备信任的 HTTPS 入口，再填写实际清单地址，不能直接使用上面的 HTTP 端口。该接收器是本地验收服务：云端部署需要 HTTPS 入口、短期上传凭据或服务端配对、限流、访问控制与运维配置，不能把本地无认证模式直接暴露到公网。尚未部署到阿里云。
