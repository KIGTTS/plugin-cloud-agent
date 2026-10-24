package com.kigtts.tools.agent

import com.kigtts.plugin.api.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.encodeToString
import java.util.UUID

class CloudAgentPlugin : KigPlugin {
    override val descriptor = PluginDescriptor("tools.cloud-agent", "AI 助手", "聊天、查资料，制作快捷文本和小工具", version = "0.9.20")
    private lateinit var environment: PluginEnvironment
    private lateinit var store: AgentStore
    private lateinit var secrets: AgentSecrets
    private lateinit var runner: AgentRunner
    private lateinit var notifications: AgentTaskNotifications
    private lateinit var taskBadges: AgentTaskBadges
    private lateinit var catalog: AgentModelCatalog
    private lateinit var providers: AgentProviderController
    private lateinit var reviews: AgentReviewController
    private val revision = MutableStateFlow(0L)
    private val uiSignals = AgentUiSignals { revision.update { it + 1 } }
    override val uiChanges = revision.asStateFlow()
    private fun changed() { uiSignals.changed() }
    override suspend fun attach(environment: PluginEnvironment) {
        this.environment = environment
        store = AgentStore(environment.storage)
        if (store.settings.access == AgentAccessMode.FULL && !store.settings.fullAccessConfirmed)
            store.updateSettings { it.copy(access = AgentAccessMode.REQUEST_APPROVAL) }
        secrets = KeystoreAgentSecrets(environment.storage)
        notifications = AgentTaskNotifications(store, environment)
        taskBadges = AgentTaskBadges(store, environment.storage)
        runner = ConcurrentAgentRunner(store, secrets, environment, ::changed) { id, running ->
            taskBadges.changed(id, running); notifications.changed(id, running)
        }
        reviews = AgentReviewController(environment, store, runner)
        catalog = AgentModelCatalog(store, secrets, ::changed, HttpsModelsTransport { environment.requirePermission("network") })
        providers = AgentProviderController(store, secrets, runner, catalog)
        catalog.refresh(store.settings.configuredConnection())
        if (store.settings.access == AgentAccessMode.FULL && store.settings.toolsEnabled)
            store.list().mapNotNull { store.pendingFor(it.id) }.forEach { reviews.resume(it.conversationId) }
    }
    override val contributions get(): PluginContributions {
        val badge = if (::taskBadges.isInitialized) taskBadges.badge() else null
        return PluginContributions(actions = if (this::runner.isInitialized && (runner.running || store.settings.pinHomeIcon || badge != null))
        listOf(UiAction("open", "AI 助手", UiSlot.HEADER_END, PluginIcon.ROBOT2, busy = runner.running, badge = badge)) else emptyList(), settingsEntries = listOf(
        SettingsEntry("assistant", "AI 助手", "chat", PluginIcon.ROBOT2, cardId = "assistant", cardTitle = "助手", summary = "聊天与创作", badge = badge),
        SettingsEntry("settings", "助手设置", "settings", PluginIcon.SETTINGS, cardId = "assistant", cardTitle = "助手", summary = "服务、模型与密钥", order = 1)),
        automationActions = listOf("open"))
    }
    override fun page(pageId: String, context: PluginContext) = agentPage(pageId, store, runner, secrets)
    override suspend fun execute(actionId: String, context: PluginContext): PluginOutcome {
        val value = context.inputs["value"].orEmpty()
        try {
            providers.handle(actionId, context)?.let { changed(); return it }
            val result = when {
                actionId == "open" -> {
                    taskBadges.attentionConversation()?.let(store::select)
                    catalog.refresh(store.selected()?.let(store::connection) ?: store.settings.configuredConnection()); PluginOutcome.OpenPage("chat")
                }
                actionId.startsWith("chat-viewed/") -> { taskBadges.viewed(actionId.substringAfter('/')); PluginOutcome.RefreshUi }
                actionId.startsWith("option/") -> {
                    val parts = actionId.removePrefix("option/").split('/')
                    val conversation = store.target(context) ?: return PluginOutcome.RefreshUi
                    val messageId = parts.getOrNull(0).orEmpty()
                    val index = parts.getOrNull(1)?.toIntOrNull() ?: return PluginOutcome.RefreshUi
                    val option = conversation.messages.firstOrNull { it.id == messageId }?.options?.getOrNull(index)
                    requireNotNull(option) { "选项已失效，请重新生成问题" }
                    runner.send(conversation.id, option.value.ifBlank { option.label })
                    PluginOutcome.RefreshUi
                }
                actionId in setOf("settings", "conversations", "connections", "dialogue-settings", "connection-settings") -> PluginOutcome.OpenPage(actionId)
                actionId == "chat-menu" -> PluginOutcome.Menu("会话", listOf(PageAction("settings", "助手设置", PluginIcon.SETTINGS)))
                actionId == "new" -> {
                    val selected = store.selected()
                    if (selected == null || selected.messages.isNotEmpty() || selected.draftImages.isNotEmpty() || selected.queued.isNotEmpty() || runner.isRunning(selected.id)) store.create()
                    PluginOutcome.OpenPage("chat")
                }
                actionId.startsWith("select/") -> { store.select(actionId.removePrefix("select/")); PluginOutcome.OpenPage("chat") }
                actionId.startsWith("rename/") -> {
                    val id = actionId.removePrefix("rename/")
                    PluginOutcome.TextInput("会话名称", "rename-save/$id", requireNotNull(store.get(id)).title)
                }
                actionId.startsWith("rename-save/") -> {
                    require(value.trim().length in 1..80)
                    val conversation = requireNotNull(store.get(actionId.removePrefix("rename-save/")))
                    store.save(conversation.copy(title = value.trim())); PluginOutcome.RefreshUi
                }
                actionId.startsWith("delete/") -> {
                    val id = actionId.removePrefix("delete/")
                    check(!runner.isRunning(id)) { "请先停止此会话的回答" }
                    val conversation = requireNotNull(store.get(id)) { "会话不存在" }
                    PluginOutcome.Confirm("删除会话？", "将删除“${conversation.title}”的所有消息，无法恢复。",
                        PageAction("delete-confirm/$id", "删除", PluginIcon.NONE, symbol = "delete"))
                }
                actionId.startsWith("delete-confirm/") -> {
                    val id = actionId.removePrefix("delete-confirm/")
                    check(!runner.isRunning(id)) { "请先停止此会话的回答" }
                    store.delete(id); PluginOutcome.RefreshUi
                }
                actionId == "attach-image" -> {
                    val conversation = store.target(context) ?: store.create()
                    if (context.inputs["source"] == "camera") PluginOutcome.CapturePhoto("拍照", "imports/chat-source", "attach-image-complete/" + conversation.id)
                    else PluginOutcome.ImportResource("添加图片", "imports/chat-source", "attach-image-complete/" + conversation.id, mimeTypes = listOf("image/*"))
                }
                actionId.startsWith("attach-image-complete/") -> {
                    val conversation = requireNotNull(store.get(actionId.substringAfter('/')))
                    require(conversation.draftImages.size < 4) { "每条消息最多四张图片" }
                    val image = AgentImages(environment.storage).prepare(requireNotNull(context.inputs["resourcePath"]))
                    store.modify(conversation.id) { current -> require(current.draftImages.size < 4); current.copy(draftImages = current.draftImages + image) }
                    context.inputs["resourcePath"]?.takeIf { it.startsWith("imports/chat-source/") }?.let { environment.storage.file(it).delete() }
                    PluginOutcome.RefreshUi
                }
                actionId == "remove-image" -> {
                    val conversation = store.target(context) ?: store.create(); val path = value
                    store.modify(conversation.id) { current -> current.copy(draftImages = current.draftImages.filterNot { it.path == path }) }; PluginOutcome.RefreshUi
                }
                actionId == "queue" || actionId == "steer" -> {
                    require(context.inputs["_callerPlugin"] == null) { "只能由用户发送插队消息" }
                    val conversation = store.target(context) ?: store.create()
                    runner.enqueue(conversation.id, context.inputs["message"].orEmpty(), actionId == "steer"); PluginOutcome.RefreshUi
                }
                actionId.startsWith("queue-remove/") || actionId.startsWith("queue-front/") -> {
                    val conversation = store.target(context) ?: store.create()
                    runner.manageQueue(conversation.id, actionId.substringAfter('/'), actionId.startsWith("queue-front/")); PluginOutcome.RefreshUi
                }
                actionId.startsWith("queue-steer/") -> {
                    val conversation = store.target(context) ?: store.create()
                    runner.steerQueued(conversation.id, actionId.substringAfter('/')); PluginOutcome.RefreshUi
                }
                actionId == "queue-order" -> {
                    val conversation = store.target(context) ?: store.create()
                    runner.reorderQueue(conversation.id, PluginJson.decodeFromString<List<String>>(context.inputs.getValue("order"))); PluginOutcome.RefreshUi
                }
                actionId == "load-earlier" -> {
                    val conversation = store.target(context) ?: return PluginOutcome.RefreshUi
                    if (context.inputs["latest"] == "true") store.latestHistory(conversation.id)
                    else store.loadEarlier(conversation.id, requireNotNull(context.inputs["before"]).toInt())
                    PluginOutcome.RefreshUi
                }
                actionId == "send" -> {
                    val text = context.inputs["message"] ?: context.inputs["text"] ?: value
                    val conversation = store.target(context) ?: store.create()
                    store.latestHistory(conversation.id)
                    runner.send(conversation.id, text); PluginOutcome.RefreshUi
                }
                actionId == "stop" -> { store.target(context)?.id?.let(runner::stopConversation); PluginOutcome.RefreshUi }
                actionId.startsWith("notification-open/") -> { store.select(actionId.substringAfter('/')); PluginOutcome.OpenPage("chat") }
                actionId.startsWith("notification-stop/") -> { runner.stopConversation(actionId.substringAfter('/')); PluginOutcome.RefreshUi }
                actionId.startsWith("export-conversation/") -> {
                    val conversation = store.get(actionId.substringAfter('/')) ?: return PluginOutcome.Message("会话不存在")
                    val path = "exports/conversation-${conversation.id}.json"
                    val file = environment.storage.file(path); file.parentFile?.mkdirs()
                    file.writeText(PluginJson.encodeToString(conversation), Charsets.UTF_8)
                    PluginOutcome.ExportResource("导出会话", PluginResource(path), "助手会话.json", mimeType = "application/json")
                }
                actionId.startsWith("access/") -> {
                    require(context.inputs["_callerPlugin"] == null) { "请通过会话输入栏切换权限" }
                    if (actionId == "access/full") require(store.settings.fullAccessConfirmed || context.inputs["_agentFullConfirmed"] == "true") { "请先确认完全访问警告" }
                    store.updateSettings { it.copy(access = when (actionId) { "access/full" -> AgentAccessMode.FULL; "access/assist" -> AgentAccessMode.ASSISTED; else -> AgentAccessMode.REQUEST_APPROVAL },
                        fullAccessConfirmed = it.fullAccessConfirmed || context.inputs["_agentFullConfirmed"] == "true") }
                    val target = store.target(context)?.id
                    if (actionId == "access/full" && target?.let(store::pendingFor)?.let { !runner.isRunning(it.conversationId) } == true) reviews.resume(target)
                    else PluginOutcome.RefreshUi
                }
                actionId == "reasoning" -> { store.updateSettings { it.copy(showReasoning = !it.showReasoning) }; PluginOutcome.RefreshUi }
                actionId == "pin-home-icon" -> { store.updateSettings { it.copy(pinHomeIcon = !it.pinHomeIcon) }; PluginOutcome.RefreshUi }
                actionId == "task-notifications" -> {
                    store.updateSettings { it.copy(taskNotifications = !it.taskNotifications) }
                    if (!store.settings.taskNotifications) notifications.clear()
                    PluginOutcome.RefreshUi
                }
                actionId == "tools" -> {
                    check(!runner.running) { "请先停止当前回答" }; store.updateSettings { it.copy(toolsEnabled = !it.toolsEnabled) }; PluginOutcome.RefreshUi
                }
                actionId == "review" -> reviews.open(store.target(context)?.id)
                actionId == "resume" -> reviews.resume(store.target(context)?.id)
                actionId == "discard-review" -> reviews.discard(store.target(context)?.id)
                actionId == "import-cards" -> PluginOutcome.ImportResource("导入快捷文本", "resources/quick-imports", "import-cards-complete")
                actionId == "import-cards-complete" -> {
                    val broker = requireNotNull(environment.quickTextBroker)
                    val revision = (broker.request(QuickTextCommand.Query(limit = 0)) as QuickTextReply.Snapshot).value.revision
                    val source = PluginResource(requireNotNull(context.inputs["resourcePath"]))
                    prepared(broker.request(QuickTextCommand.Import(source, UUID.randomUUID().toString(), revision)))
                }
                actionId == "export-cards" -> when (val reply = requireNotNull(environment.quickTextBroker).request(QuickTextCommand.Export())) {
                    is QuickTextReply.Resource -> PluginOutcome.ExportResource("导出快捷文本", reply.source, "快捷文本.kigcards")
                    else -> PluginOutcome.Message((reply as? QuickTextReply.Failure)?.message ?: "导出未完成")
                }
                else -> PluginOutcome.Message("未知助手操作")
            }
            changed(); return result
        } catch (error: IllegalArgumentException) { return PluginOutcome.Message(error.message?.take(200) ?: "请检查输入内容") }
        catch (error: IllegalStateException) {
            return when (error.message) {
                "请选择可用模型" -> PluginOutcome.Dialog("选择模型", "请选择可用模型")
                "暂无可用模型，请先在供应商配置中填写 API 密钥并刷新模型列表" -> PluginOutcome.Dialog("暂无可用模型", "请先配置供应商的 API 密钥，再回到模型选择中刷新列表。")
                else -> PluginOutcome.Message(error.message?.take(200) ?: "操作暂时无法完成")
            }
        }
        catch (_: java.io.IOException) { return PluginOutcome.Message("文件暂时无法保存，请稍后重试") }
    }
    private suspend fun prepared(reply: QuickTextReply): PluginOutcome {
        if (reply !is QuickTextReply.Prepared) return PluginOutcome.Message((reply as? QuickTextReply.Failure)?.message ?: "导入未完成")
        val conversation = store.selected() ?: store.create()
        store.setPending(PendingReview(conversation.id, "", reply.value.token, "导入内容已准备好，请审核"))
        return reviews.open()
    }
    override suspend fun close() { if (::catalog.isInitialized) catalog.close(); if (::runner.isInitialized) runner.close(); if (::notifications.isInitialized) notifications.close(); uiSignals.close() }
}
