package com.example.hivisionvr

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.tensorflow.lite.task.vision.detector.Detection
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.text.Normalizer

class MainActivity :
    AppCompatActivity(),
    ObjectDetectorHelper.DetectorListener,
    GestureHelper.GestureListener,
    BluetoothController.ConnectionListener {

    private lateinit var previewView: PreviewView
    private lateinit var overlayView: OverlayView
    private lateinit var statusText: TextView
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var gestureDetector: GestureDetector
    private val mainHandler = Handler(Looper.getMainLooper())

    private var objectDetectorHelper: ObjectDetectorHelper? = null
    private var gestureHelper: GestureHelper? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var bluetoothController: BluetoothController? = null

    private var frameCounter = 0

    // --- Máquina de estados: gesto -> escuta -> comando -> bluetooth ---------------------
    private enum class AppState { IDLE, LISTENING }
    private var state = AppState.IDLE
    private var thumbsUpStreak = 0
    private var cooldownUntil = 0L
    private val listenTimeoutRunnable = Runnable { onListenTimeout() }

    // --- Calibração física da lente -----------------------------------------------------
    private var lensWidthCm = DEFAULT_LENS_WIDTH_CM
    private var lensOffsetCm = DEFAULT_LENS_OFFSET_CM
    private var calibrationMode = CalibrationMode.WIDTH
    private enum class CalibrationMode { WIDTH, OFFSET }

    private val requestPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            if (grants[Manifest.permission.CAMERA] == true) {
                startCamera()
            } else {
                Toast.makeText(this, "Permissão de câmera é obrigatória.", Toast.LENGTH_LONG).show()
                finish()
                return@registerForActivityResult
            }

            if (grants[Manifest.permission.RECORD_AUDIO] != true) {
                Toast.makeText(
                    this,
                    "Sem permissão de microfone, o comando de voz não vai funcionar.",
                    Toast.LENGTH_LONG
                ).show()
            }

            val bluetoothOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                grants[Manifest.permission.BLUETOOTH_CONNECT] == true
            } else {
                true
            }
            if (bluetoothOk) {
                setupBluetooth()
            } else {
                Toast.makeText(
                    this,
                    "Sem permissão de Bluetooth, o comando não será enviado ao ESP32.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    private val enableBluetoothLauncher =
    registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            setupBluetooth()
        } else {
            updateStatus("Bluetooth continua desligado. O comando não será enviado ao ESP32.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()

        setContentView(R.layout.activity_main)
        previewView = findViewById(R.id.previewView)
        overlayView = findViewById(R.id.overlayView)
        statusText = findViewById(R.id.statusText)

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        lensWidthCm = prefs.getFloat(PREF_WIDTH_CM, DEFAULT_LENS_WIDTH_CM)
        lensOffsetCm = prefs.getFloat(PREF_OFFSET_CM, DEFAULT_LENS_OFFSET_CM)
        applyLensCalibration()
        setupCalibrationGestures()

        cameraExecutor = Executors.newSingleThreadExecutor()
        objectDetectorHelper = ObjectDetectorHelper(context = this, listener = this)
        gestureHelper = GestureHelper(context = this, listener = this)
        setupSpeechRecognizer()

        // Pede TODAS as permissões relevantes já na abertura do app, pra não travar
        // a experiência depois (ex: no meio de um gesto, pedindo microfone na hora).
        val permissionsToRequest = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissionsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        requestPermissionsLauncher.launch(permissionsToRequest.toTypedArray())
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
    }

    // --- Bluetooth -------------------------------------------------------------------------

    private fun setupBluetooth() {
        bluetoothController = BluetoothController(this)
        val bluetoothManager = getSystemService(BluetoothManager::class.java)
        val adapter: BluetoothAdapter? = bluetoothManager?.adapter

        if (adapter == null) {
            updateStatus("Este aparelho não tem Bluetooth.")
            return
        }
        if (!adapter.isEnabled) {
            updateStatus("Bluetooth desligado - ative para falar com o ESP32.")
            enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return
        }
        bluetoothController?.connectToBondedDevice(adapter, ESP32_DEVICE_NAME_CONTAINS)
    }

    override fun onConnected(deviceName: String) {
        runOnUiThread { updateStatus("Conectado ao $deviceName. Aguardando gesto de joinha...") }
    }

    override fun onConnectionFailed(message: String) {
        runOnUiThread {
            updateStatus(message)
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }

    override fun onDisconnected() {
        runOnUiThread { updateStatus("Bluetooth desconectado.") }
    }

    override fun onCommandSent(turnOn: Boolean) {
        runOnUiThread {
            updateStatus(if (turnOn) "✅ Confirmado: LIGA (1) enviado ao ESP32" else "✅ Confirmado: DESLIGA (0) enviado ao ESP32")
        }
    }

    // --- Fluxo: gesto -> voz -> comando -----------------------------------------------------

    override fun onGestureDetected(categoryName: String, score: Float) {
        if (categoryName != "Thumb_Up") {
            thumbsUpStreak = 0
            return
        }

        thumbsUpStreak++
        val now = SystemClock.uptimeMillis()
        if (state == AppState.IDLE && thumbsUpStreak >= REQUIRED_GESTURE_STREAK && now >= cooldownUntil) {
            thumbsUpStreak = 0
            // O callback do MediaPipe (LIVE_STREAM) não garante rodar na thread principal.
            // TextView e SpeechRecognizer só podem ser manipulados na UI thread - sem isso
            // aqui, o app derruba com CalledFromWrongThreadException / IllegalStateException
            // assim que startListeningForCommand() tenta atualizar a tela ou chamar o
            // reconhecedor de voz.
            runOnUiThread { startListeningForCommand() }
        }
    }

    override fun onResults(results: List<Detection>, imageHeight: Int, imageWidth: Int) {
        runOnUiThread { overlayView.setResults(results, imageHeight, imageWidth) }
    }

    override fun onError(message: String) {
        runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
    }

    private fun startListeningForCommand() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            updateStatus("Sem permissão de microfone - não é possível ouvir o comando.")
            return
        }
        val recognizer = speechRecognizer
        if (recognizer == null) {
            updateStatus("Reconhecimento de voz não disponível neste aparelho.")
            return
        }

        state = AppState.LISTENING
        updateStatus("👍 Joinha detectada! Ouvindo... diga \"liga\" ou \"desliga\"")

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        }

        try {
            recognizer.startListening(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao iniciar SpeechRecognizer", e)
            finishListening("Erro ao iniciar o microfone. Tente de novo com a joinha.")
            return
        }

        mainHandler.removeCallbacks(listenTimeoutRunnable)
        mainHandler.postDelayed(listenTimeoutRunnable, LISTEN_TIMEOUT_MS)
    }

    private fun onListenTimeout() {
        if (state != AppState.LISTENING) return
        try {
            speechRecognizer?.stopListening()
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao parar SpeechRecognizer no timeout", e)
        }
        finishListening("Tempo esgotado, nenhum comando reconhecido.")
    }

    private fun finishListening(statusMessage: String) {
        mainHandler.removeCallbacks(listenTimeoutRunnable)
        state = AppState.IDLE
        cooldownUntil = SystemClock.uptimeMillis() + COOLDOWN_MS
        updateStatus(statusMessage)
    }

    private fun setupSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Log.w(TAG, "Reconhecimento de voz não disponível neste aparelho.")
            return
        }
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle) {
                    if (state != AppState.LISTENING) return
                    val matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    handleSpeechResult(matches)
                }

                override fun onError(error: Int) {
                    if (state != AppState.LISTENING) return
                    finishListening("Não entendi o comando (erro $error). Tente de novo com a joinha.")
                }

                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
    }

    private fun handleSpeechResult(matches: ArrayList<String>?) {
        val command = matches
            ?.map { normalize(it) }
            ?.firstNotNullOfOrNull { text ->
                when {
                    text.contains("desliga") -> false
                    text.contains("liga") -> true
                    else -> null
                }
            }

        when (command) {
            true -> {
                bluetoothController?.send(true)
                finishListening("Comando \"liga\" reconhecido, enviando...")
            }
            false -> {
                bluetoothController?.send(false)
                finishListening("Comando \"desliga\" reconhecido, enviando...")
            }
            null -> {
                finishListening("Não reconheci \"liga\" ou \"desliga\". Tente de novo com a joinha.")
            }
        }
    }

    /** Remove acentos e deixa minúsculo, pra "Liga!", "LIGA", "ligá" etc. baterem igual. */
    private fun normalize(text: String): String {
        val noAccents = Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}"), "")
        return noAccents.lowercase(Locale.getDefault())
    }

    private fun updateStatus(message: String) {
        statusText.text = message
    }

    // --- Calibração da lente --------------------------------------------------------------

    private fun setupCalibrationGestures() {
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                calibrationMode = if (calibrationMode == CalibrationMode.WIDTH) {
                    CalibrationMode.OFFSET
                } else {
                    CalibrationMode.WIDTH
                }
                showCalibrationToast()
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                lensWidthCm = DEFAULT_LENS_WIDTH_CM
                lensOffsetCm = DEFAULT_LENS_OFFSET_CM
                saveCalibration()
                applyLensCalibration()
                Toast.makeText(this@MainActivity, "Calibração restaurada ao padrão (6.7cm)", Toast.LENGTH_SHORT).show()
            }
        })

        previewView.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            true
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val step = 0.05f
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                adjustCalibration(step)
                return true
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                adjustCalibration(-step)
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun adjustCalibration(deltaCm: Float) {
        if (calibrationMode == CalibrationMode.WIDTH) {
            lensWidthCm = (lensWidthCm + deltaCm).coerceIn(3f, 12f)
        } else {
            lensOffsetCm = (lensOffsetCm + deltaCm).coerceIn(-3f, 3f)
        }
        saveCalibration()
        applyLensCalibration()
        showCalibrationToast()
    }

    private fun saveCalibration() {
        prefs.edit()
            .putFloat(PREF_WIDTH_CM, lensWidthCm)
            .putFloat(PREF_OFFSET_CM, lensOffsetCm)
            .apply()
    }

    private fun showCalibrationToast() {
        val modeLabel = if (calibrationMode == CalibrationMode.WIDTH) "LARGURA" else "DESLOCAMENTO"
        Toast.makeText(
            this,
            "Calibrando: $modeLabel  |  largura=%.2fcm  deslocamento=%.2fcm".format(lensWidthCm, lensOffsetCm),
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun applyLensCalibration() {
        val widthPx = cmToPx(lensWidthCm)
        val offsetPx = cmToPx(lensOffsetCm)
        val params = previewView.layoutParams as ConstraintLayout.LayoutParams
        params.width = widthPx
        params.marginStart = offsetPx
        previewView.layoutParams = params
    }

    private fun cmToPx(cm: Float): Int {
        val xdpi = resources.displayMetrics.xdpi
        return ((cm / 2.54f) * xdpi).toInt()
    }

    // --- Sistema -----------------------------------------------------------------------------

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    // --- Câmera + análise de frame (objetos + gesto compartilhando o mesmo frame) -----------

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }

            // CameraX só permite 1 ImageAnalysis por vez, então os dois modelos
            // (objetos e gesto) compartilham o MESMO frame já convertido pra bitmap,
            // em vez de disputar dois use cases de análise separados.
            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor) { imageProxy -> analyzeFrame(imageProxy) }
                }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis)
            } catch (exc: Exception) {
                Log.e(TAG, "Falha ao vincular casos de uso da câmera", exc)
                Toast.makeText(this, "Não foi possível iniciar a câmera neste dispositivo.", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyzeFrame(imageProxy: androidx.camera.core.ImageProxy) {
        try {
            val bitmap = imageProxy.toBitmap()
            val rotated = rotateBitmap(bitmap, imageProxy.imageInfo.rotationDegrees)

            // Gesto roda em TODO frame (precisa ser responsivo pro "joinha" ser pego rápido).
            gestureHelper?.recognizeAsync(rotated, SystemClock.uptimeMillis())

            // Detecção de objetos é mais pesada; roda a cada 3 frames pra sobrar
            // processamento pro reconhecimento de gesto e não acumular atraso.
            frameCounter = (frameCounter + 1) % OBJECT_DETECTION_FRAME_SKIP
            if (frameCounter == 0) {
                objectDetectorHelper?.detectOnBitmap(rotated)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao processar frame", e)
        } finally {
            imageProxy.close()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacks(listenTimeoutRunnable)
        cameraExecutor.shutdown()
        objectDetectorHelper?.close()
        gestureHelper?.close()
        speechRecognizer?.destroy()
        bluetoothController?.close()
    }

    companion object {
        private const val TAG = "HiVisionVR"
        private const val PREFS_NAME = "hivisionvr_prefs"
        private const val PREF_WIDTH_CM = "lens_width_cm"
        private const val PREF_OFFSET_CM = "lens_offset_cm"
        private const val DEFAULT_LENS_WIDTH_CM = 6.7f
        private const val DEFAULT_LENS_OFFSET_CM = 0f

        // Quantos frames seguidos de "Thumb_Up" são exigidos antes de disparar a escuta
        // (evita disparo por 1 frame ruidoso/falso positivo)
        private const val REQUIRED_GESTURE_STREAK = 5

        // Quanto tempo esperar por um comando de voz antes de desistir
        private const val LISTEN_TIMEOUT_MS = 6000L

        // Tempo mínimo entre um comando processado e o próximo gesto poder disparar de novo
        private const val COOLDOWN_MS = 3000L

        // Roda a detecção de objetos a cada N frames (1 = todo frame, 3 = 1 em cada 3)
        private const val OBJECT_DETECTION_FRAME_SKIP = 3

        // AJUSTE AQUI: parte do nome do dispositivo Bluetooth pareado do seu ESP32
        private const val ESP32_DEVICE_NAME_CONTAINS = "ESP32"
    }
}
