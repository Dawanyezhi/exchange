#!/usr/bin/env bash
#
# Laser Mock Counter - Client Management Script
#
# Usage: ./client.sh <command> [options]
#

set -euo pipefail

# ======================== Configuration ========================
PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "${PROJECT_DIR}/.." && pwd)"
JAR_NAME="exchange-counter-1.0.0-SNAPSHOT.jar"
JAR_PATH="${PROJECT_DIR}/target/${JAR_NAME}"
PID_FILE="${ROOT_DIR}/pids/counter/mock-counter.pid"
LOG_FILE="${ROOT_DIR}/logs/counter/mock-counter.log"
HTTP_PORT=10880
BASE_URL="http://localhost:${HTTP_PORT}"

JVM_OPTS=(
    --add-opens java.base/jdk.internal.misc=ALL-UNNAMED
    --add-opens java.base/sun.nio.ch=ALL-UNNAMED
    --add-opens java.base/java.nio=ALL-UNNAMED
    --add-opens java.base/java.lang=ALL-UNNAMED
)

# ======================== Utility Functions ========================

log_info()  { printf '\033[32m[INFO]\033[0m  %s\n' "$*"; }
log_warn()  { printf '\033[33m[WARN]\033[0m  %s\n' "$*"; }
log_error() { printf '\033[31m[ERROR]\033[0m %s\n' "$*"; }

is_running() {
    if [ -f "$PID_FILE" ]; then
        local pid
        pid="$(cat "$PID_FILE")"
        if kill -0 "$pid" 2>/dev/null; then
            return 0
        fi
        rm -f "$PID_FILE"
    fi
    return 1
}

# ======================== Core Commands ========================

do_build() {
    log_info "Building exchange-counter and dependent modules ..."
    cd "$ROOT_DIR"
    mvn -pl counter -am clean package -DskipTests -q
    log_info "Build complete: ${JAR_PATH}"
}

do_start() {
    if is_running; then
        local pid
        pid="$(cat "$PID_FILE")"
        log_warn "exchange-counter is already running (PID: ${pid})"
        return 0
    fi

    mkdir -p "$(dirname "$PID_FILE")" "$(dirname "$LOG_FILE")"

    log_info "Starting exchange-counter (HTTP port: ${HTTP_PORT}) ..."
    nohup java "${JVM_OPTS[@]}" \
        -jar "${JAR_PATH}" \
        > "$LOG_FILE" 2>&1 &

    local pid=$!
    echo "$pid" > "$PID_FILE"
    log_info "exchange-counter started (PID: ${pid}), log: ${LOG_FILE}"
}

do_stop() {
    if ! is_running; then
        log_warn "exchange-counter is not running"
        return 0
    fi

    local pid
    pid="$(cat "$PID_FILE")"

    log_info "Stopping exchange-counter (PID: ${pid}) ..."
    kill "$pid" 2>/dev/null || true

    local waited=0
    while kill -0 "$pid" 2>/dev/null && [ "$waited" -lt 10 ]; do
        sleep 1
        waited=$((waited + 1))
    done

    if kill -0 "$pid" 2>/dev/null; then
        log_warn "Process did not exit within 10s, sending SIGKILL ..."
        kill -9 "$pid" 2>/dev/null || true
    fi

    rm -f "$PID_FILE"
    log_info "exchange-counter stopped"
}

do_restart() {
    log_info "=== Restarting exchange-counter ==="
    do_stop
    do_build
    do_start
    log_info "Restart complete"
}

do_status() {
    printf "\n"
    if is_running; then
        local pid
        pid="$(cat "$PID_FILE")"
        printf "  \033[32m●\033[0m exchange-counter  PID: %-8s  HTTP: %s  Status: \033[32mrunning\033[0m\n" "$pid" "$HTTP_PORT"
    else
        printf "  \033[31m●\033[0m exchange-counter  PID: %-8s  HTTP: %s  Status: \033[31mstopped\033[0m\n" "-" "$HTTP_PORT"
    fi
    printf "\n"
}

do_log() {
    if [ ! -f "$LOG_FILE" ]; then
        log_error "Log file not found: ${LOG_FILE}"
        exit 1
    fi
    log_info "Tailing log (Ctrl+C to quit) ..."
    tail -f "$LOG_FILE"
}

# ======================== Order Commands ========================

do_place() {
    local symbol_code="${1:-1}"
    local price="${2:-50000.00}"
    local quantity="${3:-1.5}"
    local side="${4:-BUY}"
    local order_type="${5:-LIMIT}"
    local locked_quote="${6:-0}"

    log_info "Placing order: symbolCode=${symbol_code}, type=${order_type}, price=${price}, qty=${quantity}, side=${side}, lockedQuote=${locked_quote}"
    curl -s -X POST "${BASE_URL}/api/order/place" \
        -H "Content-Type: application/json" \
        -d "{
            \"orderId\": $(date +%s%N | cut -c1-16),
            \"clientOid\": \"cli-$(date +%s)\",
            \"accountId\": 10001,
            \"symbolCode\": ${symbol_code},
            \"orderType\": \"${order_type}\",
            \"orderSide\": \"${side}\",
            \"timeInForce\": \"GTC\",
            \"delegatePrice\": ${price},
            \"delegateCount\": ${quantity},
            \"lockedQuoteAmount\": ${locked_quote},
            \"stpAccountId\": 10001,
            \"stpStrategyEnum\": \"DEFAULT\"
        }" | python3 -m json.tool 2>/dev/null || echo ""
    echo ""
}

do_cancel() {
    local order_id="$1"
    local symbol_code="${2:-1}"

    log_info "Cancelling order: orderId=${order_id}, symbolCode=${symbol_code}"
    curl -s -X POST "${BASE_URL}/api/order/cancel" \
        -H "Content-Type: application/json" \
        -d "{
            \"orderId\": ${order_id},
            \"symbolCode\": ${symbol_code}
        }" | python3 -m json.tool 2>/dev/null || echo ""
    echo ""
}

do_amend() {
    local order_id="$1"
    local new_price="${2:-50100.00}"
    local new_quantity="${3:-2.0}"
    local symbol_code="${4:-1}"

    log_info "Amending order: orderId=${order_id}, newPrice=${new_price}, newQty=${new_quantity}"
    curl -s -X POST "${BASE_URL}/api/order/amend" \
        -H "Content-Type: application/json" \
        -d "{
            \"orderId\": ${order_id},
            \"symbolCode\": ${symbol_code},
            \"newDelegatePrice\": ${new_price},
            \"newDelegateCount\": ${new_quantity}
        }" | python3 -m json.tool 2>/dev/null || echo ""
    echo ""
}

do_batch_start() {
    local rate="${1:-100}"
    local symbol_code="${2:-1}"
    local base_price="${3:-50000.00}"
    local quantity="${4:-1.5}"

    log_info "Starting batch orders: rate=${rate}/s, symbolCode=${symbol_code}"
    curl -s -X POST "${BASE_URL}/api/order/batch/start?rate=${rate}&symbolCode=${symbol_code}&basePrice=${base_price}&quantity=${quantity}" \
        | python3 -m json.tool 2>/dev/null || echo ""
    echo ""
}

do_batch_stop() {
    log_info "Stopping batch orders ..."
    curl -s -X POST "${BASE_URL}/api/order/batch/stop" \
        | python3 -m json.tool 2>/dev/null || echo ""
    echo ""
}

do_batch_status() {
    curl -s -X GET "${BASE_URL}/api/order/batch/status" \
        | python3 -m json.tool 2>/dev/null || echo ""
    echo ""
}

do_help() {
    cat <<'EOF'

  Laser Mock Counter - Client Management Script

  Usage: ./client.sh <command> [options]

  Process Commands:
    help                          Show this help message
    build                         Build exchange-common + exchange-counter
    start                         Build and start (HTTP port 10880)
    stop                          Stop (SIGTERM → 10s → SIGKILL)
    restart                       Stop + build + start
    status                        Show running status
    log                           Tail the log file (Ctrl+C to quit)

  Order Commands:
    place [symbolCode] [price] [qty] [side] [type] [lockedQuote]
                                  Place an order (defaults: 1 50000.00 1.5 BUY LIMIT 0)
    cancel <orderId> [symbolCode]
                                  Cancel an order by orderId
    amend <orderId> [newPrice] [newQty] [symbolCode]
                                  Amend an order by orderId

  Batch Commands:
    batch-start [rate] [symbolCode] [basePrice] [qty]
                                  Start batch orders (default: 100/s)
    batch-stop                    Stop batch orders
    batch-status                  Show batch order statistics

  Examples:
    ./client.sh build                       # Build only
    ./client.sh start                       # Build and start
    ./client.sh place                       # Place a default BUY order
    ./client.sh place 1 49999.99 2.0 SELL   # Place a SELL order
    ./client.sh place 1 0 1.0 BUY MARKET 50000
                                            # Place a BUY market order with quote budget
    ./client.sh cancel 1234567890           # Cancel order by ID
    ./client.sh amend 1234567890 50100 2.5  # Amend order price and qty
    ./client.sh batch-start 200             # Start batch at 200 orders/s
    ./client.sh batch-stop                  # Stop batch orders
    ./client.sh batch-status                # Check batch statistics

  Files:
    PID file: ../pids/counter/mock-counter.pid
    Log file: ../logs/counter/mock-counter.log

EOF
}

# ======================== Entry Point ========================

case "${1:-help}" in
    help)       do_help ;;
    build)      do_build ;;
    start)
        do_build
        do_start
        ;;
    stop)       do_stop ;;
    restart)    do_restart ;;
    status)     do_status ;;
    log)        do_log ;;
    place)      do_place "${2:-1}" "${3:-50000.00}" "${4:-1.5}" "${5:-BUY}" "${6:-LIMIT}" "${7:-0}" ;;
    cancel)
        if [ -z "${2:-}" ]; then
            log_error "Missing orderId. Usage: ./client.sh cancel <orderId> [symbolCode]"
            exit 1
        fi
        do_cancel "$2" "${3:-1}"
        ;;
    amend)
        if [ -z "${2:-}" ]; then
            log_error "Missing orderId. Usage: ./client.sh amend <orderId> [newPrice] [newQty] [symbolCode]"
            exit 1
        fi
        do_amend "$2" "${3:-50100.00}" "${4:-2.0}" "${5:-1}"
        ;;
    batch-start)    do_batch_start "${2:-100}" "${3:-1}" "${4:-50000.00}" "${5:-1.5}" ;;
    batch-stop)     do_batch_stop ;;
    batch-status)   do_batch_status ;;
    *)
        log_error "Unknown command: $1"
        do_help
        exit 1
        ;;
esac
