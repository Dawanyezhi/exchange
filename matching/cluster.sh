#!/usr/bin/env bash
#
# Laser Matching Engine - Aeron Cluster 管理脚本
#
# Usage: ./cluster.sh <command> [options]
#

set -euo pipefail

# ======================== 配置区 ========================
PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "${PROJECT_DIR}/.." && pwd)"
JAR_NAME="exchange-matching-1.0.0-SNAPSHOT.jar"
JAR_PATH="${PROJECT_DIR}/target/${JAR_NAME}"
PID_DIR="${ROOT_DIR}/pids/matching"
LOG_DIR="${ROOT_DIR}/logs/matching"
MAX_NODE_ID=2
BASE_SERVER_PORT=9090
WEB_SERVER_PORT=8080

JVM_OPTS=(
    --add-opens java.base/jdk.internal.misc=ALL-UNNAMED
    --add-opens java.base/sun.nio.ch=ALL-UNNAMED
    --add-opens java.base/java.nio=ALL-UNNAMED
    --add-opens java.base/java.lang=ALL-UNNAMED
)

# ======================== 工具函数 ========================

log_info()  { printf '\033[32m[INFO]\033[0m  %s\n' "$*"; }
log_warn()  { printf '\033[33m[WARN]\033[0m  %s\n' "$*"; }
log_error() { printf '\033[31m[ERROR]\033[0m %s\n' "$*"; }

validate_node_id() {
    local nid="$1"
    local max="${2:-$MAX_NODE_ID}"
    if ! [[ "$nid" =~ ^[0-9]+$ ]] || [ "$nid" -gt "$max" ]; then
        log_error "无效的 nodeId: $nid (有效范围: 0-${max})"
        exit 1
    fi
}

pid_file() { echo "${PID_DIR}/node-${1}.pid"; }
log_file() { echo "${LOG_DIR}/node-${1}.log"; }
role_file() { echo "${PID_DIR}/node-${1}.role"; }

is_running() {
    local pf
    pf="$(pid_file "$1")"
    if [ -f "$pf" ]; then
        local pid
        pid="$(cat "$pf")"
        if kill -0 "$pid" 2>/dev/null; then
            return 0
        fi
        rm -f "$pf"
    fi
    return 1
}

# ======================== 核心命令 ========================

do_build() {
    log_info "编译 exchange-matching 及依赖模块 ..."
    cd "$ROOT_DIR"
    mvn -pl matching -am clean package -DskipTests -q
    log_info "编译完成: ${JAR_PATH}"
}

# 启动节点（通用）
# 参数: $1=nodeId  $2...=额外 JVM -D 参数（可选）
do_start_node() {
    local nid="$1"; shift
    local port=$((WEB_SERVER_PORT + nid))

    if is_running "$nid"; then
        local pid
        pid="$(cat "$(pid_file "$nid")")"
        log_warn "节点 ${nid} 已在运行中 (PID: ${pid})，跳过启动"
        return 0
    fi

    mkdir -p "$PID_DIR" "$LOG_DIR"

    local lf
    lf="$(log_file "$nid")"

    log_info "启动节点 ${nid} (HTTP 端口: ${port}) ..."
    nohup java "${JVM_OPTS[@]}" \
        -Dlaser.aeron.cluster.config.nodeId="${nid}" \
        -Dserver.port="${port}" \
        ${@+"$@"} \
        -jar "${JAR_PATH}" \
        > "$lf" 2>&1 &

    local pid=$!
    echo "$pid" > "$(pid_file "$nid")"
    log_info "节点 ${nid} 已启动 (PID: ${pid}), 日志: ${lf}"
}

# 多节点模式：启动指定节点
do_start() {
    local nid="$1"
    validate_node_id "$nid"
    do_start_node "$nid"
}

# 单节点模式：nodeId=0, clusterNodeSize=1，直接成为 Leader
do_start_single() {
    log_info "=== 单节点集群模式 ==="
    do_start_node 0 "-Dlaser.aeron.cluster.config.clusterNodeSize=1"
}

# 先检查进程是否存在
# 存在就先发 SIGTERM
# 等最多 10 秒让它自己退出
# 如果还不退，再发 SIGKILL
# 清理 pid 文件
do_stop() {
    local nid="$1"
    validate_node_id "$nid"

    if ! is_running "$nid"; then
        log_warn "节点 ${nid} 未运行"
        return 0
    fi

    local pid
    pid="$(cat "$(pid_file "$nid")")"

    log_info "正在停止节点 ${nid} (PID: ${pid}) ..."
    kill "$pid" 2>/dev/null || true

    local waited=0
    while kill -0 "$pid" 2>/dev/null && [ "$waited" -lt 10 ]; do
        sleep 1
        waited=$((waited + 1))
    done

    if kill -0 "$pid" 2>/dev/null; then
        log_warn "节点 ${nid} 未在 10 秒内退出，强制终止 ..."
        kill -9 "$pid" 2>/dev/null || true
    fi

    rm -f "$(pid_file "$nid")"
    rm -f "$(role_file "$nid")"
    log_info "节点 ${nid} 已停止"
}

do_restart() {
    local nid="$1"
    validate_node_id "$nid"

    log_info "=== 重启节点 ${nid} ==="
    do_stop "$nid"
    do_build
    do_start "$nid"
    log_info "节点 ${nid} 重启完成"
}

do_start_all() {
    do_build
    for nid in $(seq 0 "$MAX_NODE_ID"); do
        do_start "$nid"
        if [ "$nid" -lt "$MAX_NODE_ID" ]; then
            sleep 2
        fi
    done
    log_info "全部 $((MAX_NODE_ID + 1)) 个节点已启动"
}

do_stop_all() {
    for nid in $(seq 0 "$MAX_NODE_ID"); do
        do_stop "$nid"
    done
    log_info "全部节点已停止"
}

do_status() {
    printf "\n  %-10s %-10s %-10s %-12s %-8s\n" "NodeId" "PID" "HTTP Port" "Role" "Status"
    printf "  %-10s %-10s %-10s %-12s %-8s\n" "------" "---" "---------" "----" "------"
    for nid in $(seq 0 "$MAX_NODE_ID"); do
        local port=$((WEB_SERVER_PORT + nid))
        local role="-"
        local rf
        rf="$(role_file "$nid")"
        if [ -f "$rf" ]; then
            role="$(cat "$rf")"
        fi
        if is_running "$nid"; then
            local pid
            pid="$(cat "$(pid_file "$nid")")"
            printf "  %-10s %-10s %-10s %-12s \033[32m%-8s\033[0m\n" "$nid" "$pid" "$port" "$role" "running"
        else
            printf "  %-10s %-10s %-10s %-12s \033[31m%-8s\033[0m\n" "$nid" "-" "$port" "-" "stopped"
        fi
    done
    echo ""
}

do_log() {
    local nid="$1"
    validate_node_id "$nid"

    local lf
    lf="$(log_file "$nid")"

    if [ ! -f "$lf" ]; then
        log_error "日志文件不存在: ${lf}"
        exit 1
    fi

    log_info "查看节点 ${nid} 日志 (Ctrl+C 退出) ..."
    tail -f "$lf"
}

# 通过 HTTP 接口手动触发快照
# 参数: $1=nodeId (默认 0，应始终对 LEADER 触发)
do_snapshot() {
    local nid="${1:-0}"
    validate_node_id "$nid"
    local port=$((WEB_SERVER_PORT + nid))
    local url="http://localhost:${port}/snapshot/trigger"

    if ! is_running "$nid"; then
        log_error "节点 ${nid} 未运行，无法触发快照"
        exit 1
    fi

    log_info "向节点 ${nid} (${url}) 发送手动快照请求 ..."
    local response
    response="$(curl -sS -X POST -w "\nHTTP_STATUS:%{http_code}" "$url")" || {
        log_error "HTTP 请求失败，检查节点是否健康"
        exit 1
    }

    local http_status
    http_status="$(echo "$response" | grep -E "^HTTP_STATUS:" | cut -d: -f2)"
    local body
    body="$(echo "$response" | grep -v "^HTTP_STATUS:")"

    echo "$body"
    echo ""

    case "$http_status" in
        200)
            log_info "快照触发成功 (HTTP 200)"
            ;;
        423)
            log_warn "节点 ${nid} 非 LEADER，请对 LEADER 节点触发 (HTTP 423 LOCKED)"
            exit 1
            ;;
        503)
            log_error "集群尚未就绪 (HTTP 503)"
            exit 1
            ;;
        *)
            log_error "未知响应: HTTP ${http_status}"
            exit 1
            ;;
    esac
}

# 控制面命令: 通过 mock-counter (10880) HTTP 转发到集群
# (mock-counter 必须先 ./client.sh start)
MOCK_COUNTER_URL="http://localhost:10880"

do_list_symbol() {
    local code="$1" name="$2" base="$3" quote="$4"
    if [[ -z "$code" || -z "$name" || -z "$base" || -z "$quote" ]]; then
        log_error "用法: ./cluster.sh list-symbol <symbolCode> <symbolName> <baseCoinId> <quoteCoinId>"
        log_error "例:   ./cluster.sh list-symbol 3 doge-usdt 100 200"
        exit 1
    fi
    log_info "上币: code=${code}, name=${name}, base=${base}, quote=${quote}"
    curl -sS -X POST "${MOCK_COUNTER_URL}/api/symbol/list?symbolCode=${code}&symbolName=${name}&baseCoinId=${base}&quoteCoinId=${quote}"
    echo ""
}

do_delist_symbol() {
    local code="$1"
    if [[ -z "$code" ]]; then
        log_error "用法: ./cluster.sh delist-symbol <symbolCode>"
        exit 1
    fi
    log_info "下币: code=${code}"
    curl -sS -X POST "${MOCK_COUNTER_URL}/api/symbol/delist?symbolCode=${code}"
    echo ""
}

do_switch_trade() {
    local code="$1" action="$2"
    if [[ -z "$code" ]]; then
        log_error "用法: ./cluster.sh ${action}-trade <symbolCode>"
        exit 1
    fi
    local endpoint
    if [[ "$action" == "enable" ]]; then
        endpoint="enable-trade"
    else
        endpoint="disable-trade"
    fi
    log_info "${action} trade: code=${code}"
    curl -sS -X POST "${MOCK_COUNTER_URL}/api/symbol/${endpoint}?symbolCode=${code}"
    echo ""
}

do_help() {
    cat <<'EOF'

  Laser Matching Engine - Aeron Cluster 管理脚本

  用法: ./cluster.sh <command> [options]

  命令:
    help                显示此帮助信息
    build               仅编译打包 (mvn clean package -DskipTests)
    start <nodeId>      编译并启动指定节点 (nodeId: 0, 1, 2)
    start-all           编译并启动全部 3 个节点
    start-single        编译并以单节点模式启动 (nodeId=0, 直接成为 Leader)
    stop  <nodeId>      停止指定节点 (先 SIGTERM, 10 秒后 SIGKILL)
    stop-all            停止全部节点
    restart <nodeId>    重启指定节点 (stop + build + start)
    status              查看各节点运行状态
    log   <nodeId>      实时查看指定节点日志 (tail -f)
    snapshot [nodeId]   手动触发快照 (默认 nodeId=0, HTTP POST /snapshot/trigger, 只在 LEADER 生效)

  控制面命令 (上下币 / 开关交易, 通过 mock-counter HTTP 转发到集群):
    list-symbol <code> <name> <baseCoin> <quoteCoin>
                        上币: 注册币对到所有节点 (默认 enabled=false, 需再调 enable-trade)
                        例: ./cluster.sh list-symbol 3 doge-usdt 100 200
    delist-symbol <code>
                        下币: 移除币对配置 + 关闭交易
                        例: ./cluster.sh delist-symbol 3
    enable-trade <code> 开启该币对交易 (MatchConfig.enabled=true)
                        例: ./cluster.sh enable-trade 3
    disable-trade <code>
                        关闭该币对交易 (MatchConfig.enabled=false)
                        例: ./cluster.sh disable-trade 3

  HTTP 端口 (自动分配, 不可修改):
    节点 0 → 8080
    节点 1 → 8081
    节点 2 → 8082

  示例:
    ./cluster.sh build              # 仅编译
    ./cluster.sh start 0            # 编译并启动节点 0 (端口 8080)
    ./cluster.sh start-all          # 编译并启动全部 3 个节点
    ./cluster.sh start-single       # 单节点模式 (端口 8080)
    ./cluster.sh stop 0             # 停止节点 0
    ./cluster.sh stop-all           # 停止全部节点
    ./cluster.sh restart 0          # 重启节点 0 (停止 + 编译 + 启动)
    ./cluster.sh status             # 查看状态
    ./cluster.sh log 0              # 实时查看节点 0 日志
    ./cluster.sh snapshot           # 对节点 0 (LEADER) 触发手动快照
    ./cluster.sh snapshot 1         # 对节点 1 触发 (非 LEADER 会返回 423 LOCKED)

  集群模式:
    三节点集群  节点 0/1/2 — Raft 共识选举 Leader
    单节点集群  节点 0     — clusterNodeSize=1, 立即成为 Leader

  文件:
    PID 文件: /tmp/laser/pids/node-{id}.pid
    日志文件: /tmp/laser/logs/node-{id}.log

EOF
}

# ======================== 入口 ========================

case "${1:-help}" in
    help)
        do_help
        ;;
    build)
        do_build
        ;;
    start)
        if [ -z "${2:-}" ]; then
            log_error "缺少 nodeId 参数。用法: ./cluster.sh start <nodeId>"
            exit 1
        fi
        do_build
        do_start "$2"
        ;;
    start-all)
        do_start_all
        ;;
    start-single)
        do_build
        do_start_single
        ;;
    stop)
        if [ -z "${2:-}" ]; then
            log_error "缺少 nodeId 参数。用法: ./cluster.sh stop <nodeId>"
            exit 1
        fi
        do_stop "$2"
        ;;
    stop-all)
        do_stop_all
        ;;
    restart)
        if [ -z "${2:-}" ]; then
            log_error "缺少 nodeId 参数。用法: ./cluster.sh restart <nodeId>"
            exit 1
        fi
        do_restart "$2"
        ;;
    status)
        do_status
        ;;
    log)
        if [ -z "${2:-}" ]; then
            log_error "缺少 nodeId 参数。用法: ./cluster.sh log <nodeId>"
            exit 1
        fi
        do_log "$2"
        ;;
    snapshot)
        do_snapshot "${2:-0}"
        ;;
    list-symbol)
        do_list_symbol "${2:-}" "${3:-}" "${4:-}" "${5:-}"
        ;;
    delist-symbol)
        do_delist_symbol "${2:-}"
        ;;
    enable-trade)
        do_switch_trade "${2:-}" enable
        ;;
    disable-trade)
        do_switch_trade "${2:-}" disable
        ;;
    *)
        log_error "未知命令: $1"
        do_help
        exit 1
        ;;
esac
