# 日志上传：阿里云 Function Compute + OSS 部署

App 的「设置 → 日志上传与诊断 → 上传日志」会把本机诊断日志和设备信息通过 HTTPS POST 到一个接收接口，服务器确认保存后 App 才清理这批日志。

**崩溃自动上传**：应用崩溃（Java 崩溃，以及 Android 11+ 上由系统记录的原生崩溃，例如 MPV/FFmpeg）后，下次启动约 15 秒会自动上传一次日志；失败则在之后的启动中重试，最多 3 次，之后放弃（日志仍留在本机，可手动上传）。新的崩溃会重置次数。其他情况仍需用户手动上传。未配置接收地址时不会上传。本方案用一个函数计算（FC）HTTP 函数接收，写入私有 OSS Bucket `ying-shi-cang`（华北2 北京，`cn-beijing`）。

**App 里不放任何 AccessKey**（APK 可被任何人解包）。函数通过绑定的 RAM 角色拿临时凭证写 OSS；App 只知道函数的 HTTPS 地址。

## 设备 ID

每台设备有一个匿名设备 ID，形如 `K7Q2-9MZD`，显示在「日志上传与诊断」页面上，上传成功的提示里也会带上。

- Android 8 及以上：由系统为本应用签名分配的 ANDROID_ID 加盐哈希得到，**卸载重装后不变**，恢复出厂设置才会变；无法与其他 App 关联。
- Android 7 及以下：随机生成并保存在应用数据里，清除数据或重装后会换一个新 ID（旧系统的 ANDROID_ID 在克隆盒子镜像上可能重复，不能用）。
- 1.1.0 及更早版本不发送设备 ID，上传会落在 `logs/unknown/`。

## 存储结构（永久保存）

```
logs/<设备ID>/device.json                                  该设备最近一次上传的设备信息
logs/<设备ID>/<UTC日期>/<App版本>_<报告编号>.log             诊断日志
logs/<设备ID>/<UTC日期>/<App版本>_<报告编号>.device.json     本次上传时的设备信息
```

- `.log`：环形诊断日志（最多 10 MB），写入时已去掉 URL、Cookie、Token、密码。包括播放器解码器/帧率/缓冲统计、源加载失败、插件初始化失败、崩溃（异常 + 前 12 帧堆栈）、进程退出原因。
- `.device.json`：设备 ID、报告编号、App 版本、上传时间（UTC）、品牌型号、Android 版本、ABI、屏幕分辨率/刷新率、内存、播放器设置、各视频解码器声明能力（是否硬解、最大尺寸、4K30/4K60/1080p60、HEVC Main10）。
- `.log` 对象还带有自定义元数据 `x-oss-meta-device-id / app-version / model / android / sha256 / bytes`，在 OSS 控制台文件详情里可直接看到。
- 不含账号、Cookie、播放地址、片名、IP 定位。
- 报告编号 = 上传批次 UUID。同一批次重试不会重复写入；不同内容冒用同一编号会被拒绝（409）。

日志**不设置生命周期删除规则，永久保留**。OSS 上传（公网流入）流量免费，费用只有存储容量和请求次数；若以后想省钱又不删，可加一条生命周期规则：前缀 `logs/`，30 天后转「低频访问」或「归档」（只转存储类型，不删除）。

## 一次性部署（在阿里云控制台操作）

1. **Bucket**（已创建 `ying-shi-cang`，华北2 北京）
   - 读写权限保持「私有」，「阻止公共访问」保持开启。
   - 不要添加删除型生命周期规则。

2. **RAM 角色**（访问控制 RAM → 角色 → 创建角色）
   - 可信实体：阿里云服务 → 函数计算（`fc.aliyuncs.com`）。
   - 名称例如 `ysc-log-receiver`。
   - 新建自定义权限策略并授权给该角色（只允许 `logs/` 下的对象；`oss:GetObject` 用于重试时 HeadObject 核对已存在的批次）：

     ```json
     {
       "Version": "1",
       "Statement": [{
         "Effect": "Allow",
         "Action": ["oss:PutObject", "oss:GetObject"],
         "Resource": ["acs:oss:*:*:ying-shi-cang/logs/*"]
       }]
     }
     ```

3. **函数**（函数计算 FC 3.0 → 创建函数 → 事件函数）
   - 地域：华北2（北京），与 Bucket 相同。
   - 运行时：Python 3.10；处理程序 `index.handler`。
   - 代码：上传本仓库 `service/aliyun_fc/index.py`（单文件，`oss2` 为运行时内置）。
   - 规格：内存 256 MB，超时 60 秒。
   - 函数角色：选择第 2 步的 `ysc-log-receiver`。
   - 环境变量：
     - `OSS_BUCKET` = `ying-shi-cang`
     - `OSS_ENDPOINT` = `https://oss-cn-beijing-internal.aliyuncs.com`（同地域内网，免流量费）
   - 并发/实例上限：设置最大实例数 3，防止被刷时费用失控。

4. **HTTP 触发器**
   - 请求方式：POST；认证方式：无需认证（App 用批次 UUID + SHA-256 校验，函数只接受合法格式、≤10 MB 的日志）。
   - 复制公网访问地址，形如 `https://xxxx.cn-beijing.fcapp.run`。这个地址不是密钥，可以发给开发者。

## 接入 App

- 正式包内置默认地址：在未跟踪的 `local.properties` 写 `ysc.logUploadUrl=https://xxxx.cn-beijing.fcapp.run`（本机已配置），或构建时加 `-PlogUploadUrl=...`（优先）。地址不提交 Git。
- Release 混淆规则必须保留 `LogUploader$Receipt`（`app/proguard-rules.pro`），否则 R8 合并该类导致 App 读不到回执，日志不会被清理。
- 设置页不提供地址输入框，用户无法修改；以前版本保存的自定义地址（`ysc_logs_url`）也不再生效。未内置地址的构建无法上传。
- 地址必须是 HTTPS；不需要也不应该填写任何 AccessKey。

## 查看日志

用户反馈时让对方报「设备 ID + 报告编号」（上传成功提示里两者都有）：

OSS 控制台 → `ying-shi-cang` → 文件列表 → `logs/` → 设备 ID → 先看 `device.json`（机型、系统、App 版本）→ 日期 → 对应 `.log`。

崩溃堆栈来自混淆后的正式包，需要配合该版本的 `app/build/outputs/mapping/leanbackRelease/mapping.txt` 还原（用 Android SDK 的 `retrace`）。**每次正式构建后都要把 mapping.txt 与 APK 一起本地存档**（不要提交 Git）。

## 下载与分析日志（开发者电脑）

读取用单独的**只读** RAM 用户，不复用函数角色：

1. 权限策略 `ysc-log-oss-read`：
   ```json
   {
     "Version": "1",
     "Statement": [
       {"Effect": "Allow", "Action": "oss:ListObjects", "Resource": "acs:oss:*:*:ying-shi-cang"},
       {"Effect": "Allow", "Action": "oss:GetObject", "Resource": "acs:oss:*:*:ying-shi-cang/logs/*"}
     ]
   }
   ```
2. 用户 `ysc-log-reader`：只开「使用永久 AccessKey 访问」，授权上面的策略。
3. 在开发电脑上设置用户环境变量（值不要带尖括号或空格，也不要发到聊天或提交到仓库）：`setx OSS_ACCESS_KEY_ID "..."`、`setx OSS_ACCESS_KEY_SECRET "..."`。
4. 安装官方 SDK：`python -m pip install oss2`。

用法（脚本先读进程环境变量，读不到再读 Windows 用户环境变量，因此 setx 后无需重启）：

```bash
python scripts/fetch_logs.py latest -n 10            # 所有设备最新的上传
python scripts/fetch_logs.py device K7Q2-9MZD --download
python scripts/fetch_logs.py report <报告编号>        # 找到并下载
```

下载到 `.codex/session/logs/<设备ID>/<日期>/`（Git 忽略）。日志里有 Java 崩溃时，脚本按文件名中的版本用 `Release/<版本>/mapping/tv-mapping.txt`（手机版用 `phone-mapping.txt`）和 Android SDK 的 `retrace` 生成 `.retraced.log`；没有对应 mapping 时跳过并提示。

## 本地测试

```bash
cd service/aliyun_fc && python -m unittest -v test_index
```

## 可选加固

- 设置环境变量 `UPLOAD_TOKEN` 后函数会要求 `Authorization: Bearer <token>`。由于令牌必须写进 APK，它只能挡住随手扫描，挡不住解包；当前 App 不发送该头，启用前需要同步修改 App。
- 若需要自定义域名，大陆地域需要 ICP 备案；默认 `fcapp.run` 域名足够 App 使用。
