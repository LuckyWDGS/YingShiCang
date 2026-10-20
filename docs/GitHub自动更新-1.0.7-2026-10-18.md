# GitHub 自动更新 1.0.7

日期：2026-10-18。版本 **1.0.7 / versionCode 63**，最低 Android **7.0 / API 24**。构建与雷电更新安装验证已完成；本版本尚未上传或发布到远程。

## 更新流程

更新地址留空时，“检查更新”读取 `LuckyWDGS/YingShiCang` 仓库的最新正式 GitHub Release。使用 Release 正文显示更新内容，读取同一 Release 的 `release-manifest.json`，根据设备支持的 ABI 顺序选择安装包，并通过实际 `versionCode` 判断是否有新版本。已有自定义单包清单地址继续生效。

用户确认后显示下载百分比、已下载大小和进度条。下载完成先核验安装包，再打开 Android 系统安装界面。Android 8 及以上未授予安装来源权限时，先打开授权页，返回应用后继续安装。支持取消、失败重试及系统安装取消后再次安装；安装仍需用户在系统界面确认。

更新专用网络客户端采用系统默认严格 TLS，只允许 HTTPS。GitHub 请求仅允许已验证的官方 API、下载和资产主机，最多五次经过校验的跳转；每次网络请求的期限为元数据120秒、APK10分钟，重定向后的新请求重新计时。日志上传客户端未改动。

## 发布附件与校验

GitHub Release 必须同时提供真实的双架构 APK 和 aggregate `release-manifest.json`，并使用递增的版本号、版本代码及既有正式签名。应用交叉检查 Release 标签、清单版本、包名、渠道、ABI、文件名、大小和 SHA-256；下载后进一步检查 APK 包名、版本、系统要求及当前安装应用的签名。

本轮真实 APK 的最低系统要求是 API 24，清单已据此生成。旧版清单缺少 `minSdk` 的兼容值不能用作新版安装包的实际系统要求。清单中的 `publishedToRemote: false` 表示本地交付状态，当前没有执行远程发布。

| 架构 | 文件 | 字节数 | SHA-256 |
| --- | --- | ---: | --- |
| ARM64 | `YingShiCang-1.0.7-tv-arm64-v8a.apk` | 94,679,992 | `9037d9d3d60db2cf6e137b0454a895dcdbb04a23a2e829abf291cdff5c018feb` |
| ARMv7 | `YingShiCang-1.0.7-tv-armeabi-v7a.apk` | 81,671,402 | `c06ddf3ac67cc9e1b4774a2ae5f15b19d2d5f4cd9d27dd2a8aa03a5963b0b72c` |

两个 APK 均通过正式 v2 签名、包名及版本、单一原生 ABI、非 debug 和16KB ZIP对齐检查。沿用正式证书 SHA-256：

`38f4139f496189ab3ff953334a7952c3cc30e48aa5c47f65b6b5904db0d2d81a`

## 已完成的验证

- 最终电视版 ARM64、ARMv7 Release 构建通过，手机版 ARM64 Release Java 编译通过。最终构建日志记录 `BUILD SUCCESSFUL in 4m 49s`。
- maintenance 相关 JVM 测试共 **5套 / 26项**，失败、错误、跳过均为0：GitHubRelease 13项、UpdateHttp 7项、UpdateManifest 2项、UpdateStorage 2项、LogUpload 2项。
- 本地发布目录包含两个安装包、aggregate清单、SHA256SUMS、安装说明和两份签名校验记录；清单的 `minSdk` 为24，`publishedToRemote` 为false。
- 新增可复用 `scripts/github_release_manifest.py`，读取真实双ABI APK生成清单。SDK36/37均验证成功；ABI互换、62/63混包、篡改APK、意外覆盖清单均被拒绝。以后发布步骤见 `docs/build.md`；Git提交本身不会创建Release，正式Release需要同时上传双APK及清单。

构建日志、测试结果及本地安装包分别保存在 `.codex/session/qa/github-update-v63/build-final63.log`、`app/build/test-results/testLeanbackDebugUnitTest/` 和 `Release/1.0.7/`。

## 雷电实际运行结果

只操作已验证的雷电 `127.0.0.2:5555`。`run01/action-results.json` 记录28项实际功能检查，均通过；输入记录在 `input-events.jsonl`，鼠标位置均取自当时UI树，遥控输入使用真实DPAD事件。

- 真实GitHub默认检查：基线1.0.6和最终正式1.0.7均显示本机已是最新版，没有降级提示。
- 受控新Release：显示1.0.7、Release正文和实际ARM64包94.7MB，遥控器能到下载／暂不更新；暂不更新后可再次检查。
- 实际流式下载显示百分比和字节数，取样16%→32%。取消后中断连接、临时APK清除，已安装基线SHA保持不变。
- HTTP503失败有可读错误及重试按钮；重试重新下载。403限流显示稍后再试，恢复正常后可重新检查。
- 完整下载但被改动一个字节的APK在100%后报SHA-256校验失败，清除文件，没有打开安装器或替换应用。
- 实际Android安装器取消后，应用显示可再次安装，点击能重开安装器；关闭时清除已下载缓存。
- 在Android设置中真实关闭安装来源后，正常包下载校验完成进入授权页。不授权返回仍可重新授权；允许来源后返回自动打开安装器，无须重复下载。
- 在系统安装界面用DPAD移动到“更新”并确认，实际完成同源码基线code62→未修改正式code63。安装后包SHA与交付ARM64包一致，设置显示1.0.7。

新版本链路使用仅进程内生效的同签名testOnly探针与严格TLS受控源：保留HTTPS主机校验，只把四个已允许官方主机解析到本机测试服务；不改变系统DNS、代理或信任证书。因adbd不能绑定443，使用独占本地回环桥及高端口reverse。系统安装会终止旧基线进程，内存中的测试客户端随进程消失；最终63未包含探针规则或测试网络入口。关闭测试服务、移除回环桥和映射后，最终63另行完成真实GitHub检查。

测试后完整恢复1401个内部文件、0个外部文件，SHA和全量inventory一致；原forward/reverse与安装来源app-op已恢复，两个诊断包及临时桥进程／文件已移除，最终停留Home。安装包SHA匹配正式ARM64产物，crash buffer为空，`state.json` 的 `restored=true`。

早期工具准备曾遇到443绑定权限、旧探针不适配OkHttp5.5 DNS接口、窗口选择器变化；首次恢复在数据校验完成后的UI探针出现暂时失败，重新安装归属探针并完整重跑恢复已成功。相关中断保留在 `harness-attempts-and-limits.json`，不能将整个历史执行描述为全部无失败。

## 验证边界

公开仓库当时没有高于本机的新版本，因此公网“未来新Release下载”尚无实测条件；上述受控源验证的是相同更新代码下载并真实安装最终63。手机版仅完成Java编译，未据此宣称手机交互或安装成功。首次需手动安装包含新更新逻辑的1.0.7，之后更高版本才可按此流程更新。

未发布新的 GitHub Release，未操作实体设备。此前记录的四项播放问题不属于本次更新功能修复范围。
