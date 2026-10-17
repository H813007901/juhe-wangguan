package dev.aigw.core.provider.codebuddy

import com.google.gson.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * WorkBuddy 粘贴凭证 JSON 导入。
 *
 * 上游没有回调链接端点，浏览器授权错过后只能手工粘贴凭证；
 * 导入必须走与设备授权相同的 [CodeBuddyProvider.credentialOf] → `toProviderAccount` 链路，
 * 否则同一个账号从两条路进来会得到不同的结构（uid 漂移 = 账号池里出现两个号）。
 */
class CodeBuddyImportTest {

    private val provider = CodeBuddyProvider(region = { CodeBuddyProvider.REGION_CN })

    private fun jwt(payloadJson: String): String {
        val encoded = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(payloadJson.toByteArray(Charsets.UTF_8))
        return "eyJhbGciOiJIUzI1NiJ9.$encoded.signature"
    }

    private fun secretOf(secret: String) = JsonParser.parseString(secret).asJsonObject

    @Test
    fun `camelCase 凭证 JSON 解析成功`() {
        val account = assertNotNull(
            provider.importCredentials(
                """
                {"accessToken":"at-1","refreshToken":"rt-1","expiresAt":1760000000,
                 "domain":"www.codebuddy.cn","uid":"u-1","enterpriseId":"e-1","nickname":"小明"}
                """.trimIndent(),
            ),
        )
        assertEquals(CodeBuddyProvider.ID, account.providerId)
        assertEquals("u-1", account.uid)
        assertEquals("小明", account.nickname)
        val secret = secretOf(account.secret)
        assertEquals("at-1", secret.get("accessToken").asString)
        assertEquals("rt-1", secret.get("refreshToken").asString)
        assertEquals("www.codebuddy.cn", secret.get("domain").asString)
        assertEquals("u-1", secret.get("uid").asString)
        assertEquals("e-1", secret.get("enterpriseId").asString)
    }

    @Test
    fun `snake_case 字段名同样兼容`() {
        val account = assertNotNull(
            provider.importCredentials(
                """
                {"access_token":"at-2","refresh_token":"rt-2","expires_at":1760000001,
                 "user_id":"u-2","enterprise_id":"e-2","nickname":"小红"}
                """.trimIndent(),
            ),
        )
        assertEquals("u-2", account.uid)
        assertEquals("小红", account.nickname)
        val secret = secretOf(account.secret)
        assertEquals("at-2", secret.get("accessToken").asString)
        assertEquals("rt-2", secret.get("refreshToken").asString)
        assertEquals("e-2", secret.get("enterpriseId").asString)
    }

    @Test
    fun `缺 accessToken 或非法 JSON 时给出可读错误`() {
        // 空输入
        assertFailsWith<IllegalStateException> { provider.importCredentials("   ") }
        // 不是 JSON
        assertFailsWith<IllegalStateException> { provider.importCredentials("not-json") }
        // 合法 JSON 但没有 accessToken
        val missing = assertFailsWith<IllegalStateException> {
            provider.importCredentials("""{"refreshToken":"rt","domain":"www.codebuddy.cn"}""")
        }
        assertEquals("凭证缺少 accessToken（access_token）字段", missing.message)
    }

    @Test
    fun `uid 缺失时从 accessToken 的 JWT 兜底，与设备授权链路一致`() {
        val token = jwt("""{"user_id":"u-jwt","tenant_id":"t-jwt","sub":"s-1"}""")
        val account = assertNotNull(provider.importCredentials("""{"accessToken":"$token"}"""))
        assertEquals("u-jwt", account.uid)
        val secret = secretOf(account.secret)
        assertEquals("u-jwt", secret.get("uid").asString)
        // enterpriseId 同样从 JWT 的 tenant_id 兜底
        assertEquals("t-jwt", secret.get("enterpriseId").asString)
        // 区域判定依赖 domain；无 domain 时回退到供应商默认区域，保证账号能被正确分桶
        assertEquals(CodeBuddyProvider.REGION_CN, provider.regionOf(account))
    }

    @Test
    fun `导入结果能驱动账号池的区域分桶（国际版域名）`() {
        val account = assertNotNull(
            provider.importCredentials(
                """{"accessToken":"at-3","domain":"www.workbuddy.ai","uid":"u-3"}""",
            ),
        )
        assertEquals(CodeBuddyProvider.REGION_GLOBAL, provider.regionOf(account))
    }

    @Test
    fun `国际版历史域名与 JWT issuer 都判为国际版`() {
        val legacyDomain = assertNotNull(
            provider.importCredentials(
                """{"accessToken":"at-4","domain":"www.codebuddy.ai","uid":"u-4"}""",
            ),
        )
        assertEquals(CodeBuddyProvider.REGION_GLOBAL, provider.regionOf(legacyDomain))

        val token = jwt("""{"iss":"https://workbuddy.ai/auth/realms/copilot","sub":"u-jwt"}""")
        val issuerOnly = assertNotNull(
            provider.importCredentials("""{"accessToken":"$token","uid":"u-5"}"""),
        )
        assertEquals(CodeBuddyProvider.REGION_GLOBAL, provider.regionOf(issuerOnly))
    }

    @Test
    fun `JWT issuer 优先于凭证里的国内域名`() {
        // 拿国际版 token 打国内站点会被上游直接回 500，所以 issuer 说国际就是国际
        val token = jwt("""{"iss":"https://workbuddy.ai/auth/realms/copilot","sub":"u-6"}""")
        val account = assertNotNull(
            provider.importCredentials("""{"accessToken":"$token","domain":"www.codebuddy.cn","uid":"u-6"}"""),
        )
        assertEquals(CodeBuddyProvider.REGION_GLOBAL, provider.regionOf(account))
    }

    @Test
    fun `缺 expiresAt 时用 expiresIn 换算绝对过期时间`() {
        // 设备授权只给相对的 expiresIn；不换算的话 expiresAt 恒为 0，
        // 「距过期还早就不刷」判断失效 —— 每个请求都会先刷一次 token（首字延迟多一跳）
        val before = System.currentTimeMillis() / 1000
        val account = assertNotNull(
            provider.importCredentials(
                """{"accessToken":"at-9","refreshToken":"rt-9","expiresIn":3600,
                 "domain":"www.workbuddy.ai","uid":"u-9"}""".trimIndent(),
            ),
        )
        val expiresAt = secretOf(account.secret).get("expiresAt").asLong
        assertTrue(expiresAt in (before + 3500)..(before + 3700), "expiresAt=$expiresAt")
    }

    @Test
    fun `国际版模型目录走 v3 config`() {
        // 国内那条 console 路径只服务网页会话（无凭证 302 跳登录页），Bearer token 打它会 500
        assertEquals("/v3/config", CodeBuddyProvider.modelsPath(CodeBuddyProvider.REGION_GLOBAL))
        assertEquals(
            "/console/enterprises/personal/models",
            CodeBuddyProvider.modelsPath(CodeBuddyProvider.REGION_CN),
        )
    }
}
