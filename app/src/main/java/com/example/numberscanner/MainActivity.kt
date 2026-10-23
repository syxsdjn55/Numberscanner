package com.example.numberscanner

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
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
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var viewFinder: PreviewView
    private lateinit var etResult: EditText
    private lateinit var cbNumbersOnly: CheckBox
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
        cbNumbersOnly = findViewById(R.id.cbNumbersOnly)
        cameraExecutor = Executors.newSingleThreadExecutor()

        findViewById<Button>(R.id.btnScan).setOnClickListener { takePhotoAndRecognize() }
        findViewById<Button>(R.id.btnExport).setOnClickListener {
            val content = etResult.text.toString().trim()
            if (content.isEmpty()) {
                Toast.makeText(this, "空白处还没有内容可导出", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            exportLauncher.launch("扫描结果_${System.currentTimeMillis()}.txt")
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

                        // 截取十字准星中心区域
                        val cropW = (rotatedBitmap.width * 0.65).toInt()
                        val cropH = (rotatedBitmap.height * 0.22).toInt()
                        val cropX = (rotatedBitmap.width - cropW) / 2
                        val cropY = (rotatedBitmap.height - cropH) / 2
                        val croppedBitmap = Bitmap.createBitmap(rotatedBitmap, cropX, cropY, cropW, cropH)

                        val image = InputImage.fromBitmap(croppedBitmap, 0)
                        // 使用支持中文+英文+数字的识别器
                        val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())

                        recognizer.process(image)
                            .addOnSuccessListener { visionText ->
                                val rawText = visionText.text.trim()
                                // 判断是否勾选了“仅提取纯数字”
                                val resultText = if (cbNumbersOnly.isChecked) {
                                    rawText.replace(Regex("[^0-9]"), "")
                                } else {
                                    // 文本模式：将多行换行合并为空格，保证每次扫描占一行
                                    rawText.replace(Regex("[\\r\\n]+"), " ")
                                }

                                if (resultText.isNotEmpty()) {
                                    etResult.append("$resultText\n")
                                    etResult.setSelection(etResult.text.length)
                                    Toast.makeText(this@MainActivity, "识别到: $resultText", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(this@MainActivity, "准星处未发现对应内容", Toast.LENGTH_SHORT).show()
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
