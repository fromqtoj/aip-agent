#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUNTIME_DIR="${AIP_AGENT_RUNTIME_DIR:-$(cd "$ROOT_DIR/.." && pwd)/$(basename "$ROOT_DIR")-runtime}"
RUN_DIR="$RUNTIME_DIR/.run"
LOG_DIR="$RUNTIME_DIR/logs"
JAR_DIR="$RUNTIME_DIR/jars"
STORAGE_DIR="$RUNTIME_DIR/storage"
EXPORT_DIR="$RUNTIME_DIR/export"

mkdir -p "$RUN_DIR" "$LOG_DIR" "$JAR_DIR" "$STORAGE_DIR/files" "$EXPORT_DIR"

usage() {
  cat <<'EOF'
Usage:
  scripts/dev.sh start [all|core|mcp] [--force]
  scripts/dev.sh stop [all|core|mcp] [--force]
  scripts/dev.sh restart [all|core|mcp] [--force]
  scripts/dev.sh status [all|core|mcp]
  scripts/dev.sh logs [core|mcp]

Examples:
  scripts/dev.sh start
  scripts/dev.sh start core
  scripts/dev.sh stop all
  scripts/dev.sh stop all --force
  scripts/dev.sh restart core
  scripts/dev.sh restart all --force
  scripts/dev.sh status
  scripts/dev.sh logs core
EOF
}

service_module() {
  case "$1" in
    core) echo "aip-core" ;;
    mcp) echo "aip-mcp-server" ;;
    *)
      echo "Unsupported service: $1" >&2
      exit 1
      ;;
  esac
}

service_port() {
  case "$1" in
    core) echo "10666" ;;
    mcp) echo "10667" ;;
    *)
      echo "Unsupported service: $1" >&2
      exit 1
      ;;
  esac
}

service_label() {
  case "$1" in
    core) echo "aip-core" ;;
    mcp) echo "aip-mcp-server" ;;
    *)
      echo "Unsupported service: $1" >&2
      exit 1
      ;;
  esac
}

module_dir() {
  echo "$ROOT_DIR/$(service_module "$1")"
}

pid_file() {
  echo "$RUN_DIR/$1.pid"
}

log_file() {
  echo "$LOG_DIR/$(service_label "$1")-console.log"
}

runtime_jar_file() {
  echo "$JAR_DIR/$(service_label "$1").jar"
}

FORCE=false

is_pid_running() {
  local pid="$1"
  kill -0 "$pid" >/dev/null 2>&1
}

terminate_pid() {
  local pid="$1"
  local label="$2"

  if [[ -z "$pid" ]]; then
    return 0
  fi

  if ! is_pid_running "$pid"; then
    return 0
  fi

  echo "Force stopping $label (PID $pid)"
  kill "$pid"

  for _ in $(seq 1 20); do
    if ! is_pid_running "$pid"; then
      return 0
    fi
    sleep 1
  done

  echo "$label did not stop within 20 seconds after force stop; please inspect it manually"
  return 1
}

listening_pid() {
  local port="$1"
  lsof -tiTCP:"$port" -sTCP:LISTEN 2>/dev/null | head -n 1 || true
}

resolve_services() {
  local target="${1:-all}"
  case "$target" in
    all)
      printf '%s\n' mcp core
      ;;
    core|mcp)
      printf '%s\n' "$target"
      ;;
    *)
      echo "Unsupported target: $target" >&2
      usage
      exit 1
      ;;
  esac
}

jar_file() {
  local service="$1"
  local module_path

  module_path="$(module_dir "$service")"
  find "$module_path/target" -maxdepth 1 -type f -name '*.jar' \
    ! -name 'original-*.jar' \
    ! -name '*-sources.jar' \
    ! -name '*-javadoc.jar' | sort | tail -n 1
}

start_service() {
  local service="$1"
  local module
  local port
  local label
  local module_path
  local pid_path
  local console_log
  local existing_pid
  local new_pid
  local jar_path
  local runtime_jar
  local java_cmd=()

  module="$(service_module "$service")"
  port="$(service_port "$service")"
  label="$(service_label "$service")"
  module_path="$(module_dir "$service")"
  pid_path="$(pid_file "$service")"
  console_log="$(log_file "$service")"

  if [[ -f "$pid_path" ]]; then
    existing_pid="$(cat "$pid_path")"
    if [[ -n "$existing_pid" ]] && is_pid_running "$existing_pid"; then
      echo "$label is already running with PID $existing_pid"
      return 0
    fi
    rm -f "$pid_path"
  fi

  existing_pid="$(listening_pid "$port")"
  if [[ -n "$existing_pid" ]]; then
    if [[ "$FORCE" == "true" ]]; then
      terminate_pid "$existing_pid" "$label"
      sleep 1
    else
      echo "$label is already listening on port $port with PID $existing_pid (not managed by this script)"
      return 0
    fi
  fi

  existing_pid="$(listening_pid "$port")"
  if [[ -n "$existing_pid" ]]; then
    echo "$label is already listening on port $port with PID $existing_pid (not managed by this script)"
    return 0
  fi

  : >"$console_log"
  echo "Packaging $label" | tee -a "$console_log"
  if ! mvn -pl "$module" -am -DskipTests package >>"$console_log" 2>&1; then
    echo "Failed to package $label. Recent log output:"
    tail -n 40 "$console_log" || true
    return 1
  fi

  jar_path="$(jar_file "$service")"
  if [[ -z "$jar_path" || ! -f "$jar_path" ]]; then
    echo "Could not locate a runnable jar for $label under $module_path/target"
    return 1
  fi

  runtime_jar="$(runtime_jar_file "$service")"
  cp "$jar_path" "$runtime_jar"

  case "$service" in
    core)
      java_cmd=(
        java
        "-Dlogging.file.path=$LOG_DIR"
        "-Daip.agent.skill.path=$ROOT_DIR/aip-core/skills"
        "-Daip.agent.claude-code-cli.working-directory=$ROOT_DIR"
        -jar
        "$runtime_jar"
      )
      ;;
    mcp)
      java_cmd=(
        java
        "-Dlogging.file.name=$LOG_DIR/aip-mcp-server.log"
        "-Daip.mcp.workspace-root=$ROOT_DIR"
        "-Dproject.workspace.path=$ROOT_DIR"
        "-Dgeneral.file.storage.path=$STORAGE_DIR/files"
        "-Dexternal.export.path=$EXPORT_DIR"
        -jar
        "$runtime_jar"
      )
      ;;
  esac

  echo "Starting $label on port $port"
  nohup "${java_cmd[@]}" >>"$console_log" 2>&1 < /dev/null &
  new_pid=$!
  echo "$new_pid" >"$pid_path"

  for _ in $(seq 1 60); do
    if ! is_pid_running "$new_pid"; then
      echo "$label exited during startup. Recent log output:"
      tail -n 40 "$console_log" || true
      rm -f "$pid_path"
      return 1
    fi

    existing_pid="$(listening_pid "$port")"
    if [[ -n "$existing_pid" ]]; then
      echo "$label is ready on port $port (PID $existing_pid)"
      return 0
    fi
    sleep 1
  done

  echo "Timed out waiting for $label to listen on port $port. Recent log output:"
  tail -n 40 "$console_log" || true
  return 1
}

stop_service() {
  local service="$1"
  local label
  local port
  local pid_path
  local managed_pid
  local existing_pid

  label="$(service_label "$service")"
  port="$(service_port "$service")"
  pid_path="$(pid_file "$service")"

  if [[ ! -f "$pid_path" ]]; then
    existing_pid="$(listening_pid "$port")"
    if [[ -n "$existing_pid" ]]; then
      if [[ "$FORCE" == "true" ]]; then
        terminate_pid "$existing_pid" "$label"
        echo "$label stopped"
        return 0
      fi
      echo "$label is running on port $port with PID $existing_pid, but it is not managed by this script"
      return 0
    fi
    echo "$label is not running"
    return 0
  fi

  managed_pid="$(cat "$pid_path")"
  if [[ -z "$managed_pid" ]]; then
    rm -f "$pid_path"
    echo "$label pid file was empty and has been cleaned up"
    return 0
  fi

  if ! is_pid_running "$managed_pid"; then
    rm -f "$pid_path"
    echo "$label was not running; cleaned up stale pid file"
    return 0
  fi

  echo "Stopping $label (PID $managed_pid)"
  kill "$managed_pid"

  for _ in $(seq 1 20); do
    if ! is_pid_running "$managed_pid"; then
      rm -f "$pid_path"
      echo "$label stopped"
      return 0
    fi
    sleep 1
  done

  echo "$label did not stop within 20 seconds; please inspect it manually"
  return 1
}

status_service() {
  local service="$1"
  local label
  local port
  local pid_path
  local managed_pid
  local existing_pid

  label="$(service_label "$service")"
  port="$(service_port "$service")"
  pid_path="$(pid_file "$service")"

  if [[ -f "$pid_path" ]]; then
    managed_pid="$(cat "$pid_path")"
    if [[ -n "$managed_pid" ]] && is_pid_running "$managed_pid"; then
      echo "$label: running (managed, PID $managed_pid, port $port)"
      return 0
    fi
    echo "$label: stopped (stale pid file found)"
    return 0
  fi

  existing_pid="$(listening_pid "$port")"
  if [[ -n "$existing_pid" ]]; then
    echo "$label: running (unmanaged, PID $existing_pid, port $port)"
    return 0
  fi

  echo "$label: stopped"
}

logs_service() {
  local service="$1"
  local console_log

  console_log="$(log_file "$service")"
  if [[ ! -f "$console_log" ]]; then
    echo "No console log found for $(service_label "$service"): $console_log"
    return 1
  fi

  if [[ "$service" == "core" ]]; then
    echo "Core log files:"
    echo "  console: $LOG_DIR/aip-core-console.log"
    echo "    Purpose: packaging and java -jar startup output captured by the script"
    echo "  app:     $LOG_DIR/aip-core.log"
    echo "    Purpose: regular Spring Boot application log"
    echo "  mcp:     $LOG_DIR/aip-core-mcp.log"
    echo "    Purpose: simplified MCP trace summary log"
  else
    echo "MCP log files:"
    echo "  console: $LOG_DIR/aip-mcp-server-console.log"
    echo "    Purpose: packaging and java -jar startup output captured by the script"
    echo "  app:     $LOG_DIR/aip-mcp-server.log"
    echo "    Purpose: regular Spring Boot application log"
  fi

  echo
  echo "Following console log:"
  tail -n 80 -f "$console_log"
}

ACTION="${1:-}"
TARGET="all"

if [[ $# -ge 2 && "${2:-}" != "--force" ]]; then
  TARGET="$2"
fi

if [[ "${2:-}" == "--force" || "${3:-}" == "--force" ]]; then
  FORCE=true
fi

if [[ -z "$ACTION" ]]; then
  usage
  exit 1
fi

case "$ACTION" in
  start)
    while IFS= read -r service; do
      start_service "$service"
    done < <(resolve_services "$TARGET")
    ;;
  stop)
    while IFS= read -r service; do
      stop_service "$service"
    done < <(resolve_services "$TARGET")
    ;;
  restart)
    while IFS= read -r service; do
      stop_service "$service"
    done < <(resolve_services "$TARGET")
    while IFS= read -r service; do
      start_service "$service"
    done < <(resolve_services "$TARGET")
    ;;
  status)
    while IFS= read -r service; do
      status_service "$service"
    done < <(resolve_services "$TARGET")
    ;;
  logs)
    if [[ "$TARGET" != "core" && "$TARGET" != "mcp" ]]; then
      echo "logs only supports 'core' or 'mcp'" >&2
      usage
      exit 1
    fi
    logs_service "$TARGET"
    ;;
  *)
    usage
    exit 1
    ;;
esac
