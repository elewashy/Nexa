package com.elewashy.nexa.feature.share.data.platform

import com.elewashy.nexa.feature.share.data.ExtractionException
import com.elewashy.nexa.feature.share.domain.model.SharePlatform
import com.elewashy.nexa.feature.share.data.platform.ShareExtractionSupport.Companion.TIKWM_BASE_URL
import com.elewashy.nexa.feature.share.domain.model.ExtractionError
import com.elewashy.nexa.feature.share.domain.model.ExtractionResult
import okhttp3.FormBody
import okhttp3.Request
import org.json.JSONObject
import javax.inject.Inject

/**
 * TikTok extraction via the TikWM API, which resolves shared links (including
 * vm.tiktok.com short links) server-side and returns direct media URLs.
 */
internal class TikTokVideoExtractor @Inject constructor(
    private val support: ShareExtractionSupport
) : PlatformVideoExtractor {

    override val platform = SharePlatform.TIKTOK

    override suspend fun extract(url: String): ExtractionResult = TikWmResponseParser.toResult(fetchTikWmData(url))

    private suspend fun fetchTikWmData(url: String): JSONObject {
        val requestBody = FormBody.Builder()
            .add("url", url)
            .add("hd", "1")
            .build()

        val request = Request.Builder()
            .url(TIKWM_API_URL)
            .post(requestBody)
            .header("User-Agent", ShareExtractionSupport.USER_AGENT_DESKTOP)
            .header("Accept", "application/json, text/plain, */*")
            .header("Origin", TIKWM_BASE_URL)
            .header("Referer", "$TIKWM_BASE_URL/")
            .build()

        val body = support.executeOrThrow(request).use { it.body.string() }
        if (body.isBlank()) throw ExtractionException("TikWM returned an empty response", ExtractionError.NETWORK)

        val json = JSONObject(body)
        if (json.optInt("code", -1) != 0) {
            // TikWM reports private, removed and unparsable links this way.
            throw ExtractionException(json.optString("msg").ifBlank { "TikWM API error" })
        }
        return json.optJSONObject("data") ?: throw ExtractionException("TikWM response is missing data")
    }

    private companion object {
        const val TIKWM_API_URL = "https://www.tikwm.com/api/"
    }
}
