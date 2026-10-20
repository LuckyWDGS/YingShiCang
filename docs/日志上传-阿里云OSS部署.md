# 日志上传：阿里云 Function Compute + OSS 部署

App 的「设置 → 日志上传与诊断 → 上传日志」会把本机诊断日志和设备信息通过 HTTPS POST 到一个接收接口，服务器确认保存后 App 才清理这批日志。本方案用一个函数计算（FC）HTTP 函数接收，写入私有 OSS Bucket `ying-shi-cang`。

**App 里不放任何 AccessKey**（APK 可被任何人解包）。函数通过绑定的 RAM 角色拿临时凭证写 OSS；App 只知道函数的 HTTPS 地址。

## 上传内容

- `logs/<UTC日期>/<App版本>/<报告编号>.log`：环形诊断日志（最多 10 MB），写入时已去掉 URL、Cookie、Token、密码。包括播放器解码器/帧率/缓冲统计、源加载失败、插件初始化失败、崩溃（异常 + 前 12 帧堆栈）、进程退出原因。
- `logs/<UTC日期>/<App版本>/<报告编号>.device.json`：品牌型号、Android 版本、ABI、屏幕分辨率/刷新率、内存、播放器设置、各视频解码器声明能力（是否硬解、最大尺寸、4K30/4K60/1080p60、HEVC Main10）。
- 不含账号、Cookie、播放地址、片名、IP 定位。
- 报告编号 = 上传批次 UUID，App 上传成功后显示，用户把它发给开发者即可定位文件。

## 一次性部署（在阿里云控制台操作）

1. **Bucket**（已创建 `ying-shi-cang`）
   - 读写权限保持「私有」。
   - 生命周期规则：前缀 `logs/`，90 天后删除（按需调整），避免无限累积。
   - 记下地域，例如华东1（杭州）`cn-hangzhou`。

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
   - 地域：与 Bucket 相同。
   - 运行时：Python 3.10；处理程序 `index.handler`。
   - 代码：上传本仓库 `service/aliyun_fc/index.py`（单文件，`oss2` 为运行时内置）。
   - 规格：内存 256 MB，超时 60 秒。
   - 函数角色：选择第 2 步的 `ysc-log-receiver`。
   - 环境变量：
     - `OSS_BUCKET` = `ying-shi-cang`
     - `OSS_ENDPOINT` = `https://oss-<地域>-internal.aliyuncs.com`（同地域内网，例如 `https://oss-cn-hangzhou-internal.aliyuncs.com`，免公网流量费）
   - 并发/实例上限：设置最大实例数 3，防止被刷时费用失控。

4. **HTTP 触发器**
   - 请求方式：POST；认证方式：无需认证（App 用批次 UUID + SHA-256 校验，函数只接受合法格式、≤10 MB 的日志）。
   - 复制公网访问地址，形如 `https://xxxx.cn-hangzhou.fcapp.run`。这个地址不是密钥，可以发给开发者。

## 接入 App

- 正式包内置默认地址：构建时加 `-PlogUploadUrl=https://xxxx.cn-hangzhou.fcapp.run`。
- 或者在「设置 → 日志上传与诊断」里手动填写并保存。
- 地址必须是 HTTPS；不需要也不应该填写任何 AccessKey。

## 查看日志

OSS 控制台 → `ying-shi-cang` → `logs/` → 日期 → 版本 → 按报告编号找到 `.log` 与 `.device.json`。

崩溃堆栈来自混淆后的正式包，需要配合该版本的 `app/build/outputs/mapping/leanbackRelease/mapping.txt` 还原（用 Android SDK 的 `retrace`）。**每次正式构建后都要把 mapping.txt 与 APK 一起本地存档**（不要提交 Git）。

## 本地测试

```bash
cd service/aliyun_fc && python -m unittest -v test_index
```

## 可选加固

- 设置环境变量 `UPLOAD_TOKEN` 后函数会要求 `Authorization: Bearer <token>`。由于令牌必须写进 APK，它只能挡住随手扫描，挡不住解包；当前 App 不发送该头，启用前需要同步修改 App。
- 若需要自定义域名，大陆地域需要 ICP 备案；默认 `fcapp.run` 域名足够 App 使用。
