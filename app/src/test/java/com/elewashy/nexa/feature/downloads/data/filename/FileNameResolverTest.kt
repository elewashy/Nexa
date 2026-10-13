package com.elewashy.nexa.feature.downloads.data.filename

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FileNameResolverTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun `source filenames remain unchanged`() {
        listOf("My Video.MP4", "archive.tar.gz", "README", ".hidden", "a__b.txt", "a+b%20c.txt", "file.longextension", "CON", "file.", "日本語 😀.PDF", "a:b?c.txt").forEach {
            assertEquals(it, FileNameResolver.sanitise(it, "application/octet-stream"))
        }
    }

    @Test fun `only unsafe path and control bytes change`() {
        assertEquals(".._etc_passwd", FileNameResolver.sanitise("../etc/passwd", null))
        assertEquals("a_b_c.txt", FileNameResolver.sanitise("a/b\\c.txt", null))
        assertEquals("bad_name", FileNameResolver.sanitise("bad\u0000name", null))
        assertEquals("download", FileNameResolver.sanitise("..", null))
        assertEquals("download", FileNameResolver.sanitise("", null))
    }

    @Test fun `rfc extended parameter takes precedence and preserves plus`() {
        assertEquals("résumé+ final.PDF", FileNameResolver.extractFilenameFromContentDisposition("attachment; filename=old.pdf; filename*=UTF-8'en'r%C3%A9sum%C3%A9+%20final.PDF"))
        assertEquals("a+b%20c.txt", FileNameResolver.extractFilenameFromContentDisposition("attachment; filename=\"a+b%20c.txt\""))
        assertEquals("a;b.txt", FileNameResolver.extractFilenameFromContentDisposition("attachment; filename=\"a;b.txt\""))
        assertEquals("a\"b.txt", FileNameResolver.extractFilenameFromContentDisposition("attachment; filename=\"a\\\"b.txt\""))
        assertEquals("fallback.txt", FileNameResolver.extractFilenameFromContentDisposition("attachment; filename*=UTF-8''bad%ZZ; filename=fallback.txt"))
        assertEquals("café.txt", FileNameResolver.extractFilenameFromContentDisposition("attachment; filename*=ISO-8859-1''caf%E9.txt"))
    }

    @Test fun `url path decodes exactly once`() {
        assertEquals("a+b c.txt", FileNameResolver.fromSource("https://example.test/a+b%20c.txt?signature=%2F", null))
        assertEquals("a%20b.txt", FileNameResolver.fromSource("https://example.test/a%2520b.txt", null))
    }

    @Test fun `filesystem limit is utf8 bytes and extension survives`() {
        val name = FileNameResolver.sanitise("😀".repeat(100) + ".LONGEXTENSION", null)
        assertTrue(name.toByteArray().size <= 255)
        assertTrue(name.endsWith(".LONGEXTENSION"))
        assertFalse(name.substringBeforeLast('.').last().isHighSurrogate())
    }

    @Test fun `collision protects files partials and reservations`() {
        val dir = temp.newFolder()
        File(dir, "My File.PDF").createNewFile()
        File(dir, "My File_1.PDF.part").createNewFile()
        assertEquals("My File_3.PDF", FileNameResolver.uniqueName(dir, "My File.PDF", setOf("My File_2.PDF")))
        assertEquals("README", FileNameResolver.uniqueName(dir, "README"))
        val long = "a".repeat(251) + ".txt"
        val resolved = FileNameResolver.uniqueName(dir, long, setOf(long))
        assertTrue(resolved.endsWith("_1.txt"))
        assertTrue(resolved.toByteArray().size <= 255)
    }

    @Test fun `explicit format override is the only extension rewrite`() {
        assertEquals("My Audio.mp3", FileNameResolver.sanitiseWithForcedExtension("My Audio.webm", "mp3"))
    }
}
