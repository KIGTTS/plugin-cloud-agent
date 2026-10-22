package com.kigtts.tools.agent

import com.kigtts.plugin.api.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import java.io.File

class AgentHomeIconTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun pinningTheRobotEntryIsOptionalAndPersistsWithNormalSettings() = runBlocking {
        val env = object : PluginEnvironment {
            override val storage = PluginStorage(temp.root); override val assets = temp.root
            override suspend fun download(url: String, relativePath: String, sha256: String?): File = error("unused")
        }
        val plugin = CloudAgentPlugin(); plugin.attach(env)
        assertTrue(plugin.contributions.actions.isEmpty())
        assertEquals(PluginIcon.ROBOT2, plugin.contributions.settingsEntries.first().icon)
        plugin.execute("pin-home-icon", PluginContext("", ""))
        assertEquals(UiSlot.HEADER_END, plugin.contributions.actions.single().slot)
        assertFalse(plugin.contributions.actions.single().busy)
        assertTrue(AgentStore(env.storage).settings.pinHomeIcon)
        plugin.execute("pin-home-icon", PluginContext("", "")); assertTrue(plugin.contributions.actions.isEmpty())
        plugin.close()
    }
}
