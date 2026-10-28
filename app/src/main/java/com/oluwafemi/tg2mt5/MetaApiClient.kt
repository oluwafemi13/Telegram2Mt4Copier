package com.oluwafemi.tg2mt5

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class MetaApiException(message: String) : Exception(message)

data class AccountInfo(val balance: Double, val equity: Double, val currency: String, val broker: String, val login: String, val tradeAllowed: Boolean)
data class SymbolSpec(val tickSize: Double, val minVolume: Double, val maxVolume: Double, val volumeStep: Double, val contractSize: Double, val digits: Int)
data class SymbolPrice(val bid: Double, val ask: Double, val lossTickValue: Double)
data class TradeResult(val ok: Boolean, val code: String, val message: String, val orderId: String?, val positionId: String?)

/** Talks to the MetaApi cloud REST API, which connects to your MT5 account. */
class MetaApiClient(private val token: String, private val accountId: String, region: String) {
    private val base = "https://mt-client-api-v1.$region.agiliumtrade.ai/users/current/accounts/${enc(accountId)}"
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun accountInfo(): AccountInfo {
        val o = get("/account-information")
        return AccountInfo(
            o.optDouble("balance"), o.optDouble("equity"), o.optString("currency"),
            o.optString("broker"), o.opt("login")?.toString() ?: "", o.optBoolean("tradeAllowed", true),
        )
    }

    suspend fun spec(symbol: String): SymbolSpec {
        val o = get("/symbols/${enc(symbol)}/specification")
        return SymbolSpec(
            o.optDouble("tickSize", 0.0), o.optDouble("minVolume", 0.01), o.optDouble("maxVolume", 0.0),
            o.optDouble("volumeStep", 0.01), o.optDouble("contractSize", 0.0), o.optInt("digits", 5),
        )
    }

    suspend fun price(symbol: String): SymbolPrice {
        val o = get("/symbols/${enc(symbol)}/current-price?keepSubscription=true")
        return SymbolPrice(o.optDouble("bid"), o.optDouble("ask"), o.optDouble("lossTickValue", 0.0))
    }

    suspend fun trade(body: JSONObject): TradeResult {
        val o = request(Request.Builder().url("$base/trade").post(body.toString().toRequestBody(JSON)))
        val code = o.optString("stringCode")
        val ok = code == "TRADE_RETCODE_DONE" || code == "TRADE_RETCODE_PLACED" || code == "TRADE_RETCODE_DONE_PARTIAL"
        return TradeResult(ok, code, o.optString("message"), o.optString("orderId").ifEmpty { null }, o.optString("positionId").ifEmpty { null })
    }

    /** All deals (open, close, partial closes) that belong to one position. */
    suspend fun dealsByPosition(positionId: String): List<Deal> {
        val text = requestText(Request.Builder().url("$base/history-deals/position/${enc(positionId)}").get())
        val trimmed = text.trim()
        val arr = when {
            trimmed.startsWith("[") -> JSONArray(trimmed)
            trimmed.startsWith("{") -> JSONObject(trimmed).let { o -> o.optJSONArray("deals") ?: JSONArray().put(o) }
            else -> JSONArray()
        }
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Deal(
                o.optString("entryType"), o.optDouble("price", 0.0), o.optDouble("profit", 0.0),
                o.optDouble("swap", 0.0), o.optDouble("commission", 0.0), o.optString("reason"),
                o.optString("brokerComment").ifEmpty { o.optString("comment") },
            )
        }
    }

    private suspend fun get(path: String) = request(Request.Builder().url(base + path).get())

    private suspend fun requestText(builder: Request.Builder): String = withContext(Dispatchers.IO) {
        val req = builder.header("auth-token", token).header("Accept", "application/json").build()
        try {
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw MetaApiException("HTTP ${resp.code}: ${text.take(200)}")
                text
            }
        } catch (e: MetaApiException) {
            throw e
        } catch (e: Exception) {
            throw MetaApiException("Network error: ${e.message}")
        }
    }

    private suspend fun request(builder: Request.Builder): JSONObject = withContext(Dispatchers.IO) {
        val req = builder.header("auth-token", token).header("Accept", "application/json").build()
        try {
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val msg = try { JSONObject(text).optString("message").ifEmpty { text } } catch (e: Exception) { text }
                    val hint = when (resp.code) {
                        401 -> " (check your MetaApi token)"
                        404 -> " (check the account ID, region, symbol name, and that the account is deployed in MetaApi)"
                        else -> ""
                    }
                    throw MetaApiException("HTTP ${resp.code}: ${msg.take(300)}$hint")
                }
                if (text.isBlank()) JSONObject() else JSONObject(text)
            }
        } catch (e: MetaApiException) {
            throw e
        } catch (e: Exception) {
            throw MetaApiException("Network error: ${e.message}")
        }
    }

    companion object {
        private val JSON = "application/json".toMediaType()
        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    }
}
