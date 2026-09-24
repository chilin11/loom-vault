# Loom · 密语

离线优先的 Android 手动密码笔记：手动新增、编辑、搜索并保存账号密码；可选自建服务端同步加密保险库，也支持导入/导出加密 JSON 备份。桌面小组件提供快速新建入口，不显示任何账号或密码。

## Android APK 和源码构建

- 调试 APK：`app/build/outputs/apk/debug/app-debug.apk`（debug 签名，适合自用试装，不是 Google Play 发布包）。
- JDK 17、Android SDK 35、Gradle；项目 Android Gradle Plugin 8.13.2。构建：`ANDROID_HOME=你的SDK目录 gradle :app:assembleDebug`。
- 最低 Android 8.0（API 26）。卸载前请先导出加密备份。

## 日常使用、换机和桌面小组件

1. 首次启动创建**主密码**（至少 10 位），之后手动新增记录。点「加密保存」后才写入保险库；切到后台会锁定应用，未保存草稿会关闭且不会保留，请离开前保存。
2. 工具箱可导出 `.vault.json` 加密备份。导入前输入备份的主密码，验证成功才替换；替换前的密文副本保存在应用私有文件中，但卸载应用会删掉它。请另存离线备份。
3. 长按桌面空白处 → **小组件** → **Loom · 密语**，添加「快速新建」。点击后，未解锁时先输入主密码，再进入新增表单。小组件不读取或显示密码，也不绕过锁定。
4. 登录/注册使用的账号密码只做身份验证；保险库主密码独立保管，只在设备上派生密钥，不上传服务端。换机时登录云同步账号，验证原保险库主密码，恢复服务端保存的密文。
5. 如果本机和云端保险库不同，应用会要求选择保留哪份；上传本机内容会替换云端，选择前请先导出备份。
6. 主密码遗失时，服务器无法解密或重置它。复制密码后应用会在 60 秒后尽力清除剪贴板，但其他应用可能已读取剪贴板。调试版尚未完成真实手机端到端测试或专业安全审计；不要把它当唯一密码副本。

## VPS + Cloudflare Tunnel 部署服务端（Ubuntu 示例）

服务端只用 Node.js 内建模块，无需 `npm install`、无需数据库服务。推荐 Cloudflare Tunnel 的 Public Hostname 将 HTTPS 转发到 VPS 的 **`127.0.0.1` 本地 HTTP 端口**。Node 不直接暴露公网，VPS 防火墙不必开放这个应用端口。

### 1. 先检查端口，再选一个专用端口

以下示例用 `31847`；这是本机回环监听端口，与 cloudflared 对外提供的 HTTPS 443 **不是同一个端口**。部署前检查本机有没有进程占用：

```sh
sudo ss -ltnp | grep -E ':(31847)([^0-9]|$)' || true
```

无输出才表示当前没有监听 31847。若有输出，**不要停掉未知服务**；换成任意未占用的高位端口（例如 31848），并把下文每个 `31847` 一起改成你选的端口。Cloudflare Tunnel 的本地 Service URL 也要填同一个端口。

### 2. 安装 Node.js、创建独立服务账号和数据目录

安装 Node.js 18+（如果 VPS 已安装，可略过安装，但请检查 `node --version`）：

```sh
node --version
sudo useradd --system --home /opt/loom-vault --shell /usr/sbin/nologin loom-vault
sudo install -d -o loom-vault -g loom-vault -m 750 /opt/loom-vault
sudo install -d -o loom-vault -g loom-vault -m 700 /var/lib/loom-vault
sudo install -d -o root -g root -m 755 /etc/loom-vault
```

为只读检出配置仓库 Deploy Key：在 VPS 上生成专用 SSH key，并将 `.pub` 公钥添加到 GitHub 仓库 `Settings → Deploy keys → Add deploy key`；不要勾选写权限。私钥仅留在 VPS：
```sh
sudo install -d -o loom-vault -g loom-vault -m 700 /opt/loom-vault/.ssh
sudo -u loom-vault ssh-keygen -t ed25519 -N '' -f /opt/loom-vault/.ssh/id_ed25519
sudo cat /opt/loom-vault/.ssh/id_ed25519.pub
```
把公钥粘贴到 Deploy keys 后，用该只读 key 克隆：
```sh
sudo -u loom-vault env GIT_SSH_COMMAND='ssh -i /opt/loom-vault/.ssh/id_ed25519 -o IdentitiesOnly=yes -o StrictHostKeyChecking=accept-new' git clone --depth 1 git@github.com:chilin11/loom-vault.git /opt/loom-vault/app
```

不要复制或在 VPS 命令行中输入个人 GitHub Token；Deploy Key 只授权此私有仓库。若不想配置 Deploy Key，也可用自己电脑的安全 SSH/SCP 把 `server/` 目录传到 VPS。

```sh
sudo -u loom-vault git clone --depth 1 git@github.com:chilin11/loom-vault.git /opt/loom-vault/app
```

如果你尚未给 VPS 配置 Deploy Key，可先在自己的电脑通过 SSH/SCP 将项目的 `server/` 目录安全复制到 VPS，再将下面 `ExecStart` 对应路径改成复制后的位置；本服务端没有 npm 依赖，只需 Node.js 和 `server/server.js`。

### 3. 建立只监听回环地址的 systemd 服务

配置环境文件，数据文件放在源码目录之外，代码更新不会碰到用户数据：

```sh
sudo sh -c 'printf "%s\\n" "HOST=127.0.0.1" "PORT=31847" "DATA_FILE=/var/lib/loom-vault/data.json" > /etc/loom-vault/server.env'
sudo chown root:loom-vault /etc/loom-vault/server.env
sudo chmod 640 /etc/loom-vault/server.env
```

创建 `/etc/systemd/system/loom-vault.service`：

```ini
[Unit]
Description=Loom Vault encrypted sync API
After=network.target

[Service]
Type=simple
User=loom-vault
Group=loom-vault
WorkingDirectory=/opt/loom-vault/app
EnvironmentFile=/etc/loom-vault/server.env
ExecStart=/usr/bin/node /opt/loom-vault/app/server/server.js
Restart=on-failure
RestartSec=3
NoNewPrivileges=true
PrivateTmp=true
ProtectHome=true
ProtectSystem=strict
ReadWritePaths=/var/lib/loom-vault

[Install]
WantedBy=multi-user.target
```

确认 Node 路径与 `command -v node` 一致（如不是 `/usr/bin/node`，修改 `ExecStart`）。然后启动并检查日志及端口：

```sh
sudo systemctl daemon-reload
sudo systemctl enable --now loom-vault
sudo systemctl status loom-vault --no-pager
sudo journalctl -u loom-vault -n 50 --no-pager
sudo ss -ltnp | grep -E ':(31847)\\b'
```

预期监听地址必须是 `127.0.0.1:31847`，不要改成 `0.0.0.0`。如果服务启动失败，先查看 `journalctl`；常见原因是端口占用、Node 路径不符或数据目录权限。需要换端口时编辑 `/etc/loom-vault/server.env` 的 `PORT`，再执行 `sudo systemctl restart loom-vault`。

确认本机 API 有响应（未鉴权请求预期返回 HTTP 401，这表示请求到达 API）：

```sh
curl -i http://127.0.0.1:31847/api/vault
```

**不要**在 VPS 防火墙、安全组或路由器开放 31847，也不用把 Node 改成 HTTPS。外部 TLS 由 cloudflared 提供。

### 4. 在 Cloudflare Tunnel 添加 HTTPS 转发

在 Cloudflare Zero Trust 的 Tunnels 中选中你已经运行的 tunnel，添加 Public Hostname：

- **Subdomain / Domain**：选一个你控制的名称，例如 `vault.example.com`。
- **Service type**：HTTP。
- **URL**：`127.0.0.1:31847`（若第 1 步换了端口，用实际端口）。
- 保存并等待 DNS/隧道状态就绪。Cloudflare 对客户端提供 HTTPS，Tunnel 在 VPS 本机转发到 `http://127.0.0.1:31847`。

若你使用本地 `cloudflared` 配置文件而非 Dashboard，等效的 ingress 项是：

```yaml
ingress:
  - hostname: vault.example.com
    service: http://127.0.0.1:31847
  - service: http_status:404
```

替换域名后按你现有的 cloudflared Tunnel 服务方式重启/更新。用浏览器或手机检查 `https://vault.example.com/api/vault` 返回 HTTP 401；公网访问回环服务不需要单独开 VPS 入站端口。**不要**把这个 hostname 配置成裸 `http://`，也不要把应用程序端口设置成 443。

### 5. 在 Android 应用中登录并验收

在工具箱的服务器地址中填写 HTTPS 域名本身，例如 `https://vault.example.com`（末尾不要添加 `/api` 或本地端口）；注册或登录，然后新建一条测试记录，点击「立即同步」。换机前确认能在另一台设备上登录并用主密码恢复。

运维要点：JSON 数据库默认不含在 Git 源码部署中，位于 `/var/lib/loom-vault/data.json`；应定期做权限受限、最好加密的异机备份。该 JSON 服务只适合**单实例**，不要让多个进程同时写同一文件。服务端仅按网络连接来源 IP 做内存限流；代理环境下额外配置 Cloudflare 访问策略/速率限制并关注滥用。Cloudflare 账号或 Tunnel 中断时同步会暂不可用，但本机保险库仍可离线使用。

## API

JSON 请求/响应，vault 是不透明的加密字符串（最多 2 MiB）；所有非本机连接必须 HTTPS。账号为 3–64 位 ASCII 字符；登录密码为 8–1024 UTF-8 字节。

| 方法与路径 | 请求 | 成功响应 |
|---|---|---|
| `POST /api/register` | `{"username":"...","password":"..."}` | `201 {"token":"...","revision":0,"vault":null}` |
| `POST /api/login` | 同上 | `200 {"token":"...","revision":n,"vault":string或null}` |
| `GET /api/vault` | `Authorization: Bearer <token>` | `200 {"revision":n,"vault":string或null}` |
| `PUT /api/vault` | Bearer token；`{"revision":n,"vault":"密文"}` | `200 {"revision":n+1}` |

PUT 使用 compare-and-swap 版本号；冲突返回 `409 {"error":"conflict","revision":当前值,"vault":当前密文}`，必须先协调版本，不能盲目覆盖。服务器用随机盐 scrypt 哈希校验账号密码；随机 Bearer 会话在 12 小时后失效，服务器重启会清空会话。

## 本地验证

```sh
node --test server/test/*.test.js
ANDROID_HOME=你的SDK目录 gradle :app:assembleDebug
```

服务端测试覆盖注册/登录、鉴权、冲突、持久化和无效输入。Debug APK 已通过构建；尚未完成真实手机端到端测试和专业安全审计。
