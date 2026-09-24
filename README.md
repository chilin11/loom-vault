# Loom · 密语

离线优先的 Android 手动密码笔记，支持本地加密保存、加密备份、私有云端密文同步和桌面快速新增。

## Android

调试 APK 构建：`ANDROID_HOME=你的SDK目录 gradle :app:assembleDebug`（JDK 17、SDK 35；最低 Android 8.0）。主密码**不会上传**，忘记后服务端无法恢复；请定期从 App 工具箱导出加密备份。新增或编辑要点「加密保存」，切后台会丢失未保存草稿。

## VPS：Git 克隆 + 固定端口部署

服务端只需 **Node.js 18+、Git、curl 和 iproute2（`ss`）**，不需 `npm install` 或数据库安装。固定监听 **`127.0.0.1:31847`**；端口已被其他进程占用时脚本会**报错停止，不会改端口或停止别的服务**。cloudflared 必须运行在这台 VPS 的主机网络环境；Docker 桥接网络中的 `127.0.0.1` 指向容器自身，不能直接使用下面的地址。

### 1. 给 VPS 配置此私有 GitHub 仓库的只读权限

以日常 SSH 用户登录 VPS（下方**不要**使用 root，也不要把私钥发给别人），生成专用密钥：

```sh
mkdir -p "$HOME/.ssh" && chmod 700 "$HOME/.ssh"
ssh-keygen -t ed25519 -f ~/.ssh/loom-vault-github -N ''
cat ~/.ssh/loom-vault-github.pub
```

复制 `.pub` 的整行公钥，打开 [仓库 Settings → Deploy keys](https://github.com/chilin11/loom-vault/settings/keys) → **Add deploy key**，粘贴公钥，**不要勾选 Allow write access**。私钥 `~/.ssh/loom-vault-github` 留在 VPS，不能上传。

配置仅这个仓库使用专用密钥（不改变你其他 Git 仓库的 SSH 设置）：

```sh
GIT_SSH_COMMAND="ssh -i $HOME/.ssh/loom-vault-github -o IdentitiesOnly=yes -o StrictHostKeyChecking=accept-new" git clone git@github.com:chilin11/loom-vault.git "$HOME/loom-vault"
```

如果已有 `~/loom-vault` 目录，不要再次克隆；请先确认它是否是此前克隆的本仓库，再按下文更新步骤执行 `git pull`。

### 2. 检查固定端口并一键安装

```sh
node --version
sudo ss -ltnp 'sport = :31847'
sudo bash ~/loom-vault/server/install.sh
```

Node 须为 18+，建议通过系统包安装，使独立服务账号也能运行。`ss` 有输出且不是此前安装的 `loom-vault` 服务时，**不要**运行安装脚本或关停未知服务；需要先自行处理占用。安装脚本也会自行检查端口、同名 systemd 服务与原有数据配置，遇到不一致就退出。安装成功时打印：

```text
Cloudflare Tunnel → Public Hostname → Service type: HTTP；URL: 127.0.0.1:31847
```

验证本机 API：

```sh
curl -i http://127.0.0.1:31847/api/vault
```

返回 HTTP `401` 表示服务可访问，只是没有登录令牌。应用端口**不需对公网开放**。

### 3. 接上 cloudflared

在 Cloudflare Tunnel 的 Public Hostname 配域名，例如 `vault.example.com`；Service type 选 **HTTP**，URL 固定填 **`127.0.0.1:31847`**。通过公网访问 `https://vault.example.com/api/vault` 应返回 `401`。App 工具箱中的服务器地址只填写 **`https://vault.example.com`**，不要加 `/api` 或端口，也不要填 HTTP/IP。

### 更新和备份

在 VPS 执行（仍用上面同一个只读 SSH 密钥）：

```sh
cd ~/loom-vault
GIT_SSH_COMMAND="ssh -i $HOME/.ssh/loom-vault-github -o IdentitiesOnly=yes -o StrictHostKeyChecking=accept-new" git pull --ff-only
sudo bash server/install.sh
```

重复安装**仍用 31847**，并保留 `/var/lib/loom-vault/data.json`；如果现有配置不一致则拒绝覆盖。定期将该数据文件备份到别处，它包含账号密码哈希和加密保险库。服务端仅支持单实例；重启会令登录会话失效，需重新登录。故障时检查 `sudo journalctl -u loom-vault -n 60 --no-pager` 与 `sudo systemctl status loom-vault --no-pager`。

## 验证与说明

```sh
bash -n server/install.sh
node --test server/test/*.test.js
ANDROID_HOME=你的SDK目录 gradle :app:assembleDebug
```

服务端提供 `POST /api/register`、`POST /api/login`、Bearer 保护的 `GET/PUT /api/vault`；仅保存密文和版本号，更新遇到版本冲突返回 `409`。本项目尚未通过 VPS 实机部署测试或专业安全审计，请勿将其作为密码的唯一副本。
