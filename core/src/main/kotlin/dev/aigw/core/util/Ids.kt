package dev.aigw.core.util

import java.security.SecureRandom
import java.util.UUID

/** 生成 OpenAI 风格的 completion id：`chatcmpl-` + 24 位无横线 UUID。 */
fun newCompletionId(): String = "chatcmpl-" + UUID.randomUUID().toString().replace("-", "").take(24)

/** 进程内共用的强随机源（SecureRandom 初始化昂贵，避免每次新建）。 */
private val secureRandom = SecureRandom()

/** 生成 [byteCount] 字节随机数据的小写十六进制串（如设备指纹、state、trace id）。 */
fun randomHex(byteCount: Int): String {
    val bytes = ByteArray(byteCount)
    secureRandom.nextBytes(bytes)
    return bytes.joinToString("") { "%02x".format(it) }
}
