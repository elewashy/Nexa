package com.elewashy.nexa.feature.share.data.platform

import com.elewashy.nexa.feature.share.domain.model.MediaImage
import org.json.JSONException
import org.json.JSONObject

/**
 * Media of one tweet as reported by X's syndication endpoint.
 *
 * @property videos progressive MP4 renditions of each video / GIF, in tweet order.
 * @property hasVideo the tweet has a video or GIF, even if no MP4 rendition was listed.
 */
internal data class SyndicatedTweet(
    val photos: List<MediaImage>,
    val hasVideo: Boolean,
    val videos: List<TweetVideo> = emptyList(),
) {
    val hasMedia: Boolean get() = photos.isNotEmpty() || hasVideo
}

/** One video (or GIF) of a tweet; [variants] are sorted by bitrate, best first. */
internal data class TweetVideo(val variants: List<TweetVideoVariant>)

internal data class TweetVideoVariant(
    val url: String,
    val bitrate: Long,
    val width: Int,
    val height: Int,
)

/**
 * X's public syndication endpoint — the one that powers embedded tweets
 * (`platform.twitter.com` embeds) — returns a tweet's media without login.
 */
internal object TwitterSyndication {

    private const val ENDPOINT = "https://cdn.syndication.twimg.com/tweet-result"

    fun requestUrl(tweetId: String): String = "$ENDPOINT?id=$tweetId&token=${token(tweetId)}&lang=en"

    /**
     * The embed widget's request token: `(id / 1e15 * π)` in base 36 without
     * zeros and the radix point.
     */
    internal fun token(tweetId: String): String {
        val value = (tweetId.toDoubleOrNull() ?: return "0") / 1e15 * Math.PI
        val integer = value.toLong()
        var fraction = value - integer
        val digits = StringBuilder(java.lang.Long.toString(integer, 36))
        repeat(TOKEN_FRACTION_DIGITS) {
            if (fraction == 0.0) return@repeat
            fraction *= 36
            val digit = fraction.toInt()
            digits.append(Character.forDigit(digit, 36))
            fraction -= digit
        }
        return digits.toString().replace("0", "").ifEmpty { "0" }
    }

    /** Parses a syndication response; null when the tweet is unavailable or the payload is unexpected. */
    fun parse(json: String): SyndicatedTweet? = try {
        val root = JSONObject(json)
        if (root.optString("__typename") != "Tweet") {
            null
        } else {
            parseMedia(root)
        }
    } catch (_: JSONException) {
        null
    }

    private fun parseMedia(root: JSONObject): SyndicatedTweet {
        val photos = mutableListOf<MediaImage>()
        val videos = mutableListOf<TweetVideo>()
        var hasVideo = root.has("video")
        val details = root.optJSONArray("mediaDetails")
        if (details != null) {
            for (i in 0 until details.length()) {
                val media = details.optJSONObject(i) ?: continue
                when (media.optString("type")) {
                    "photo" -> {
                        val url = media.optString("media_url_https").takeIf { it.startsWith("https://") } ?: continue
                        val size = media.optJSONObject("original_info")
                        photos += MediaImage(originalQuality(url), size?.optInt("width") ?: 0, size?.optInt("height") ?: 0)
                    }
                    "video", "animated_gif" -> {
                        hasVideo = true
                        parseVideo(media)?.let(videos::add)
                    }
                }
            }
        } else {
            val legacyPhotos = root.optJSONArray("photos")
            for (i in 0 until (legacyPhotos?.length() ?: 0)) {
                val photo = legacyPhotos?.optJSONObject(i) ?: continue
                val url = photo.optString("url").takeIf { it.startsWith("https://") } ?: continue
                photos += MediaImage(originalQuality(url), photo.optInt("width"), photo.optInt("height"))
            }
        }
        return SyndicatedTweet(photos.distinctBy(MediaImage::url), hasVideo, videos)
    }

    /** Progressive MP4 variants only: HLS playlists cannot be saved as a file. */
    private fun parseVideo(media: JSONObject): TweetVideo? {
        val variants = media.optJSONObject("video_info")?.optJSONArray("variants") ?: return null
        val original = media.optJSONObject("original_info")
        val mp4 = (0 until variants.length())
            .mapNotNull(variants::optJSONObject)
            .filter { it.optString("content_type") == "video/mp4" && it.optString("url").startsWith("https://") }
            .map { variant ->
                val url = variant.optString("url")
                val size = VARIANT_SIZE_RE.find(url)
                TweetVideoVariant(
                    url = url,
                    bitrate = variant.optLong("bitrate"),
                    width = size?.groupValues?.get(1)?.toInt() ?: original?.optInt("width") ?: 0,
                    height = size?.groupValues?.get(2)?.toInt() ?: original?.optInt("height") ?: 0,
                )
            }
            .distinctBy { it.url }
            .sortedByDescending { it.bitrate }
        return mp4.takeIf { it.isNotEmpty() }?.let(::TweetVideo)
    }

    /**
     * Requests the original upload instead of the default resized rendition:
     * `…/media/ID.jpg` → `…/media/ID?format=jpg&name=orig`.
     */
    internal fun originalQuality(url: String): String {
        if (!url.startsWith(PHOTO_HOST) || '?' in url) return url
        val extension = url.substringAfterLast('/').substringAfterLast('.', "")
        if (extension.isEmpty()) return "$url?name=orig"
        return "${url.removeSuffix(".$extension")}?format=$extension&name=orig"
    }

    private const val PHOTO_HOST = "https://pbs.twimg.com/"

    /** Rendition size in video.twimg.com paths: `/vid/avc1/1280x720/…` or `/vid/1280x720/…`. */
    private val VARIANT_SIZE_RE = Regex("""/vid/(?:[^/]+/)?(\d+)x(\d+)/""")
    private const val TOKEN_FRACTION_DIGITS = 12
}
