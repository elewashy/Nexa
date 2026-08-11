package com.elewashy.nexa.feature.downloads.data.filename

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * JVM tests for the pure helpers of [FileNameResolver]: filename sanitization
 * (path traversal, unsafe/control chars, Windows reserved names, extension
 * handling) and [FileNameResolver.uniqueName] collision behavior including
 * the in-memory reservedNames parameter.
 *
 * Gap: the MIME-type → extension path of [FileNameResolver.sanitise] goes
 * through android.webkit.MimeTypeMap and is not exercisable on the JVM.
 */
class FileNameResolverTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    // ── sanitise: path traversal & unsafe characters ──────────────────

    @Test
    fun `path separators are neutralised`() {
        listOf(
            "../etc/passwd",
            "..\\..\\windows\\system32",
            "foo/bar/baz",
            "a:b|c<d>e?f*g\"h"
        ).forEach { input ->
            val result = FileNameResolver.sanitise(input, null)
            assertTrue("'$result' must not contain path separators (from '$input')",
                !result.contains('/') && !result.contains('\\'))
            assertTrue("'$result' must not contain a parent-dir sequence (from '$input')",
                !result.contains(".."))
        }
    }

    @Test
    fun `unix traversal input collapses to a safe name`() {
        assertEquals("download.etcpa", FileNameResolver.sanitise("../../etc/passwd", null))
    }

    @Test
    fun `percent encoded traversal is decoded then neutralised`() {
        // %2e%2e%2f decodes to "../" — the decoded form must be sanitised too.
        // (The surviving extension is truncated to 5 chars like any other.)
        val result = FileNameResolver.sanitise("%2e%2e%2fpasswd", null)
        assertEquals("download.passw", result)
    }

    @Test
    fun `control characters are replaced`() {
        assertEquals("bad_name.txt", FileNameResolver.sanitise("bad\u0001name.txt", null))
        assertEquals("tabbed_name.txt", FileNameResolver.sanitise("tabbed\tname.txt", null))
    }

    @Test
    fun `spaces become underscores`() {
        assertEquals("my_video.mp4", FileNameResolver.sanitise("my video.mp4", null))
    }

    @Test
    fun `percent encoded spaces are decoded then replaced`() {
        assertEquals("my_video.mp4", FileNameResolver.sanitise("my%20video.mp4", null))
    }

    // ── sanitise: Windows reserved names ───────────────────────────────

    @Test
    fun `windows reserved base names are prefixed`() {
        assertEquals("download_CON.bin", FileNameResolver.sanitise("CON", null))
        assertEquals("download_con.txt", FileNameResolver.sanitise("con.txt", null))
        assertEquals("download_NUL.bin", FileNameResolver.sanitise("NUL", null))
        assertEquals("download_COM1.bin", FileNameResolver.sanitise("COM1", null))
        // The original case of the base is preserved in the prefixed name.
        assertEquals("download_lpt3.log", FileNameResolver.sanitise("lpt3.log", null))
    }

    @Test
    fun `reserved lookalikes are untouched`() {
        // Only exact reserved names (case-insensitive) are rewritten.
        assertEquals("CONSOLE.bin", FileNameResolver.sanitise("CONSOLE", null))
        assertEquals("com10.txt", FileNameResolver.sanitise("com10.txt", null))
    }

    // ── sanitise: extension handling ───────────────────────────────────

    @Test
    fun `extensions are lowercased cleaned and truncated to five chars`() {
        assertEquals("VIDEO.mp4", FileNameResolver.sanitise("VIDEO.MP4", null))
        assertEquals("file.abcde", FileNameResolver.sanitise("file.abcdefg", null))
        // Non-alphanumerics are stripped from the extension.
        assertEquals("file.mp4", FileNameResolver.sanitise("file.mp4!", null))
    }

    @Test
    fun `the last dot splits base from extension`() {
        // Inner dots move into the base and become underscores.
        assertEquals("file_mp4.exe", FileNameResolver.sanitise("file.mp4.exe", null))
        assertEquals("my_video_file.mp4", FileNameResolver.sanitise("my.video.file.mp4", null))
    }

    @Test
    fun `missing extension falls back to bin`() {
        assertEquals("README.bin", FileNameResolver.sanitise("README", null))
        // Trailing dot counts as missing too.
        assertEquals("file.bin", FileNameResolver.sanitise("file.", null))
    }

    // NOTE: any sanitise() call with a non-null contentType and a
    // missing extension goes through android.webkit.MimeTypeMap and is
    // therefore NOT exercisable on the JVM — see the class KDoc gap note.

    @Test
    fun `leading dot names keep their content`() {
        // A single leading dot is not an extension separator.
        assertEquals("hidden", FileNameResolver.sanitise(".hidden", null))
    }

    @Test
    fun `empty input falls back to the download base name`() {
        assertEquals("download", FileNameResolver.sanitise("", null))
    }

    @Test
    fun `runs of underscores collapse and edges are trimmed`() {
        assertEquals("a_b.txt", FileNameResolver.sanitise("a__b.txt", null))
        assertEquals("video.mp4", FileNameResolver.sanitise("_video_.mp4", null))
        assertEquals("video.mp4", FileNameResolver.sanitise("...video....mp4", null))
    }

    @Test
    fun `base name is capped at 120 chars`() {
        val result = FileNameResolver.sanitise("a".repeat(150) + ".mp4", null)
        assertEquals("a".repeat(120) + ".mp4", result)
    }

    // ── sanitiseWithForcedExtension ────────────────────────────────────

    @Test
    fun `forced extension replaces the existing one`() {
        assertEquals("video.mp3", FileNameResolver.sanitiseWithForcedExtension("video.webm", "mp3"))
    }

    @Test
    fun `forced extension is cleaned and lowercased`() {
        assertEquals("video.mp3", FileNameResolver.sanitiseWithForcedExtension("video", "MP3"))
        assertEquals("video.mp3", FileNameResolver.sanitiseWithForcedExtension("video", ".mp3"))
        assertEquals("video.mp3", FileNameResolver.sanitiseWithForcedExtension("video.wav", "m!p@3"))
    }

    @Test
    fun `forced extension applies to empty base via download fallback`() {
        assertEquals("download.mp3", FileNameResolver.sanitiseWithForcedExtension("", "mp3"))
    }

    // ── uniqueName ─────────────────────────────────────────────────────

    @Test
    fun `free name is returned unchanged`() {
        val dir = tempFolder.newFolder()
        assertEquals("video.mp4", FileNameResolver.uniqueName(dir, "video.mp4"))
    }

    @Test
    fun `existing file gets numeric suffix`() {
        val dir = tempFolder.newFolder()
        File(dir, "video.mp4").createNewFile()
        assertEquals("video_1.mp4", FileNameResolver.uniqueName(dir, "video.mp4"))
    }

    @Test
    fun `suffix increments until a free slot is found`() {
        val dir = tempFolder.newFolder()
        File(dir, "video.mp4").createNewFile()
        File(dir, "video_1.mp4").createNewFile()
        File(dir, "video_2.mp4").createNewFile()
        assertEquals("video_3.mp4", FileNameResolver.uniqueName(dir, "video.mp4"))
    }

    @Test
    fun `leftover part file counts as a collision`() {
        val dir = tempFolder.newFolder()
        File(dir, "video.mp4.part").createNewFile()
        assertEquals("video_1.mp4", FileNameResolver.uniqueName(dir, "video.mp4"))
    }

    @Test
    fun `extensionless names collide too`() {
        val dir = tempFolder.newFolder()
        File(dir, "README").createNewFile()
        assertEquals("README_1", FileNameResolver.uniqueName(dir, "README"))
    }

    @Test
    fun `in memory reserved names block the exact name`() {
        val dir = tempFolder.newFolder()
        // Nothing exists on disk — an in-flight download reserved the name.
        assertEquals(
            "video_1.mp4",
            FileNameResolver.uniqueName(dir, "video.mp4", reservedNames = setOf("video.mp4"))
        )
    }

    @Test
    fun `in memory reserved names also block generated candidates`() {
        val dir = tempFolder.newFolder()
        assertEquals(
            "video_2.mp4",
            FileNameResolver.uniqueName(
                dir, "video.mp4",
                reservedNames = setOf("video.mp4", "video_1.mp4")
            )
        )
    }

    @Test
    fun `disk collisions and reserved names combine`() {
        val dir = tempFolder.newFolder()
        File(dir, "video.mp4").createNewFile()
        assertEquals(
            "video_2.mp4",
            FileNameResolver.uniqueName(dir, "video.mp4", reservedNames = setOf("video_1.mp4"))
        )
    }

    @Test
    fun `exhausting the suffix range falls back to a timestamp`() {
        val dir = tempFolder.newFolder()
        // No disk files — reserve the base name and all 1000 candidates.
        val reserved = buildSet {
            add("video.mp4")
            for (i in 1..1000) add("video_$i.mp4")
        }
        val result = FileNameResolver.uniqueName(dir, "video.mp4", reservedNames = reserved)
        assertTrue("fallback must keep the extension: $result", result.endsWith(".mp4"))
        assertTrue("fallback must keep the base: $result", result.startsWith("video_"))
        assertNotEquals("video.mp4", result)
        assertTrue("fallback must not be a reserved candidate: $result", result !in reserved)
    }
}
