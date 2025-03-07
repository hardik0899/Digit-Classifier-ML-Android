package org.tensorflow.lite.examples.digitclassifier

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.divyanshu.draw.widget.DrawView
import com.google.android.gms.tasks.Task
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.analytics.ktx.analytics
import com.google.firebase.ktx.Firebase
import com.google.firebase.ml.common.modeldownload.FirebaseModelDownloadConditions
import com.google.firebase.ml.common.modeldownload.FirebaseModelManager
import com.google.firebase.ml.custom.FirebaseCustomRemoteModel
import com.google.firebase.perf.FirebasePerformance
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.ktx.remoteConfig
import com.google.firebase.remoteconfig.ktx.remoteConfigSettings
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

class MainActivity : AppCompatActivity() {

  private var drawView: DrawView? = null
  private var clearButton: Button? = null
  private var yesButton: Button? = null
  private var predictedTextView: TextView? = null
  private var digitClassifier = DigitClassifier(this)
  private var firebasePerformance = FirebasePerformance.getInstance()
  private lateinit var remoteConfig: FirebaseRemoteConfig

  @SuppressLint("ClickableViewAccessibility")
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.tfe_dc_activity_main)

    // Setup view instances
    drawView = findViewById(R.id.draw_view)
    drawView?.setStrokeWidth(70.0f)
    drawView?.setColor(Color.WHITE)
    drawView?.setBackgroundColor(Color.BLACK)
    clearButton = findViewById(R.id.clear_button)
    yesButton = findViewById(R.id.yes_button)
    predictedTextView = findViewById(R.id.predicted_text)

    // Setup clear drawing button
    clearButton?.setOnClickListener {
      drawView?.clearCanvas()
      predictedTextView?.text = getString(R.string.tfe_dc_prediction_text_placeholder)
    }

    // Setup YES button
    yesButton?.setOnClickListener {
      Firebase.analytics.logEvent("correct_inference", null)
    }

    // Setup classification trigger so that it classify after every stroke drew
    drawView?.setOnTouchListener { _, event ->
      // As we have interrupted DrawView's touch event,
      // we first need to pass touch events through to the instance for the drawing to show up
      drawView?.onTouchEvent(event)

      // Then if user finished a touch event, run classification
      if (event.action == MotionEvent.ACTION_UP) {
        classifyDrawing()
      }

      true
    }
    setupDigitClassifier()
  }

  private fun setupDigitClassifier() {
    configureRemoteConfig()
    remoteConfig.fetchAndActivate()
      .addOnCompleteListener { task ->
        if (task.isSuccessful) {
          val modelName = remoteConfig.getString("model_name")
          val downloadTrace = firebasePerformance.newTrace("download_model")
          downloadTrace.start()
          downloadModel("mnist_v2")
            .addOnSuccessListener {
              downloadTrace.stop()
            }
        } else {
          showToast("Failed to fetch model name.")
        }
      }
  }

  private fun configureRemoteConfig() {
    remoteConfig = Firebase.remoteConfig
    val configSettings = remoteConfigSettings {
      minimumFetchIntervalInSeconds = 3600
    }
    remoteConfig.setConfigSettingsAsync(configSettings)
  }

  private fun downloadModel(modelName: String): Task<Void> {
    val remoteModel = FirebaseCustomRemoteModel.Builder(modelName).build()
    val firebaseModelManager = FirebaseModelManager.getInstance()
    return firebaseModelManager
      .isModelDownloaded(remoteModel)
      .continueWithTask { task ->
        // Create update condition if model is already downloaded, otherwise create download
        // condition.
        val conditions = if (task.result != null && task.result == true) {
          FirebaseModelDownloadConditions.Builder()
            .requireWifi()
            .build() // Update condition that requires wifi.
        } else {
          FirebaseModelDownloadConditions.Builder().build(); // Download condition.
        }
        firebaseModelManager.download(remoteModel, conditions)
      }
      .addOnSuccessListener {
        firebaseModelManager.getLatestModelFile(remoteModel)
          .addOnCompleteListener {
            val model = it.result
            if (model == null) {
              showToast("Failed to get model file.")
            } else {
              showToast("Downloaded remote model: $modelName")
              digitClassifier.initialize(model)
            }
          }
      }
      .addOnFailureListener {
        showToast("Model download failed for $modelName, please check your connection.")
      }
  }

  override fun onDestroy() {
    digitClassifier.close()
    super.onDestroy()
  }

  private fun classifyDrawing() {
    val bitmap = drawView?.getBitmap()

    if ((bitmap != null) && (digitClassifier.isInitialized)) {
      val classifyTrace = firebasePerformance.newTrace("classify")
      classifyTrace.start()

      digitClassifier
        .classifyAsync(bitmap)
        .addOnSuccessListener { resultText ->
          classifyTrace.stop()
          predictedTextView?.text = resultText
        }
        .addOnFailureListener { e ->
          predictedTextView?.text = getString(
            R.string.tfe_dc_classification_error_message,
            e.localizedMessage
          )
          Log.e(TAG, "Error classifying drawing.", e)
        }
    }
  }

  @Throws(IOException::class)
  private fun loadModelFile(): ByteBuffer {
    val fileDescriptor = assets.openFd(MainActivity.MODEL_FILE)
    val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
    val fileChannel = inputStream.channel
    val startOffset = fileDescriptor.startOffset
    val declaredLength = fileDescriptor.declaredLength
    return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
  }

  private fun showToast(text: String) {
    Toast.makeText(
      this,
      text,
      Toast.LENGTH_LONG
    ).show()
  }

  companion object {
    private const val TAG = "MainActivity"

    private const val MODEL_FILE = "mnist.tflite"
  }
}
