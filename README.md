# Loom · 密语

离线优先的 Android 手动密码笔记，支持本地加密保存、加密备份、私有云端密文同步和桌面快速新增。

## Android

调试 APK 构建：`ANDROID_HOME=你的SDK目录 gradle :app:assembleDebug`（JDK 17、SDK 35；最低 Android 8.0）。新增记录默认只需填**网站/名称别名和密码**；账号可选，分类、网址、备注点「更多信息」再填。主密码**不会上传**，忘记后服务端无法恢复；请定期从 App 工具箱导出加密备份。新增或编辑要点「加密保存」，切后台会丢失未保存草稿。

## VPS：Git 克隆 + 固定端口部署

服务端只需 **Node.js 18+、Git、curl 和 iproute2（`ss`）**，不需 `npm install` 或数据库安装。固定监听 **`127.0.0.1:31847`**；端口已被其他进程占用时脚本会**报错停止，不会改端口或停止别的服务**。cloudflared 必须运行在这台 VPS 的主机网络环境；Docker 桥接网络中的 `127.0.0.1` 指向容器自身，不能直接使用下面的地址。

### 1. 用已有 GitHub Token 克隆私有仓库

以平时登录 VPS 的普通用户运行（无需生成 SSH Key，也无需配置 Deploy Key）：

```sh
git -c credential.helper= clone https://github.com/chilin11/loom-vault.git "$HOME/loom-vault"
```

Git 提示 `Username for 'https://github.com'` 时输入 `chilin11`；提示 `Password` 时粘贴你的 GitHub Token（输入时不会显示）。Token 必须有读取该私有仓库的权限：fine-grained token 至少需该仓库的 **Contents: Read-only**；经典 PAT 需 `repo` 权限。**不要**把 Token 放进克隆 URL、命令行、截图或聊天。这里关闭此命令的凭据缓存，后续更新会再次提示输入；请自行安全保管 Token。

如果 `~/loom-vault` 已存在，不要重复 clone；先用 `git -C "$HOME/loom-vault" remote -v` 确认它是此仓库，再按下方更新步骤操作。

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

在 VPS 执行；`git pull` 如再次提示密码，继续粘贴 Token：

```sh
cd "$HOME/loom-vault"
git -c credential.helper= pull --ff-only
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
