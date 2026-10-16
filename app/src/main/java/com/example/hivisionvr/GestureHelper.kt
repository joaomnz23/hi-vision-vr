package com.example.hivisionvr

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizer
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizerResult

/**
 * Encapsula o MediaPipe Gesture Recognizer, usando o modelo pré-treinado padrão do Google
 * (gesture_recognizer.task), que já reconhece "Thumb_Up" nativamente - não precisa treinar
 * nada. Roda em modo LIVE_STREAM (assíncrono, não bloqueia a thread de análise de imagem).
 */
class GestureHelper(
    private val context: Context,
    private val listener: GestureListener,
    private val minConfidence: Float = 0.6f,
    private val modelFileName: String = "gesture_recognizer.task"
) {
    interface GestureListener {
        /** categoryName vem como "Thumb_Up", "Open_Palm", "None", etc. */
        fun onGestureDetected(categoryName: String, score: Float)
        fun onError(message: String)
    }

    private var recognizer: GestureRecognizer? = null

    init {
        try {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath(modelFileName)
                .build()

            val options = GestureRecognizer.GestureRecognizerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setMinHandDetectionConfidence(0.5f)
                .setMinHandPresenceConfidence(0.5f)
                .setMinTrackingConfidence(0.5f)
                .setResultListener(::handleResult)
                .setErrorListener { e -> listener.onError("Erro no reconhecimento de gesto: ${e.message}") }
                .build()

            recognizer = GestureRecognizer.createFromOptions(context, options)
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao carregar '$modelFileName'", e)
            listener.onError(
                "Não foi possível carregar o modelo de gestos. Confira se o arquivo " +
                    "'$modelFileName' está em app/src/main/assets/."
            )
        }
    }

    private fun handleResult(result: GestureRecognizerResult, input: com.google.mediapipe.framework.image.MPImage) {
        val topGesture = result.gestures().firstOrNull()?.firstOrNull() ?: return
        if (topGesture.score() >= minConfidence) {
            listener.onGestureDetected(topGesture.categoryName(), topGesture.score())
        }
    }

    /**
     * Deve ser chamado a partir de uma thread de background. O resultado chega de forma
     * assíncrona no onResults do listener. Não fecha nenhum ImageProxy - quem chama é
     * responsável pelo ciclo de vida do frame original.
     */
    fun recognizeAsync(bitmap: Bitmap, timestampMs: Long) {
        val gestureRecognizer = recognizer ?: return
        try {
            val mpImage = BitmapImageBuilder(bitmap).build()
            gestureRecognizer.recognizeAsync(mpImage, timestampMs)
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao enviar frame para reconhecimento de gesto", e)
        }
    }

    fun close() {
        recognizer?.close()
        recognizer = null
    }

    companion object {
        private const val TAG = "GestureHelper"
    }
}
