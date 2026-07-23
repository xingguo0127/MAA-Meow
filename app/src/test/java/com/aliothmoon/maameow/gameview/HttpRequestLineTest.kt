package com.aliothmoon.maameow.gameview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HttpRequestLineTest {
    @Test
    fun `解析带 query 的 GET`() {
        val r = parseRequestLine("GET /frame?display=9 HTTP/1.1")!!
        assertEquals("GET", r.method)
        assertEquals("/frame", r.path)
        assertEquals("9", r.query["display"])
    }

    @Test
    fun `解析多参数 POST`() {
        val r = parseRequestLine("POST /swipe?display=9&x1=1&y1=2&x2=3&y2=4&ms=300 HTTP/1.1")!!
        assertEquals("POST", r.method)
        assertEquals("/swipe", r.path)
        assertEquals("300", r.query["ms"])
        assertEquals("4", r.query["y2"])
    }

    @Test
    fun `无 query`() {
        val r = parseRequestLine("GET /displays HTTP/1.1")!!
        assertEquals("/displays", r.path)
        assertEquals(0, r.query.size)
    }

    @Test
    fun `畸形行返回 null`() {
        assertNull(parseRequestLine("garbage"))
    }
}
