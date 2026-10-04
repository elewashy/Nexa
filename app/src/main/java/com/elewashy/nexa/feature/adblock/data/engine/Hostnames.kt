package com.elewashy.nexa.feature.adblock.data.engine

import java.net.IDN
import java.util.concurrent.ConcurrentHashMap

/**
 * Allocation-free 64-bit FNV-1a hashing over string ranges. Hostname and
 * token indexes store these hashes instead of strings, which keeps the
 * hundreds of thousands of hostname rules in compact primitive arrays.
 * With 64 bits the collision probability for a few hundred thousand keys is
 * ~1e-9; filters are still verified against the URL after a hash hit.
 */
internal object Fnv {
    const val OFFSET = -0x340d631b7bdddcdbL // 0xcbf29ce484222325
    private const val PRIME = 0x100000001b3L

    fun hash(s: CharSequence, start: Int = 0, end: Int = s.length): Long = append(OFFSET, s, start, end)

    fun append(seed: Long, s: CharSequence, start: Int = 0, end: Int = s.length): Long {
        var h = seed
        for (i in start until end) {
            h = (h xor s[i].code.toLong()) * PRIME
        }
        return h
    }

    /** Hash of `s[start, end) + ".*"` — the uBO entity form (`example.*`). */
    fun entity(s: CharSequence, start: Int, end: Int): Long = append(hash(s, start, end), ".*")
}

/**
 * Resolves the registrable domain (eTLD+1) of a hostname. Used for
 * first/third-party classification and uBO "entity" matching (`example.*`).
 */
fun interface RegistrableDomainResolver {
    /** Returns the registrable domain for [host], or null for IPs/public suffixes. */
    fun registrableDomain(host: String): String?
}

/**
 * Last-two-labels approximation with a small table of common multi-label
 * public suffixes. Used only when the Public Suffix List is unavailable
 * (JVM tests); production resolves through OkHttp's bundled PSL.
 */
object HeuristicRegistrableDomainResolver : RegistrableDomainResolver {
    private val MULTI_LABEL_SUFFIXES = setOf(
        "co.uk", "org.uk", "ac.uk", "gov.uk", "com.au", "net.au", "org.au", "co.jp", "ne.jp", "or.jp",
        "com.br", "com.cn", "com.eg", "com.sa", "com.tr", "com.mx", "com.ar", "co.in", "co.kr", "co.nz",
        "co.za", "com.sg", "com.hk", "com.tw", "github.io", "blogspot.com", "appspot.com", "herokuapp.com",
        "netlify.app", "vercel.app", "pages.dev", "workers.dev", "cloudfront.net", "azurewebsites.net",
    )

    override fun registrableDomain(host: String): String? {
        if (host.isEmpty() || Hostnames.isIpAddress(host)) return null
        val last = host.lastIndexOf('.')
        if (last <= 0) return null
        val second = host.lastIndexOf('.', last - 1)
        if (second == -1) return host
        val suffixCandidate = host.substring(second + 1)
        if (suffixCandidate in MULTI_LABEL_SUFFIXES) {
            val third = host.lastIndexOf('.', second - 1)
            return if (third == -1) host else host.substring(third + 1)
        }
        return host.substring(second + 1)
    }
}

/** Hostname helpers shared by the parser, the indexes and the matcher. */
object Hostnames {

    /** Lowercases, strips a trailing dot and converts IDN labels to punycode. */
    fun normalize(raw: String): String? {
        var host = raw.trim().trimEnd('.')
        if (host.isEmpty()) return null
        if (host.any { it.code > 0x7f }) {
            host = try {
                IDN.toASCII(host, IDN.ALLOW_UNASSIGNED)
            } catch (_: IllegalArgumentException) {
                return null
            }
        }
        return host.lowercase()
    }

    fun isIpAddress(host: String): Boolean {
        if (host.startsWith('[')) return true
        if (host.isEmpty() || !host[host.length - 1].isDigit()) return false
        var dots = 0
        for (c in host) {
            if (c == '.') dots++ else if (!c.isDigit()) return false
        }
        return dots == 3
    }

    /** True for a syntactically plausible hostname made of `[a-z0-9-_.]`. */
    fun isValidHostname(host: String): Boolean {
        if (host.isEmpty() || host.length > 253 || host[0] == '.' || host[0] == '-' || host.last() == '.') {
            return false
        }
        var previous = '.'
        for (c in host) {
            val ok = c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '.' || c == '_'
            if (!ok || (c == '.' && previous == '.')) return false
            previous = c
        }
        return true
    }

    /** Extracts the lowercase host of an http(s)/ws(s) URL without allocating a URI. */
    fun hostOf(url: String): String? {
        val schemeEnd = url.indexOf("://")
        if (schemeEnd <= 0) return null
        var start = schemeEnd + 3
        var end = url.length
        for (i in start until url.length) {
            val c = url[i]
            if (c == '/' || c == '?' || c == '#') {
                end = i
                break
            }
        }
        val at = url.lastIndexOf('@', end - 1)
        if (at >= start) start = at + 1
        if (start >= end) return null
        var hostEnd = end
        if (url[start] == '[') {
            val close = url.indexOf(']', start)
            if (close == -1 || close >= end) return null
            hostEnd = close + 1
        } else {
            val colon = url.indexOf(':', start)
            if (colon in start until end) hostEnd = colon
        }
        if (hostEnd <= start) return null
        return url.substring(start, hostEnd).lowercase().trimEnd('.')
    }
}

/**
 * Bounded cache in front of a [RegistrableDomainResolver]. PSL lookups cost a
 * few microseconds each; pages issue hundreds of requests to a handful of
 * hosts, so caching makes party checks effectively free.
 */
class CachingRegistrableDomainResolver(
    private val delegate: RegistrableDomainResolver,
    private val maxEntries: Int = 4096,
) : RegistrableDomainResolver {
    private val cache = ConcurrentHashMap<String, String>()

    override fun registrableDomain(host: String): String? {
        cache[host]?.let { return it.ifEmpty { null } }
        val resolved = try {
            delegate.registrableDomain(host)
        } catch (_: RuntimeException) {
            HeuristicRegistrableDomainResolver.registrableDomain(host)
        }
        if (cache.size >= maxEntries) cache.clear()
        cache[host] = resolved.orEmpty()
        return resolved
    }
}

/**
 * A hostname plus the derived data needed by domain constraints: the
 * registrable domain and the public-suffix length for entity matching.
 * Instances are built once per page / request host and reused.
 */
class HostContext private constructor(
    val host: String,
    val registrableDomain: String?,
    /** Length of the public suffix including its leading dot, or 0 when unknown. */
    val publicSuffixLength: Int,
) {
    companion object {
        val EMPTY = HostContext("", null, 0)

        fun of(host: String, resolver: RegistrableDomainResolver): HostContext {
            if (host.isEmpty()) return EMPTY
            val registrable = resolver.registrableDomain(host)
            val firstDot = registrable?.indexOf('.') ?: -1
            val suffixLength = if (registrable != null && firstDot > 0) registrable.length - firstDot else 0
            return HostContext(host, registrable, suffixLength)
        }
    }
}
