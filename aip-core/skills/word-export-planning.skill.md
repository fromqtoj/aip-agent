---
name: Word Export Planning
keywords: word, doc, docx, 文档, 导出, 保存到, 指定路径, 保存路径
planned-tools: generate_word_document
priority: 100
always: false
---

# Skill: Word Export Planning

## 适用场景
- 用户要求先写文章、总结、方案、报告，再生成 Word 文档
- 用户明确指定了保存目录或完整保存路径
- 需要规划多步 MCP 工具调用，而不是只做普通问答

## 工作目标
- 先判断当前对话里是否已经有可用于导出的完整正文
- 如果正文还没有准备好，先完成正文内容
- 再调用 `generate_word_document`
- 确保文档保存到用户指定的位置

## MCP 工具规划
1. 如果用户只是要“写一篇文章”，先完成文章正文，不急着调用工具。
2. 如果用户明确要求“生成 Word”或“导出 Word”，必须调用 `generate_word_document`，不要只口头说“可以生成”。
3. 调用 `generate_word_document` 时，优先明确这 4 个参数：
   - `title`
   - `content`
   - `fileName`
   - `savePath`
4. 当用户给的是目录路径而不是完整文件路径时：
   - `savePath` 传目录本身
   - `fileName` 单独传具体文件名
5. 当用户给的是完整文件路径时：
   - `savePath` 传完整路径
   - `fileName` 仍可保留用户指定的文件名，但不要改写目录根路径
6. 对于 `/Users/qijian/Desktop/爱化身` 这类明确目录，必须按用户原意传递，不要擅自改成别的目录。

## 回复要求
- 先告诉用户是否已经成功生成
- 明确返回保存路径
- 如果生成失败，说明失败原因和下一步建议

## 禁止事项
- 不要在需要生成 Word 时只输出文章正文而不调用工具
- 不要伪造“已经生成成功”
- 不要擅自修改用户指定的根目录
