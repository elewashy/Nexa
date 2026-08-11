package com.elewashy.nexa.feature.update.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * JVM tests for the pure parts of [UpdateArtifactVerifier]: streaming
 * SHA-256 hashing and checksum-file parsing. The signing-certificate checks
 * need a real PackageManager and are intentionally not covered here.
 */
class UpdateArtifactVerifierTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun fileWith(content: ByteArray): File =
        tempFolder.newFile().apply { writeBytes(content) }

    // ── sha256Hex ───────────────────────────────────────────────────────

    @Test
    fun `sha256Hex matches known digest for small content`() {
        val file = fileWith("abc".toByteArray())
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            UpdateArtifactVerifier.sha256Hex(file)
        )
    }

    @Test
    fun `sha256Hex matches known digest for empty file`() {
        val file = fileWith(ByteArray(0))
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            UpdateArtifactVerifier.sha256Hex(file)
        )
    }

    @Test
    fun `sha256Hex streams content larger than one buffer`() {
        // 100 KB exercises the buffered read loop (buffer is 8 KB).
        val file = fileWith(ByteArray(100_000) { 'a'.code.toByte() })
        assertEquals(
            "6d1cf22d7cc09b085dfc25ee1a1f3ae0265804c607bc2074ad253bcc82fd81ee",
            UpdateArtifactVerifier.sha256Hex(file)
        )
    }

    // ── parseExpectedSha256 (also pure) ─────────────────────────────────

    private val abcHash = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    private val emptyHash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

    @Test
    fun `bare single hash applies to any apk name`() {
        assertEquals(abcHash, UpdateArtifactVerifier.parseExpectedSha256(abcHash, "Nexa.apk"))
        assertEquals(abcHash, UpdateArtifactVerifier.parseExpectedSha256("$abcHash\n", "Anything.apk"))
    }

    @Test
    fun `hash with matching filename is returned lowercased`() {
        val content = "${abcHash.uppercase()}  Nexa_V1.2.0.apk"
        assertEquals(abcHash, UpdateArtifactVerifier.parseExpectedSha256(content, "Nexa_V1.2.0.apk"))
    }

    @Test
    fun `binary marker and path prefixes are handled`() {
        assertEquals(abcHash, UpdateArtifactVerifier.parseExpectedSha256("$abcHash *Nexa.apk", "Nexa.apk"))
        assertEquals(abcHash, UpdateArtifactVerifier.parseExpectedSha256("$abcHash  dist/release/Nexa.apk", "Nexa.apk"))
    }

    @Test
    fun `multi entry files only match the named apk`() {
        val content = "$abcHash  other.apk\n$emptyHash  Nexa.apk\n"
        assertEquals(emptyHash, UpdateArtifactVerifier.parseExpectedSha256(content, "Nexa.apk"))
        assertNull(UpdateArtifactVerifier.parseExpectedSha256(content, "missing.apk"))
    }

    @Test
    fun `bare hash line in a multi entry file matches no apk`() {
        // A bare hash could belong to any asset of the release (apk, source
        // tarballs, …), so it must never be attributed to an APK in a
        // multi-entry file — only the single-entry fallback accepts bare hashes.
        val content = "$abcHash\n$emptyHash  Nexa.apk\n"
        assertEquals(emptyHash, UpdateArtifactVerifier.parseExpectedSha256(content, "Nexa.apk"))
        assertNull(UpdateArtifactVerifier.parseExpectedSha256(content, "missing.apk"))
        assertNull(UpdateArtifactVerifier.parseExpectedSha256(content, "other.apk"))
    }

    @Test
    fun `all bare multi entry file matches no apk`() {
        val content = "$abcHash\n$emptyHash\n"
        assertNull(UpdateArtifactVerifier.parseExpectedSha256(content, "Nexa.apk"))
        assertNull(UpdateArtifactVerifier.parseExpectedSha256(content, "anything.apk"))
    }

    @Test
    fun `comments and blanks do not count as entries for the single entry fallback`() {
        // Still a single-ENTRY file once comments/blanks are filtered, so the
        // bare hash is accepted.
        val content = "# release checksums\n\n$abcHash\n"
        assertEquals(abcHash, UpdateArtifactVerifier.parseExpectedSha256(content, "Nexa.apk"))
    }

    // NOTE: when a release PUBLISHES a checksum asset but none of its entries
    // applies to the APK, parseExpectedSha256 returns null — and
    // UpdateViewModel.verifyChecksum is REQUIRED to fail closed (throw
    // UpdateVerificationException) instead of degrading to signature-only
    // verification. That enforcement is wired to the ViewModel's OkHttp fetch
    // and localized error strings, so it is not JVM-testable here; the null
    // contract it depends on is pinned by the tests above.

    @Test
    fun `comments and blank lines are ignored`() {
        val content = "# checksums\n\n$abcHash  Nexa.apk\n"
        assertEquals(abcHash, UpdateArtifactVerifier.parseExpectedSha256(content, "Nexa.apk"))
    }

    @Test
    fun `content without a digest yields null`() {
        assertNull(UpdateArtifactVerifier.parseExpectedSha256("", "Nexa.apk"))
        assertNull(UpdateArtifactVerifier.parseExpectedSha256("not a hash", "Nexa.apk"))
        assertNull(UpdateArtifactVerifier.parseExpectedSha256("abcd1234", "Nexa.apk"))
    }
}
