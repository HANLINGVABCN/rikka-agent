package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.agent.AgentRuntime

/**
 * 把一个完整任务委派给容器里的 pi agent。
 *
 * 与 `workspace_shell` 的分工: shell 执行**一条**命令并返回输出, 每次都要模型自己
 * 决定下一步; 这个工具交给 pi 一个**目标**, 由它自己循环(读文件、跑命令、看报错、
 * 改了再试)直到做完。适合"把这个项目跑起来""修好这个编译错误"这类多步骤工作。
 *
 * pi 用的是当前 chat 选中的同一个模型和 API key —— 见 [AgentRuntime]。
 */
internal fun createAgentTaskTool(
    workspaceId: String,
    runtime: AgentRuntime,
    needsApproval: (String) -> Boolean,
): Tool = Tool(
    name = "workspace_agent",
    description = buildString {
        append("Delegate a multi-step task to the pi coding agent running inside the workspace container. ")
        append("Unlike workspace_shell (one command per call), the agent works autonomously: it explores files, ")
        append("runs commands, reads errors and iterates until the task is done. ")
        append("Use it for goals like 'get this project building' or 'fix the failing tests', ")
        append("and use workspace_shell for single commands you already know. ")
        append("Give a complete, self-contained goal — the agent does not see this conversation. ")
        append("Returns the agent's final report. May take several minutes.")
    },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("task", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        "Complete task description including any context the agent needs. " +
                            "It starts from a clean slate and cannot see the chat history."
                    )
                })
            },
            required = listOf("task"),
        )
    },
    // agent 能跑任意命令, 与 workspace_shell 同级风险 —— 走同一套审批覆盖机制
    needsApproval = { needsApproval("workspace_agent") },
    execute = { args ->
        val task = args.jsonObject["task"]?.jsonPrimitive?.contentOrNull
        if (task.isNullOrBlank()) {
            listOf(UIMessagePart.Text("Error: task is required"))
        } else {
            val report = runtime.runTask(workspaceId, task)
                .getOrElse { "Agent failed: ${it.message}" }
            listOf(UIMessagePart.Text(report))
        }
    },
)
