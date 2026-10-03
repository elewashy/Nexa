package com.elewashy.nexa.feature.browser.presentation.webview

import android.os.Bundle
import android.util.Log
import android.webkit.WebBackForwardList
import android.webkit.WebView
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

/**
 * Captures and restores a tab WebView's complete navigation state — the back/forward list plus
 * per-entry page state — so a tab reopens exactly as it was after the app is closed or killed.
 *
 * `WebView.saveState` fills a [Bundle] with the engine's own versioned, self-validating snapshot.
 * A Bundle must never be persisted with `Parcel.marshall` (the format is not stable across platform
 * releases), so [encode] writes the Bundle's entries with a small explicit, versioned format.
 * A value type the format does not know makes capture fail (nothing is stored) instead of
 * producing a partial snapshot; any malformed input makes [decode] return null. In both cases the
 * caller falls back to loading the tab's persisted URL.
 *
 * Main thread only (WebView threading contract).
 */
object WebViewSessionState {

    private const val TAG = "WebViewSessionState"
    private const val MAGIC = 0x4E58_5753 // "NXWS"
    private const val FORMAT_VERSION = 1
    private const val MAX_ENTRIES = 64

    private const val TYPE_BYTE_ARRAY: Byte = 1
    private const val TYPE_STRING: Byte = 2
    private const val TYPE_INT: Byte = 3
    private const val TYPE_LONG: Byte = 4
    private const val TYPE_BOOLEAN: Byte = 5

    /** Snapshot of [webView]'s navigation state, or null when there is nothing restorable. */
    fun capture(webView: WebView): ByteArray? = try {
        val bundle = Bundle()
        val history = webView.saveState(bundle)
        if (history == null || history.size == 0) null else encode(bundle)
    } catch (e: RuntimeException) {
        Log.w(TAG, "WebView state capture failed", e)
        null
    }

    /**
     * Restores [state] into a freshly created [webView] that has not loaded anything yet.
     * Returns the restored history, or null when the snapshot was unusable.
     */
    fun restore(webView: WebView, state: ByteArray): WebBackForwardList? {
        val bundle = decode(state) ?: return null
        return try {
            webView.restoreState(bundle)?.takeIf { it.size > 0 }
        } catch (e: RuntimeException) {
            Log.w(TAG, "WebView state restore failed", e)
            null
        }
    }

    /** Bundle → bytes. Null when the Bundle holds a value type this format does not support. */
    internal fun encode(bundle: Bundle): ByteArray? {
        val keys = bundle.keySet()
        if (keys.isEmpty() || keys.size > MAX_ENTRIES) return null
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(FORMAT_VERSION)
            out.writeInt(keys.size)
            for (key in keys) {
                out.writeSizedBytes(key.toByteArray(Charsets.UTF_8))
                @Suppress("DEPRECATION") // Typed getters need the type, which is what is being discovered.
                when (val value = bundle.get(key)) {
                    is ByteArray -> {
                        out.writeByte(TYPE_BYTE_ARRAY.toInt())
                        out.writeSizedBytes(value)
                    }
                    is String -> {
                        out.writeByte(TYPE_STRING.toInt())
                        out.writeSizedBytes(value.toByteArray(Charsets.UTF_8))
                    }
                    is Int -> {
                        out.writeByte(TYPE_INT.toInt())
                        out.writeInt(value)
                    }
                    is Long -> {
                        out.writeByte(TYPE_LONG.toInt())
                        out.writeLong(value)
                    }
                    is Boolean -> {
                        out.writeByte(TYPE_BOOLEAN.toInt())
                        out.writeBoolean(value)
                    }
                    else -> {
                        Log.w(TAG, "Unsupported WebView state value for key $key")
                        return null
                    }
                }
            }
        }
        return buffer.toByteArray()
    }

    /** Bytes → Bundle. Null for anything that is not a complete, well-formed snapshot. */
    internal fun decode(bytes: ByteArray): Bundle? = try {
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            if (input.readInt() != MAGIC || input.readInt() != FORMAT_VERSION) return null
            val count = input.readInt()
            if (count !in 1..MAX_ENTRIES) return null
            val bundle = Bundle(count)
            repeat(count) {
                val key = String(input.readSizedBytes(bytes.size), Charsets.UTF_8)
                when (input.readByte()) {
                    TYPE_BYTE_ARRAY -> bundle.putByteArray(key, input.readSizedBytes(bytes.size))
                    TYPE_STRING -> bundle.putString(key, String(input.readSizedBytes(bytes.size), Charsets.UTF_8))
                    TYPE_INT -> bundle.putInt(key, input.readInt())
                    TYPE_LONG -> bundle.putLong(key, input.readLong())
                    TYPE_BOOLEAN -> bundle.putBoolean(key, input.readBoolean())
                    else -> return null
                }
            }
            // Trailing garbage means the blob is not one of ours.
            if (input.read() != -1) null else bundle
        }
    } catch (e: IOException) {
        null
    }

    private fun DataOutputStream.writeSizedBytes(value: ByteArray) {
        writeInt(value.size)
        write(value)
    }

    private fun DataInputStream.readSizedBytes(limit: Int): ByteArray {
        val size = readInt()
        if (size < 0 || size > limit) throw IOException("Invalid length $size")
        return ByteArray(size).also(::readFully)
    }
}
