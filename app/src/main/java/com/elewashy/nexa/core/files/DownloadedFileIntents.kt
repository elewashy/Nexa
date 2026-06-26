package com.elewashy.nexa.core.files

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File

object DownloadedFileIntents {
    const val APK_MIME_TYPE = "application/vnd.android.package-archive"

    fun isApk(file: File, declaredMimeType: String?): Boolean {
        return file.extension.equals("apk", ignoreCase = true) ||
            declaredMimeType.equals(APK_MIME_TYPE, ignoreCase = true)
    }

    fun resolveMimeType(file: File, declaredMimeType: String?, contentResolverMimeType: String? = null): String {
        val extensionMimeType = mimeTypeFromExtension(file.extension)
        val normalizedDeclared = declaredMimeType?.substringBefore(';')?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
        val normalizedResolver = contentResolverMimeType?.substringBefore(';')?.trim()?.lowercase()?.takeIf { it.isNotBlank() }

        if (file.extension.equals("apk", ignoreCase = true) || normalizedDeclared == APK_MIME_TYPE) {
            return APK_MIME_TYPE
        }

        return when {
            extensionMimeType != null -> extensionMimeType
            !normalizedDeclared.isNullOrGenericMimeType() -> normalizedDeclared!!
            !normalizedResolver.isNullOrGenericMimeType() -> normalizedResolver!!
            else -> "*/*"
        }
    }

    fun createViewIntent(context: Context, file: File, declaredMimeType: String?): Intent {
        val fileUri = getUri(context, file)
        val mimeType = resolveMimeType(
            file = file,
            declaredMimeType = declaredMimeType,
            contentResolverMimeType = context.contentResolver.getType(fileUri),
        )
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(fileUri, mimeType)
            clipData = ClipData.newUri(context.contentResolver, file.name, fileUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun getUri(context: Context, file: File): Uri {
        return FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
    }

    private fun mimeTypeFromExtension(extension: String): String? {
        val cleanExtension = extension.trim().lowercase()
        if (cleanExtension.isEmpty()) return null

        COMMON_MIME_TYPES[cleanExtension]?.let { return it }

        return MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(cleanExtension)
            ?.lowercase()
    }

    private fun String?.isNullOrGenericMimeType(): Boolean {
        return this == null || this == "*/*" || this == "application/octet-stream" || this == "binary/octet-stream"
    }

    private val COMMON_MIME_TYPES = mapOf(
        "apk" to APK_MIME_TYPE,
        "mp3" to "audio/mpeg",
        "m4a" to "audio/mp4",
        "aac" to "audio/aac",
        "wav" to "audio/wav",
        "flac" to "audio/flac",
        "ogg" to "audio/ogg",
        "mp4" to "video/mp4",
        "m4v" to "video/mp4",
        "mkv" to "video/x-matroska",
        "webm" to "video/webm",
        "avi" to "video/x-msvideo",
        "mov" to "video/quicktime",
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "png" to "image/png",
        "webp" to "image/webp",
        "gif" to "image/gif",
        "pdf" to "application/pdf",
        "txt" to "text/plain",
        "csv" to "text/csv",
        "json" to "application/json",
        "zip" to "application/zip",
        "rar" to "application/vnd.rar",
        "7z" to "application/x-7z-compressed",
        "doc" to "application/msword",
        "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "xls" to "application/vnd.ms-excel",
        "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "ppt" to "application/vnd.ms-powerpoint",
        "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    )
}
