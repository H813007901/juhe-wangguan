package dev.aigw.core.gateway

import com.google.gson.JsonObject
import fi.iki.elonen.NanoHTTPD

/**
 * 对话管线的响应整形钩子：/v1/chat/completions（OpenAI 直通）与 /v1/messages（Anthropic 转换）
 * 共用同一套熔断/选号/并发闸/限速/换号/换家逻辑，只有出口整形不同。
 */
internal class ChatProto(
    /** 日志里显示的端点名。 */
    val label: String,
    /** 管道内错误响应（400 upstream_rejected / 503 等）。 */
    val error: (status: Int, code: String, message: String) -> NanoHTTPD.Response,
    /** 非流式成功响应的整形：payload 已解析，model 归一化由实现负责。 */
    val nonStream: (completion: JsonObject, requestedModel: String) -> String,
    /** 流式行整形器：Anthropic 转换器有状态，每条流新建一个。 */
    val stream: (requestedModel: String) -> SseShaper,
)

/** 流式转发的行整形钩子：OpenAI 直通 vs Anthropic 事件转换。 */
internal class SseShaper(
    /** 上游一行 → 零或多个输出行（每行一个完整帧/事件块）。 */
    val feed: (String) -> List<String>,
    /** 上游 EOF 没发结束帧（或网关错误帧之后）的收尾输出，可为空串。 */
    val finish: () -> String,
    /** 上游无数据/网关异常时的错误帧。 */
    val error: (code: String, message: String) -> String,
)
