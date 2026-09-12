package com.catsmoker.app.features.editgamefiles.wuwa

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * The Convene (gacha) record pipeline's pure half: record-URL parsing, the pool table,
 * response decoding, and the pity math. No network, no Android — everything here is
 * JVM-testable, and [WuwaGachaTest] pins it.
 *
 * Ported from `referance/gamingtools/WuWa-Config-Android-main/config/GachaApi.kt` and
 * `model/GachaRecord.kt`, both read in full before this file was written. The agreements:
 *
 *  - [parseUrl] reads the `#/record?` **fragment** — the record parameters never appear in
 *    the query string — and refuses the URL when any of `player_id` / `record_id` /
 *    `resources_id` / `gacha_type` / `svr_id` is missing; only `lang` is optional (default
 *    "en"). A URL with no fragment at all fails the same way, because the key lookup comes
 *    up empty — exactly the reference's behaviour, including that accident.
 *  - the eleven-pool table is the reference's `GachaPool.ALL` verbatim. Standard rosters are
 *    pools 3/8/11 and character events are 1/7/10 — the two sets the pity status reads: the
 *    union of ★5 names pulled from the standard pools decides whether a character-event
 *    account is "50/50" or "Guaranteed".
 *  - pity constants and estimates are the reference's own: character hard 80 / soft 66,
 *    weapon hard 70 / soft 57 with the "75/25" status; soft-pity estimate
 *    max(SOFT−p+4, 1)+6 (character) or +4 (weapon); not in soft pity the estimate falls back
 *    to the pool's own measured ★5 average once two or more featured ★5s exist, else the
 *    hard cap. `pullsSinceLastFourStar` counts records after the last ★4 *or* ★5, capped at
 *    10 only when none exists.
 *  - "every pool answered but rejected the query" (bad/expired recordId, wrong playerId) is
 *    a failure carrying the server's message — it must not masquerade as an empty history.
 *    Same rule, same comment, as the reference's `fetchAllRecords`.
 *
 * Deliberately not ported: the reference's retry loop around Client.log reading (it polls
 * 6×10 s while guiding the user to open Convene History in-game — this app's log read is
 * one shot and definitive, so the failure text carries the guidance instead) and the
 * background GACHA_DATA_READY broadcast poller (fetching here is on demand). The HTTP half
 * lives in [WuwaGachaFetcher]; the 12-hour cache in [WuwaGachaHistoryStore].
 */
object WuwaGacha {

    data class GachaUrlParams(
        val playerId: String,
        val recordId: String,
        val cardPoolId: String,
        val cardPoolType: String,
        val serverId: String,
        val languageCode: String,
    )

    data class GachaRecord(
        val cardPoolType: String,
        val qualityLevel: Int,
        val name: String,
        val count: Int,
        val time: String,
    )

    data class GachaPool(val type: String, val label: String)

    /** The reference's `GachaPool.ALL`, verbatim — every pool is queried, not just the URL's own. */
    val POOLS = listOf(
        GachaPool("1", "Character Event"),
        GachaPool("2", "Weapon Event"),
        GachaPool("3", "Standard"),
        GachaPool("4", "Beginner 1"),
        GachaPool("5", "Beginner 2"),
        GachaPool("6", "Weapon 2"),
        GachaPool("7", "Character 2"),
        GachaPool("8", "Standard 2"),
        GachaPool("9", "Weapon 3"),
        GachaPool("10", "Character 3"),
        GachaPool("11", "Standard 3"),
    )

    /** Permanent "Standard" banners: any ★5 pulled there is a standard ★5. */
    private val STANDARD_POOLS = setOf("3", "8", "11")

    /** Character event banners — the pools that get the 50/50-vs-Guaranteed prediction. */
    private val CHARACTER_POOLS = setOf("1", "7", "10")

    data class ApiResponse(
        val code: Int = -1,
        val message: String = "",
        val data: List<GachaRecord>? = null,
    )

    data class GachaData(
        val records: List<GachaRecord> = emptyList(),
        val poolsWithData: List<String> = emptyList(),
        val totalPulls: Int = 0,
        val fiveStars: Int = 0,
        val fourStars: Int = 0,
        val avgPity5: Double = 0.0,
        val avgPity4: Double = 0.0,
        val predictions: List<PityPrediction> = emptyList(),
    )

    data class PityPrediction(
        val poolType: String,
        val poolLabel: String,
        val status: String,
        val lastFiveStarName: String,
        val lastFiveStarTime: String,
        val currentCharacterName: String = "",
        val pullsSinceLastFive: Int,
        val estimatedNextFive: Int,
        val hardPity: Int = 80,
        val softPityThreshold: Int = 66,
        val isInSoftPity: Boolean = false,
        val pullsUntilHardPity: Int = 80,
        val pullsSinceLastFourStar: Int = 0,
        val estimatedNextFourStar: Int = 10,
    )

    /**
     * Parses the Convene History page's `index.html#/record?...` link. Returns null when a
     * required parameter is missing — never a partially-filled params object.
     */
    fun parseUrl(url: String): GachaUrlParams? {
        val fragment = url.substringAfter("#/record?")
        val params = fragment.split("&").associate {
            val parts = it.split("=", limit = 2)
            parts[0] to (parts.getOrNull(1) ?: "")
        }
        val playerId = params["player_id"] ?: return null
        val recordId = params["record_id"] ?: return null
        val cardPoolId = params["resources_id"] ?: return null
        val cardPoolType = params["gacha_type"] ?: return null
        val serverId = params["svr_id"] ?: return null
        val languageCode = params["lang"] ?: "en"
        return GachaUrlParams(playerId, recordId, cardPoolId, cardPoolType, serverId, languageCode)
    }

    /**
     * The record-page URL the game writes into its own Client.log once the Convene History
     * page has been opened — the same extraction the reference's `LogParser` runs over the
     * decrypted text, regex verbatim (`[^"\s]*` stops at the log line's quote or whitespace).
     */
    private val CONVENE_URL_REGEX = Regex(
        """https://aki-gm-resources(-oversea)?\.aki-game\.(net|com)/aki/gacha/index\.html#/record[^"\s]*""",
        RegexOption.IGNORE_CASE,
    )

    fun extractConveneUrl(text: String): String? = CONVENE_URL_REGEX.find(text)?.value

    /** Oversea accounts (player id starting "1") query the `.com` server, the rest `.net`. */
    fun endpointFor(playerId: String): String =
        if (playerId.startsWith("1")) {
            "https://gmserver-api.aki-game2.com/gacha/record/query"
        } else {
            "https://gmserver-api.aki-game2.net/gacha/record/query"
        }

    /**
     * Decodes one pool's POST response body. Gson delivers JSON numbers as Doubles here, so
     * `code` comes through `(as? Double)?.toInt()`; a record without `cardPoolType` is
     * dropped entirely (the reference's `mapNotNull`), and a missing `count` defaults to 1.
     */
    fun parseResponse(responseText: String): ApiResponse {
        val mapType = object : TypeToken<Map<String, Any?>>() {}.type
        val map: Map<String, Any?> = gson.fromJson(responseText, mapType)
        val code = (map["code"] as? Double)?.toInt() ?: -1
        val message = map["message"] as? String ?: ""
        val dataRaw = map["data"] as? List<Map<String, Any?>> ?: emptyList()
        val records = dataRaw.mapNotNull { item ->
            try {
                GachaRecord(
                    cardPoolType = (item["cardPoolType"] as? String) ?: return@mapNotNull null,
                    qualityLevel = (item["qualityLevel"] as? Number)?.toInt() ?: 0,
                    name = item["name"] as? String ?: "",
                    count = (item["count"] as? Number)?.toInt() ?: 1,
                    time = item["time"] as? String ?: "",
                )
            } catch (_: Exception) {
                null
            }
        }
        return ApiResponse(code = code, message = message, data = records)
    }

    private val gson = Gson()

    /**
     * Combines the per-pool answers into one [GachaData], with the reference's exact
     * precedence: a transport failure anywhere wins if nothing succeeded; otherwise every
     * pool having rejected the query is a failure carrying the last server message; only a
     * real answer aggregates.
     */
    fun combinePoolResults(results: List<Pair<GachaPool, Result<ApiResponse>>>): Result<GachaData> {
        val records = mutableListOf<GachaRecord>()
        val poolsWithData = mutableListOf<String>()
        var anyFailure: Throwable? = null
        var anySuccess = false
        var lastErrorMsg: String? = null
        for ((pool, result) in results) {
            val response = result.getOrNull()
            if (response == null) {
                anyFailure = result.exceptionOrNull()
                continue
            }
            if (response.code == 0 && !response.data.isNullOrEmpty()) {
                records.addAll(response.data)
                poolsWithData.add(pool.type)
                anySuccess = true
            } else if (response.code != 0) {
                lastErrorMsg = response.message
            }
        }

        if (!anySuccess && anyFailure != null) {
            return Result.failure(anyFailure)
        }
        // Every pool answered but rejected the query (bad/expired recordId, wrong
        // playerId...) — that must not masquerade as an empty history.
        if (!anySuccess) {
            return Result.failure(
                Exception(lastErrorMsg ?: "Server returned no gacha data for any pool"),
            )
        }
        return Result.success(aggregate(records, poolsWithData))
    }

    /**
     * Totals, average pity over the merged records, and one prediction per pool with data.
     * Pool records are ordered by the record `time` string — the reference sorts these
     * lexicographically and so does this, because that is the format the server writes.
     */
    fun aggregate(records: List<GachaRecord>, poolsWithData: List<String>): GachaData {
        val pity5 = calculateAvgPity(records, 5)
        val pity4 = calculateAvgPity(records, 4)

        val predictions = mutableListOf<PityPrediction>()
        val standardFiveStars = records
            .filter { it.cardPoolType in STANDARD_POOLS && it.qualityLevel == 5 }
            .map { it.name }
            .toSet()
        for (pool in POOLS) {
            val poolRecords = records.filter { it.cardPoolType == pool.type }
            if (poolRecords.isEmpty()) continue

            val pred = when (pool.type) {
                in CHARACTER_POOLS -> calcCharacterPrediction(poolRecords, pool, standardFiveStars)
                "2" -> calcWeaponPrediction(poolRecords, pool)
                else -> null
            }
            if (pred != null) predictions.add(pred)
        }

        return GachaData(
            records = records.sortedByDescending { it.time },
            poolsWithData = poolsWithData,
            totalPulls = records.size,
            fiveStars = records.count { it.qualityLevel == 5 },
            fourStars = records.count { it.qualityLevel == 4 },
            avgPity5 = pity5,
            avgPity4 = pity4,
            predictions = predictions,
        )
    }

    /** Average gap between pulls of [rarity], over the merged records (the reference's `calculateAvgPity`). */
    private fun calculateAvgPity(records: List<GachaRecord>, rarity: Int): Double {
        if (records.none { it.qualityLevel == rarity }) return 0.0
        val sorted = records.sortedBy { it.time }
        val groups = mutableListOf<Int>()
        var count = 0
        for (rec in sorted) {
            count++
            if (rec.qualityLevel == rarity) {
                groups.add(count)
                count = 0
            }
        }
        if (groups.isEmpty()) return 0.0
        return groups.average()
    }

    /** Records after the last ★4-or-★5; capped at 10 only when the pool holds neither. */
    private fun calcPullsSinceLastFourStar(sorted: List<GachaRecord>): Int {
        val lastFourIndex = sorted.indexOfLast { it.qualityLevel == 4 || it.qualityLevel == 5 }
        if (lastFourIndex < 0) return sorted.size.coerceAtMost(10)
        return sorted.size - lastFourIndex - 1
    }

    private fun calcCharacterPrediction(
        records: List<GachaRecord>,
        pool: GachaPool,
        standardFiveStars: Set<String>,
    ): PityPrediction {
        val HARD_PITY = 80
        val SOFT_PITY_START = 66
        val sorted = records.sortedBy { it.time }
        val fiveStarRecords = sorted.filter { it.qualityLevel == 5 }

        val pullsSinceLastFive: Int
        val lastFiveName: String
        val lastFiveTime: String
        val isLastFiveStandard: Boolean

        if (fiveStarRecords.isNotEmpty()) {
            val lastFive = fiveStarRecords.last()
            lastFiveName = lastFive.name
            lastFiveTime = lastFive.time
            isLastFiveStandard = lastFive.name in standardFiveStars
            val lastFiveIndex = sorted.indexOfLast { it.qualityLevel == 5 }
            pullsSinceLastFive = sorted.size - lastFiveIndex - 1
        } else {
            lastFiveName = ""
            lastFiveTime = ""
            isLastFiveStandard = false
            pullsSinceLastFive = sorted.size
        }

        val status =
            when {
                fiveStarRecords.isEmpty() -> "Unknown"
                isLastFiveStandard -> "Guaranteed"
                else -> "50/50"
            }

        // The featured (rate-up) character of the current/last banner: the most recent
        // non-standard ★5 pulled. Falls back to "" (the UI then uses the pool label).
        val currentCharacterName =
            fiveStarRecords
                .filter { it.name !in standardFiveStars }
                .lastOrNull()
                ?.name ?: ""

        val nearbyFives = fiveStarRecords.filter { it.name !in standardFiveStars }.toSet()
        val avgCharPity =
            if (nearbyFives.size >= 2) {
                val pityGroups = mutableListOf<Int>()
                var cnt = 0
                for (rec in sorted) {
                    cnt++
                    if (rec.qualityLevel == 5 && rec in nearbyFives) {
                        pityGroups.add(cnt)
                        cnt = 0
                    }
                }
                if (pityGroups.isNotEmpty()) pityGroups.average().toInt() else HARD_PITY
            } else {
                HARD_PITY
            }

        val isInSoftPity = pullsSinceLastFive >= SOFT_PITY_START
        val pullsUntilHardPity = maxOf(HARD_PITY - pullsSinceLastFive, 0)
        val estimated =
            if (isInSoftPity) {
                maxOf(SOFT_PITY_START - pullsSinceLastFive + 4, 1) + 6
            } else {
                maxOf(avgCharPity - pullsSinceLastFive, 1)
            }

        val pulls4 = calcPullsSinceLastFourStar(sorted)

        return PityPrediction(
            poolType = pool.type,
            poolLabel = pool.label,
            status = status,
            lastFiveStarName = lastFiveName,
            lastFiveStarTime = lastFiveTime,
            currentCharacterName = currentCharacterName,
            pullsSinceLastFive = pullsSinceLastFive,
            estimatedNextFive = estimated,
            hardPity = HARD_PITY,
            softPityThreshold = SOFT_PITY_START,
            isInSoftPity = isInSoftPity,
            pullsUntilHardPity = pullsUntilHardPity,
            pullsSinceLastFourStar = pulls4,
            estimatedNextFourStar = maxOf(10 - pulls4, 1),
        )
    }

    private fun calcWeaponPrediction(
        records: List<GachaRecord>,
        pool: GachaPool,
    ): PityPrediction {
        val HARD_PITY = 70
        val SOFT_PITY_START = 57
        val sorted = records.sortedBy { it.time }
        val fiveStarRecords = sorted.filter { it.qualityLevel == 5 }

        val pullsSinceLastFive: Int
        val lastFiveName: String
        val lastFiveTime: String

        if (fiveStarRecords.isNotEmpty()) {
            val lastFive = fiveStarRecords.last()
            lastFiveName = lastFive.name
            lastFiveTime = lastFive.time
            val lastFiveIndex = sorted.indexOfLast { it.qualityLevel == 5 }
            pullsSinceLastFive = sorted.size - lastFiveIndex - 1
        } else {
            lastFiveName = ""
            lastFiveTime = ""
            pullsSinceLastFive = sorted.size
        }

        val isInSoftPity = pullsSinceLastFive >= SOFT_PITY_START
        val pullsUntilHardPity = maxOf(HARD_PITY - pullsSinceLastFive, 0)
        val estimated =
            if (isInSoftPity) {
                maxOf(SOFT_PITY_START - pullsSinceLastFive + 4, 1) + 4
            } else {
                maxOf(65 - pullsSinceLastFive, 1)
            }

        val pulls4 = calcPullsSinceLastFourStar(sorted)

        return PityPrediction(
            poolType = pool.type,
            poolLabel = pool.label,
            status = "75/25",
            lastFiveStarName = lastFiveName,
            lastFiveStarTime = lastFiveTime,
            pullsSinceLastFive = pullsSinceLastFive,
            estimatedNextFive = estimated,
            hardPity = HARD_PITY,
            softPityThreshold = SOFT_PITY_START,
            isInSoftPity = isInSoftPity,
            pullsUntilHardPity = pullsUntilHardPity,
            pullsSinceLastFourStar = pulls4,
            estimatedNextFourStar = maxOf(10 - pulls4, 1),
        )
    }
}
