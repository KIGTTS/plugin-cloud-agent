package com.kigtts.tools.agent

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.Reader
import java.io.StringReader

class SseDecoderTest {
    @Test fun framesCrLfCommentsMultilineAndPartialReader() = runTest {
        val values = mutableListOf<String>()
        val source = ": heartbeat\r\ndata: 第一\r\ndata: 第二\r\n\r\ndata: [DONE]\n\n"
        val reader = object : Reader() {
            var index = 0
            override fun read(chars: CharArray, offset: Int, length: Int): Int {
                if (index == source.length) return -1
                chars[offset] = source[index++]; return 1
            }
            override fun close() {}
        }
        SseDecoder().read(reader) { values += it; true }
        assertEquals(listOf("第一\n第二", "[DONE]"), values)
    }
    @Test fun doneStopsBeforeFollowingEvents() = runTest {
        val values = mutableListOf<String>()
        SseDecoder().read(StringReader("data: [DONE]\n\ndata: should-not-run\n\n")) { values += it; it != "[DONE]" }
        assertEquals(listOf("[DONE]"), values)
    }
    @Test fun eventAndLineLimitAreBounded() = runTest {
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking { SseDecoder(10).read(StringReader("data: " + "a".repeat(20))) { true } }
        }
    }
    @Test fun fragmentedToolsAndReasoningAreAccumulated() {
        val stream = StreamAssembler()
        stream.accept("""{"choices":[{"index":0,"delta":{"reasoning_content":"检查","tool_calls":[{"index":0,"id":"call1","function":{"name":"app","arguments":"{\"comm"}}]}}]}""")
        stream.accept("""{"choices":[{"index":0,"delta":{"reasoning_content":"状态","tool_calls":[{"index":0,"function":{"arguments":"and\":{\"type\":\"state\"}}"}}]},"finish_reason":"tool_calls"}]}""")
        stream.accept("[DONE]")
        assertEquals("检查状态", stream.reasoningContent)
        assertEquals(ToolCall("call1", "app", "{\"command\":{\"type\":\"state\"}}"), stream.result().single())
    }
    @Test fun truncatedToolsNeverBecomeExecutable() {
        val stream = StreamAssembler()
        stream.accept("""{"choices":[{"delta":{"tool_calls":[{"id":"x","function":{"name":"app","arguments":"{"}}]}}]}""")
        assertThrows(IllegalStateException::class.java) { stream.result() }
    }
    @Test fun lengthTerminationFailsInsteadOfRunningTools() {
        val stream = StreamAssembler()
        stream.accept("""{"choices":[{"delta":{"content":"partial"},"finish_reason":"length"}]}""")
        assertThrows(IllegalStateException::class.java) { stream.result() }
        assertEquals("partial", stream.content)
    }
    @Test fun providerErrorCannotBeConfusedWithSuccessfulEmptyReply() {
        assertThrows(IllegalStateException::class.java) { StreamAssembler().accept("{\"error\":{\"message\":\"secret\"}}") }
    }
    @Test fun outputLimitIncludesReasoning() {
        assertThrows(IllegalArgumentException::class.java) { StreamAssembler(3).accept("""{"choices":[{"delta":{"reasoning_content":"1234"}}]}""") }
    }
}
