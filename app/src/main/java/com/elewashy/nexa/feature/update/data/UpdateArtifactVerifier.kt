package com.elewashy.nexa.feature.update.data

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import java.io.File
import java.security.MessageDigest

/**
 * Integrity verification for self-update APKs.
 *
 * Two independent checks:
 *  - SHA-256 of the downloaded file against the checksum asset published in
 *    the GitHub release (when the release publishes one).
 *  - Signing certificate of the APK file compared against the signing
 *    certificate of the installed app. This runs ALWAYS, checksum or not:
 *    a checksum proves the file is the one the release intended, the signing
 *    certificate proves it can actually replace the running app and was
 *    signed with the same key.
 *
 * RESIDUAL RISK: when a release publishes no SHA-256 checksum asset, the
 * download cannot be verified by hash. The install is still allowed (a missing
 * checksum must not block updates), but only after the signing-certificate
 * check passes, and a warning is logged (see `UpdateViewModel.verifyChecksum`).
 * The signing check limits the blast radius to APKs signed with the app's own
 * key, but it does not prove the bytes are the exact asset the release shipped.
 */
object UpdateArtifactVerifier {

    /** Hex-encoded SHA-256 of [file], computed with streaming reads. */
    fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHex()
    }

    /**
     * Extracts the expected SHA-256 for [apkName] from checksum-file content.
     *
     * Handles both single-hash files (`<hash>` or `<hash> <name>`) and
     * multi-entry `checksums.txt` / `SHA256SUMS` style files. Returns null
     * when no 64-char hex digest applies to [apkName].
     */
    fun parseExpectedSha256(checksumContent: String, apkName: String): String? {
        val lines = checksumContent.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
        if (lines.isEmpty()) return null

        // Only a line that explicitly names this APK may match. A bare-hash
        // line is ambiguous in a multi-entry file (it could belong to any
        // asset), so it must never match here — the single-entry fallback
        // below is the only place a bare hash is accepted.
        for (line in lines) {
            val hash = SHA256_REGEX.find(line)?.value ?: continue
            // "hash  filename" or "hash *filename" (binary marker).
            val remainder = line.removePrefix(hash).trim().removePrefix("*").trim()
            if (remainder.isNotEmpty() && remainder.substringAfterLast('/') == apkName) {
                return hash.lowercase()
            }
        }

        // Single-entry file without a filename: accept its only hash.
        if (lines.size == 1) {
            return SHA256_REGEX.find(lines[0])?.value?.lowercase()
        }
        return null
    }

    /**
     * SHA-256 hashes of the installed app's signing certificates, including
     * the signing history (covers key rotation).
     */
    fun installedSignerHashes(context: Context): Set<String> {
        val packageManager = context.packageManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = try {
                packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.GET_SIGNING_CERTIFICATES
                )
            } catch (e: PackageManager.NameNotFoundException) {
                return emptySet()
            }
            val signingInfo = info.signingInfo ?: return emptySet()
            val signers = signingInfo.apkContentsSigners.orEmpty().toMutableList()
            if (signingInfo.hasMultipleSigners()) {
                signingInfo.signingCertificateHistory?.let { signers.addAll(it) }
            }
            signers.mapTo(mutableSetOf()) { it.hashHex() }
        } else {
            @Suppress("DEPRECATION")
            val info = try {
                packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.GET_SIGNATURES
                )
            } catch (e: PackageManager.NameNotFoundException) {
                return emptySet()
            }
            @Suppress("DEPRECATION")
            info.signatures.orEmpty().mapTo(mutableSetOf()) { it.hashHex() }
        }
    }

    /**
     * SHA-256 hashes of the APK file's signing certificates, read via
     * [PackageManager.getPackageArchiveInfo] (public API, no PackageParser).
     * Returns an empty set when the file cannot be parsed as a signed APK.
     */
    fun apkSignerHashes(context: Context, apkFile: File): Set<String> {
        val path = apkFile.absolutePath
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = context.packageManager.getPackageArchiveInfo(
                path,
                PackageManager.GET_SIGNING_CERTIFICATES
            ) ?: return emptySet()
            info.signingInfo
                ?.apkContentsSigners
                .orEmpty()
                .mapTo(mutableSetOf()) { it.hashHex() }
        } else {
            @Suppress("DEPRECATION")
            val info = context.packageManager.getPackageArchiveInfo(
                path,
                PackageManager.GET_SIGNATURES
            ) ?: return emptySet()
            @Suppress("DEPRECATION")
            info.signatures.orEmpty().mapTo(mutableSetOf()) { it.hashHex() }
        }
    }

    /**
     * True when at least one signer of [apkFile] matches a signer of the
     * installed app. Both sides must resolve to at least one certificate.
     */
    fun isSignedByInstalledAppSigner(context: Context, apkFile: File): Boolean {
        val installed = installedSignerHashes(context)
        if (installed.isEmpty()) return false
        val apkSigners = apkSignerHashes(context, apkFile)
        return apkSigners.isNotEmpty() && apkSigners.any { it in installed }
    }

    private fun Signature.hashHex(): String =
        MessageDigest.getInstance("SHA-256").digest(toByteArray()).toHex()

    private fun ByteArray.toHex(): String =
        joinToString(separator = "") { "%02x".format(it) }

    private val SHA256_REGEX = Regex("\\b[a-fA-F0-9]{64}\\b")
}
