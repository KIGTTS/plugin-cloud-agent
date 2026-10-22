package com.kigtts.tools.agent

import com.kigtts.plugin.api.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class AgentTaskNotificationsTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun concurrentTasksKeepTheirConversationAndDenialDoesNotStopEvents() = runTest {
        val calls = mutableListOf<PluginNotification>()
        val env = object : PluginEnvironment {
            override val storage = PluginStorage(temporary.root); override val assets = temporary.root
            override val appBroker = object : AppBroker {
                override suspend fun request(command: AppCommand): AppReply {
                    (command as? AppCommand.Notifications)?.notification?.let { calls += it }
                    return AppReply.Failure("PERMISSION_DENIED", "通知已关闭")
                }
            }
            override suspend fun download(url: String, relativePath: String, sha256: String?): File = error("unused")
        }
        val store = AgentStore(env.storage)
        val first = store.create(); val second = store.create()
        store.modify(first.id) { it.copy(title = "第一个任务") }; store.modify(second.id) { it.copy(title = "第二个任务") }
        val notifier = AgentTaskNotifications(store, env, backgroundScope)
        notifier.changed(first.id, true); notifier.changed(second.id, true); runCurrent()
        notifier.changed(first.id, false); runCurrent()
        assertEquals(listOf("task-${first.id}", "task-${second.id}", "task-${first.id}"), calls.map { it.id })
        assertEquals(listOf("第一个任务", "第二个任务", "第一个任务"), calls.map { it.text })
        assertEquals("notification-open/${second.id}", calls[1].contentAction!!.actionId)
        assertEquals("notification-stop/${first.id}", calls[0].actions.single().actionId)
        assertTrue(calls[0].ongoing); assertTrue(calls[0].live)
        assertFalse(calls[2].ongoing); assertFalse(calls[2].live); assertTrue(calls[2].title.contains("已完成"))
        store.updateSettings { it.copy(taskNotifications = false) }
        notifier.changed(second.id, false); runCurrent(); assertEquals(3, calls.size)
        notifier.close()
    }
}
