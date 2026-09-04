package com.elewashy.nexa.feature.downloads.presentation.components

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.vector.ImageVector
import com.elewashy.nexa.R
import com.elewashy.nexa.feature.downloads.domain.model.DownloadFilterCategory
import com.elewashy.nexa.feature.downloads.domain.model.DownloadManagerLayout
import com.elewashy.nexa.ui.icons.Android
import com.elewashy.nexa.ui.icons.AudioFile
import com.elewashy.nexa.ui.icons.Description
import com.elewashy.nexa.ui.icons.FolderZip
import com.elewashy.nexa.ui.icons.Image
import com.elewashy.nexa.ui.icons.InsertDriveFile
import com.elewashy.nexa.ui.icons.VideoFile

/**
 * Single mapping from download domain enums to their user-facing label and
 * glyph, shared by the Media gallery chips, the settings toggles, and the
 * design picker so a category can never be named or drawn differently on two
 * screens.
 */
@get:StringRes
val DownloadFilterCategory.labelRes: Int
    get() = when (this) {
        DownloadFilterCategory.Videos -> R.string.download_filter_videos
        DownloadFilterCategory.Audio -> R.string.download_filter_audio
        DownloadFilterCategory.Images -> R.string.download_filter_images
        DownloadFilterCategory.Apk -> R.string.download_filter_apk
        DownloadFilterCategory.Pdf -> R.string.download_filter_pdf
        DownloadFilterCategory.Archives -> R.string.download_filter_archives
        DownloadFilterCategory.Other -> R.string.download_filter_other
    }

val DownloadFilterCategory.icon: ImageVector
    get() = when (this) {
        DownloadFilterCategory.Videos -> VideoFile
        DownloadFilterCategory.Audio -> AudioFile
        DownloadFilterCategory.Images -> Image
        DownloadFilterCategory.Apk -> Android
        DownloadFilterCategory.Pdf -> Description
        DownloadFilterCategory.Archives -> FolderZip
        DownloadFilterCategory.Other -> InsertDriveFile
    }

@get:StringRes
val DownloadManagerLayout.labelRes: Int
    get() = when (this) {
        DownloadManagerLayout.MediaGallery -> R.string.download_layout_media_gallery
        DownloadManagerLayout.TabbedList -> R.string.download_layout_tabbed_list
    }

@get:StringRes
val DownloadManagerLayout.descriptionRes: Int
    get() = when (this) {
        DownloadManagerLayout.MediaGallery -> R.string.download_layout_media_gallery_desc
        DownloadManagerLayout.TabbedList -> R.string.download_layout_tabbed_list_desc
    }
