package com.kigtts.tools.agent

import com.kigtts.plugin.api.*

internal fun agentChat(store: AgentStore, runner: AgentRunner): PageBlock.Chat {
    val conversation = store.selected()
    val settings = conversation?.let(store::connection) ?: store.settings
    val profile = requireNotNull(settings.profiles[settings.provider])
    val pending = store.pending?.takeIf { it.conversationId == conversation?.id }
    val all = conversation?.messages.orEmpty()
    val before = conversation?.let { store.historyBefore(it.id) }
    val indices = if (before == null) (all.size - 100).coerceAtLeast(0) until all.size
        else ((before - 50).coerceAtLeast(0) until before.coerceAtMost(all.size)) + ((all.size - 10).coerceAtLeast(0) until all.size)
    val positions = indices.associate { all[it].id to it }
    val page = indices.map { all[it] }.distinctBy { it.id }
    val needed = page.mapNotNull { it.toolCallId }.toMutableSet()
    val calls = mutableMapOf<String, ToolCall>()
    // Most pages need only the last few call groups, regardless of total history length.
    for (message in all.asReversed()) {
        if (needed.isEmpty()) break
        message.calls.forEach { call -> if (needed.remove(call.id)) calls[call.id] = call }
    }
    // An earlier-page request owns the payload budget. Large recent answers must not crowd it out.
    val visible = if (before == null) visibleMessages(page, calls = calls) else {
        val earlier = visibleMessages(page.filter { (positions[it.id] ?: before) < before }, calls = calls)
        val remaining = (40 * 1024 - earlier.sumOf { visibleMessageCost(it, calls) }).coerceAtLeast(0)
        (earlier + visibleMessages(page.filter { (positions[it.id] ?: 0) >= before }, remaining, calls)).distinctBy { it.id }
    }
    val messages = visible.mapNotNull { message ->
        // Send final states even for empty tool-call messages so the host cache can retire
        // their earlier streaming thought row instead of leaving it "thinking" forever.
        val tool = calls[message.toolCallId]
        val title = when (tool?.name) {
            "read_file" -> "已读取文件"; "write_file" -> "已写入文件"; "list_files" -> "已查看目录"
            "create_page" -> "创建网页卡片"; "web_search" -> "联网搜索"; "web_fetch" -> "读取网页"
            "image_edit" -> "处理图片"; "resize_image" -> "缩放图片"; "svg_edit" -> "编辑矢量图"; "image_search" -> "搜索图片"; "download_image" -> "下载图片"; "read_quick_image" -> "读取快捷文本图片"; "write_quick_image" -> "保存快捷文本图片"
            "deliver_lua" -> "安装 Lua 插件"; "draft_lua" -> "编写 Lua 插件"; "lua" -> "调试 Lua 插件"
            "list_quick_text", "read_quick_text" -> "读取快捷文本"; "edit_quick_text" -> "编辑快捷文本"; "read_page_file" -> "读取页面文件"; "write_page_file" -> "修改页面文件";
            "browser_screenshot" -> "网页截图验收"; "web_ui_guide" -> "读取网页样式规范"; "read_image" -> "读取图片"; "send_image" -> "发送图片";
            "quick_text" -> "管理快捷文本"; "stickers" -> "管理图片与贴纸"; else -> tool?.name ?: "软件操作"
        }
        val icon = when (tool?.name) { "read_file", "list_files" -> PluginIcon.TEXT; "web_search", "web_fetch" -> PluginIcon.INFO
            "image_edit", "resize_image", "svg_edit", "image_search", "download_image", "read_quick_image", "write_quick_image", "read_image", "send_image" -> PluginIcon.IMAGE
            "create_page" -> PluginIcon.ADD; "write_file", "draft_lua" -> PluginIcon.EDIT; else -> PluginIcon.SETTINGS }
        ChatMessage(message.id, when (message.role) { "user" -> if (message.visualInput) ChatRole.ASSISTANT else ChatRole.USER; "tool" -> ChatRole.TOOL; else -> ChatRole.ASSISTANT },
            if (message.visualInput) "" else if (message.role == "tool") message.content.take(10000) else clippedMessage(message.content, 16 * 1024),
            reasoning = if (settings.showReasoning) message.reasoning.takeLast(8 * 1024) else "",
            state = ChatMessageState.valueOf(message.state.name), summary = if (message.role == "tool") toolSummary(message.content) else message.summary.take(1600), toolName = title,
            detail = if (message.role == "tool") tool?.arguments.orEmpty().take(8000) else "", icon = icon,
            images = message.images.map { ChatImage(PluginResource(it.path), it.description) },
            thinking = message.role == "assistant" && message.state == MessageState.STREAMING && message.content.isBlank() && !message.reasoningDone, position = positions[message.id] ?: -1,
            hasReasoning = message.role == "assistant" && message.reasoning.isNotBlank())
    }
    return PageBlock.Chat(messages, "send", "stop", runner.runningConversation == conversation?.id && runner.running,
        placeholder = "发送消息", conversationId = conversation?.id.orEmpty(), controls = listOf(
            ChatControl("供应商", providerOptions(store, true).map { ChatOption(it.id, it.label) }, providerAction(settings, true), ChatControlKind.PROVIDER),
            ChatControl("模型", modelOptions(store, settings), modelAction(settings, profile.model, true)),
            ChatControl("权限", listOf(ChatOption("access/ask", "手动审核", "ask"), ChatOption("access/assist", "帮我批准", "assist"), ChatOption("access/full", "完全访问", "full")),
                when (settings.access) { AgentAccessMode.FULL -> "access/full"; AgentAccessMode.ASSISTED -> "access/assist"; else -> "access/ask" }, ChatControlKind.ACCESS)) + reasoningControl(settings),
        approval = pending?.let { ChatApproval("请求批准", "resume", "discard-review", it.summary.take(4000),
            if (it.token.isNotEmpty()) "review" else null) }, backgroundRunning = runner.running, imageAction = "attach-image", removeImageAction = "remove-image",
        draftImages = conversation?.draftImages.orEmpty().map { ChatImage(PluginResource(it.path), it.description) },
        queueAction = "queue", queued = conversation?.queued.orEmpty().map { ChatQueuedMessage(it.id, it.text.take(1000).ifBlank { "图片消息" }, "queue-remove/" + it.id, "queue-front/" + it.id, "queue-steer/" + it.id, "queue-order") },
        historyAction = "load-earlier", historyTotal = all.size)
}
