#!/usr/bin/env bash
# Run on an Ubuntu/Debian VPS: sudo bash /tmp/install.sh (server.js in the same directory).
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
PORT=''
if [[ -f $CONFIG_FILE ]]; then
  # Existing config is intentionally immutable on update, to avoid switching databases.
  grep -Fqx 'HOST=127.0.0.1' "$CONFIG_FILE" || fail '现有配置 HOST 不是 127.0.0.1；请先人工检查'
  grep -Fqx "DATA_FILE=$DATA_DIR/data.json" "$CONFIG_FILE" || fail '现有 DATA_FILE 不匹配；拒绝覆盖旧数据配置'
  PORT="$(awk -F= '$1=="PORT"{print $2}' "$CONFIG_FILE")"
  [[ $PORT =~ ^[0-9]{1,5}$ ]] && (( 10#$PORT > 0 && 10#$PORT < 65536 )) || fail '现有端口无效'
  if port_used "$PORT"; then
    pid="$(systemctl show loom-vault.service -p MainPID --value 2>/dev/null || true)"
    [[ $pid =~ ^[1-9][0-9]*$ ]] || fail "端口 $PORT 已占用，但服务进程未知；拒绝重装"
    listener="$(ss -H -ltnp "sport = :$PORT")"
    [[ $listener == *"pid=$pid,"* ]] || fail "端口 $PORT 并非本服务占用；拒绝重装"
  fi
else
  [[ $unit_state == not-found || -z $unit_state ]] || fail '已有服务但没有配置文件；拒绝接管'
  for candidate in $(seq 31847 31947); do
    if ! port_used "$candidate"; then PORT=$candidate; break; fi
  done
  [[ -n $PORT ]] || fail '31847–31947 全部被占用；不会停止任何现有服务'
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
