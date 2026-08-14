package com.capswriter.ime

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 协议序列化/解析单元测试（JVM，org.json 由测试依赖提供）。
 *
 * 覆盖与服务端 core/protocol.py 对齐的关键字段：
 *  - AudioMessage.toJson 包含 task_id/source/data/is_final/time_start/seg 段参数/context/language
 *  - 收尾包 data 为空、is_final=true
 *  - RecognitionMessage.parse 容缺字段、解析非法 JSON 返回 null
 *  - 累积文本语义（整体覆盖，不拼接）
 */
class ProtocolTest {

    @Test
    fun audioMessage_toJson_包含全部必填字段() {
        val msg = AudioMessage(
            taskId = "t1", data = "base64==", isFinal = false, timeStart = 1.0,
        )
        val json = JSONObject(msg.toJson())
        assertEquals("t1", json.getString("task_id"))
        assertEquals("mic", json.getString("source"))
        assertEquals("base64==", json.getString("data"))
        assertFalse(json.getBoolean("is_final"))
        assertEquals(1.0, json.getDouble("time_start"), 0.001)
        assertEquals(Protocol.SEG_DURATION, json.getDouble("seg_duration"), 0.001)
        assertEquals(Protocol.SEG_OVERLAP, json.getDouble("seg_overlap"), 0.001)
        assertEquals("", json.getString("context"))
        assertEquals("auto", json.getString("language"))
    }

    @Test
    fun 收尾包_data为空且isFinal为true() {
        val finalMsg = AudioMessage(taskId = "t2", data = "", isFinal = true, timeStart = 0.0)
        val json = JSONObject(finalMsg.toJson())
        assertEquals("", json.getString("data"))
        assertTrue(json.getBoolean("is_final"))
    }

    @Test
    fun prompt透传到context字段() {
        val msg = AudioMessage(
            taskId = "t3", data = "x", isFinal = false, timeStart = 0.0,
            prompt = "人名：张三", language = "chinese",
        )
        val json = JSONObject(msg.toJson())
        assertEquals("人名：张三", json.getString("context"))
        assertEquals("chinese", json.getString("language"))
    }

    @Test
    fun recognitionMessage_parse_合法字段() {
        val raw = JSONObject().apply {
            put("task_id", "r1")
            put("is_final", false)
            put("duration", 2.5)
            put("text", "你好世界")
        }.toString()
        val parsed = RecognitionMessage.parse(raw)
        assertNotNull(parsed)
        assertEquals("r1", parsed!!.taskId)
        assertFalse(parsed.isFinal)
        assertEquals(2.5, parsed.duration, 0.001)
        assertEquals("你好世界", parsed.text)
    }

    @Test
    fun recognitionMessage_parse_缺字段用默认值() {
        // 服务端某些阶段可能只返回 task_id，其余字段应容缺
        val raw = """{"task_id":"r2"}"""
        val parsed = RecognitionMessage.parse(raw)
        assertNotNull(parsed)
        assertEquals("r2", parsed!!.taskId)
        assertFalse(parsed.isFinal)
        assertEquals(0.0, parsed.duration, 0.001)
        assertEquals("", parsed.text)
    }

    @Test
    fun recognitionMessage_parse_final包语义() {
        val raw = """{"task_id":"r3","is_final":true,"duration":3.0,"text":"最终结果"}"""
        val parsed = RecognitionMessage.parse(raw)!!
        assertTrue(parsed.isFinal)
        assertEquals("最终结果", parsed.text)
    }

    @Test
    fun recognitionMessage_parse_非法JSON返回null() {
        assertNull(RecognitionMessage.parse("not a json"))
    }

    @Test
    fun recognitionMessage_parse_空字符串返回null() {
        assertNull(RecognitionMessage.parse(""))
    }

    @Test
    fun 累积文本语义_多条结果整体覆盖不拼接() {
        // 模拟服务端逐段累积：同 task 返回越来越长的整段文本
        val seg1 = RecognitionMessage.parse("""{"task_id":"s","is_final":false,"text":"你好"}""")!!
        val seg2 = RecognitionMessage.parse("""{"task_id":"s","is_final":false,"text":"你好世界"}""")!!
        val segFinal = RecognitionMessage.parse("""{"task_id":"s","is_final":true,"text":"你好世界。"}""")!!
        // 客户端每次整体覆盖显示，故末条即全段
        assertEquals("你好", seg1.text)
        assertEquals("你好世界", seg2.text)
        assertEquals("你好世界。", segFinal.text)
        assertFalse(seg1.isFinal)
        assertFalse(seg2.isFinal)
        assertTrue(segFinal.isFinal)
    }
}
