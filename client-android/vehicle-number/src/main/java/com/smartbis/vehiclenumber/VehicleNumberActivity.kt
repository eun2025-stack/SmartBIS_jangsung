package com.smartbis.vehiclenumber

import android.content.Intent
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.app.AlertDialog
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.Executors

/** Operator utility: search the TAGO vehicle directory and hand the selected vehicle to SmartBIS Client. */
class VehicleNumberActivity : AppCompatActivity() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val preferences by lazy { getSharedPreferences("vehicle_selector", MODE_PRIVATE) }
    private lateinit var apiInput: EditText
    private lateinit var queryInput: EditText
    private lateinit var vehicleInput: TextView
    private lateinit var status: TextView
    private lateinit var selectedLabel: TextView
    private lateinit var vehicleList: ListView
    private lateinit var adapter: ArrayAdapter<VehicleOption>
    private lateinit var saveButton: Button
    private lateinit var cancelButton: Button
    private lateinit var runButton: Button
    private var selectedVehicle: String? = null
    private var assignedVehicleNumber: String? = null
    private var assignedDeviceNumber: String? = null
    private var deviceRegistrationVerified = false
    private var debounce: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (redirectToClientAfterUpdate()) return
        window.statusBarColor = Color.rgb(16, 61, 48)
        window.navigationBarColor = Color.rgb(16, 61, 48)
        window.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
        )
        buildScreen()
        loadCurrentAssignment()
        search("")
    }

    private fun redirectToClientAfterUpdate(): Boolean {
        val updatedAt = preferences.getLong(VehicleNumberUpdateReceiver.KEY_UPDATED_AT, 0L)
        if (updatedAt <= 0L) return false
        preferences.edit().remove(VehicleNumberUpdateReceiver.KEY_UPDATED_AT).apply()
        if (System.currentTimeMillis() - updatedAt > VehicleNumberUpdateReceiver.REDIRECT_WINDOW_MS) return false
        if (VehicleNumberUpdateReceiver.openClient(this)) {
            finish()
            return true
        }
        return false
    }

    private fun buildScreen() {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val page = FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(238, 242, 238))
            isFocusableInTouchMode = true
        }
        val pageWidth = minOf(dp(1480), resources.displayMetrics.widthPixels - dp(48))
        val pageHeight = minOf(dp(920), resources.displayMetrics.heightPixels - dp(48))
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(22), dp(20), dp(22), dp(20))
            setBackgroundColor(Color.rgb(250, 251, 249))
            elevation = dp(3).toFloat()
        }
        page.addView(root, FrameLayout.LayoutParams(pageWidth, pageHeight, Gravity.CENTER))

        val listPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(4), dp(20), dp(4))
        }
        root.addView(listPanel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.65f))
        listPanel.addView(TextView(this).apply {
            text = "차량 목록"
            textSize = 28f
            setTextColor(Color.rgb(20, 59, 46))
            setTypeface(null, android.graphics.Typeface.BOLD)
        })
        listPanel.addView(TextView(this).apply {
            text = "차량번호와 배정 상태를 확인하고 선택하세요. 다른 단말 배정 차량은 선택할 수 없습니다."
            textSize = 15f
            setTextColor(Color.rgb(83, 96, 87))
            setPadding(0, dp(4), 0, dp(10))
        })

        val searchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        queryInput = EditText(this).apply {
            hint = "차량번호 일부 검색 (예: 12)"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            textSize = 19f
            contentDescription = "차량번호 검색어"
        }
        searchRow.addView(queryInput, LinearLayout.LayoutParams(0, dp(58), 1f))
        val searchButton = Button(this).apply {
            text = "검색"
            isAllCaps = false
            setOnClickListener {
                debounce?.let(mainHandler::removeCallbacks)
                search(queryInput.text.toString().trim())
                hideKeyboard()
                queryInput.clearFocus()
            }
        }
        searchRow.addView(searchButton, LinearLayout.LayoutParams(dp(96), dp(54)))
        listPanel.addView(searchRow)

        status = TextView(this).apply {
            text = "서버 차량목록 불러오는 중…"
            textSize = 15f
            setTextColor(Color.rgb(78, 91, 82))
            setPadding(dp(4), dp(5), dp(4), dp(8))
        }
        listPanel.addView(status)

        vehicleList = ListView(this).apply {
            choiceMode = ListView.CHOICE_MODE_SINGLE
            dividerHeight = dp(1)
            setBackgroundColor(Color.WHITE)
            contentDescription = "차량번호와 배정 상태 목록"
        }
        adapter = object : ArrayAdapter<VehicleOption>(this, 0, mutableListOf()) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val option = getItem(position) ?: return TextView(this@VehicleNumberActivity)
                val row = (convertView as? LinearLayout) ?: LinearLayout(this@VehicleNumberActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(18), dp(9), dp(18), dp(9))
                    addView(TextView(this@VehicleNumberActivity).apply {
                        id = android.R.id.text1
                        textSize = 21f
                        setTypeface(null, android.graphics.Typeface.BOLD)
                        setTextColor(Color.rgb(34, 45, 38))
                    })
                    addView(TextView(this@VehicleNumberActivity).apply {
                        id = android.R.id.text2
                        textSize = 15f
                        setPadding(0, dp(2), 0, 0)
                    })
                }
                row.findViewById<TextView>(android.R.id.text1).text = option.vehicleNumber
                row.findViewById<TextView>(android.R.id.text2).apply {
                    text = option.statusLabel
                    setTextColor(when (option.assignmentStatus) {
                        STATUS_ASSIGNED_THIS -> Color.rgb(21, 112, 67)
                        STATUS_ASSIGNED_OTHER -> Color.rgb(177, 54, 45)
                        else -> Color.rgb(94, 105, 96)
                    })
                }
                row.minimumHeight = dp(68)
                return row
            }
        }
        vehicleList.adapter = adapter
        vehicleList.setOnItemClickListener { _, _, position, _ ->
            val option = adapter.getItem(position) ?: return@setOnItemClickListener
            if (option.assignmentStatus == STATUS_ASSIGNED_OTHER) {
                selectedVehicle = null
                vehicleInput.text = ""
                selectedLabel.text = "선택된 차량 없음"
                saveButton.isEnabled = false
                status.text = "${option.vehicleNumber} 차량은 다른 단말에 이미 배정되어 있습니다."
                return@setOnItemClickListener
            }
            selectedVehicle = option.vehicleNumber
            vehicleInput.setText(selectedVehicle.orEmpty())
            hideKeyboard()
            queryInput.clearFocus()
            selectedLabel.text = option.statusLabel
            saveButton.isEnabled = deviceRegistrationVerified
            status.text = if (deviceRegistrationVerified) {
                "차량번호를 선택했습니다. ‘차량 배정 저장’을 누르세요."
            } else {
                "차량번호는 선택됐습니다. 단말 ID 등록 확인 후 저장할 수 있습니다."
            }
        }
        listPanel.addView(vehicleList, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val actionPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(4), dp(8), dp(4))
            setBackgroundColor(Color.rgb(243, 247, 242))
        }
        root.addView(actionPanel, LinearLayout.LayoutParams(dp(390), ViewGroup.LayoutParams.MATCH_PARENT))
        actionPanel.addView(TextView(this).apply {
            text = "단말 · 배정 정보"
            textSize = 25f
            setTextColor(Color.rgb(20, 59, 46))
            setTypeface(null, android.graphics.Typeface.BOLD)
        })
        selectedLabel = TextView(this).apply {
            text = "단말 확인 중"
            textSize = 16f
            setTextColor(Color.rgb(83, 96, 87))
            setPadding(0, dp(12), 0, dp(4))
        }
        actionPanel.addView(selectedLabel)
        vehicleInput = TextView(this).apply {
            text = "차량 미선택"
            textSize = 26f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.rgb(20, 59, 46))
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(14), 0)
            setBackgroundColor(Color.WHITE)
            contentDescription = "선택 또는 현재 배정된 차량번호"
        }
        actionPanel.addView(vehicleInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(76)))

        actionPanel.addView(TextView(this).apply {
            text = "서버 주소"
            textSize = 14f
            setTextColor(Color.rgb(83, 96, 87))
            setPadding(0, dp(14), 0, dp(2))
        })
        apiInput = EditText(this).apply {
            setSingleLine(true)
            hint = "https://서버:8443/api"
            setText(preferences.getString(KEY_API_URL, BuildConfig.SMARTBIS_API_BASE_URL))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            textSize = 15f
            contentDescription = "SmartBIS API 주소"
        }
        actionPanel.addView(apiInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))

        saveButton = Button(this).apply {
            text = "차량 배정 저장"
            textSize = 18f
            isAllCaps = false
            isEnabled = false
            setOnClickListener { saveVehicleAssignment() }
        }
        actionPanel.addView(saveButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58)).apply { topMargin = dp(18) })
        cancelButton = Button(this).apply {
            text = "현재 차량 배정 취소"
            textSize = 17f
            isAllCaps = false
            isEnabled = false
            setOnClickListener { confirmCancelVehicleAssignment() }
        }
        actionPanel.addView(cancelButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)).apply { topMargin = dp(8) })
        runButton = Button(this).apply {
            text = "SmartBIS 표출 실행"
            textSize = 18f
            isAllCaps = false
            isEnabled = false
            setOnClickListener { launchSmartBisDisplay() }
        }
        actionPanel.addView(runButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58)).apply { topMargin = dp(8) })
        setContentView(page)
        page.requestFocus()

        queryInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                debounce?.let(mainHandler::removeCallbacks)
                search(queryInput.text.toString().trim())
                hideKeyboard()
                queryInput.clearFocus()
                true
            } else {
                false
            }
        }

        queryInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                selectedVehicle = null
                vehicleInput.text = ""
                selectedLabel.text = "선택된 차량 없음"
                saveButton.isEnabled = false
                adapter.clear()
                val query = s?.toString()?.trim().orEmpty()
                debounce?.let(mainHandler::removeCallbacks)
                status.text = if (query.isBlank()) "서버 차량목록 새로고침 중…" else "차량목록 필터링 중…"
                val request = Runnable { search(query) }
                debounce = request
                mainHandler.postDelayed(request, SEARCH_DELAY_MS)
            }
            override fun afterTextChanged(s: android.text.Editable?) = Unit
        })
    }

    private fun search(query: String) {
        val base = apiInput.text.toString().trim().trimEnd('/')
        val parsed = runCatching { URL(base) }.getOrNull()
        if (parsed?.protocol != "https" || !base.endsWith("/api")) {
            runOnUiThread { status.text = "HTTPS API 주소를 https://서버:8443/api 형식으로 입력하세요." }
            return
        }
        worker.execute {
            val result = runCatching {
                val fingerprint = URLEncoder.encode(deviceFingerprint(), Charsets.UTF_8.name())
                val queryPart = if (query.isBlank()) "" else "q=${URLEncoder.encode(query, Charsets.UTF_8.name())}&"
                val path = "$base/v1/vehicles/availability?${queryPart}identitySha256=$fingerprint"
                val connection = URL(path).openConnection() as HttpURLConnection
                try {
                    connection.requestMethod = "GET"
                    connection.connectTimeout = CONNECT_TIMEOUT_MS
                    connection.readTimeout = READ_TIMEOUT_MS
                    connection.setRequestProperty("Accept", "application/json")
                    check(connection.responseCode == HttpURLConnection.HTTP_OK) {
                        "차량 검색 HTTP ${connection.responseCode}"
                    }
                    org.json.JSONArray(connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
                        .let { array ->
                            (0 until array.length()).mapNotNull { index ->
                                val item = array.optJSONObject(index) ?: return@mapNotNull null
                                val number = item.optString("vehicleNumber").takeIf(String::isNotBlank)
                                    ?: return@mapNotNull null
                                VehicleOption(number, item.optString("assignmentStatus"))
                            }
                        }
                } finally {
                    connection.disconnect()
                }
            }
            runOnUiThread {
                if (isFinishing || queryInput.text.toString().trim() != query) return@runOnUiThread
                result.onSuccess { vehicles ->
                    adapter.clear()
                    adapter.addAll(vehicles)
                    status.text = when {
                        vehicles.isNotEmpty() && query.isBlank() -> "차량 DB에서 ${vehicles.size}대 표시 중 · 차량을 선택하세요."
                        vehicles.isNotEmpty() -> "${vehicles.size}대 일치 · 목록에서 차량을 선택하세요."
                        query.isBlank() -> "차량 DB에 활성 차량이 없습니다. TAGO 차량 위치 수집/차량 등록 상태를 확인하세요."
                        else -> "일치하는 차량 없음 · 일부 번호를 다시 입력하세요."
                    }
                }.onFailure { error ->
                    status.text = "검색 실패: ${error.message ?: "서버 연결을 확인하세요."}"
                }
            }
        }
    }

    private fun hideKeyboard() {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(queryInput.windowToken, 0)
    }

    private fun loadCurrentAssignment() {
        val base = apiInput.text.toString().trim().trimEnd('/')
        worker.execute {
            val result = runCatching {
                val fingerprint = deviceFingerprint()
                val encoded = URLEncoder.encode(fingerprint, Charsets.UTF_8.name())
                val connection = URL("$base/v1/device-vehicle-assignment/current?identitySha256=$encoded")
                    .openConnection() as HttpURLConnection
                try {
                    connection.requestMethod = "GET"
                    connection.connectTimeout = CONNECT_TIMEOUT_MS
                    connection.readTimeout = READ_TIMEOUT_MS
                    connection.setRequestProperty("Accept", "application/json")
                    check(connection.responseCode == HttpURLConnection.HTTP_OK) {
                        "단말 등록 조회 HTTP ${connection.responseCode}"
                    }
                    org.json.JSONObject(connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
                } finally {
                    connection.disconnect()
                }
            }
            runOnUiThread {
                result.onSuccess { payload ->
                    assignedDeviceNumber = payload.optString("deviceNumber").takeIf { it.isNotBlank() && it != "null" }
                    val assignedVehicle = payload.optString("vehicleNumber").takeIf { it.isNotBlank() && it != "null" }
                    assignedVehicleNumber = assignedVehicle
                    deviceRegistrationVerified = !assignedDeviceNumber.isNullOrBlank()
                    saveButton.isEnabled = deviceRegistrationVerified && !selectedVehicle.isNullOrBlank()
                    if (assignedVehicle != null) {
                        selectedVehicle = assignedVehicle
                        vehicleInput.setText(assignedVehicle)
                        selectedLabel.text = "단말 ${assignedDeviceNumber.orEmpty()} · 배정 차량: $assignedVehicle"
                        cancelButton.isEnabled = true
                        runButton.isEnabled = true
                    } else {
                        selectedVehicle = null
                        vehicleInput.text = ""
                        cancelButton.isEnabled = false
                        runButton.isEnabled = false
                        selectedLabel.text = "단말 ${assignedDeviceNumber.orEmpty()} · 차량번호 미배정"
                    }
                    status.text = if (assignedVehicle != null) {
                        "단말 등록 확인 완료 · 기존 차량 배정이 있습니다. 표출 실행 또는 차량 변경 후 저장하세요."
                    } else {
                        "단말 ID 등록 확인 완료 · 차량을 선택하고 ‘차량 배정 저장’을 누르세요."
                    }
                }.onFailure { error ->
                    assignedDeviceNumber = null
                    deviceRegistrationVerified = false
                    saveButton.isEnabled = false
                    cancelButton.isEnabled = false
                    runButton.isEnabled = false
                    status.text = when {
                        error.message?.contains("HTTP 404") == true -> "서버에 이 단말의 차량관리 등록 ID가 없습니다. Headwind 자동 ID 등록을 먼저 완료하세요."
                        else -> "단말 확인 실패: ${error.message ?: "서버 연결을 확인하세요."}"
                    }
                }
            }
        }
    }

    private fun saveAssignment(base: String, vehicle: String, onComplete: (Result<org.json.JSONObject>) -> Unit) {
        worker.execute {
            val result = runCatching {
                val connection = URL("$base/v1/device-vehicle-assignment").openConnection() as HttpURLConnection
                try {
                    val body = org.json.JSONObject()
                        .put("identitySha256", deviceFingerprint())
                        .put("vehicleNumber", vehicle)
                    connection.requestMethod = "POST"
                    connection.connectTimeout = CONNECT_TIMEOUT_MS
                    connection.readTimeout = READ_TIMEOUT_MS
                    connection.doOutput = true
                    connection.setRequestProperty("Accept", "application/json")
                    connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body.toString()) }
                    val statusCode = connection.responseCode
                    val stream = if (statusCode in 200..299) connection.inputStream else connection.errorStream
                    val response = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                    check(statusCode == HttpURLConnection.HTTP_OK) {
                        assignmentFailureMessage(statusCode, response)
                    }
                    org.json.JSONObject(response)
                } finally {
                    connection.disconnect()
                }
            }
            runOnUiThread { onComplete(result) }
        }
    }

    private fun cancelAssignment(base: String, onComplete: (Result<org.json.JSONObject>) -> Unit) {
        worker.execute {
            val result = runCatching {
                val fingerprint = URLEncoder.encode(deviceFingerprint(), Charsets.UTF_8.name())
                val connection = URL("$base/v1/device-vehicle-assignment?identitySha256=$fingerprint")
                    .openConnection() as HttpURLConnection
                try {
                    connection.requestMethod = "DELETE"
                    connection.connectTimeout = CONNECT_TIMEOUT_MS
                    connection.readTimeout = READ_TIMEOUT_MS
                    connection.setRequestProperty("Accept", "application/json")
                    val statusCode = connection.responseCode
                    val stream = if (statusCode in 200..299) connection.inputStream else connection.errorStream
                    val response = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                    check(statusCode == HttpURLConnection.HTTP_OK) {
                        assignmentFailureMessage(statusCode, response)
                    }
                    org.json.JSONObject(response)
                } finally {
                    connection.disconnect()
                }
            }
            runOnUiThread { onComplete(result) }
        }
    }

    private fun confirmCancelVehicleAssignment() {
        val assignedVehicle = assignedVehicleNumber
        if (assignedVehicle.isNullOrBlank() || !deviceRegistrationVerified) {
            status.text = "취소할 차량 배정이 없습니다."
            return
        }
        AlertDialog.Builder(this)
            .setTitle("차량 배정 취소")
            .setMessage("${assignedDeviceNumber.orEmpty()} 단말의 차량 $assignedVehicle 배정을 해제할까요?\n단말 등록 정보와 과거 운행 데이터는 유지됩니다.")
            .setNegativeButton("돌아가기", null)
            .setPositiveButton("배정 취소") { _, _ ->
                val base = apiInput.text.toString().trim().trimEnd('/')
                cancelButton.isEnabled = false
                saveButton.isEnabled = false
                runButton.isEnabled = false
                status.text = "${assignedDeviceNumber.orEmpty()} · 차량 배정 취소 중…"
                cancelAssignment(base) { result ->
                    result.onSuccess { assignment ->
                        assignedDeviceNumber = assignment.optString("deviceNumber")
                        assignedVehicleNumber = null
                        selectedVehicle = null
                        vehicleInput.text = ""
                        selectedLabel.text = "단말 ${assignedDeviceNumber.orEmpty()} · 차량번호 미배정"
                        preferences.edit().remove(KEY_VEHICLE_NUMBER).apply()
                        cancelButton.isEnabled = false
                        runButton.isEnabled = false
                        saveButton.isEnabled = deviceRegistrationVerified && !selectedVehicle.isNullOrBlank()
                        status.text = "차량 배정 취소 완료 · 단말 등록과 과거 데이터는 유지됩니다. 목록을 갱신합니다."
                        Toast.makeText(this, "차량 배정 취소가 서버에 반영되었습니다.", Toast.LENGTH_LONG).show()

                        // Refresh both the assignment summary and availability list so the
                        // released vehicle is immediately shown as unassigned on this device.
                        debounce?.let(mainHandler::removeCallbacks)
                        debounce = null
                        queryInput.setText("")
                        debounce?.let(mainHandler::removeCallbacks)
                        debounce = null
                        loadCurrentAssignment()
                        search("")
                    }.onFailure { error ->
                        cancelButton.isEnabled = true
                        runButton.isEnabled = true
                        status.text = "차량 배정 취소 실패: ${error.message ?: "서버 연결을 확인하세요."}"
                        Toast.makeText(this, status.text, Toast.LENGTH_LONG).show()
                    }
                }
            }
            .show()
    }

    private fun deviceFingerprint(): String {
        val androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
            ?.trim()?.takeIf { it.isNotEmpty() }
            ?: error("Android 설치 식별값을 읽을 수 없습니다.")
        return MessageDigest.getInstance("SHA-256").digest(androidId.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun assignmentFailureMessage(statusCode: Int, response: String): String {
        val serverCode = runCatching {
            val body = org.json.JSONObject(response)
            sequenceOf("message", "code", "detail", "error").map { body.optString(it) }
                .firstOrNull { it.isNotBlank() && it != "null" }
        }.getOrNull().orEmpty()

        return when (statusCode) {
            HttpURLConnection.HTTP_CONFLICT -> when {
                serverCode.contains("VEHICLE_ALREADY_ASSIGNED_TO_ANOTHER_DEVICE") ->
                    "이 차량은 다른 단말에 이미 배정되어 있습니다."
                serverCode.contains("DEVICE_NOT_ENROLLED") ->
                    "이 단말의 차량관리 등록정보가 서버에 없습니다. Headwind 자동 등록 상태를 확인하세요."
                serverCode.contains("DEVICE_IDENTITY_AMBIGUOUS") ->
                    "이 단말 식별정보가 여러 ID에 연결되어 있습니다. 관리자 확인이 필요합니다."
                else -> "서버가 차량 배정을 거부했습니다 (HTTP 409${serverCode.takeIf { it.isNotBlank() }?.let { ": $it"}.orEmpty()})."
            }
            HttpURLConnection.HTTP_NOT_FOUND -> when {
                serverCode.contains("VEHICLE_NOT_IN_ACTIVE_CATALOG") ->
                    "선택한 차량이 현재 활성 차량목록에 없습니다. 차량목록을 새로고침하세요."
                else -> "대상 단말 또는 차량을 서버에서 찾지 못했습니다 (HTTP 404${serverCode.takeIf { it.isNotBlank() }?.let { ": $it"}.orEmpty()})."
            }
            else -> "서버 저장 HTTP $statusCode ${serverCode.ifBlank { response.take(180) }}"
        }
    }

    private fun saveVehicleAssignment() {
        val vehicle = selectedVehicle
        val base = apiInput.text.toString().trim().trimEnd('/')
        val parsed = runCatching { URL(base) }.getOrNull()
        if (parsed?.protocol != "https" || !base.endsWith("/api")) {
            apiInput.error = "https://서버:8443/api 주소를 확인하세요."
            return
        }
        if (!deviceRegistrationVerified || assignedDeviceNumber.isNullOrBlank()) {
            status.text = "단말 등록 확인 전에는 차량을 배정할 수 없습니다. Headwind 자동 ID 등록 상태를 확인하세요."
            Toast.makeText(this, status.text, Toast.LENGTH_LONG).show()
            loadCurrentAssignment()
            return
        }
        if (vehicle.isNullOrBlank()) {
            Toast.makeText(this, "검색 목록에서 차량을 먼저 선택하세요.", Toast.LENGTH_SHORT).show()
            return
        }
        status.text = "${assignedDeviceNumber.orEmpty()} · 차량 배정 저장 중…"
        saveAssignment(base, vehicle) { result ->
            result.onSuccess { assignment ->
                assignedDeviceNumber = assignment.optString("deviceNumber")
                assignedVehicleNumber = vehicle
                preferences.edit().putString(KEY_API_URL, base).putString(KEY_VEHICLE_NUMBER, vehicle).apply()
                vehicleInput.setText(vehicle)
                selectedLabel.text = "단말 ${assignedDeviceNumber.orEmpty()} · 배정 차량: $vehicle"
                cancelButton.isEnabled = true
                status.text = "차량 배정 저장 완료 · SmartBIS 표출 화면으로 이동합니다."
                runButton.isEnabled = true
                Toast.makeText(this, "차량 배정이 완료되어 SmartBIS 표출을 시작합니다.", Toast.LENGTH_SHORT).show()
                launchSmartBisDisplay()
            }.onFailure { error ->
                status.text = "차량 매핑 저장 실패: ${error.message ?: "서버 연결을 확인하세요."}"
                Toast.makeText(this, status.text, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun launchSmartBisDisplay() {
        val vehicle = assignedVehicleNumber
        if (vehicle.isNullOrBlank()) {
            Toast.makeText(this, "먼저 배정된 차량번호를 확인하세요.", Toast.LENGTH_SHORT).show()
            return
        }
        val base = apiInput.text.toString().trim().trimEnd('/')
        val launch = Intent(ACTION_SET_VEHICLE).apply {
            setClassName(SMARTBIS_PACKAGE, SMARTBIS_ACTIVITY)
            putExtra(EXTRA_VEHICLE_NUMBER, vehicle)
            putExtra(EXTRA_API_BASE_URL, base)
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        }
        try {
            startActivity(launch)
            finish()
        } catch (_: Exception) {
            Toast.makeText(this, "SmartBIS Client가 설치되지 않았습니다.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        debounce?.let(mainHandler::removeCallbacks)
        worker.shutdownNow()
        super.onDestroy()
    }

    private companion object {
        const val KEY_API_URL = "api_url"
        const val KEY_VEHICLE_NUMBER = "vehicle_number"
        const val ACTION_SET_VEHICLE = "com.smartbis.client.action.SET_VEHICLE"
        const val EXTRA_VEHICLE_NUMBER = "com.smartbis.client.extra.VEHICLE_NUMBER"
        const val EXTRA_API_BASE_URL = "com.smartbis.client.extra.API_BASE_URL"
        const val SMARTBIS_PACKAGE = "com.smartbis.client"
        const val SMARTBIS_ACTIVITY = "com.smartbis.client.MainActivity"
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 45_000
        const val SEARCH_DELAY_MS = 350L
        const val STATUS_UNASSIGNED = "UNASSIGNED"
        const val STATUS_ASSIGNED_THIS = "ASSIGNED_TO_THIS_DEVICE"
        const val STATUS_ASSIGNED_OTHER = "ASSIGNED_TO_OTHER_DEVICE"
    }

    private data class VehicleOption(val vehicleNumber: String, val assignmentStatus: String) {
        val statusLabel: String
            get() = when (assignmentStatus) {
                STATUS_ASSIGNED_THIS -> "현재 단말 배정"
                STATUS_ASSIGNED_OTHER -> "다른 단말 배정"
                STATUS_UNASSIGNED -> "미배정"
                else -> "상태 확인 불가"
            }

        override fun toString(): String = "$vehicleNumber  ·  $statusLabel"
    }
}
