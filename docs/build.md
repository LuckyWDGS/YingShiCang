# 构建与模拟器验证

## 环境

- JDK 21、Python 3.10、Android SDK（platform android-37.0、build-tools 36.0.0）、网络可访问 Gradle／Maven／Chaquopy。
- 设置 `JAVA_HOME`、`ANDROID_HOME`、`PYTHON_BUILD`，或复制 `local.properties.example` 为未跟踪的 `local.properties` 填本机路径。
- 运行 `python scripts/prepare_media.py` 下载固定提交的 Media3 依赖；已有该提交源码可设置 `MEDIA3_DIR`。不再依赖开发者写死的磁盘路径。
- Android 原生编译还会需要 NDK 29.0.14206865、28.2.13676358 和 CMake；由 Gradle／SDK Manager 根据已接受的 SDK 配置准备。

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
.\gradlew.bat :app:assembleLeanbackRelease -PappVersionCode=2 -PappVersionName=0.2.0
```

版本清单生成器：

```powershell
python scripts/publish_manifest.py path/to/signed.apk --aapt path/to/aapt.exe --url https://your-download-host/releases/tv.apk --flavor leanback --output release.json
```

先上传 APK，再发布清单；App 更新时还会匹配 APK 的版本、大小、SHA-256 和当前签名。仅改清单不能让另一个签名的应用覆盖安装。

## 本地日志／升级闭环测试

```powershell
python service/receiver.py --storage .codex/session/server-data --artifacts .codex/session/releases
```

模拟器调试包可配置 `http://10.0.2.2:8765/logs` 和 `http://10.0.2.2:8765/releases/release.json`。正式包仅允许 HTTPS。该接收器是本地验收服务：云端部署需要 HTTPS 入口、短期上传凭据或服务端配对、限流、访问控制与运维配置，不能把本地无认证模式直接暴露到公网。尚未部署到阿里云。
