package com.smartbis.client

import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private val autoRefreshHandler = Handler(Looper.getMainLooper())
    private var autoRefreshEnabled = false
    private var displayRequestRunning = false
    private var videoPlaying = false

    private val autoRefreshRunnable = object : Runnable {
        override fun run() {
            if (!autoRefreshEnabled) return
            if (!displayRequestRunning && !videoPlaying) loadDisplay()
            autoRefreshHandler.postDelayed(this, 30_000L)
        }
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val baseUrl = "https://10.0.2.2:8443/api"
    private val vehicleNumber = "SAMPLE-001"

    private lateinit var statusText: TextView
    private lateinit var imageView: ImageView
    private lateinit var videoView: VideoView

    override fun onStart() {
        super.onStart()
        autoRefreshEnabled = true
        autoRefreshHandler.removeCallbacks(autoRefreshRunnable)
        autoRefreshHandler.post(autoRefreshRunnable)
    }

    override fun onStop() {
        autoRefreshEnabled = false
        autoRefreshHandler.removeCallbacks(autoRefreshRunnable)
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        imageView = findViewById(R.id.imageView)
        videoView = findViewById(R.id.videoView)

        findViewById<Button>(R.id.downloadImageButton).setOnClickListener {
            loadDisplay()
        }

        findViewById<Button>(R.id.downloadVideoButton).setOnClickListener {
            loadDisplay()
        }
    }

    private fun loadDisplay() {
        if (displayRequestRunning) return
        displayRequestRunning = true

        statusText.text = "표출정보 조회 중..."
        imageView.visibility = ImageView.GONE
        videoView.visibility = VideoView.GONE

        executor.execute {
            try {
                val url = URL(
                    "$baseUrl/v1/display/next?vehicleNumber=$vehicleNumber"
                )

                val response = requestText(url)
                val item = JSONObject(response)

                val displayType = item.optString("displayType")
                val text = item.optString("text")
                val mediaUrl = if (item.isNull("mediaUrl")) "" else item.optString("mediaUrl")
                val contentId = if (item.isNull("contentId")) "" else item.optString("contentId")
                val displaySeconds = item.optInt("displaySeconds", 10)

                if (mediaUrl.isBlank()) {
                    videoPlaying = false
                    runOnUiThread {
                        statusText.text =
                            "[$displayType]\n\n$text"
                        displayRequestRunning = false

                        autoRefreshHandler.postDelayed(
                            {
                                if (autoRefreshEnabled) {
                                    loadDisplay()
                                }
                            },
                            displaySeconds * 1000L
                        )
                    }
                    return@execute
                }

                val mediaFile = File(cacheDir, "$contentId.tmp")
                val finalFile = File(cacheDir, contentId)

                download(
                    URL("https://10.0.2.2:8443$mediaUrl"),
                    mediaFile
                )

                if (finalFile.exists()) {
                    finalFile.delete()
                }

                check(mediaFile.renameTo(finalFile)) {
                    "미디어 파일 확정 실패"
                }

                runOnUiThread {
                    statusText.text =
                        "[$displayType]\n\n$text"

                    if (displayType == "VIDEO") {
                        videoView.setVideoPath(finalFile.absolutePath)
                        videoView.visibility = VideoView.VISIBLE

                        videoView.setOnPreparedListener { player ->
                            player.isLooping = false
                            displayRequestRunning = false
                            videoPlaying = true
                            player.start()
                        }

                        videoView.setOnCompletionListener {
                            videoPlaying = false
                            videoView.stopPlayback()
                            loadDisplay()
                        }
                    } else {
                        videoPlaying = false
                        imageView.setImageBitmap(
                            BitmapFactory.decodeFile(finalFile.absolutePath)
                        )
                        imageView.visibility = ImageView.VISIBLE
                        displayRequestRunning = false

                        autoRefreshHandler.postDelayed(
                            {
                                if (autoRefreshEnabled) {
                                    loadDisplay()
                                }
                            },
                            displaySeconds * 1000L
                        )
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    displayRequestRunning = false
                    statusText.text =
                        "표출정보 처리 실패: ${e.javaClass.simpleName}\n${e.message}"
                }
            }
        }
    }

    private fun requestText(url: URL): String {
        val connection = url.openConnection() as HttpURLConnection

        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000

            check(connection.responseCode == HttpURLConnection.HTTP_OK) {
                "HTTP ${connection.responseCode}"
            }

            connection.inputStream.bufferedReader().use {
                it.readText()
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun download(url: URL, target: File) {
        val connection = url.openConnection() as HttpURLConnection

        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 15_000
            connection.readTimeout = 300_000

            check(connection.responseCode == HttpURLConnection.HTTP_OK) {
                "미디어 HTTP ${connection.responseCode}"
            }

            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    input.copyTo(output, bufferSize = 64 * 1024)
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }
}
