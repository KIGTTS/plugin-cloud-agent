package com.kigtts.tools.agent

import com.kigtts.plugin.api.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AgentBrowserDeviceTest {
    @Test fun currentDeviceCanRotateAndExplicitViewportNeedsNoHostQuery() = runTest {
        var queries = 0
        val broker = object : AppBroker {
            override suspend fun request(command: AppCommand): AppReply {
                assertEquals("devices", (command as AppCommand.BrowserSession).operation); queries++
                return AppReply.Data(PluginJson.parseToJsonElement("""{"devices":[{"id":"current","width":400,"height":889}]}""").jsonObject)
            }
        }
        assertEquals(400 to 889, browserDeviceSize(buildJsonObject {}, broker))
        assertEquals(889 to 400, browserDeviceSize(buildJsonObject { put("device", "current"); put("orientation", "landscape") }, broker))
        assertEquals(1280 to 800, browserDeviceSize(buildJsonObject { put("width", 800); put("height", 1280); put("orientation", "landscape") }, broker))
        assertEquals(2, queries)
    }
}
