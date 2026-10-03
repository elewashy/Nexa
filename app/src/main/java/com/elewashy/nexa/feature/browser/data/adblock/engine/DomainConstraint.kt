package com.elewashy.nexa.feature.browser.data.adblock.engine

/**
 * Compiled `domain=` / `to=` / `denyallow=` / cosmetic hostname list.
 *
 * Entries are 64-bit hashes of hostnames (`example.com`) or entities
 * (`example.*`), kept in sorted arrays for binary search. Matching walks the
 * candidate host from the most specific label upward; the first entry hit
 * decides, so `example.com|~ads.example.com` excludes only the subdomain.
 * Exclusions win over inclusions at the same level. Without any hit the
 * constraint matches only when it has no positive entries.
 */
class DomainConstraint private constructor(
    internal val includes: LongArray,
    internal val excludes: LongArray,
    /** Whether any entry uses the entity form; skips entity hashing otherwise. */
    internal val hasEntities: Boolean,
) {
    val hasIncludes: Boolean get() = includes.isNotEmpty()

    /** Positive hostname hashes, used to index specific cosmetic filters. */
    internal fun includeHashes(): LongArray = includes

    fun matches(ctx: HostContext): Boolean {
        val host = ctx.host
        if (host.isEmpty()) return includes.isEmpty()
        val hostEnd = host.length
        val entityEnd = hostEnd - ctx.publicSuffixLength
        var start = 0
        while (true) {
            val hostHash = Fnv.hash(host, start, hostEnd)
            val entityHash = if (hasEntities && ctx.publicSuffixLength > 0 && start < entityEnd) {
                Fnv.entity(host, start, entityEnd)
            } else {
                0L
            }
            if (excludes.isNotEmpty() &&
                (excludes.binarySearch(hostHash) >= 0 || (entityHash != 0L && excludes.binarySearch(entityHash) >= 0))
            ) {
                return false
            }
            if (includes.isNotEmpty() &&
                (includes.binarySearch(hostHash) >= 0 || (entityHash != 0L && includes.binarySearch(entityHash) >= 0))
            ) {
                return true
            }
            val dot = host.indexOf('.', start)
            if (dot == -1) break
            start = dot + 1
        }
        return includes.isEmpty()
    }

    companion object {
        /** Rebuilds a constraint from its sorted hash arrays (see [FilterEngineSnapshot]). */
        internal fun restore(includes: LongArray, excludes: LongArray, hasEntities: Boolean): DomainConstraint =
            DomainConstraint(includes, excludes, hasEntities)

        /**
         * Parses a separated hostname list (`a.com|~b.com|c.*`).
         * Returns null when the list contains an unsupported entry (regex
         * domains); callers drop such filters instead of over-applying them.
         */
        fun parse(list: String, separator: Char): DomainConstraint? {
            val includes = ArrayList<Long>()
            val excludes = ArrayList<Long>()
            var hasEntities = false
            var start = 0
            while (start <= list.length) {
                var end = list.indexOf(separator, start)
                if (end == -1) end = list.length
                var token = list.substring(start, end).trim()
                start = end + 1
                if (token.isEmpty()) continue
                val negated = token[0] == '~'
                if (negated) token = token.substring(1)
                if (token.startsWith('/')) return null
                val isEntity = token.endsWith(".*")
                val hostPart = if (isEntity) token.substring(0, token.length - 2) else token
                val host = Hostnames.normalize(hostPart) ?: continue
                if (!Hostnames.isValidHostname(host) && !Hostnames.isIpAddress(host)) continue
                val hash = if (isEntity) Fnv.entity(host, 0, host.length) else Fnv.hash(host)
                if (isEntity) hasEntities = true
                if (negated) excludes.add(hash) else includes.add(hash)
            }
            if (includes.isEmpty() && excludes.isEmpty()) return null
            return DomainConstraint(
                includes.toLongArray().apply { sort() },
                excludes.toLongArray().apply { sort() },
                hasEntities,
            )
        }
    }
}
