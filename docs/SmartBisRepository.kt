package com.smartbis.client.model

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import android.provider.Settings

class SmartBisRepository(
    private val cacheDirectory: File,
    private val baseUrl: () -> String,
    private val vehicleNumber: () -> String
) {
    fun currentAssignedVehicle(androidId: String): Pair<String, String?> {
        val fingerprint = MessageDigest.getInstance("SHA-256")
            .digest(androidId.trim().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val encoded = java.net.URLEncoder.encode(fingerprint, Charsets.UTF_8.name())
        val url = URL("${baseUrl().trimEnd('/')}/v1/device-vehicle-assignment/current?identitySha256=$encoded")
        val body = request(url, allowNoContent = false)
            ?: throw IOException("단말 배정 정보를 받지 못했습니다.")
        val json = JSONObject(body)
        val device = json.optString("deviceNumber").takeIf { it.isNotBlank() && it != "null" }
            ?: throw IOException("MDM 단말 ID가 비어 있습니다.")
        val vehicle = json.optString("vehicleNumber").takeIf { it.isNotBlank() && it != "null" }
        return device to vehicle
    }

    fun searchVehicles(partialNumber: String, serverBaseUrl: String = baseUrl()): List<String> {
        val query = java.net.URLEncoder.encode(partialNumber.trim(), Charsets.UTF_8.name())
        val url = URL("${serverBaseUrl.trimEnd('/')}/v1/vehicles?q=$query")
        val body = request(url, allowNoContent = false) ?: return emptyList()
        val array = org.json.JSONArray(body)
        return buildList {
            for (index in 0 until array.length()) {
                array.optString(index).takeIf(String::isNotBlank)?.let(::add)
            }
        }
    }

    fun currentArrival(): ArrivalSnapshot? {
        val vehicle = java.net.URLEncoder.encode(vehicleNumber(), Charsets.UTF_8.name())
        val url = URL("${baseUrl().trimEnd('/')}/v1/arrival?vehicleNumber=$vehicle")
        val body = request(url, allowNoContent = false, allowNotFound = true) ?: return null
        val json = JSONObject(body)
        return ArrivalSnapshot(
            currentStop = json.optString("currentStop").takeUnless { it.isBlank() || it == "null" } ?: "",
            nextStop = json.optString("nextStop").takeUnless { it.isBlank() || it == "null" } ?: "",
            currentNodeId = json.optString("currentNodeId").takeUnless { it.isBlank() || it == "null" } ?: "",
            nextNodeId = json.optString("nextNodeId").takeUnless { it.isBlank() || it == "null" } ?: "",
            distanceMeters = if (json.isNull("distanceMeters")) 0.0 else json.optDouble("distanceMeters"),
            distanceFromCurrentStopMeters = if (json.isNull("distanceFromCurrentStopMeters")) 0.0
                else json.optDouble("distanceFromCurrentStopMeters"),
            within100m = json.optBoolean("within100m", false),
            estimatedArrivalMinutes = if (json.isNull("estimatedArrivalMinutes")) null
                else json.optInt("estimatedArrivalMinutes").coerceAtLeast(0)
        )
    }

    fun nextDisplay(): DisplayContent? {
        val vehicle = java.net.URLEncoder.encode(vehicleNumber(), Charsets.UTF_8.name())
        val url = URL("${baseUrl().trimEnd('/')}/v1/display/next?vehicleNumber=$vehicle")
        return try {
            val body = request(url, allowNoContent = true) ?: return null
            val content = DisplayContent.from(JSONObject(body))
            if (content.kind !in setOf(DisplayKind.ARRIVAL, DisplayKind.DISASTER, DisplayKind.UNKNOWN)) {
                saveLastDisplay(body)
            }
            content
        } catch (error: Exception) {
            loadLastDisplay() ?: throw error
        }
    }

    /** Last valid non-transient item, available while assignment/API connectivity is down. */
    fun cachedDisplay(): DisplayContent? = loadLastDisplay()

    fun reportDeviceStatus(
        playbackStatus: String,
        content: DisplayContent?,
        errorMessage: String? = null
    ) {
        val url = URL("${baseUrl().trimEnd('/')}/v1/device-status")
        val connection = url.openConnection() as HttpURLConnection
        try {
            val payload = JSONObject()
                .put("vehicleNumber", vehicleNumber())
                .put("playbackStatus", playbackStatus)
                .put("displayType", content?.displayType ?: JSONObject.NULL)
                .put("contentId", content?.contentId ?: JSONObject.NULL)
                .put("contentTitle", content?.title ?: JSONObject.NULL)
                .put("appVersion", com.smartbis.client.BuildConfig.VERSION_NAME)
                .put("errorMessage", errorMessage?.take(1000) ?: JSONObject.NULL)
                .toString()
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = API_READ_TIMEOUT_MS
            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(payload) }
            check(connection.responseCode == HttpURLConnection.HTTP_NO_CONTENT) {
                "단말 상태 보고 HTTP ${connection.responseCode}"
            }
        } finally {
            connection.disconnect()
        }
    }

    fun downloadAssets(content: DisplayContent): List<Pair<DisplayAsset, File>> =
        content.assets.map { asset -> asset to download(asset, content.contentId) }

    private fun download(asset: DisplayAsset, contentId: String): File {
        val safeId = contentId.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "display" }
        val digest = asset.sha256?.take(16) ?: "legacy"
        val extension = asset.fileName.substringAfterLast('.', "bin")
            .lowercase().takeIf { it.matches(Regex("[a-z0-9]{1,8}")) } ?: "bin"
        val target = File(cacheDirectory, "$safeId-${asset.sequence}-$digest.$extension")
        cacheDirectory.mkdirs()
        cleanCache()
        if (target.isFile && AssetIntegrity.isValid(target, asset)) return target
        target.delete()

        val temporary = File(cacheDirectory, "${target.name}.part")
        temporary.delete()
        val connection = (URL(URL(baseUrl().trimEnd('/') + "/"), asset.mediaUrl)
            .openConnection() as HttpURLConnection)
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = MEDIA_READ_TIMEOUT_MS
            check(connection.responseCode == HttpURLConnection.HTTP_OK) {
                "미디어 HTTP ${connection.responseCode}: ${asset.fileName}"
            }
            connection.inputStream.buffered().use { input ->
                temporary.outputStream().buffered().use { output ->
                    input.copyTo(output, DEFAULT_BUFFER_SIZE)
                }
            }
            check(AssetIntegrity.isValid(temporary, asset)) {
                "미디어 무결성 검증 실패(크기/SHA-256): ${asset.fileName}"
            }
            if (target.exists()) target.delete()
            check(temporary.renameTo(target)) { "미디어 파일 확정 실패: ${asset.fileName}" }
            return target
        } catch (error: Exception) {
            temporary.delete()
            throw error
        } finally {
            connection.disconnect()
        }
    }

    private fun saveLastDisplay(body: String) {
        cacheDirectory.mkdirs()
        val target = File(cacheDirectory, LAST_DISPLAY_FILE)
        val temporary = File(cacheDirectory, "$LAST_DISPLAY_FILE.part")
        temporary.writeText(
            JSONObject().put("savedAtMillis", System.currentTimeMillis())
                .put("payload", JSONObject(body)).toString(),
            Charsets.UTF_8
        )
        if (target.exists()) target.delete()
        check(temporary.renameTo(target)) { "마지막 정상 표출 정보 저장 실패" }
    }

    private fun loadLastDisplay(): DisplayContent? = runCatching {
        val file = File(cacheDirectory, LAST_DISPLAY_FILE)
        if (!file.isFile) return null
        val envelope = JSONObject(file.readText(Charsets.UTF_8))
        val content = DisplayContent.from(envelope.getJSONObject("payload"))
        if (content.kind in setOf(DisplayKind.ARRIVAL, DisplayKind.DISASTER, DisplayKind.UNKNOWN)) return null
        content.copy(offlineFallback = true)
    }.getOrNull()

    private fun cleanCache() {
        cacheDirectory.listFiles()?.forEach { file ->
            if (file.name == LAST_DISPLAY_FILE) return@forEach
            // Retain completed media indefinitely so the last saved playlist item remains
            // playable through long server/network outages. Only abandoned partial files expire.
            if (file.name.endsWith(".part") &&
                file.lastModified() < System.currentTimeMillis() - PARTIAL_CACHE_MAX_AGE_MS) file.delete()
        }
    }

    private fun request(
        url: URL,
        allowNoContent: Boolean,
        allowNotFound: Boolean = false
    ): String? {
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/json")
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = API_READ_TIMEOUT_MS
            val code = connection.responseCode
            if (allowNoContent && code == HttpURLConnection.HTTP_NO_CONTENT) return null
            if (allowNotFound && code == HttpURLConnection.HTTP_NOT_FOUND) return null
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            check(code == HttpURLConnection.HTTP_OK) {
                "API HTTP $code" + response.take(240).takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()
            }
            return response
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        const val API_READ_TIMEOUT_MS = 45_000
        const val MEDIA_READ_TIMEOUT_MS = 300_000
        const val LAST_DISPLAY_FILE = "last-display.json"
        const val PARTIAL_CACHE_MAX_AGE_MS = 24 * 60 * 60 * 1000L
    }
}

internal object AssetIntegrity {
    fun isValid(file: File, asset: DisplayAsset): Boolean {
        if (!file.isFile || file.length() <= 0L) return false
        if (asset.sizeBytes != null && file.length() != asset.sizeBytes) return false
        val expected = asset.sha256?.trim()?.lowercase() ?: return true
        if (!expected.matches(Regex("[a-f0-9]{64}"))) return false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        return MessageDigest.isEqual(expected.toByteArray(), actual.toByteArray())
    }
}
