package com.example.hivisionvr

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.camera.core.ImageProxy
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.task.core.BaseOptions
import org.tensorflow.lite.task.vision.detector.Detection
import org.tensorflow.lite.task.vision.detector.ObjectDetector

/**
 * Encapsula o carregamento do modelo EfficientDet-Lite0 (treinado no dataset COCO) e a
 * inferência sobre cada frame da câmera. Usa a TensorFlow Lite Task Library, que já lê os
 * rótulos e parâmetros de pré-processamento direto dos metadados embutidos no .tflite.
 *
 * O modelo detecta com boa confiabilidade: pessoa, tv, mouse, teclado, celular, livro,
 * notebook, entre outras classes do COCO. Não detecta caneta/lápis - o COCO não tem essas
 * classes; isso exigiria treinar um modelo customizado à parte.
 */
class ObjectDetectorHelper(
    private val context: Context,
    private val listener: DetectorListener,
    private val maxResults: Int = 5,
    private val scoreThreshold: Float = 0.5f,
    private val modelFileName: String = "efficientdet_lite0.tflite"
) {
    interface DetectorListener {
        fun onResults(results: List<Detection>, imageHeight: Int, imageWidth: Int)
        fun onError(message: String)
    }

    private var objectDetector: ObjectDetector? = null

    init {
        setupDetector()
    }

    private fun setupDetector() {
        try {
            val baseOptions = BaseOptions.builder()
                .setNumThreads(4)
                .build()

            val options = ObjectDetector.ObjectDetectorOptions.builder()
                .setBaseOptions(baseOptions)
                .setMaxResults(maxResults)
                .setScoreThreshold(scoreThreshold)
                .build()

            objectDetector = ObjectDetector.createFromFileAndOptions(context, modelFileName, options)
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao carregar o modelo '$modelFileName'", e)
            listener.onError(
                "Não foi possível carregar o modelo de detecção. Confira se o arquivo " +
                    "'$modelFileName' está em app/src/main/assets/."
            )
        }
    }

    /** Deve ser chamado a partir de uma thread de background (não a UI thread). Fecha o imageProxy sozinho. */
    fun detect(imageProxy: ImageProxy) {
        try {
            val bitmap = imageProxy.toBitmap()
            val rotated = rotateBitmap(bitmap, imageProxy.imageInfo.rotationDegrees)
            detectOnBitmap(rotated)
        } catch (e: Exception) {
            Log.e(TAG, "Erro durante a inferência", e)
        } finally {
            imageProxy.close()
        }
    }

    /** Versão que recebe um bitmap já rotacionado/pronto (usada quando o frame é compartilhado com outro modelo). */
    fun detectOnBitmap(rotatedBitmap: Bitmap) {
        val detector = objectDetector ?: return
        try {
            val tensorImage = TensorImage.fromBitmap(rotatedBitmap)
            val results = detector.detect(tensorImage)
            listener.onResults(results, rotatedBitmap.height, rotatedBitmap.width)
        } catch (e: Exception) {
            Log.e(TAG, "Erro durante a inferência", e)
        }
    }

    fun close() {
        objectDetector?.close()
        objectDetector = null
    }

    companion object {
        private const val TAG = "ObjectDetectorHelper"
    }
}
