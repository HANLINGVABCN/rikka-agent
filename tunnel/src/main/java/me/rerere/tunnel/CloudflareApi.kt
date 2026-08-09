package me.rerere.tunnel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Cloudflare Tunnel API。
 *
 * 只保留"把本机一个端口暴露到一个域名"所需的操作 —— 原 RikkaTunnel 的多隧道管理、
 * 云端对账、失效标记那些都不要了, 因为这里隧道不是主功能而是设置项。
 */
class CloudflareApi(
    private val client: OkHttpClient = defaultClient(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    class ApiException(message: String) : Exception(message)

    data class Account(val id: String, val name: String)

    data class RemoteTunnel(val id: String, val name: String)

    /**
     * 校验令牌并拿到账号。
     *
     * `/user/tokens/verify` 对 API Token 通常只返回令牌 id/status, 并不保证包含 account;
     * `/accounts` 又可能要求令牌额外具备账户读取权限。因此优先从可见 zone 携带的
     * `account` 字段推导账户, 避免把有效的 Tunnel Token 误判成无效。
     */
    suspend fun verifyToken(apiToken: String): Account {
        val verify = request("GET", "/user/tokens/verify", apiToken)
        checkSuccess(verify, "verify token")

        verify["result"]?.jsonObject?.get("account")?.jsonObject?.let { account ->
            val id = account["id"]?.jsonPrimitive?.contentOrNull
            if (!id.isNullOrBlank()) {
                return Account(id, account["name"]?.jsonPrimitive?.contentOrNull.orEmpty())
            }
        }

        // 回退: 从 zone 列表里推账户 —— 见上面的注释
        val zones = request("GET", "/zones?per_page=50", apiToken)
        checkSuccess(zones, "list zones")
        zones["result"]?.jsonArray?.forEach { zone ->
            zone.jsonObject["account"]?.jsonObject?.let { account ->
                val id = account["id"]?.jsonPrimitive?.contentOrNull
                if (!id.isNullOrBlank()) {
                    return Account(id, account["name"]?.jsonPrimitive?.contentOrNull.orEmpty())
                }
            }
        }
        throw ApiException("Cannot determine account from token; check token permissions")
    }

    suspend fun createTunnel(apiToken: String, name: String): RemoteTunnel {
        val accountId = verifyToken(apiToken).id
        val body = buildJsonObject {
            put("name", name)
            put("config_src", "cloudflare")
        }
        val result = request("POST", "/accounts/$accountId/cfd_tunnel", apiToken, body)
        checkSuccess(result, "create tunnel")
        val tunnel = result["result"]?.jsonObject ?: throw ApiException("Empty tunnel response")
        return RemoteTunnel(
            id = tunnel["id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            name = tunnel["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        )
    }

    /** 隧道运行令牌 —— 这就是 `cloudflared tunnel run --token` 要的那个 */
    suspend fun getRunToken(apiToken: String, tunnelId: String): String {
        val accountId = verifyToken(apiToken).id
        val result = request("GET", "/accounts/$accountId/cfd_tunnel/$tunnelId/token", apiToken)
        checkSuccess(result, "get run token")
        return result["result"]?.jsonPrimitive?.contentOrNull
            ?: throw ApiException("Empty run token")
    }

    suspend fun deleteTunnel(apiToken: String, tunnelId: String) {
        val accountId = verifyToken(apiToken).id
        val result = request("DELETE", "/accounts/$accountId/cfd_tunnel/$tunnelId", apiToken)
        checkSuccess(result, "delete tunnel")
    }

    /**
     * 把 hostname 指到本机端口: 写 ingress 配置 + 建 DNS CNAME。
     *
     * 两步都要做 —— 只写 ingress 则域名解析不到, 只建 DNS 则 cloudflared 不知道往哪转发。
     */
    suspend fun route(apiToken: String, tunnelId: String, hostname: String, port: Int) {
        val accountId = verifyToken(apiToken).id
        val config = buildJsonObject {
            putJsonObject("config") {
                putJsonArray("ingress") {
                    add(buildJsonObject {
                        put("hostname", hostname)
                        put("service", "http://localhost:$port")
                    })
                    // ingress 规则表必须以 catch-all 结尾, 否则 Cloudflare 拒绝整份配置
                    add(buildJsonObject { put("service", "http_status:404") })
                }
            }
        }
        val result = request(
            "PUT",
            "/accounts/$accountId/cfd_tunnel/$tunnelId/configurations",
            apiToken,
            config,
        )
        checkSuccess(result, "write ingress")
        upsertDns(apiToken, hostname, tunnelId)
    }

    private suspend fun upsertDns(apiToken: String, hostname: String, tunnelId: String) {
        val zoneId = zoneId(apiToken, hostname)
        val target = "$tunnelId.cfargotunnel.com"

        val existing = request(
            "GET",
            "/zones/$zoneId/dns_records?type=CNAME&name=${encode(hostname)}",
            apiToken,
        )
        checkSuccess(existing, "query DNS")
        val recordId = existing["result"]?.jsonArray?.firstOrNull()
            ?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull

        val body = buildJsonObject {
            put("type", "CNAME")
            put("name", hostname)
            put("content", target)
            put("proxied", true)
        }
        val result = if (recordId != null) {
            request("PUT", "/zones/$zoneId/dns_records/$recordId", apiToken, body)
        } else {
            request("POST", "/zones/$zoneId/dns_records", apiToken, body)
        }
        checkSuccess(result, "upsert DNS")
    }

    /**
     * 找 hostname 所属的 zone。
     *
     * 从最长后缀往短了试(a.b.example.com → b.example.com → example.com), 因为
     * 子域名可能自己就是一个 zone, 直接取末两段会找错。
     */
    private suspend fun zoneId(apiToken: String, hostname: String): String {
        val parts = hostname.split(".")
        for (i in 0 until parts.size - 1) {
            val candidate = parts.drop(i).joinToString(".")
            val result = request("GET", "/zones?name=${encode(candidate)}", apiToken)
            if (result["success"]?.jsonPrimitive?.booleanOrNull != true) continue
            result["result"]?.jsonArray?.firstOrNull()?.jsonObject
                ?.get("id")?.jsonPrimitive?.contentOrNull
                ?.let { return it }
        }
        throw ApiException("No Cloudflare zone found for $hostname")
    }

    private suspend fun request(
        method: String,
        path: String,
        apiToken: String,
        body: JsonObject? = null,
    ): JsonObject = withContext(Dispatchers.IO) {
        val requestBody = body?.toString()?.toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url(BASE_URL + path)
            .header("Authorization", "Bearer $apiToken")
            .header("Content-Type", "application/json")
            .method(method, requestBody)
            .build()

        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty().ifBlank { "{}" }
            runCatching { json.parseToJsonElement(text).jsonObject }
                .getOrElse { throw ApiException("Invalid response from Cloudflare: HTTP ${response.code}") }
        }
    }

    /** Cloudflare 把错误藏在 errors 数组里, 抽出来拼成人话, 否则界面上只能显示一坨 JSON */
    private fun checkSuccess(obj: JsonObject, what: String) {
        if (obj["success"]?.jsonPrimitive?.booleanOrNull == true) return
        val message = obj["errors"]?.jsonArray
            ?.mapNotNull { it.jsonObject["message"]?.jsonPrimitive?.contentOrNull }
            ?.joinToString("; ")
            ?.takeIf { it.isNotBlank() }
            ?: "unknown error"
        throw ApiException("$what failed: $message")
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private companion object {
        private const val BASE_URL = "https://api.cloudflare.com/client/v4"
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()

        fun defaultClient() = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }
}
