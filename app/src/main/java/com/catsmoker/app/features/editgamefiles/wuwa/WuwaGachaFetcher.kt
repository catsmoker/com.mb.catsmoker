package com.catsmoker.app.features.editgamefiles.wuwa

import com.google.gson.Gson
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * The Convene record API's network half: one POST per pool, over [Dispatchers.IO] by the
 * caller. The pure half lives in [WuwaGacha] (URL parsing, response decoding, pool
 * aggregation, pity math) so it stays JVM-testable; this object only moves bytes.
 *
 * Ported from `referance/gamingtools/WuWa-Config-Android-main/config/GachaApi.kt`'s
 * `postRequest`/`getEndpoint` (read in full before this file was written): the 15 s
 * connect/read timeouts, `Content-Type: application/json`, the per-pool body keys
 * (`playerId`/`recordId`/`cardPoolId`/`cardPoolType`/`serverId`/`languageCode` — note
 * `cardPoolId` is the URL's `resources_id` and stays fixed across pools while
 * `cardPoolType` walks [WuwaGacha.POOLS]), a non-200 turned into a plain "HTTP nnn"
 * failure with the error stream drained, and the endpoint split on the player id's first
 * digit. Kept out of the pure object deliberately: it exists only to keep `WuwaGacha`
 * free of anything a JVM test cannot run.
 */
object WuwaGachaFetcher {

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 15_000

    private val gson = Gson()

    /**
     * Queries every pool in turn and hands the per-pool results to [WuwaGacha.combinePoolResults],
     * which owns the precedence rules (transport failure vs all-pools-rejected vs success).
     * One pool failing its POST does not stop the walk — the reference's loop `continue`s and
     * remembers the failure, and so does this.
     */
    suspend fun fetchAllRecords(params: WuwaGacha.GachaUrlParams): Result<WuwaGacha.GachaData> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val endpoint = WuwaGacha.endpointFor(params.playerId)
            val results = mutableListOf<Pair<WuwaGacha.GachaPool, Result<WuwaGacha.ApiResponse>>>()
            for (pool in WuwaGacha.POOLS) {
                val body = mapOf(
                    "playerId" to params.playerId,
                    "recordId" to params.recordId,
                    "cardPoolId" to params.cardPoolId,
                    "cardPoolType" to pool.type,
                    "serverId" to params.serverId,
                    "languageCode" to params.languageCode,
                )
                results.add(pool to postRequest(endpoint, body))
            }
            WuwaGacha.combinePoolResults(results)
        }

    private fun postRequest(endpoint: String, body: Map<String, String>): Result<WuwaGacha.ApiResponse> {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(endpoint).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS

            val jsonBody = gson.toJson(body)
            OutputStreamWriter(conn.outputStream).use { it.write(jsonBody) }

            val responseCode = conn.responseCode
            if (responseCode != 200) {
                conn.errorStream?.bufferedReader()?.use { it.readText() }
                return Result.failure(Exception("HTTP $responseCode"))
            }

            val responseText = conn.inputStream.bufferedReader().use { it.readText() }
            Result.success(WuwaGacha.parseResponse(responseText))
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            conn?.disconnect()
        }
    }
}
