package com.example.pricecalculator

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.microsoft.appcenter.AppCenter
import com.microsoft.appcenter.analytics.Analytics
import com.microsoft.appcenter.crashes.Crashes
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.regex.Pattern

class MainActivity : AppCompatActivity() {
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var textRecognizer: TextRecognizer
    private lateinit var priceTextView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize AppCenter
        AppCenter.start(
            application,
            "4fd5f314-e3dc-4b1c-baf5-c6c9ae44201b",
            Analytics::class.java,
            Crashes::class.java
        )

        priceTextView = findViewById(R.id.priceTextView)
        
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS
            )
        }

        textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(findViewById<androidx.camera.view.PreviewView>(R.id.viewFinder).surfaceProvider)
                }

            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor, PriceAnalyzer())
                }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageAnalyzer
                )
            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private inner class PriceAnalyzer : ImageAnalysis.Analyzer {
        override fun analyze(imageProxy: ImageProxy) {
            val mediaImage = imageProxy.image
            if (mediaImage != null) {
                val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                
                textRecognizer.process(image)
                    .addOnSuccessListener { visionText ->
                        val text = visionText.text
                        val priceInfo = extractPriceInfo(text)
                        runOnUiThread {
                            priceTextView.text = priceInfo
                        }
                    }
                    .addOnFailureListener { e ->
                        Log.e(TAG, "Text recognition failed", e)
                    }
                    .addOnCompleteListener {
                        imageProxy.close()
                    }
            } else {
                imageProxy.close()
            }
        }
    }

    private fun extractPriceInfo(text: String): String {
        // Паттерны для разных форматов цен
        val patterns = listOf(
            // Цена за граммы (например: 25р за 100г)
            Pattern.compile("(\\d+[.,]?\\d*)\\s*[рр]\\s*(?:за|/)\\s*(\\d+)\\s*г"),
            // Цена за миллилитры (например: 70р за 940мл)
            Pattern.compile("(\\d+[.,]?\\d*)\\s*[рр]\\s*(?:за|/)\\s*(\\d+)\\s*мл"),
            // Цена за килограмм (например: 250р/кг)
            Pattern.compile("(\\d+[.,]?\\d*)\\s*[рр]\\s*/\\s*кг"),
            // Цена за литр (например: 80р/л)
            Pattern.compile("(\\d+[.,]?\\d*)\\s*[рр]\\s*/\\s*л")
        )

        for (pattern in patterns) {
            val matcher = pattern.matcher(text)
            if (matcher.find()) {
                val price = matcher.group(1).replace(",", ".").toDouble()
                
                return when {
                    // Цена за граммы
                    pattern.pattern.contains("г") -> {
                        val grams = matcher.group(2).toInt()
                        val pricePerKg = (price * 1000) / grams
                        String.format("Цена за кг: %.2f ₽", pricePerKg)
                    }
                    // Цена за миллилитры
                    pattern.pattern.contains("мл") -> {
                        val ml = matcher.group(2).toInt()
                        val pricePerL = (price * 1000) / ml
                        String.format("Цена за литр: %.2f ₽", pricePerL)
                    }
                    // Цена за килограмм
                    pattern.pattern.contains("кг") -> {
                        String.format("Цена за кг: %.2f ₽", price)
                    }
                    // Цена за литр
                    pattern.pattern.contains("л") -> {
                        String.format("Цена за литр: %.2f ₽", price)
                    }
                    else -> "Не найдено"
                }
            }
        }
        return "Не найдено"
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    companion object {
        private const val TAG = "PriceCalculator"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }
} 