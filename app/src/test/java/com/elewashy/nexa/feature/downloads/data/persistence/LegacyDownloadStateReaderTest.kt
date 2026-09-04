package com.elewashy.nexa.feature.downloads.data.persistence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LegacyDownloadStateReaderTest {

    @Test
    fun `parses a valid document`() {
        val state = LegacyDownloadStateReader.read(
            """
            {
              "lastId": 7,
              "items": [
                {"id": 1, "url": "https://a.example/", "fileName": "a.bin",
                 "filePath": "/storage/a.bin", "status": "COMPLETED",
                 "totalBytes": 10, "downloadedBytes": 10, "createdAt": 1},
                {"id": 2, "url": "https://b.example/", "fileName": "b.bin",
                 "filePath": "/storage/b.bin", "status": "PAUSED", "createdAt": 2}
              ],
              "segments": {"2": [{"startByte": 0, "endByte": 99, "downloadedBytes": 20}]}
            }
            """.trimIndent()
        )!!

        assertEquals(7L, state.lastId)
        assertEquals(listOf(1L, 2L), state.items.map { it.id })
        assertEquals(1, state.segments[2L]?.size)
    }

    @Test
    fun `skips malformed entries without aborting`() {
        val state = LegacyDownloadStateReader.read(
            """
            {
              "lastId": "not-a-number",
              "items": [
                {"id": "bad", "status": "PAUSED"},
                {"id": 3, "url": "https://c.example/", "fileName": "c.bin",
                 "filePath": "/storage/c.bin", "status": "PAUSED"}
              ],
              "segments": {"abc": [], "3": "not-an-array"}
            }
            """.trimIndent()
        )!!

        assertEquals(0L, state.lastId)
        assertEquals(listOf(3L), state.items.map { it.id })
        assertEquals(emptyMap<Long, List<PersistedSegment>>(), state.segments)
    }

    @Test
    fun `blank document yields null`() {
        assertNull(LegacyDownloadStateReader.read("   "))
    }

    @Test(expected = com.google.gson.JsonSyntaxException::class)
    fun `corrupt document throws for caller quarantine`() {
        LegacyDownloadStateReader.read("{this is not json!!")
    }

    @Test
    fun `parses the pre-1_2_0 bare array prefs format with fallback lastId`() {
        val state = LegacyDownloadStateReader.read(
            """[
                {"id": 5, "url": "https://old.example/", "fileName": "old.bin",
                 "filePath": "/storage/old.bin", "status": "COMPLETED", "createdAt": 9}
            ]""",
            fallbackLastId = 7,
        )!!

        assertEquals(7L, state.lastId)
        assertEquals(listOf(5L), state.items.map { it.id })
        assertEquals(emptyMap<Long, List<PersistedSegment>>(), state.segments)
    }

    @Test
    fun `negative fallback lastId clamps to zero`() {
        val state = LegacyDownloadStateReader.read("[ ]", fallbackLastId = -3)!!
        assertEquals(0L, state.lastId)
    }

    @Test
    fun `primitive root degrades to null`() {
        assertNull(LegacyDownloadStateReader.read("\"garbage\""))
        assertNull(LegacyDownloadStateReader.read("42"))
    }

    @Test
    fun `negative document lastId clamps to zero`() {
        val state = LegacyDownloadStateReader.read("""{"lastId": -9}""")!!
        assertEquals(0L, state.lastId)
    }
}
