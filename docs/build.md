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
.\gradlew.bat :app:assembleLeanbackRelease "-PappVersionCode=79" "-PappVersionName=1.7.4"
```

应用默认从 [GitHub 最新正式 Release](https://github.com/LuckyWDGS/YingShiCang/releases/latest) 检查更新，使用 Release 正文作为更新说明。设置里的自定义更新地址留空即可；已有自定义地址仍沿用单包清单协议。

以上命令对应当前本地源码 1.7.4 / code79；以后每次发版继续递增 `versionCode` 和 `versionName`，沿用相同正式签名。Gradle 构建不会生成 GitHub 聚合清单；将已签名的两个 TV APK 放进新的发布目录，再运行生成器：

```powershell
New-Item -ItemType Directory -Path Release/1.7.4
Copy-Item -LiteralPath app/build/outputs/apk/leanback/release/leanback-arm64_v8a.apk -Destination Release/1.7.4/YingShiCang-1.7.4-tv-arm64-v8a.apk
Copy-Item -LiteralPath app/build/outputs/apk/leanback/release/leanback-armeabi_v7a.apk -Destination Release/1.7.4/YingShiCang-1.7.4-tv-armeabi-v7a.apk
python scripts/github_release_manifest.py --arm64 Release/1.7.4/YingShiCang-1.7.4-tv-arm64-v8a.apk --armv7 Release/1.7.4/YingShiCang-1.7.4-tv-armeabi-v7a.apk --output Release/1.7.4/release-manifest.json
```

生成器需要 Python 3.10+、Java 和 Android SDK build-tools。默认从 `ANDROID_HOME` / `ANDROID_SDK_ROOT` 选择最新稳定 build-tools，从 `JAVA_HOME` / `PATH` 找 Java；可用 `--sdk` 或 `--aapt`、`--apksigner-jar`、`--java` 显式指定工具。它实际检查包名、两个 TV 架构、正式签名及非 debug、相同版本与最低系统要求，读取真实大小和 SHA-256，只生成本地清单。默认拒绝覆盖已有清单；仅重新生成尚未发布的本地清单时使用 `--overwrite`。APK 在生成清单后不得修改或改名。

GitHub 发版流程：

1. 新建草稿 Release，标签使用与 APK `versionName` 对应的 `v版本号`，例如 `v1.7.4`，不要添加 alpha 等后缀。Release 正文填写本版更新内容，App 直接使用正文显示更新说明。
2. 上传上述两个 APK 和 **release-manifest.json**。不能只上传 APK，也不能上传旧单包格式的清单。清单读取附件的实际文件名，不要求固定 APK 名称。
3. 等全部附件上传完成后，发布为正式版本，并标记 **Latest**。不要勾选预发布。已发布附件保持不变；修复安装包时递增版本重新发版。

**普通版本与重要版本**：普通版本在启动时每个版本只提示一次；用户选「暂不更新」、下载失败或安装未完成后，要等更高版本发布才会再次自动弹出（设置里的「版本更新」红点会一直保留，可随时手动更新）。需要强制更新时，在 Release 正文任意位置加入 GitHub 不显示的隐藏标记：

```markdown
<!-- ysc-min-version: 1.3.0 -->
<!-- ysc-important: 旧版本无法播放网盘资源，请更新后继续使用 -->
```

低于 `ysc-min-version` 的设备每次启动都会弹窗，并显示「重要更新」提示（第二行是可选原因，省略时显示默认文案）；弹窗没有「暂不更新」，返回键退出应用。只有下载失败或网络无法下载安装包时才提供「暂时继续使用」，下次启动仍会提示。最低版本不能高于本次 Release 的版本号，否则不生效；它对以后的版本同样有效，例如 1.4 声明最低 1.4、之后 1.5 不再声明时，仍在 1.3 的设备看到 1.5 不会被强制——需要持续强制时，在之后的 Release 正文里继续保留该标记。只有包含此功能的版本（1.4.0 及以后）能识别标记。

所有附件名（包括额外说明文件）只能使用 ASCII 字母、数字、点、下划线、连字符，首字符必须是字母或数字，总长不超过200，且不能含 `..`。附件必须非空、上传完成，单文件不超过300MB；例如可用 `INSTALL.txt`，不要使用中文附件名。

提交代码或 `git push` 本身不会发布 APK。仓库没有自动发布流程；需要按上述流程在 [GitHub Releases](https://github.com/LuckyWDGS/YingShiCang/releases) 发布，也可以用 GitHub CLI：`gh release create v1.7.4 --verify-tag --title "影视仓 1.7.4" --notes-file Release/1.7.4/RELEASE_NOTES.md --latest` 后面加两个 APK 和 `release-manifest.json`。此聚合生成器面向 TV `leanback`，不能用作手机版更新清单。

首次需安装包含此更新功能的1.0.7或后续版本。设备按支持的 ABI 顺序选择附件；发现更高 `versionCode` 才提示更新。用户确认后显示下载进度，核验 APK 的版本、包名、大小、SHA-256、系统要求及当前签名，再由 Android 系统确认安装。Android 8 及以上首次更新可能需要允许此应用安装未知来源，返回应用后继续。

显式自定义服务的单包版本清单生成器（不用于 GitHub 的多包清单）：

```powershell
python scripts/publish_manifest.py path/to/signed.apk --aapt path/to/aapt.exe --url https://your-download-host/releases/tv.apk --flavor leanback --output release.json
```

先上传 APK，再发布清单；App 更新时还会匹配 APK 的版本、大小、SHA-256 和当前签名。仅改清单不能让另一个签名的应用覆盖安装。

## 本地日志／升级闭环测试

```powershell
python service/receiver.py --storage .codex/session/server-data --artifacts .codex/session/releases
```

模拟器调试包的日志接口可配置 `http://10.0.2.2:8765/logs`；正式日志接口要求 HTTPS。应用更新在所有构建中都要求受信任的 HTTPS，自定义更新测试需要先为接收器配置可由设备信任的 HTTPS 入口，再填写实际清单地址，不能直接使用上面的 HTTP 端口。该接收器是本地验收服务：云端部署需要 HTTPS 入口、短期上传凭据或服务端配对、限流、访问控制与运维配置，不能把本地无认证模式直接暴露到公网。正式日志接收服务见[阿里云部署说明](日志上传-阿里云OSS部署.md)。
