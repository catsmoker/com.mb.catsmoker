package com.catsmoker.app.features.editgamefiles.wuwa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the Convene (gacha) pipeline's pure half against
 * `referance/gamingtools/WuWa-Config-Android-main/config/GachaApi.kt` and
 * `model/GachaRecord.kt` (both read in full before the port): the `#/record?` fragment rule
 * with the reference's own ten parseUrl cases, the verbatim pool table, response decoding
 * (Gson's Double numbers included), the all-pools-rejected-must-not-masquerade-empty rule,
 * and the character (80/66) and weapon (70/57) pity math.
 */
class WuwaGachaTest {

    // --------------------------------------------------------------- parseUrl

    @Test
    fun parseUrlExtractsAllParametersFromAStandardUrl() {
        val url = "https://aki-gm-resources-oversea.aki-game.net/aki/gacha/index.html" +
            "#/record?player_id=1234567890&record_id=abcdef1234567890&resources_id=1&gacha_type=1&svr_id=1&lang=en"
        val params = WuwaGacha.parseUrl(url)
        assertEquals("1234567890", params?.playerId)
        assertEquals("abcdef1234567890", params?.recordId)
        assertEquals("1", params?.cardPoolId)
        assertEquals("1", params?.cardPoolType)
        assertEquals("1", params?.serverId)
        assertEquals("en", params?.languageCode)
    }

    @Test
    fun parseUrlReadsTheComHostToo() {
        val url = "https://aki-gm-resources.aki-game.com/aki/gacha/index.html" +
            "#/record?player_id=9876543210&record_id=fedcba0987654321&resources_id=7&gacha_type=2&svr_id=2&lang=zh"
        val params = WuwaGacha.parseUrl(url)
        assertEquals("9876543210", params?.playerId)
        assertEquals("zh", params?.languageCode)
    }

    @Test
    fun languageDefaultsToEnWhenMissing() {
        val url = "https://aki-gm-resources.aki-game.com/aki/gacha/index.html" +
            "#/record?player_id=123&record_id=abc&resources_id=1&gacha_type=1&svr_id=1"
        assertEquals("en", WuwaGacha.parseUrl(url)?.languageCode)
    }

    @Test
    fun aMissingRequiredParameterRefusesTheUrl() {
        val base = "https://aki-gm-resources.aki-game.com/aki/gacha/index.html#/record?"
        assertNull(WuwaGacha.parseUrl(base + "record_id=abc&resources_id=1&gacha_type=1&svr_id=1"))
        assertNull(WuwaGacha.parseUrl(base + "player_id=123&resources_id=1&gacha_type=1&svr_id=1"))
        assertNull(WuwaGacha.parseUrl(base + "player_id=123&record_id=abc&gacha_type=1&svr_id=1"))
        assertNull(WuwaGacha.parseUrl(base + "player_id=123&record_id=abc&resources_id=1&svr_id=1"))
        assertNull(WuwaGacha.parseUrl(base + "player_id=123&record_id=abc&resources_id=1&gacha_type=1"))
    }

    @Test
    fun extraParametersAreIgnored() {
        val url = "https://aki-gm-resources-oversea.aki-game.net/aki/gacha/index.html" +
            "#/record?player_id=123&record_id=abc&resources_id=1&gacha_type=1&svr_id=1&lang=en&extra=ignored&foo=bar"
        val params = WuwaGacha.parseUrl(url)
        assertEquals("123", params?.playerId)
        assertEquals("en", params?.languageCode)
    }

    @Test
    fun aUrlWithoutTheRecordFragmentIsRefused() {
        assertNull(WuwaGacha.parseUrl("https://aki-gm-resources.aki-game.com/aki/gacha/index.html"))
    }

    // --------------------------------------------------------------- URL extraction from the log

    @Test
    fun conveneUrlIsExtractedFromALogLine() {
        val line = "[GameThread]LogLaunchUrl: https://aki-gm-resources-oversea.aki-game.net/aki/gacha/index.html" +
            "#/record?player_id=42&record_id=r1&resources_id=1&gacha_type=1&svr_id=1&lang=en \""
        val extracted = WuwaGacha.extractConveneUrl(line)
        assertTrue(extracted!!.endsWith("lang=en"))
        assertEquals("42", WuwaGacha.parseUrl(extracted)?.playerId)
    }

    @Test
    fun aLineWithoutTheRecordPageYieldsNoUrl() {
        assertNull(WuwaGacha.extractConveneUrl("LogInit: normal boot text"))
    }

    // --------------------------------------------------------------- endpoint + response decoding

    @Test
    fun overseaPlayerIdsQueryTheComServer() {
        assertEquals(
            "https://gmserver-api.aki-game2.com/gacha/record/query",
            WuwaGacha.endpointFor("100000001")
        )
        assertEquals(
            "https://gmserver-api.aki-game2.net/gacha/record/query",
            WuwaGacha.endpointFor("900000001")
        )
    }

    @Test
    fun responseBodyDecodesCodeMessageAndRecords() {
        val body = """
            {"code":0,"message":"success","data":[
              {"cardPoolType":"1","qualityLevel":5,"name":"Char A","count":1,"time":"2026-08-01 10:00:00"},
              {"cardPoolType":"1","qualityLevel":4,"name":"Weapon B","count":1,"time":"2026-08-01 10:01:00"}
            ]}
        """.trimIndent()
        val response = WuwaGacha.parseResponse(body)
        assertEquals(0, response.code)
        assertEquals("success", response.message)
        assertEquals(2, response.data?.size)
        assertEquals("Char A", response.data?.first()?.name)
        assertEquals(5, response.data?.first()?.qualityLevel)
    }

    @Test
    fun aRecordWithoutCardPoolTypeIsDroppedAndMissingCountDefaultsToOne() {
        val body = """
            {"code":0,"message":"ok","data":[
              {"qualityLevel":3,"name":"??","count":1,"time":"t"},
              {"cardPoolType":"3","qualityLevel":4,"name":"ok","time":"t"}
            ]}
        """.trimIndent()
        val response = WuwaGacha.parseResponse(body)
        assertEquals(1, response.data?.size)
        assertEquals(1, response.data?.first()?.count)
    }

    @Test
    fun aRejectionCarriesTheServerMessageAndEmptyData() {
        val response = WuwaGacha.parseResponse("""{"code":-1,"message":"expired","data":null}""")
        assertEquals(-1, response.code)
        assertEquals("expired", response.message)
        // The reference's postRequest always hands back a list (empty when the body holds
        // data:null) — the isNullOrEmpty() check at the call site covers both shapes.
        assertTrue(response.data.isNullOrEmpty())
    }

    // --------------------------------------------------------------- aggregation + the masquerade rule

    private fun rec(
        pool: String,
        rarity: Int,
        name: String = "item",
        time: String,
    ) = WuwaGacha.GachaRecord(pool, rarity, name, 1, time)

    private val charPool = WuwaGacha.GachaPool("1", "Character Event")

    @Test
    fun transportFailureEverywhereIsAFailureNotAnEmptyHistory() {
        val results = listOf(
            charPool to Result.failure<WuwaGacha.ApiResponse>(Exception("timeout")),
            WuwaGacha.GachaPool("2", "Weapon Event") to Result.failure<WuwaGacha.ApiResponse>(Exception("timeout")),
        )
        val result = WuwaGacha.combinePoolResults(results)
        assertEquals("timeout", result.exceptionOrNull()?.message)
    }

    @Test
    fun everyPoolAnsweringButRejectingIsAFailureCarryingTheServerMessage() {
        // (bad/expired recordId, wrong playerId...) — that must not masquerade as an empty history.
        val reject = Result.success(WuwaGacha.ApiResponse(code = -1, message = "invalid record", data = null))
        val results = listOf(
            charPool to reject,
            WuwaGacha.GachaPool("2", "Weapon Event") to reject,
        )
        val result = WuwaGacha.combinePoolResults(results)
        assertEquals("invalid record", result.exceptionOrNull()?.message)
    }

    @Test
    fun onePoolSucceedingIsEnoughAndPoolsAreReported() {
        val ok = Result.success(
            WuwaGacha.ApiResponse(
                code = 0,
                message = "ok",
                data = listOf(rec("1", 5, "Char A", "2026-08-01 10:00:00"))
            )
        )
        val reject = Result.success(WuwaGacha.ApiResponse(code = -1, message = "no data", data = null))
        val result = WuwaGacha.combinePoolResults(listOf(charPool to ok, WuwaGacha.GachaPool("2", "Weapon Event") to reject))
        val data = result.getOrThrow()
        assertEquals(listOf("1"), data.poolsWithData)
        assertEquals(1, data.totalPulls)
        assertEquals(1, data.fiveStars)
    }

    @Test
    fun recordsAreSortedNewestFirstAndCountsSum() {
        val records = listOf(
            rec("1", 4, "w1", "2026-08-01 10:00:00"),
            rec("1", 5, "Char A", "2026-08-03 10:00:00"),
            rec("1", 3, "junk", "2026-08-02 10:00:00"),
        )
        val data = WuwaGacha.aggregate(records, listOf("1"))
        assertEquals("2026-08-03 10:00:00", data.records.first().time)
        assertEquals(3, data.totalPulls)
        assertEquals(1, data.fiveStars)
        assertEquals(1, data.fourStars)
    }

    // --------------------------------------------------------------- pity math

    @Test
    fun avgPityIsZeroWhenARarityNeverAppeared() {
        val data = WuwaGacha.aggregate(listOf(rec("1", 3, "junk", "2026-08-01 10:00:00")), listOf("1"))
        assertEquals(0.0, data.avgPity5, 0.0)
        assertEquals(0.0, data.avgPity4, 0.0)
    }

    @Test
    fun avgPityIsTheMeanGapBetweenRarities() {
        // times ascending: pulls at t1..t8, a ★5 at positions 3 and 5 → gaps 3 and 2 → avg 2.5.
        var t = 0
        fun tick(): String = "2026-08-01 10:00:%02d".format(t++)
        val records = listOf(
            rec("1", 3, time = tick()),
            rec("1", 3, time = tick()),
            rec("1", 5, "Char A", time = tick()),
            rec("1", 3, time = tick()),
            rec("1", 5, "Char B", time = tick()),
            rec("1", 3, time = tick()),
            rec("1", 3, time = tick()),
            rec("1", 3, time = tick()),
        )
        val data = WuwaGacha.aggregate(records, listOf("1"))
        assertEquals(2.5, data.avgPity5, 0.0)
    }

    @Test
    fun characterPredictionWithoutAFiveStarIsUnknownAndCountsAllPulls() {
        val records = listOf(
            rec("1", 3, time = "2026-08-01 10:00:00"),
            rec("1", 4, time = "2026-08-02 10:00:00"),
        )
        val pred = WuwaGacha.aggregate(records, listOf("1")).predictions.single()
        assertEquals("Unknown", pred.status)
        assertEquals(2, pred.pullsSinceLastFive)
        assertEquals(80, pred.hardPity)
        assertEquals(78, pred.estimatedNextFive) // avgCharPity fallback = hard 80 − 2
        assertFalse(pred.isInSoftPity)
    }

    @Test
    fun lastFiveBeingStandardMeansGuaranteed() {
        val records = listOf(
            rec("1", 5, "Standard Char", time = "2026-08-01 10:00:00"),
            rec("3", 5, "Standard Char", time = "2026-08-01 09:00:00"), // the standard pool proves the name is standard
            rec("1", 3, time = "2026-08-02 10:00:00"),
            rec("1", 3, time = "2026-08-03 10:00:00"),
        )
        val data = WuwaGacha.aggregate(records, listOf("1", "3"))
        val charPred = data.predictions.first { it.poolType == "1" }
        assertEquals("Guaranteed", charPred.status)
        assertEquals("Standard Char", charPred.lastFiveStarName)
        assertEquals(2, charPred.pullsSinceLastFive)
    }

    @Test
    fun lastFiveNotInStandardMeansFiftyFifty() {
        val records = listOf(
            rec("1", 5, "Featured Char", time = "2026-08-01 10:00:00"),
            rec("1", 3, time = "2026-08-02 10:00:00"),
        )
        val pred = WuwaGacha.aggregate(records, listOf("1")).predictions.single()
        assertEquals("50/50", pred.status)
        assertEquals("Featured Char", pred.currentCharacterName)
    }

    @Test
    fun softPityStartsAtSixtySixAndEstimateShiftsForward() {
        // 68 pulls since the last ★5 → in soft pity; estimate = max(66−68+4,1)+6 = 8.
        var t = 0
        fun tick(): String = "2026-08-01 10:00:%02d".format(t++)
        val records = mutableListOf(rec("1", 5, "Featured Char", time = tick()))
        repeat(68) { records.add(rec("1", 3, time = tick())) }
        val pred = WuwaGacha.aggregate(records, listOf("1")).predictions.single()
        assertEquals(68, pred.pullsSinceLastFive)
        assertTrue(pred.isInSoftPity)
        assertEquals(8, pred.estimatedNextFive)
        assertEquals(12, pred.pullsUntilHardPity) // 80 − 68
    }

    @Test
    fun weaponPoolUsesSeventyAndFiftySevenAndReports7525() {
        // 60 pulls since the last ★5 → past weapon soft pity (57); estimate = max(57−60+4,1)+4 = 5.
        var t = 0
        fun tick(): String = "2026-08-01 10:00:%02d".format(t++)
        val records = mutableListOf(rec("2", 5, "Weapon X", time = tick()))
        repeat(60) { records.add(rec("2", 3, time = tick())) }
        val pred = WuwaGacha.aggregate(records, listOf("2")).predictions.single()
        assertEquals("Weapon Event", pred.poolLabel)
        assertEquals("75/25", pred.status)
        assertEquals(70, pred.hardPity)
        assertEquals(57, pred.softPityThreshold)
        assertTrue(pred.isInSoftPity)
        assertEquals(5, pred.estimatedNextFive)
        assertEquals(10, pred.pullsUntilHardPity)
    }

    @Test
    fun pullsSinceLastFourStarStopsAtTheLastFourOrFive() {
        val records = listOf(
            rec("1", 4, time = "2026-08-01 10:00:00"),
            rec("1", 3, time = "2026-08-02 10:00:00"),
            rec("1", 3, time = "2026-08-03 10:00:00"),
        )
        val pred = WuwaGacha.aggregate(records, listOf("1")).predictions.single()
        assertEquals(2, pred.pullsSinceLastFourStar)
        assertEquals(8, pred.estimatedNextFourStar) // max(10−2, 1)
    }

    @Test
    fun aPoolWithNoFourOrFiveCapsTheFourStarCounterAtTen() {
        val records = (1..14).map { rec("1", 3, time = "2026-08-01 10:00:%02d".format(it)) }
        val pred = WuwaGacha.aggregate(records, listOf("1")).predictions.single()
        assertEquals(10, pred.pullsSinceLastFourStar)
        assertEquals(1, pred.estimatedNextFourStar) // max(10−10, 1)
    }

    @Test
    fun standardPoolsGetNoPrediction() {
        val records = listOf(
            rec("3", 5, "Standard Char", time = "2026-08-01 10:00:00"),
            rec("3", 4, time = "2026-08-02 10:00:00"),
        )
        val data = WuwaGacha.aggregate(records, listOf("3"))
        assertTrue(data.predictions.isEmpty())
        assertEquals(1, data.fiveStars)
    }

    private fun assertFalse(value: Boolean) = org.junit.Assert.assertFalse(value)
}
