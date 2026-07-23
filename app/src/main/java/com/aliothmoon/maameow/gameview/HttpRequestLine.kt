package com.aliothmoon.maameow.gameview

/** GameViewServer 的极简 HTTP 请求行解析（fork 专属）。 */
data class HttpRequestLine(val method: String, val path: String, val query: Map<String, String>)

fun parseRequestLine(line: String): HttpRequestLine? {
    val parts = line.trim().split(" ")
    if (parts.size < 3 || !parts[2].startsWith("HTTP/")) return null
    val url = parts[1]
    val query = url.substringAfter("?", "")
        .split("&").filter { it.contains("=") }
        .associate { it.substringBefore("=") to it.substringAfter("=") }
    return HttpRequestLine(parts[0], url.substringBefore("?"), query)
}
