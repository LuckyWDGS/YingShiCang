# 更新镜像（VPS）

App 检查更新和下载安装包时先走自己的服务器，失败再回到 GitHub。版本信息仍以 GitHub 为准：

- `sync.py` 每 10 分钟取一次 GitHub API 的最新正式版，原样保存为 `ysc/latest.json`；`release-manifest.json` 和 APK 必须与 GitHub 声明的大小、SHA-256 一致才发布，`latest.json` 最后替换。保留最近 2 个版本。
- App 用 `GitHubRelease` 照常校验这份 JSON，再用 GitHub 的摘要校验清单和安装包，安装前还核对签名。镜像只提供文件，伪造不了版本。
- 连接只信任钉住的服务器公钥（自签证书，20 年），不依赖域名、CA 或电视的系统时间。
- 独立端口 18443，与服务器上其他站点互不影响。除了 ufw，还要在服务商面板的防火墙放行该端口（未放行时国内直连收不到握手，服务器上也看不到请求）。
- 下载限流：每 IP 2 个、总计 10 个并发，每个连接 8 MB/s（服务器上行实测 11 MB/s 以上；3 MB/s 时每次完整下载都卡在上限），发版当天不挤占服务器其他流量。

## 服务器上的文件

| 路径 | 说明 |
|---|---|
| `/opt/ysc-update/sync.py` | 同步脚本（仅标准库） |
| `/etc/systemd/system/ysc-update-sync.{service,timer}` | 以 `ysc-update` 系统用户运行，低优先级、内存上限 128M |
| `/etc/nginx/sites-available/ysc-update`（链接到 `sites-enabled`） | 18443 端口独立站点，自己的证书和日志（不记录 IP）与限流 |
| `/etc/nginx/ssl/ysc-update.{crt,key}` | 自签证书，SAN 含服务器 IP；换证书时沿用同一私钥，指纹不变 |
| `/var/www/ysc-update/ysc/` | `latest.json` 与 `download/<tag>/` |
| `/var/lib/ysc-update/etag` | GitHub ETag，未变化时不重复下载 |

部署：`ysc-update.nginx.conf` 装为 `/etc/nginx/sites-available/ysc-update` 并链接到 `sites-enabled`，`nginx -t` 通过后 `systemctl reload nginx`，`ufw allow 18443/tcp` 并在服务商面板放行。不改动其他站点。

公钥指纹（写进 App）：

```bash
openssl x509 -in /etc/nginx/ssl/ysc-update.crt -pubkey -noout | openssl pkey -pubin -outform der | openssl dgst -sha256
```

## App 配置

`ysc.updateMirrorAddress` 只在 URL 用自定义主机名（SNI）时填写服务器 IP，直接用 IP 时留空。地址和指纹不入库，写在未跟踪的 `local.properties`（或 `-PupdateMirrorUrl` / `-PupdateMirrorAddress` / `-PupdateMirrorPin`）：

```
ysc.updateMirrorUrl=https://<服务器 IP>:18443/ysc/
ysc.updateMirrorPin=<上面命令输出的 64 位十六进制>
```

留空则只走 GitHub。换服务器、换私钥后，旧版本 App 会自动回落到 GitHub，需要发新版写入新地址/指纹。

## 发版后

GitHub 发布完成后立即同步（不等 10 分钟）：

```bash
ssh <服务器> systemctl start ysc-update-sync.service
```

查看状态：`journalctl -u ysc-update-sync.service -n 20`；下载记录：`/var/log/nginx/ysc-update.access.log`。
