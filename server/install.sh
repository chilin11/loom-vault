#!/usr/bin/env bash
# 从已克隆的私有仓库运行：sudo bash ~/loom-vault/server/install.sh
set -euo pipefail
fail() { printf '错误：%s\n' "$*" >&2; exit 1; }
[[ $EUID -eq 0 ]] || fail '请使用 sudo bash install.sh 运行'
for tool in node systemctl ss curl useradd install runuser readlink; do
  command -v "$tool" >/dev/null 2>&1 || fail "找不到 $tool；请先安装 Node.js 18+、iproute2、curl、systemd"
done
node -e 'if(Number(process.versions.node.split(".")[0])<18)process.exit(1)' || fail '需要 Node.js 18 或更新版本'
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
[[ -f "$SCRIPT_DIR/server.js" ]] || fail "找不到 $SCRIPT_DIR/server.js；请将两个文件放在同一目录"
APP_DIR=/opt/loom-vault
DATA_DIR=/var/lib/loom-vault
CONFIG_FILE=/etc/loom-vault/server.env
SERVICE_FILE=/etc/systemd/system/loom-vault.service
NODE_BIN="$(readlink -f "$(command -v node)")"
[[ $NODE_BIN == /* && -x $NODE_BIN ]] || fail '找不到可执行的 Node.js'
case $NODE_BIN in /root/*|/home/*|/run/user/*) fail 'Node 安装在用户私有目录中；请安装系统级 Node.js' ;; esac

# Never shadow an existing unit, including units installed in /lib, /usr/lib or /run.
unit_state="$(systemctl show loom-vault.service -p LoadState --value 2>/dev/null || true)"
unit_path="$(systemctl show loom-vault.service -p FragmentPath --value 2>/dev/null || true)"
if [[ $unit_state != not-found && -n $unit_state ]]; then
  [[ $unit_path == "$SERVICE_FILE" && -f $SERVICE_FILE ]] || fail "已有同名 systemd 服务 ($unit_path)，拒绝接管"
  grep -Fqx '# Managed by Loom Vault install.sh' "$SERVICE_FILE" || fail '已有同名服务不属于本安装脚本，拒绝接管'
elif [[ -e $SERVICE_FILE ]]; then
  fail "发现未知的 $SERVICE_FILE，拒绝覆盖"
fi

port_used() { [[ -n "$(ss -H -ltn "sport = :$1")" ]]; }
PORT=31847
if [[ -f $CONFIG_FILE ]]; then
  # 重装只接受本脚本生成的三行配置；重复键或未知键一律拒绝，避免 systemd 读取到不同的有效值。
  mapfile -t config_lines < "$CONFIG_FILE"
  [[ ${#config_lines[@]} -eq 3 && ${config_lines[0]} == 'HOST=127.0.0.1' && ${config_lines[1]} == "PORT=$PORT" && ${config_lines[2]} == "DATA_FILE=$DATA_DIR/data.json" ]] || fail '已有服务配置与固定端口/数据路径不一致，拒绝覆盖，请人工检查'
else
  [[ $unit_state == not-found || -z $unit_state ]] || fail '已有服务但没有配置文件，拒绝接管'
fi
if port_used "$PORT"; then
  pid="$(systemctl show loom-vault.service -p MainPID --value 2>/dev/null || true)"
  [[ $pid =~ ^[1-9][0-9]*$ ]] || fail "固定端口 $PORT 已被其他进程占用；请用 sudo ss -ltnp 'sport = :$PORT' 检查，不会停止它"
  listener="$(ss -H -ltnp "sport = :$PORT")"
  [[ $listener == *"pid=$pid,"* ]] || fail "固定端口 $PORT 不是本服务占用，拒绝重装"
fi

if ! id -u loom-vault >/dev/null 2>&1; then
  useradd --system --home "$APP_DIR" --shell /usr/sbin/nologin loom-vault
fi
runuser -u loom-vault -- test -x "$NODE_BIN" || fail "服务账号不能运行 $NODE_BIN；请安装系统级 Node.js"
runuser -u loom-vault -- "$NODE_BIN" -e 'if(Number(process.versions.node.split(".")[0])<18)process.exit(1)' || fail '服务账号无法运行 Node.js 18+'
install -d -o root -g root -m 755 "$APP_DIR" /etc/loom-vault
install -d -o loom-vault -g loom-vault -m 700 "$DATA_DIR"
install -o root -g root -m 644 "$SCRIPT_DIR/server.js" "$APP_DIR/server.js"
if [[ ! -e $CONFIG_FILE ]]; then
  printf 'HOST=127.0.0.1\nPORT=%s\nDATA_FILE=%s/data.json\n' "$PORT" "$DATA_DIR" > "$CONFIG_FILE"
  chmod 644 "$CONFIG_FILE"
fi
cat > "$SERVICE_FILE" <<UNIT
# Managed by Loom Vault install.sh
[Unit]
Description=Loom Vault encrypted sync API
After=network.target

[Service]
Type=simple
User=loom-vault
Group=loom-vault
WorkingDirectory=/opt/loom-vault
EnvironmentFile=/etc/loom-vault/server.env
ExecStart=$NODE_BIN /opt/loom-vault/server.js
Restart=on-failure
RestartSec=3
NoNewPrivileges=true
PrivateTmp=true
ProtectHome=true
ProtectSystem=strict
ReadWritePaths=/var/lib/loom-vault

[Install]
WantedBy=multi-user.target
UNIT
chmod 644 "$SERVICE_FILE"
systemctl daemon-reload
systemctl enable loom-vault.service >/dev/null
systemctl restart loom-vault.service

# A 401 alone may come from someone else's process: verify PID and bound address too.
ready=false
for _ in {1..20}; do
  pid="$(systemctl show loom-vault.service -p MainPID --value 2>/dev/null || true)"
  listener="$(ss -H -ltnp "sport = :$PORT" 2>/dev/null || true)"
  code="$(curl --max-time 2 -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:$PORT/api/vault" || true)"
  if systemctl is-active --quiet loom-vault.service && [[ $pid =~ ^[1-9][0-9]*$ && $listener == *"127.0.0.1:$PORT"* && $listener == *"pid=$pid,"* && $code == 401 ]]; then
    ready=true; break
  fi
  sleep 1
done
if [[ $ready != true ]]; then
  fail '服务未通过进程、端口和 HTTP 检查。查看：sudo journalctl -u loom-vault -n 60 --no-pager'
fi
printf '\n安装成功。仅监听 VPS 本机，无需开放防火墙端口。\n'
printf 'Cloudflare Tunnel → Public Hostname → Service type: HTTP；URL: 127.0.0.1:%s\n' "$PORT"
printf '（配置文件方式：service: http://127.0.0.1:%s）\n' "$PORT"
printf '手机 App 里填 HTTPS 域名，如 https://vault.example.com，不要填本地端口。\n'
printf '日志：sudo journalctl -u loom-vault -n 60 --no-pager\n数据：%s/data.json（请另行备份）\n' "$DATA_DIR"
