# aip-agent

本项目是一个基于 Spring Boot 的多模块 AI Agent 服务：

- `aip-core`：主聊天服务，默认端口 `10666`
- `aip-mcp-server`：MCP 工具服务，默认端口 `10667`

## 环境要求

- Java 21
- Maven 3.9+
- 本机可执行 `claude` 命令

当前 `aip-core` 默认配置走 `claude-code-cli`，配置文件在 `aip-core/src/main/resources/application.yml`。

## 推荐启动方式

项目根目录已经提供脚本：

```bash
scripts/dev.sh start all
```

脚本默认使用项目平级运行目录：

```text
../aip-agent-runtime
```

如果你的源码目录是：

```text
/Users/qijian/Desktop/爱化身/aip-agent
```

那么默认运行目录就是：

```text
/Users/qijian/Desktop/爱化身/aip-agent-runtime
```

也可以通过环境变量覆盖：

```bash
AIP_AGENT_RUNTIME_DIR=/custom/runtime scripts/dev.sh start all
```

常用命令：

```bash
scripts/dev.sh start all
scripts/dev.sh start core
scripts/dev.sh start mcp

scripts/dev.sh stop all
scripts/dev.sh restart all
scripts/dev.sh restart all --force
scripts/dev.sh status

scripts/dev.sh logs core
scripts/dev.sh logs mcp
```

说明：

- `start` 会先执行 `mvn package`，再把 jar 复制到运行目录后用 `java -jar` 后台启动
- 运行目录下会生成 `jars/`、`logs/`、`.run/`、`storage/`、`export/`
- 控制台输出写入运行目录下的 `logs/*-console.log`
- PID 文件写入运行目录下的 `.run/`
- `scripts/dev.sh logs core` 会先说明 `aip-core-console.log`、`aip-core.log`、`aip-core-mcp.log` 的用途，再默认跟随 console 日志
- `--force` 会主动停止占用 `10666` / `10667` 端口但不受脚本托管的旧进程

## 运行目录结构

默认会生成类似结构：

```text
/Users/qijian/Desktop/爱化身/aip-agent-runtime
  .run/
  export/
  jars/
    aip-core.jar
    aip-mcp-server.jar
  logs/
    aip-core-console.log
    aip-core.log
    aip-core-mcp.log
    aip-mcp-server-console.log
    aip-mcp-server.log
  storage/
    files/
```

其中：

- 源码仓库仍然是构建输入目录
- `claude-code-cli` 的工作目录仍然指向源码仓库
- `aip.agent.skill.path` 仍然指向源码仓库里的 `aip-core/skills`
- MCP 的 workspace-root 仍然指向源码仓库

## 接口验证

启动 `aip-core` 后，可以直接验证聊天接口：

```bash
curl -X POST http://127.0.0.1:10666/agent/chat \
  -H 'Content-Type: application/json' \
  -d '{"question":"你是什么模型"}'
```

预期会返回类似：

```json
{
  "conversationId": "xxx",
  "answer": "我是 **Kimi K2.5**。",
  "fileNames": []
}
```

## 常见问题

### 1. `ClaudeCode CLI 执行超时`

当前仓库已经修复了一个常见问题：Java 调起 `claude` 子进程后会立即关闭 stdin，避免 CLI 一直等待输入导致超时。

修复位置：

- `aip-core/src/main/java/com/aip/agent/service/ClaudeCodeCliAgentService.java`

### 2. 端口被占用

如果 `10666` 或 `10667` 被其他进程占用，脚本会提示已有进程在监听该端口。可以先排查占用后再重启，或者直接使用：

```bash
scripts/dev.sh restart all --force
```

### 3. 只想启动主服务

如果当前配置仍是 `claude-code-cli`，通常只启动 `aip-core` 也能工作：

```bash
scripts/dev.sh start core
```

如果你切回 `mcp` 模式，再启动：

```bash
scripts/dev.sh start all
```
