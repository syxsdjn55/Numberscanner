package com.example.numberscanner

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var viewFinder: PreviewView
    private lateinit var etResult: EditText
    private var imageCapture: ImageCapture? = null
    private lateinit var cameraExecutor: ExecutorService

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) startCamera()
        else Toast.makeText(this, "需要相机权限才能扫描", Toast.LENGTH_LONG).show()
    }

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri != null) {
            try {
                val content = etResult.text.toString()
                contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(content.toByteArray())
                }
                Toast.makeText(this, "导出成功！", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this, "导出失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        viewFinder = findViewById(R.id.viewFinder)
        etResult = findViewById(R.id.etResult)
        cameraExecutor = Executors.newSingleThreadExecutor()

        findViewById<Button>(R.id.btnScan).setOnClickListener { takePhotoAndRecognize() }
        findViewById<Button>(R.id.btnExport).setOnClickListener {
            val content = etResult.text.toString().trim()
            if (content.isEmpty()) {
                Toast.makeText(this, "空白处还没有数字可导出", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            exportLauncher.launch("扫描数字_${System.currentTimeMillis()}.txt")
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(viewFinder.surfaceProvider)
            }
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
            } catch (e: Exception) {
                Toast.makeText(this, "相机启动失败", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhotoAndRecognize() {
        val imageCapture = imageCapture ?: return

        imageCapture.takePicture(
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(imageProxy: ImageProxy) {
                    try {
                        val bitmap = imageProxy.toBitmap()
                        val matrix = Matrix().apply { postRotate(imageProxy.imageInfo.rotationDegrees.toFloat()) }
                        val rotatedBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)

                        // 只裁剪十字准星所在的中心区域（宽60%，高20%），防止拍到周围无关数字
                        val cropW = (rotatedBitmap.width * 0.6).toInt()
                        val cropH = (rotatedBitmap.height * 0.2).toInt()
                        val cropX = (rotatedBitmap.width - cropW) / 2
                        val cropY = (rotatedBitmap.height - cropH) / 2
                        val croppedBitmap = Bitmap.createBitmap(rotatedBitmap, cropX, cropY, cropW, cropH)

                        val image = InputImage.fromBitmap(croppedBitmap, 0)
                        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

                        recognizer.process(image)
                            .addOnSuccessListener { visionText ->
                                val numbersOnly = visionText.text.replace(Regex("[^0-9]"), "")
                                if (numbersOnly.isNotEmpty()) {
                                    etResult.append("$numbersOnly\n")
                                    etResult.setSelection(etResult.text.length)
                                    Toast.makeText(this@MainActivity, "识别到: $numbersOnly", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(this@MainActivity, "准星处未发现数字", Toast.LENGTH_SHORT).show()
                                }
                            }
                            .addOnFailureListener {
                                Toast.makeText(this@MainActivity, "识别出错", Toast.LENGTH_SHORT).show()
                            }
                            .addOnCompleteListener {
                                imageProxy.close()
                            }
                    } catch (e: Exception) {
                        imageProxy.close()
                    }
                }

                override fun onError(exc: ImageCaptureException) {
                    Toast.makeText(this@MainActivity, "拍照失败", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}
