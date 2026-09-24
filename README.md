# Loom · 密语

离线优先的 Android 手动密码笔记：手动新增、编辑、搜索密码；手机本地加密保存；支持加密文件导入/导出和可选的自建服务端密文同步。桌面小组件只提供快速新增入口，不显示秘密。

## 安装与使用

- 调试 APK：`app/build/outputs/apk/debug/app-debug.apk`（debug 签名，仅供自用试装）。
- 构建：安装 JDK 17、Android SDK 35 和 Gradle，运行 `ANDROID_HOME=你的SDK目录 gradle :app:assembleDebug`。最低 Android 8.0。
- 创建并保管**主密码**；服务端不知道主密码，忘记后无法恢复。登录密码用于账号验证，应与主密码不同。
- 新增/编辑要点「加密保存」；离开或切到后台会丢失尚未保存的草稿。工具箱可导出 `.vault.json` 加密备份；请定期备份，勿将此试用版作为唯一密码副本。
- 长按 Android 桌面空白处 → 小组件 → Loom · 密语 → 快速新建；未解锁时仍需要主密码。

## VPS 服务端：简易部署（Ubuntu/Debian + systemd）

服务端只需要 Node.js 18+，**不需要数据库安装或 `npm install`**。安装脚本会从 31847–31947 自动选择第一个未监听的端口，**不会停止占用端口的其他程序**；新建独立低权限账号、数据目录并设置开机自启。它只监听 VPS 的 `127.0.0.1`，无需对外开放任何应用端口。**cloudflared 必须在同一台 VPS 的主机网络环境运行**；若它运行在 Docker 桥接网络里，容器内的 `127.0.0.1` 指向容器自身，不能直接使用本方案的 URL。

### 第 1 步：把两个文件传到 VPS

在你**自己的电脑**上，从本仓库目录运行（把 `你的用户@VPS地址` 换成真实 SSH 地址）：

```sh
scp server/server.js server/install.sh 你的用户@VPS地址:/tmp/
```

如果你的 SSH 登录用户没有 `/tmp/` 写权限，可换用该用户的家目录，再在 VPS 上按实际文件路径运行。**只需要传这两个文件**，无需在 VPS 上配置 GitHub 私有仓库 Deploy Key。未来更新同样传文件并重新运行安装脚本，原数据文件不会覆盖。

### 第 2 步：在 VPS 上运行安装脚本

SSH 登录 VPS，确认 Node 版本 `node --version` ≥ 18。若未安装，请先安装发行版提供的 Node.js 18+ 和 `curl`、`iproute2`（分别提供 `curl` 和 `ss` 命令）。然后运行：

```sh
sudo bash /tmp/install.sh
```

脚本会先检查原有的 `loom-vault.service`，**不会覆盖来源不明的同名 systemd 服务**。新安装自动跳过已占用端口；重复安装沿用原端口。安装成功时会打印类似：

```text
Cloudflare Tunnel → Public Hostname → Service type: HTTP；URL: 127.0.0.1:31847
```

**以脚本实际打印的端口为准**，不要盲用 31847。若提示端口冲突或启动失败，检查：

```sh
sudo ss -ltnp
sudo journalctl -u loom-vault -n 60 --no-pager
sudo systemctl status loom-vault --no-pager
```

本机连通性测试：`curl -i http://127.0.0.1:实际端口/api/vault`，预期 `401`（未带登录令牌，这是正常响应）。**不要在 VPS 防火墙或云安全组开放这个端口**。

### 第 3 步：Cloudflare Tunnel 和手机

在 Cloudflare Zero Trust 的 Tunnel → Public Hostname 配置你自己的域名，例如 `vault.example.com`；**Service type = HTTP**、**URL = `127.0.0.1:实际端口`**（端口来自安装脚本输出）。HTTPS 由 Cloudflare 对外提供；cloudflared 在 VPS 内访问本地 HTTP。外部用 `https://vault.example.com/api/vault` 测试也应返回 `401`。

在 APK 的工具箱中，服务器地址填 **`https://vault.example.com`**，不要填 `/api`，也不要填 VPS IP、本地端口或 `http://`。注册或登录后添加一条测试记录并「立即同步」。换机后通过同一账号登录，使用原主密码解密恢复。

### 数据、升级与限制

- 数据存于 `/var/lib/loom-vault/data.json`，不在源码目录；**请定期将其备份到另一台设备**，备份仍含敏感账号哈希和密文。手机端也要导出加密备份。
- 本服务适合单台小型 VPS 的**单实例**运行，不得让多个进程同时写一个 JSON 数据文件。服务端重启后账号登录会话会失效，需要重新登录；手机离线使用不受影响。
- 检查服务：`sudo systemctl status loom-vault --no-pager`；查看日志：`sudo journalctl -u loom-vault -n 60 --no-pager`；更新程序：从电脑重新 `scp` 两个文件，再 `sudo bash /tmp/install.sh`。端口保持不变，无须修改 Tunnel。
- Cloudflare Tunnel 的访问/速率限制、VPS 安全更新和外部备份仍需自行配置。没有进行过专业安全审计，勿用作唯一密码副本。

## 开发验证

```sh
node --test server/test/*.test.js
ANDROID_HOME=你的SDK目录 gradle :app:assembleDebug
```

API 为 `POST /api/register`、`POST /api/login`、Bearer 保护的 `GET/PUT /api/vault`；PUT 用修订号 compare-and-swap，冲突返回 `409`。服务器只存加密 vault 字符串，不知道主密码。测试覆盖注册登录、鉴权、冲突、持久化和无效请求。
