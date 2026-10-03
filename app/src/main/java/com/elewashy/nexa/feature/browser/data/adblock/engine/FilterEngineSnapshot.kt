package com.elewashy.nexa.feature.browser.data.adblock.engine

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.IdentityHashMap

/**
 * Binary snapshot of a compiled [FilterEngine], so app start restores the
 * engine instead of re-parsing several MB of filter lists.
 *
 * The snapshot stores the *compiled* structures — filter tables plus every
 * index's sorted keys and buckets — so restoring involves no parsing,
 * tokenizing or hashing. Filters shared between indexes (a `redirect=`
 * filter is both a block and a redirect) are written once and restored as
 * the same instance.
 *
 * A snapshot is bound to a caller-supplied [key] (app build + list
 * signature): [read] returns null for any other key or format, and the
 * caller recompiles. A truncated or corrupt file surfaces as [IOException].
 */
object FilterEngineSnapshot {

    private const val MAGIC = 0x4E584645 // "NXFE"
    private const val FORMAT_VERSION = 1
    private const val END_MARKER = 0x454E4421 // "END!"
    private const val BUFFER_SIZE = 1 shl 16

    private const val ABSENT = 0
    private const val PRESENT = 1

    /** Writes [engine] tagged with [key]. The caller owns (and closes) [output]. */
    @Throws(IOException::class)
    fun write(engine: FilterEngine, key: String, output: OutputStream) {
        val out = DataOutputStream(BufferedOutputStream(output, BUFFER_SIZE))
        out.writeInt(MAGIC)
        out.writeInt(FORMAT_VERSION)
        out.writeString(key)

        out.writeInt(engine.stats.networkFilters)
        out.writeInt(engine.stats.cosmeticFilters)
        out.writeInt(engine.stats.scriptletFilters)
        out.writeLongArray(engine.pureHostnames.sortedValues())

        val indexes = networkIndexes(engine)
        val networkIds = IdentityHashMap<NetworkFilter, Int>()
        val networkTable = ArrayList<NetworkFilter>()
        for (index in indexes) {
            index.filters().forEach { f -> networkTable.register(f, networkIds) }
        }
        out.writeInt(networkTable.size)
        for (f in networkTable) out.writeNetworkFilter(f)
        for (index in indexes) {
            out.writeMap(index.byHost, networkIds)
            out.writeMap(index.byToken, networkIds)
            out.writeMap(index.byPage, networkIds)
            out.writeIds(index.unindexed, networkIds)
            out.writeInt(index.size)
        }

        val cosmetics = engine.cosmetics
        val cosmeticIds = IdentityHashMap<CosmeticFilter, Int>()
        val cosmeticTable = ArrayList<CosmeticFilter>()
        val register: (CosmeticFilter) -> Unit = { f -> cosmeticTable.register(f, cosmeticIds) }
        cosmetics.specific.values().forEach(register)
        cosmetics.ancestorSpecific.values().forEach(register)
        cosmetics.regexSpecific.forEach(register)
        cosmetics.negatedGeneric.forEach(register)
        cosmetics.genericScriptlets.forEach(register)
        out.writeInt(cosmeticTable.size)
        for (f in cosmeticTable) out.writeCosmeticFilter(f)
        out.writeMap(cosmetics.specific, cosmeticIds)
        out.writeMap(cosmetics.ancestorSpecific, cosmeticIds)
        out.writeIds(cosmetics.regexSpecific, cosmeticIds)
        out.writeIds(cosmetics.negatedGeneric, cosmeticIds)
        out.writeIds(cosmetics.genericScriptlets, cosmeticIds)
        out.writeStrings(cosmetics.genericExceptions)
        out.writeInt(cosmetics.lowGeneric.size)
        for ((token, selectors) in cosmetics.lowGeneric) {
            out.writeString(token)
            out.writeStrings(selectors)
        }
        out.writeStrings(cosmetics.highGenericSelectors)
        out.writeInt(cosmetics.cosmeticCount)
        out.writeInt(cosmetics.scriptletCount)
        out.writeInt(END_MARKER)
        out.flush()
    }

    /**
     * Restores an engine written by [write] with the same [key], or returns
     * null when the snapshot belongs to another key or format version.
     */
    @Throws(IOException::class)
    fun read(input: InputStream, key: String, resolver: RegistrableDomainResolver): FilterEngine? {
        val inp = DataInputStream(BufferedInputStream(input, BUFFER_SIZE))
        if (inp.readInt() != MAGIC || inp.readInt() != FORMAT_VERSION) return null
        if (inp.readString() != key) return null

        val stats = FilterEngineStats(inp.readInt(), inp.readInt(), inp.readInt())
        val pureHostnames = SortedLongSet.ofSorted(inp.readLongArray())

        val networkTable = Array(inp.readCount()) { inp.readNetworkFilter() }
        val indexes = Array(NETWORK_INDEX_COUNT) {
            NetworkFilterIndex(
                byHost = inp.readMap(networkTable),
                byToken = inp.readMap(networkTable),
                byPage = inp.readMap(networkTable),
                unindexed = inp.readIds(networkTable),
                size = inp.readInt(),
            )
        }

        val cosmeticTable = Array(inp.readCount()) { inp.readCosmeticFilter() }
        val cosmetics = CosmeticIndex(
            specific = inp.readMap(cosmeticTable),
            ancestorSpecific = inp.readMap(cosmeticTable),
            regexSpecific = inp.readIds(cosmeticTable),
            negatedGeneric = inp.readIds(cosmeticTable),
            genericScriptlets = inp.readIds(cosmeticTable),
            genericExceptions = inp.readStrings().toHashSet(),
            lowGeneric = HashMap<String, List<String>>().apply {
                repeat(inp.readCount()) { put(inp.readString(), inp.readStrings()) }
            },
            highGenericSelectors = inp.readStrings(),
            cosmeticCount = inp.readInt(),
            scriptletCount = inp.readInt(),
        )
        if (inp.readInt() != END_MARKER) throw IOException("Corrupt filter engine snapshot")

        return FilterEngine(
            resolver = resolver,
            pureHostnames = pureHostnames,
            importantBlocks = indexes[0],
            blocks = indexes[1],
            exceptions = indexes[2],
            redirects = indexes[3],
            redirectExceptions = indexes[4],
            pageExceptions = indexes[5],
            removeParamFilters = indexes[6],
            removeParamExceptions = indexes[7],
            cspFilters = indexes[8],
            cspExceptions = indexes[9],
            cosmetics = cosmetics,
            stats = stats,
        )
    }

    private const val NETWORK_INDEX_COUNT = 10

    /** Order shared by [write] and [read]. */
    private fun networkIndexes(engine: FilterEngine): List<NetworkFilterIndex> = listOf(
        engine.importantBlocks, engine.blocks, engine.exceptions, engine.redirects, engine.redirectExceptions,
        engine.pageExceptions, engine.removeParamFilters, engine.removeParamExceptions, engine.cspFilters,
        engine.cspExceptions,
    ).also { check(it.size == NETWORK_INDEX_COUNT) }

    // ── Filters ─────────────────────────────────────────────────────────

    private fun DataOutputStream.writeNetworkFilter(f: NetworkFilter) {
        writeInt(f.flags)
        writeInt(f.typeMask)
        writeInt(f.pageOptions)
        writeInt(f.methodMask)
        writeString(f.pattern)
        writeInt(f.hostnameLength)
        writeConstraint(f.domains)
        writeConstraint(f.toDomains)
        writeConstraint(f.denyAllow)
        writeNullableString(f.redirect)
        writeInt(f.redirectPriority)
        writeNullableString(f.modifier)
        writeString(f.text)
    }

    private fun DataInputStream.readNetworkFilter(): NetworkFilter = NetworkFilter(
        flags = readInt(),
        typeMask = readInt(),
        pageOptions = readInt(),
        methodMask = readInt(),
        pattern = readString(),
        hostnameLength = readInt(),
        domains = readConstraint(),
        toDomains = readConstraint(),
        denyAllow = readConstraint(),
        redirect = readNullableString(),
        redirectPriority = readInt(),
        modifier = readNullableString(),
        text = readString(),
    )

    private fun DataOutputStream.writeCosmeticFilter(f: CosmeticFilter) {
        writeByte(f.kind.ordinal)
        writeBoolean(f.exception)
        writeConstraint(f.domains)
        writeConstraint(f.ancestors)
        writeStrings(f.hostRegexes?.map { it.pattern }.orEmpty())
        val call = f.scriptlet
        if (call != null) {
            // Key and selector derive from the call.
            writeByte(PRESENT)
            writeString(call.name)
            writeStrings(call.args)
            return
        }
        writeByte(ABSENT)
        writeString(f.selector)
        // Hide/style keys usually equal the selector; store them only when they differ.
        if (f.key == f.selector) writeByte(ABSENT) else {
            writeByte(PRESENT)
            writeString(f.key)
        }
        writeNullableString(f.styleCss)
    }

    private fun DataInputStream.readCosmeticFilter(): CosmeticFilter {
        val kind = KINDS.getOrNull(readUnsignedByte()) ?: throw IOException("Corrupt cosmetic filter kind")
        val exception = readBoolean()
        val domains = readConstraint()
        val ancestors = readConstraint()
        val hostRegexes = readStrings().map { source ->
            CosmeticFilterParser.compileHostRegex(source) ?: throw IOException("Corrupt snapshot: host regex")
        }.ifEmpty { null }
        if (readUnsignedByte() == PRESENT) {
            val call = ScriptletCall(readString(), readStrings())
            return CosmeticFilter(kind, exception, domains, ancestors, hostRegexes, call.key, call.name, null, call)
        }
        val selector = readString()
        val key = if (readUnsignedByte() == PRESENT) readString() else selector
        return CosmeticFilter(kind, exception, domains, ancestors, hostRegexes, key, selector, readNullableString(), null)
    }

    private val KINDS = CosmeticFilter.Kind.entries

    private fun DataOutputStream.writeConstraint(c: DomainConstraint?) {
        if (c == null) {
            writeByte(ABSENT)
            return
        }
        writeByte(PRESENT)
        writeLongArray(c.includes)
        writeLongArray(c.excludes)
        writeBoolean(c.hasEntities)
    }

    private fun DataInputStream.readConstraint(): DomainConstraint? {
        if (readUnsignedByte() == ABSENT) return null
        return DomainConstraint.restore(readLongArray(), readLongArray(), readBoolean())
    }

    // ── Collections ─────────────────────────────────────────────────────

    /** Appends [item] to the table once, recording its id (its first position). */
    private fun <T : Any> MutableList<T>.register(item: T, ids: IdentityHashMap<T, Int>) {
        if (ids.containsKey(item)) return
        ids[item] = size
        add(item)
    }

    private fun <T : Any> DataOutputStream.writeMap(map: SortedLongMap<T>, ids: IdentityHashMap<T, Int>) {
        writeInt(map.size)
        map.forEachEntry { key, bucket ->
            writeLong(key)
            writeIds(bucket, ids)
        }
    }

    private inline fun <reified T : Any> DataInputStream.readMap(table: Array<T>): SortedLongMap<T> {
        val size = readCount()
        val keys = LongArray(size)
        val buckets = arrayOfNulls<List<T>>(size)
        for (i in 0 until size) {
            keys[i] = readLong()
            buckets[i] = readIds(table)
        }
        @Suppress("UNCHECKED_CAST")
        return SortedLongMap.ofSorted(keys, buckets as Array<List<T>>)
    }

    private fun <T : Any> DataOutputStream.writeIds(items: List<T>, ids: IdentityHashMap<T, Int>) {
        writeInt(items.size)
        for (item in items) writeInt(ids.getValue(item))
    }

    private fun <T : Any> DataInputStream.readIds(table: Array<T>): List<T> {
        val size = readCount()
        if (size == 1) return listOf(table.at(readInt()))
        return List(size) { table.at(readInt()) }
    }

    private fun <T> Array<T>.at(index: Int): T =
        getOrNull(index) ?: throw IOException("Corrupt snapshot: filter id $index out of range")

    private fun DataOutputStream.writeLongArray(values: LongArray) {
        writeInt(values.size)
        for (v in values) writeLong(v)
    }

    private fun DataInputStream.readLongArray(): LongArray {
        val values = LongArray(readCount())
        for (i in values.indices) values[i] = readLong()
        return values
    }

    private fun DataOutputStream.writeStrings(values: Collection<String>) {
        writeInt(values.size)
        for (v in values) writeString(v)
    }

    private fun DataInputStream.readStrings(): List<String> = List(readCount()) { readString() }

    /** UTF-8 with an int length: unlike `writeUTF`, not limited to 64 KB (long scriptlet arguments). */
    private fun DataOutputStream.writeString(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readString(): String {
        val bytes = ByteArray(readCount())
        readFully(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    private fun DataOutputStream.writeNullableString(value: String?) {
        if (value == null) writeByte(ABSENT) else {
            writeByte(PRESENT)
            writeString(value)
        }
    }

    private fun DataInputStream.readNullableString(): String? = if (readUnsignedByte() == ABSENT) null else readString()

    /** A length/count field; rejects values a corrupt file could use to exhaust memory. */
    private fun DataInputStream.readCount(): Int {
        val n = readInt()
        if (n < 0 || n > MAX_COUNT) throw IOException("Corrupt snapshot: count $n")
        return n
    }

    private const val MAX_COUNT = 1 shl 26
}
