package com.example.hivisionvr

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import org.tensorflow.lite.task.vision.detector.Detection
import kotlin.math.max

/**
 * Desenha, por cima da PreviewView, um retângulo colorido + rótulo + confiança (%) para
 * cada objeto detectado. Sempre tem exatamente o mesmo tamanho/posição da PreviewView
 * (garantido pelas constraints no XML), então não precisa lidar com o deslocamento da
 * área de 6.7cm — só reproduz a mesma escala "fillCenter" que a PreviewView usa.
 */
class OverlayView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    private var detections: List<Detection> = emptyList()
    private var imageWidth = 1
    private var imageHeight = 1

    private val boxPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        isAntiAlias = true
    }

    private val labelBackgroundPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val labelTextPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.FILL
        textSize = 38f
        isAntiAlias = true
    }

    // Uma cor fixa por classe conhecida, pra você reconhecer de longe sem precisar ler o texto
    private val classColors = mapOf(
        "person" to Color.parseColor("#FF3B30"),
        "tv" to Color.parseColor("#34C759"),
        "mouse" to Color.parseColor("#007AFF"),
        "keyboard" to Color.parseColor("#FF9500"),
        "cell phone" to Color.parseColor("#AF52DE"),
        "book" to Color.parseColor("#5AC8FA"),
        "laptop" to Color.parseColor("#FFCC00")
    )
    private val fallbackColors = listOf(
        Color.parseColor("#FF2D55"), Color.parseColor("#4CD964"),
        Color.parseColor("#5856D6"), Color.parseColor("#00C7BE")
    )

    private val labelsPtBr = mapOf(
        "person" to "Pessoa",
        "tv" to "TV",
        "mouse" to "Mouse",
        "keyboard" to "Teclado",
        "cell phone" to "Celular",
        "book" to "Livro",
        "laptop" to "Notebook"
    )

    /** Chamado pela MainActivity a cada resultado novo do detector. */
    fun setResults(newDetections: List<Detection>, imgHeight: Int, imgWidth: Int) {
        detections = newDetections
        imageHeight = imgHeight
        imageWidth = imgWidth
        invalidate()
    }

    fun clear() {
        detections = emptyList()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (detections.isEmpty() || imageWidth <= 0 || imageHeight <= 0) return

        // Mesma matemática do scaleType="fillCenter" da PreviewView: a imagem é
        // escalada até cobrir totalmente a view (mantendo proporção) e depois as
        // bordas que sobram são cortadas. Precisamos aplicar o mesmo corte aqui pra
        // as caixas caírem exatamente em cima dos objetos reais.
        val scale = max(width.toFloat() / imageWidth, height.toFloat() / imageHeight)
        val scaledImageWidth = imageWidth * scale
        val scaledImageHeight = imageHeight * scale
        val offsetX = (scaledImageWidth - width) / 2f
        val offsetY = (scaledImageHeight - height) / 2f

        for (detection in detections) {
            val category = detection.categories.firstOrNull() ?: continue
            val color = classColors[category.label]
                ?: fallbackColors[(category.label.hashCode() and 0x7fffffff) % fallbackColors.size]

            boxPaint.color = color
            labelBackgroundPaint.color = color

            val box = detection.boundingBox
            val mapped = RectF(
                box.left * scale - offsetX,
                box.top * scale - offsetY,
                box.right * scale - offsetX,
                box.bottom * scale - offsetY
            )
            canvas.drawRect(mapped, boxPaint)

            val labelText = "${labelsPtBr[category.label] ?: category.label} ${(category.score * 100).toInt()}%"
            val textWidth = labelTextPaint.measureText(labelText)
            val labelTop = max(0f, mapped.top - 46f)
            canvas.drawRect(mapped.left, labelTop, mapped.left + textWidth + 16f, mapped.top, labelBackgroundPaint)
            canvas.drawText(labelText, mapped.left + 8f, mapped.top - 10f, labelTextPaint)
        }
    }
}
