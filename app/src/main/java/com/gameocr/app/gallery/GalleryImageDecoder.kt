package com.gameocr.app.gallery

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

data class GalleryDecodedImage(
    val bitmap: Bitmap,
    val originalWidth: Int,
    val originalHeight: Int,
)

@Singleton
class GalleryImageDecoder @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun decode(item: GalleryTranslationItemEntity, maxDimension: Int = MAX_OCR_DIMENSION): GalleryDecodedImage {
        require(maxDimension > 0)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open(item).use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) {
            "Unsupported or damaged image."
        }

        val orientation = readExifOrientation(item)
        val sampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxDimension)
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = open(item).use { input ->
            requireNotNull(BitmapFactory.decodeStream(input, null, options)) {
                "Unable to decode image."
            }
        }
        val oriented = applyExifOrientation(decoded, orientation)
        val swapsDimensions = orientation in setOf(
            ExifInterface.ORIENTATION_TRANSPOSE,
            ExifInterface.ORIENTATION_ROTATE_90,
            ExifInterface.ORIENTATION_TRANSVERSE,
            ExifInterface.ORIENTATION_ROTATE_270,
        )
        return GalleryDecodedImage(
            bitmap = oriented,
            originalWidth = if (swapsDimensions) bounds.outHeight else bounds.outWidth,
            originalHeight = if (swapsDimensions) bounds.outWidth else bounds.outHeight,
        )
    }

    fun decodeThumbnail(
        sourceUri: String,
        localPath: String,
        maxDimension: Int = THUMBNAIL_DIMENSION,
    ): Bitmap? = runCatching {
        decode(
            GalleryTranslationItemEntity(
                id = "",
                taskId = "",
                position = 0,
                sourceUri = sourceUri,
                localPath = localPath,
                displayName = "",
                updatedAtMs = 0,
            ),
            maxDimension = maxDimension,
        ).bitmap
    }.getOrNull()

    fun decodePreview(
        sourceUri: String,
        localPath: String,
        maxDimension: Int = PREVIEW_DIMENSION,
    ): Bitmap? = runCatching {
        decode(
            GalleryTranslationItemEntity(
                id = "",
                taskId = "",
                position = 0,
                sourceUri = sourceUri,
                localPath = localPath,
                displayName = "",
                updatedAtMs = 0,
            ),
            maxDimension = maxDimension,
        ).bitmap
    }.getOrNull()

    private fun open(item: GalleryTranslationItemEntity): InputStream {
        if (item.localPath.isNotBlank()) return FileInputStream(File(item.localPath))
        return requireNotNull(context.contentResolver.openInputStream(Uri.parse(item.sourceUri))) {
            "Selected image is no longer accessible."
        }
    }

    private fun readExifOrientation(item: GalleryTranslationItemEntity): Int = runCatching {
        open(item).use { input ->
            ExifInterface(input).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        }
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    private fun applyExifOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
                matrix.setRotate(180f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.setRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.setRotate(-90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
            else -> return bitmap
        }
        val transformed = Bitmap.createBitmap(
            bitmap,
            0,
            0,
            bitmap.width,
            bitmap.height,
            matrix,
            true,
        )
        if (transformed !== bitmap) bitmap.recycle()
        return transformed
    }

    private fun sampleSize(width: Int, height: Int, maxDimension: Int): Int {
        var sample = 1
        while (maxOf(width / sample, height / sample) > maxDimension && sample <= 32) {
            sample *= 2
        }
        return sample
    }

    private companion object {
        const val MAX_OCR_DIMENSION = 3072
        const val THUMBNAIL_DIMENSION = 160
        const val PREVIEW_DIMENSION = 3072
    }
}
