package com.example.hivisionvr

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.ImageProxy

/**
 * Converte um ImageProxy no formato RGBA_8888 (configurado no ImageAnalysis) para Bitmap,
 * descartando o padding de linha (rowStride) que o Android às vezes adiciona.
 */
fun ImageProxy.toBitmap(): Bitmap {
    val plane = planes[0]
    val pixelStride = plane.pixelStride
    val rowStride = plane.rowStride
    val rowPadding = rowStride - pixelStride * width

    val bitmap = Bitmap.createBitmap(
        width + rowPadding / pixelStride,
        height,
        Bitmap.Config.ARGB_8888
    )
    bitmap.copyPixelsFromBuffer(plane.buffer)

    return if (rowPadding == 0) {
        bitmap
    } else {
        Bitmap.createBitmap(bitmap, 0, 0, width, height)
    }
}

fun rotateBitmap(bitmap: Bitmap, degrees: Int): Bitmap {
    if (degrees == 0) return bitmap
    val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}
