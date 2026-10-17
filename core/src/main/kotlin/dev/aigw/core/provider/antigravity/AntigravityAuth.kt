package dev.aigw.core.provider.antigravity

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.aigw.core.provider.antigravity.AntigravityProvider.Credential

internal fun AntigravityProvider.exchangeCodeForTokens(code: String): Tokens {
        val form = mapOf(
            "code" to code,
            "client_id" to AntigravityProvider.CLIENT_ID,
            "client_secret" to AntigravityProvider.CLIENT_SECRET,
            "redirect_uri" to redirectUri(),
            "grant_type" to "authorization_code",
        )
        val (status, body) = postForm(tokenEndpoint, form)
        if (status !in 200..299) throw IllegalStateException("换取 token 失败（HTTP $status）：${body.take(200)}")
        val obj = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull()
            ?: throw IllegalStateException("token 响应不是合法 JSON")
        val access = obj.get("access_token")?.asString.orEmpty()
        if (access.isEmpty()) throw IllegalStateException("token 响应缺少 access_token")
        val expiresIn = obj.get("expires_in")?.asLong ?: 3600L
        return Tokens(
            accessToken = access,
            refreshToken = obj.get("refresh_token")?.asString.orEmpty(),
            expiresAt = nowMillis() / 1000 + expiresIn,
        )
    }

internal fun AntigravityProvider.refreshAccessToken(credential: Credential): Credential? {
        if (credential.refreshToken.isEmpty()) return null
        val form = mapOf(
            "client_id" to AntigravityProvider.CLIENT_ID,
            "client_secret" to AntigravityProvider.CLIENT_SECRET,
            "refresh_token" to credential.refreshToken,
            "grant_type" to "refresh_token",
        )
        val (status, body) = try {
            postForm(tokenEndpoint, form)
        } catch (e: Exception) {
            hooks.onLog("Antigravity 刷新 Token 网络异常：${e.message}")
            return null
        }
        if (status !in 200..299) {
            hooks.onLog("Antigravity 刷新 Token 失败（HTTP $status）：${body.take(160)}")
            return null
        }
        val obj = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull() ?: return null
        val access = obj.get("access_token")?.asString.orEmpty()
        if (access.isEmpty()) return null
        val expiresIn = obj.get("expires_in")?.asLong ?: 3600L
        // Google 可能轮换 refresh_token（此时旧值作废），不回存会导致下次刷新 invalid_grant
        val nextRefresh = obj.get("refresh_token")?.asString?.takeIf { it.isNotEmpty() }
        return credential.copy(
            accessToken = access,
            refreshToken = nextRefresh ?: credential.refreshToken,
            expiresAt = nowMillis() / 1000 + expiresIn,
        )
    }

internal fun AntigravityProvider.fetchEmail(accessToken: String): String {
        val (status, body) = try {
            get("https://www.googleapis.com/oauth2/v2/userinfo?alt=json", accessToken)
        } catch (_: Exception) {
            return ""
        }
        if (status !in 200..299) return ""
        return runCatching { JsonParser.parseString(body).asJsonObject.get("email")?.asString.orEmpty() }
            .getOrDefault("")
    }

    /** `loadCodeAssist` 取 project id；字段名以响应为准，做宽容提取。 */
internal fun AntigravityProvider.fetchProjectId(accessToken: String): String {
        val payload = JsonObject().apply {
            add("metadata", JsonObject().apply {
                addProperty("ideType", "IDE_UNSPECIFIED")
                addProperty("platform", "PLATFORM_UNSPECIFIED")
                addProperty("pluginType", "GEMINI")
            })
        }.toString()
        val (status, body) = try {
            postJson("$apiBase/${AntigravityProvider.API_VERSION}:loadCodeAssist", accessToken, payload)
        } catch (_: Exception) {
            return ""
        }
        if (status !in 200..299) return ""
        val obj = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull() ?: return ""
        for (key in listOf("cloudaicompanionProject", "project", "projectId")) {
            obj.get(key)?.let { value ->
                when {
                    value.isJsonPrimitive -> value.asString.takeIf { it.isNotEmpty() }?.let { return it }
                    value.isJsonObject -> value.asJsonObject.get("id")?.asString
                        ?.takeIf { it.isNotEmpty() }?.let { return it }
                }
            }
        }
        return ""
    }

internal data class Tokens(val accessToken: String, val refreshToken: String, val expiresAt: Long)
