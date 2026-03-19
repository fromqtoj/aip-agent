---
name: General MCP Planning
keywords: 文件, 目录, 读取, 查看, 修改, 时间, 命令, 接口, http, 上传, 下载
planned-tools: read_file, scan_directory, search_files, get_file_info, http_get, http_post, get_current_time, execute_command
priority: 20
always: true
---

# Skill: General MCP Planning

## 适用场景
- 需要通过 MCP 工具读取信息、执行检查、调用接口、处理文件
- 用户问题不是纯常识问答，而是依赖真实环境或真实文件

## 工具规划原则
1. 能直接回答就不要乱调用工具。
2. 涉及项目结构、文件内容、目录状态时，优先调用文件类 MCP 工具。
3. 涉及当前时间、时间差、时间格式转换时，优先调用时间类 MCP 工具。
4. 涉及外部接口、下载、上传时，优先调用网络类 MCP 工具。
5. 涉及命令执行时，只在必要时调用系统命令类工具，并保持最小化调用。

## 回复要求
- 先给结论
- 再说明查看了哪些真实信息
- 不要编造未读取到的内容
