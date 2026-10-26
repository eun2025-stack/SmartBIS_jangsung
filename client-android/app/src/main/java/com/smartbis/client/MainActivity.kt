package com.smartbis.client

import android.content.pm.ActivityInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.smartbis.client.controller.DisplayController
import com.smartbis.client.model.SmartBisRepository
import com.smartbis.client.view.NaturalDisplayView
import java.net.URL
import java.time.ZonedDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.Executors

/** Activity is the Android host/controller adapter; presentation and API logic live in separate classes. */
class MainActivity : AppCompatActivity() {
    private val clockHandler = Handler(Looper.getMainLooper())
    private val settingsHandler = Handler(Looper.getMainLooper())
    private val settingsWorker = Executors.newSingleThreadExecutor()
    private val settings by lazy { getSharedPreferences("smartbis_device", MODE_PRIVATE) }
    private lateinit var displayView: NaturalDisplayView
    private lateinit var displayController: DisplayController
    private lateinit var repository: SmartBisRepository
    private var baseUrl = DEFAULT_BASE_URL
    private var vehicleNumber = DEFAULT_VEHICLE_NUMBER
    private var assignmentResolved = false
    private var activityStarted = false
    private var assignmentCheckRunning = false
    private var displayControllerStarted = false

    private val assignmentRetryTask = object : Runnable {
        override fun run() {
            if (activityStarted && !isFinishing) resolveVehicleAssignment()
        }
    }

    private val clockTask = object : Runnable {
        override fun run() {
            if (!isFinishing && ::displayView.isInitialized) {
                val time = ZonedDateTime.now(ZoneId.of("Asia/Seoul")).format(CLOCK_FORMAT)
                displayView.updateClock(time)
                clockHandler.postDelayed(this, CLOCK_REFRESH_MS)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)
        enterImmersiveMode()

        baseUrl = settings.getString(KEY_BASE_URL, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL
        vehicleNumber = settings.getString(KEY_VEHICLE_NUMBER, DEFAULT_VEHICLE_NUMBER)
            ?: DEFAULT_VEHICLE_NUMBER
        applyVehicleSelectionIntent(intent)

        val root = findViewById<ViewGroup>(R.id.displayRoot)
        displayView = NaturalDisplayView(root)
        repository = SmartBisRepository(java.io.File(filesDir, "playback-cache"), { baseUrl }, { vehicleNumber })
        displayController = DisplayController(displayView, repository)
        displayView.setOperatorGesture { showConnectionSettings() }
        displayView.showLoading()
        resolveVehicleAssignment()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (applyVehicleSelectionIntent(intent) && ::displayController.isInitialized) {
            assignmentResolved = true
            if (activityStarted) displayController.start()
            displayController.refresh()
        }
    }

    private fun resolveVehicleAssignment() {
        if (assignmentCheckRunning) return
        assignmentCheckRunning = true
        settingsWorker.execute {
            val result = runCatching {
                val androidId = android.provider.Settings.Secure.getString(contentResolver,
                    android.provider.Settings.Secure.ANDROID_ID)?.takeIf(String::isNotBlank)
                    ?: error("Android 설치 식별값을 읽을 수 없습니다.")
                repository.currentAssignedVehicle(androidId)
            }
            runOnUiThread {
                assignmentCheckRunning = false
                assignmentResolved = true
                result.onSuccess { (deviceNumber, assignedVehicle) ->
                    settingsHandler.removeCallbacks(assignmentRetryTask)
                    if (assignedVehicle.isNullOrBlank()) {
                        vehicleNumber = ""
                        settings.edit().remove(KEY_VEHICLE_NUMBER).apply()
                        displayView.showConnectionError(null)
                        displayView.showWaiting("단말 $deviceNumber · 차량번호를 먼저 배정하세요")
                        return@onSuccess
                    }
                    vehicleNumber = assignedVehicle
                    settings.edit().putString(KEY_VEHICLE_NUMBER, assignedVehicle).apply()
                    displayView.showConnectionError(null)
                    if (activityStarted) {
                        if (displayControllerStarted) displayController.refresh()
                        else {
                            displayController.start()
                            displayControllerStarted = true
                        }
                    }
                }.onFailure { error ->
                    // Keep the last assigned vehicle locally; it identifies the cached playlist.
                    displayView.showConnectionError(
                        "단말 등록/차량 배정 확인 실패 : ${error.message ?: "서버 연결 끊김"}"
                    )
                    if (activityStarted && !displayControllerStarted) {
                        displayController.start()
                        displayControllerStarted = true
                    }
                    settingsHandler.removeCallbacks(assignmentRetryTask)
                    settingsHandler.postDelayed(assignmentRetryTask, ASSIGNMENT_RETRY_MS)
                }
            }
        }
    }

    private fun applyVehicleSelectionIntent(intent: android.content.Intent?): Boolean {
        if (intent?.action != ACTION_SET_VEHICLE) return false
        val candidateVehicle = intent.getStringExtra(EXTRA_VEHICLE_NUMBER)?.trim().orEmpty()
        val candidateUrl = intent.getStringExtra(EXTRA_API_BASE_URL)?.trim()?.trimEnd('/')
        val parsed = candidateUrl?.let { runCatching { URL(it) }.getOrNull() }
        if (candidateVehicle.isBlank() || candidateVehicle.length > 50 ||
            candidateUrl == null || parsed?.protocol != "https" || !candidateUrl.endsWith("/api")) {
            return false
        }
        baseUrl = candidateUrl
        vehicleNumber = candidateVehicle
        settings.edit()
            .putString(KEY_BASE_URL, baseUrl)
            .putString(KEY_VEHICLE_NUMBER, vehicleNumber)
            .apply()
        return true
    }

    override fun onStart() {
        super.onStart()
        activityStarted = true
        if (assignmentResolved) {
            if (!displayControllerStarted) {
                displayController.start()
                displayControllerStarted = true
            }
            if (vehicleNumber.isBlank()) settingsHandler.post(assignmentRetryTask)
        }
        clockHandler.removeCallbacks(clockTask)
        clockHandler.post(clockTask)
    }

    override fun onStop() {
        activityStarted = false
        settingsHandler.removeCallbacks(assignmentRetryTask)
        clockHandler.removeCallbacks(clockTask)
        displayController.stop()
        displayControllerStarted = false
        super.onStop()
    }

    override fun onDestroy() {
        clockHandler.removeCallbacksAndMessages(null)
        settingsHandler.removeCallbacksAndMessages(null)
        settingsWorker.shutdownNow()
        if (::displayController.isInitialized) displayController.destroy()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersiveMode()
    }

    private fun enterImmersiveMode() {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    /** Long-press the Jangseong brand mark to open the normally hidden operator settings. */
    private fun showConnectionSettings() {
        val fields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 8, 48, 0)
        }
        val serverInput = EditText(this).apply {
            hint = "예: https://10.0.2.2:8443/api"
            setText(baseUrl)
            textSize = 18f
            minHeight = (64 * resources.displayMetrics.density).toInt()
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            contentDescription = "서버 API 주소"
        }
        val vehicleSearch = EditText(this).apply {
            hint = "차량번호 일부 입력 (예: 12)"
            textSize = 18f
            minHeight = (64 * resources.displayMetrics.density).toInt()
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            contentDescription = "차량번호 일부 검색"
        }
        val selectedVehicleText = TextView(this).apply {
            text = "선택된 차량: $vehicleNumber"
            textSize = 18f
            setPadding(0, 12, 0, 12)
        }
        val vehicleList = ListView(this).apply {
            choiceMode = ListView.CHOICE_MODE_SINGLE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (190 * resources.displayMetrics.density).toInt()
            )
        }
        val vehicleAdapter = ArrayAdapter<String>(this, android.R.layout.simple_list_item_1)
        vehicleList.adapter = vehicleAdapter
        var selectedVehicle: String? = vehicleNumber
        fields.addView(serverInput)
        fields.addView(vehicleSearch)
        fields.addView(selectedVehicleText)
        fields.addView(vehicleList)

        val emptyListText = TextView(this).apply {
            text = "두 글자 이상 입력하면 서버의 차량 목록을 검색합니다."
            textSize = 16f
            setPadding(0, 24, 0, 24)
        }
        vehicleList.emptyView = emptyListText
        fields.addView(emptyListText)

        var pendingSearch: Runnable? = null
        vehicleSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                selectedVehicle = null
                selectedVehicleText.text = "목록에서 차량을 선택하세요."
                vehicleAdapter.clear()
                val query = s?.toString()?.trim().orEmpty()
                val searchBaseUrl = serverInput.text.toString().trim()
                pendingSearch?.let(settingsHandler::removeCallbacks)
                if (query.length < 2) {
                    emptyListText.text = "두 글자 이상 입력하면 서버의 차량 목록을 검색합니다."
                    return
                }
                emptyListText.text = "차량 목록을 검색하고 있습니다…"
                val searchTask = Runnable {
                    settingsWorker.execute {
                        val result = runCatching { repository.searchVehicles(query, searchBaseUrl) }
                        runOnUiThread {
                            if (isFinishing || vehicleSearch.text.toString().trim() != query) return@runOnUiThread
                            vehicleAdapter.clear()
                            result.onSuccess { vehicles ->
                                vehicleAdapter.addAll(vehicles)
                                emptyListText.text = if (vehicles.isEmpty())
                                    "일치하는 차량이 없습니다. TAGO에 등록된 차량번호를 확인하세요."
                                else "차량을 선택하세요."
                            }.onFailure {
                                emptyListText.text = "차량 목록을 가져오지 못했습니다. 서버 연결을 확인하세요."
                            }
                        }
                    }
                }
                pendingSearch = searchTask
                settingsHandler.postDelayed(searchTask, VEHICLE_SEARCH_DELAY_MS)
            }
            override fun afterTextChanged(s: android.text.Editable?) = Unit
        })
        vehicleList.setOnItemClickListener { _, _, position, _ ->
            selectedVehicle = vehicleAdapter.getItem(position)
            selectedVehicleText.text = "선택된 차량: $selectedVehicle"
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("단말 연결 설정")
            .setMessage("장성군 버스 운행 화면의 HTTPS API 주소와 차량번호입니다.")
            .setView(fields)
            .setNegativeButton("취소", null)
            .setPositiveButton("저장", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val candidateUrl = serverInput.text.toString().trim().trimEnd('/')
                val candidateVehicle = selectedVehicle
                val parsed = runCatching { URL(candidateUrl) }.getOrNull()
                if (parsed == null || parsed.protocol != "https" || parsed.host.isBlank() ||
                    !candidateUrl.endsWith("/api")) {
                    serverInput.error = "https://주소:포트/api 형식으로 입력하세요."
                    return@setOnClickListener
                }
                if (candidateVehicle.isNullOrBlank()) {
                    vehicleSearch.error = "검색 결과에서 차량을 선택하세요."
                    return@setOnClickListener
                }
                baseUrl = candidateUrl
                vehicleNumber = candidateVehicle
                settings.edit()
                    .putString(KEY_BASE_URL, baseUrl)
                    .putString(KEY_VEHICLE_NUMBER, vehicleNumber)
                    .apply()
                dialog.dismiss()
                displayController.refresh()
            }
        }
        dialog.show()
    }

    private companion object {
        const val DEFAULT_BASE_URL = BuildConfig.SMARTBIS_API_BASE_URL
        const val DEFAULT_VEHICLE_NUMBER = ""
        const val KEY_BASE_URL = "base_url"
        const val KEY_VEHICLE_NUMBER = "vehicle_number"
        const val ACTION_SET_VEHICLE = "com.smartbis.client.action.SET_VEHICLE"
        const val EXTRA_VEHICLE_NUMBER = "com.smartbis.client.extra.VEHICLE_NUMBER"
        const val EXTRA_API_BASE_URL = "com.smartbis.client.extra.API_BASE_URL"
        const val CLOCK_REFRESH_MS = 30_000L
        const val ASSIGNMENT_RETRY_MS = 30_000L
        const val VEHICLE_SEARCH_DELAY_MS = 350L
        val CLOCK_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("M월 d일(E) HH:mm", Locale.KOREAN)
    }
}
