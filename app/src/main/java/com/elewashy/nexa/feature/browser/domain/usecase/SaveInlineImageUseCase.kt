package com.elewashy.nexa.feature.browser.domain.usecase

import android.net.Uri
import com.elewashy.nexa.feature.browser.data.image.InlineImageRepository
import javax.inject.Inject

/** Why an inline image is being saved; decides the gallery file name. */
enum class InlineImagePurpose(internal val filePrefix: String) {
    SAVE("Nexa_Image"),
    SHARE("Nexa_Share"),
}

/**
 * Saves an inline (`data:` URL) image from a page to the gallery, off the main thread.
 *
 * @return the saved image's content URI, or `null` if the payload is not a decodable image or
 *   the write failed.
 */
class SaveInlineImageUseCase @Inject constructor(
    private val repository: InlineImageRepository,
) {
    suspend operator fun invoke(
        dataUrl: String,
        purpose: InlineImagePurpose,
        timestampMillis: Long = System.currentTimeMillis(),
    ): Uri? = repository.saveToGallery(dataUrl, "${purpose.filePrefix}_$timestampMillis")
}
