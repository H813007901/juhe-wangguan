package dev.aigw.core.gateway

import fi.iki.elonen.NanoHTTPD

/** 自建状态对象，避免依赖 NanoHTTPD 枚举里是否存在某个状态码。 */
internal class SimpleStatus(private val code: Int, private val text: String) : NanoHTTPD.Response.IStatus {
    override fun getRequestStatus(): Int = code
    override fun getDescription(): String = "$code $text"
}
