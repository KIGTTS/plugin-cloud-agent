package com.kigtts.tools.agent

import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.File

/** Tool crop coordinates and model previews share the photograph's displayed orientation. */
internal fun orientAgentBitmap(bitmap: Bitmap, file: File): Bitmap {
    val orientation = runCatching { ExifInterface(file).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) }.getOrDefault(1)
    val matrix = Matrix().apply { when (orientation) {
        2 -> setScale(-1f, 1f); 3 -> setRotate(180f); 4 -> setScale(1f, -1f)
        5 -> { setRotate(90f); postScale(-1f, 1f) }; 6 -> setRotate(90f)
        7 -> { setRotate(-90f); postScale(-1f, 1f) }; 8 -> setRotate(-90f)
    } }
    return if (matrix.isIdentity) bitmap else Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}
