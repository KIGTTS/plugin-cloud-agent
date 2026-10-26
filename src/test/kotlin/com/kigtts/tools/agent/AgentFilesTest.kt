package com.kigtts.tools.agent

import com.kigtts.plugin.api.*
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AgentFilesTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun environment() = object : PluginEnvironment {
        override val storage = PluginStorage(temporary.root)
        override val assets = temporary.root
        override suspend fun download(url: String, relativePath: String, sha256: String?): File = error("network disabled")
    }
    private fun args(vararg values: Pair<String, JsonElement>) = JsonObject(values.toMap())
    @Test fun copyKeepsBinaryBytesAndRefusesUnapprovedOverwrite() = runTest {
        val env = environment(); val tools = AgentTools(env)
        val bytes = ByteArray(4096) { it.toByte() }
        env.storage.file("imports/mesh.ply").apply { parentFile!!.mkdirs(); writeBytes(bytes) }
        val call = ToolCall("c", "copy_file", """{"source":"imports/mesh.ply","destination":"workspace/demo/models/mesh.ply"}""")
        tools.execute(call)
        assertArrayEquals(bytes, env.storage.file("workspace/demo/models/mesh.ply").readBytes())
        try { tools.execute(call); fail() } catch (_: IllegalArgumentException) { }
        try { tools.execute(call.copy(arguments = """{"source":"imports/mesh.ply","destination":"workspace/../config/key"}""")); fail() } catch (_: IllegalArgumentException) { }
    }
    @Test fun utf8PagingDoesNotSplitChineseOrEmoji() {
        val env = environment(); val file = env.storage.file("imports/文字.c").apply { parentFile!!.mkdirs(); writeText("你好🙂int main(){}", Charsets.UTF_8) }
        val files = AgentFiles(env.storage) { it }
        var offset = 0; val result = StringBuilder()
        do {
            val page = files.read(args("path" to JsonPrimitive("imports/文字.c"), "offset" to JsonPrimitive(offset), "limit" to JsonPrimitive(7)))
            result.append(page.getValue("text").jsonPrimitive.content)
            offset = page["nextOffset"]?.jsonPrimitive?.int ?: file.length().toInt()
        } while (offset < file.length())
        assertEquals("你好🙂int main(){}", result.toString())
    }
    @Test fun binaryIsNotReturnedAsInventedTextAndMidiPlyHaveHeaders() {
        val env = environment(); val files = AgentFiles(env.storage) { it }
        env.storage.file("imports/song.mid").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(77,84,104,100,0,0,0,6,0,1,0,2,1,(-32).toByte())) }
        val midi = files.inspect("imports/song.mid")
        assertEquals(2, midi.getValue("tracks").jsonPrimitive.int)
        assertTrue(files.read(args("path" to JsonPrimitive("imports/song.mid"))).getValue("binary").jsonPrimitive.boolean)
        env.storage.file("imports/mesh.ply").writeText("ply\nformat ascii 1.0\nelement vertex 4\nproperty float x\nend_header\n0\n")
        assertTrue(files.inspect("imports/mesh.ply").getValue("header").jsonPrimitive.content.contains("element vertex 4"))
    }
    @Test fun attachmentMetadataSurvivesHistoryAndIsPresentInModelRequest() {
        val env = environment(); val store = AgentStore(env.storage); val conversation = store.create()
        val file = AgentFile("imports/chat-files/a/model.gltf", "model.gltf", 20, "model/gltf+json")
        store.save(conversation.copy(draftFiles = listOf(file), queued = listOf(AgentQueuedMessage(text = "", files = listOf(file))),
            messages = listOf(AgentMessage(role = "user", files = listOf(file)))))
        val restored = AgentStore(env.storage).get(conversation.id)!!
        assertEquals(file, restored.draftFiles.single()); assertEquals(file, restored.queued.single().files.single())
        val wire = restored.messages.single().toWire().getValue("content").jsonPrimitive.content
        assertTrue(wire.contains(file.path)); assertTrue(wire.contains("read_file")); assertFalse(wire.contains("data:"))
        assertTrue(contextCost(restored.messages.single()) > contextCost(AgentMessage(role = "user")))
    }
    @Test fun sendFileReturnsActualScopedAttachmentAndSaveRequiresAnExistingReference() = runTest {
        val env = environment(); val store = AgentStore(env.storage); val conversation = store.create()
        env.storage.file("workspace/main.h").apply { parentFile!!.mkdirs(); writeText("int main();") }
        val result = AgentTools(env).execute(ToolCall("c", "send_file", """{"path":"workspace/main.h"}"""))
        val ref = result.files.single(); assertEquals("main.h", ref.name)
        val actions = AgentFileActions(env, store)
        val action = ref.chatFile().openAction!!
        try { actions.handle(action, PluginContext("", "")); fail() } catch (_: IllegalStateException) { }
        store.message(conversation.id, AgentMessage(role = "assistant", files = result.files))
        val export = actions.handle(action, PluginContext("", "")) as PluginOutcome.ExportResource
        assertEquals(ref.path, export.resource.path); assertEquals("main.h", export.fileName)
    }
    @Test fun importsAcceptUnusualExtensionsAndRejectOversizedFilesWithoutKeepingThem() = runTest {
        val env = environment(); val store = AgentStore(env.storage); val c = store.create()
        val actions = AgentFileActions(env, store)
        val path = "imports/chat-files/b/test.h"
        env.storage.file(path).apply { parentFile!!.mkdirs(); writeText("#define A 1") }
        actions.handle("attach-file-complete/${c.id}", PluginContext("", "", inputs = mapOf("resourcePath" to path)))
        assertEquals("test.h", store.get(c.id)!!.draftFiles.single().name)
        val large = env.storage.file("imports/chat-files/b/large.bin")
        java.io.RandomAccessFile(large, "rw").use { it.setLength(AGENT_FILE_LIMIT + 1) }
        try { actions.handle("attach-file-complete/${c.id}", PluginContext("", "", inputs = mapOf("resourcePath" to "imports/chat-files/b/large.bin"))); fail() } catch (_: IllegalArgumentException) { }
        assertFalse(large.exists()); assertEquals(1, store.get(c.id)!!.draftFiles.size)
    }
    @Test fun deliveryUsesOwnedResourceAndStopsAtPendingRecipientReview() = runTest {
        var received: AppCommand.DeliverResource? = null
        val env = object : PluginEnvironment {
            override val storage = PluginStorage(temporary.root); override val assets = temporary.root
            override val appBroker = object : AppBroker {
                override suspend fun request(command: AppCommand): AppReply {
                    received = command as AppCommand.DeliverResource
                    return AppReply.Data(buildJsonObject { put("pending", true); put("completed", false) })
                }
            }
            override suspend fun download(url: String, relativePath: String, sha256: String?): File = error("network disabled")
        }
        env.storage.file("workspace/assets/data.json").apply { parentFile!!.mkdirs(); writeText("{}") }
        val tools = AgentTools(env)
        val call = ToolCall("c", "deliver_file", """{"path":"workspace/assets/data.json","pluginId":"example.receiver","actionId":"import"}""")
        assertTrue(tools.requiresApproval(call)); assertFalse(tools.readOnly(call))
        val result = tools.execute(call)
        assertEquals("workspace/assets/data.json", received!!.source.path); assertNull(received!!.source.pluginId)
        assertTrue(result.pause); assertEquals(ReviewKind.EXTERNAL, result.reviewKind)
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun fileOnlyMessagesAndQueueRetainIndependentAttachments() = runTest {
        val env = environment(); val store = AgentStore(env.storage); val c = store.create()
        val first = AgentFile("imports/a.c", "a.c", 1); val second = AgentFile("imports/b.mid", "b.mid", 2)
        val requests = mutableListOf<String>()
        val transport = object : ChatTransport {
            override suspend fun stream(endpoint: String, apiKey: String, request: JsonObject, delta: suspend (StreamAssembler) -> Unit): StreamAssembler {
                requests += request.toString()
                return StreamAssembler().apply { accept("""{"choices":[{"delta":{"content":"收到"},"finish_reason":"stop"}]}"""); accept("[DONE]") }
            }
        }
        val secrets = object : AgentSecrets {
            override fun read(provider: Provider) = "test"; override fun exists(provider: Provider) = true
            override fun write(provider: Provider, value: String) {}
        }
        val runner = AgentRunner(store, secrets, transport, AgentTools(env), {}, this)
        store.modify(c.id) { it.copy(draftFiles = listOf(first)) }; runner.send(c.id, "")
        store.modify(c.id) { it.copy(draftFiles = listOf(second)) }; runner.enqueue(c.id, "")
        advanceUntilIdle()
        assertEquals(listOf(listOf(first), listOf(second)), store.get(c.id)!!.messages.filter { it.role == "user" }.map { it.files })
        assertTrue(requests[0].contains("imports/a.c")); assertTrue(requests[1].contains("imports/b.mid"))
        assertTrue(store.get(c.id)!!.draftFiles.isEmpty()); assertTrue(store.get(c.id)!!.queued.isEmpty())
    }
}
