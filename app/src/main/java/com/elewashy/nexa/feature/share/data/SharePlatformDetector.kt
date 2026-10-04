package com.elewashy.nexa.feature.share.data

import androidx.core.net.toUri
import com.elewashy.nexa.feature.share.domain.model.SharePlatform

object SharePlatformDetector {
    private val urlRegex = Regex("https?://\\S+", RegexOption.IGNORE_CASE)

    fun extractFirstUrl(text: String): String? {
        return urlRegex.find(text)
            ?.value
            ?.trimEnd('.', ',', ';', ':', ')', ']', '}', '>', '"', '\'')
    }

    fun detect(url: String?): SharePlatform = SharePlatform.fromHost(url?.hostOrNull())

    private fun String.hostOrNull(): String? {
        return try {
            toUri().host
        } catch (_: Exception) {
            null
        }
    }
}
