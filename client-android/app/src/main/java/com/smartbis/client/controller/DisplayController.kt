package com.smartbis.client.controller

import android.os.Handler
import android.os.Looper
import com.smartbis.client.model.DisplayContent
import com.smartbis.client.model.DisplayKind
import com.smartbis.client.model.DisplayAsset
import com.smartbis.client.model.ArrivalSnapshot
import com.smartbis.client.model.ArrivalDisplayWindow
import com.smartbis.client.model.ArrivalHeaderState
import com.smartbis.client.model.SmartBisRepository
import java.io.File
import java.util.concurrent.Executors

interface DisplayView {
    fun showLoading()
    fun showWaiting(message: String)
    fun showConnectionState(message: String, healthy: Boolean)
    fun showConnectionError(message: String?)
    fun showPersistentArrival(arrival: ArrivalSnapshot?)
    fun showFullScreenArrival(arrival: ArrivalSnapshot, targetStopName: String, hasReachedTarget: Boolean)
    fun showArrival(content: DisplayContent)
    fun showText(content: DisplayContent)
    fun showPosterText(content: DisplayContent, poster: File?)
    fun showCardNews(content: DisplayContent, image: File, index: Int, count: Int)
    fun showImage(content: DisplayContent, image: File)
    fun showDisaster(content: DisplayContent)
    fun showVideo(content: DisplayContent, videoFile: File, onCompleted: () -> Unit, onError: (String) -> Unit)
    fun stopVideo()
}

/** Controller for the device's server-directed display cycle. The server remains the priority authority. */
class DisplayController(
    private val view: DisplayView,
    private val repository: SmartBisRepository
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val arrivalWorker = Executors.newSingleThreadExecutor()
    private val statusWorker = Executors.newSingleThreadExecutor()
    @Volatile
    private var active = false
    private var requestRunning = false
    private var arrivalRequestRunning = false
    private var videoPlaying = false
    private var currentContent: DisplayContent? = null
    private var currentStatus = "CONNECTING"
    private var currentError: String? = null
    private val arrivalDisplayWindow = ArrivalDisplayWindow()
    private val arrivalHeader = ArrivalHeaderState()
    @Volatile
    private var fullscreenArrivalActive = false
    private var arrivalUnavailableSince: Long? = null
    private var transition: Runnable? = null

    private val poll = object : Runnable {
        override fun run() {
            requestNext()
        }
    }

    private val arrivalPoll = object : Runnable {
        override fun run() {
            requestArrival()
        }
    }

    private val heartbeat = object : Runnable {
        override fun run() {
            if (!active) return
            reportStatus(currentStatus, currentContent, currentError)
            mainHandler.postDelayed(this, HEARTBEAT_INTERVAL_MS)
        }
    }

    private val headerTick = object : Runnable {
        override fun run() {
            if (!active) return
            val now = System.currentTimeMillis()
            view.showPersistentArrival(arrivalHeader.display(now))
            if (arrivalHeader.needsNext(now) && !arrivalRequestRunning) {
                arrivalHeader.markNextRequested()
                requestArrival()
            }
            mainHandler.postDelayed(this, 1_000L)
        }
    }

    fun start() {
        active = true
        view.showLoading()
        reportStatus("CONNECTING", null)
        mainHandler.removeCallbacks(heartbeat)
        mainHandler.postDelayed(heartbeat, HEARTBEAT_INTERVAL_MS)
        mainHandler.removeCallbacks(headerTick)
        mainHandler.post(headerTick)
        requestArrival()
        requestNext()
    }

    fun stop() {
        active = false
        mainHandler.removeCallbacks(poll)
        mainHandler.removeCallbacks(arrivalPoll)
        mainHandler.removeCallbacks(heartbeat)
        mainHandler.removeCallbacks(headerTick)
        transition?.let(mainHandler::removeCallbacks)
        transition = null
        videoPlaying = false
        view.stopVideo()
    }

    fun refresh() {
        if (requestRunning) return
        if (videoPlaying) {
            videoPlaying = false
            view.stopVideo()
        }
        requestNext()
    }

    fun destroy() {
        stop()
        worker.shutdownNow()
        arrivalWorker.shutdownNow()
        statusWorker.shutdownNow()
    }

    private fun requestNext() {
        if (!active || requestRunning || videoPlaying || fullscreenArrivalActive) return
        transition?.let(mainHandler::removeCallbacks)
        transition = null
        requestRunning = true
        reportStatus("CONNECTING", currentContent)
        worker.execute {
            try {
                val content = repository.nextDisplay()
                if (!active) return@execute
                if (content == null) {
                    onMain {
                        requestRunning = false
                        view.showWaiting("운행 정보를 기다리고 있습니다")
                        reportStatus("WAITING", null)
                        schedule(RETRY_NO_CONTENT_MS)
                    }
                    return@execute
                }
                val media = repository.downloadAssets(content)
                if (!active) return@execute
                onMain {
                    requestRunning = false
                    if (fullscreenArrivalActive) return@onMain
                    view.showConnectionState(if (content.offlineFallback) "저장 콘텐츠 재생 중" else "운영 중", healthy = !content.offlineFallback)
                    view.showConnectionError(if (content.offlineFallback) {
                        "단말 등록/차량 배정 확인 실패 : 서버 연결을 복구하는 중 · 저장 콘텐츠 재생 중"
                    } else null)
                    present(content, media)
                }
            } catch (error: Exception) {
                if (!active) return@execute
                val cached = repository.cachedDisplay()
                val cachedMedia = cached?.let { runCatching { repository.downloadAssets(it) }.getOrNull() }
                onMain {
                    requestRunning = false
                    if (cached != null && cachedMedia != null) {
                        view.showConnectionState("저장 콘텐츠 재생 중", healthy = false)
                        view.showConnectionError("단말 등록/차량 배정 확인 실패 : ${error.message ?: "서버 연결 끊김"} · 저장 콘텐츠 재생 중")
                        present(cached, cachedMedia)
                    } else {
                        view.showConnectionState("연결 재시도 중", healthy = false)
                        view.showConnectionError("단말 등록/차량 배정 확인 실패 : ${error.message ?: "서버 연결 끊김"}")
                        if (currentContent == null) view.showWaiting("서버 연결 복구를 기다리고 있습니다")
                        reportStatus("ERROR", currentContent, error.message)
                        schedule(RETRY_AFTER_ERROR_MS)
                    }
                }
            }
        }
    }

    private fun requestArrival() {
        if (!active || arrivalRequestRunning) return
        arrivalRequestRunning = true
        val afterPassedNodeId = arrivalHeader.cursor(System.currentTimeMillis())
        arrivalWorker.execute {
            val arrival = runCatching { repository.currentArrival(afterPassedNodeId) }.getOrNull()
            onMain {
                arrivalRequestRunning = false
                if (!active) return@onMain
                arrivalHeader.accept(arrival, System.currentTimeMillis())
                if (arrival == null) {
                    // A missing/stale backend position must not leave an old ETA on the public display.
                    view.showPersistentArrival(null)
                    val unavailableSince = arrivalUnavailableSince ?: android.os.SystemClock.elapsedRealtime().also {
                        arrivalUnavailableSince = it
                    }
                    if (fullscreenArrivalActive &&
                        android.os.SystemClock.elapsedRealtime() - unavailableSince >= ARRIVAL_STALE_TIMEOUT_MS) {
                        arrivalDisplayWindow.reset()
                        fullscreenArrivalActive = false
                        requestNext()
                    }
                    mainHandler.removeCallbacks(arrivalPoll)
                    mainHandler.postDelayed(arrivalPoll, ARRIVAL_REFRESH_MS)
                    return@onMain
                }
                arrivalUnavailableSince = null
                view.showPersistentArrival(arrivalHeader.display(System.currentTimeMillis()))
                val shouldShowFullscreen = arrivalDisplayWindow.update(arrival)
                if (shouldShowFullscreen) {
                    if (!fullscreenArrivalActive) {
                        transition?.let(mainHandler::removeCallbacks)
                        transition = null
                        if (videoPlaying) {
                            videoPlaying = false
                            view.stopVideo()
                        }
                    }
                    fullscreenArrivalActive = true
                    view.showFullScreenArrival(
                        arrival,
                        arrivalDisplayWindow.trackedStopName(arrival.nextStop),
                        arrivalDisplayWindow.hasReachedTarget()
                    )
                } else if (fullscreenArrivalActive) {
                    fullscreenArrivalActive = false
                    requestNext()
                }
                mainHandler.removeCallbacks(arrivalPoll)
                mainHandler.postDelayed(arrivalPoll, ARRIVAL_REFRESH_MS)
            }
        }
    }

    private fun present(content: DisplayContent, media: List<Pair<DisplayAsset, File>>) {
        currentContent = content
        currentError = if (content.offlineFallback) "서버 연결 끊김 · 저장 콘텐츠 재생 중" else null
        currentStatus = if (content.offlineFallback) "OFFLINE_PLAYBACK" else "PLAYING"
        reportStatus(currentStatus, content, currentError)
        val files = media.map { it.second }
        when (content.kind) {
            DisplayKind.ARRIVAL -> view.showArrival(content)
            DisplayKind.DISASTER -> view.showDisaster(content)
            DisplayKind.POSTER_TEXT -> {
                val poster = media.firstOrNull { it.first.role.contains("POSTER", ignoreCase = true) }
                    ?: media.firstOrNull()
                view.showPosterText(content, poster?.second)
            }
            DisplayKind.TEXT -> view.showText(content)
            DisplayKind.CARD_NEWS -> {
                if (files.isEmpty()) {
                    view.showText(content)
                } else {
                    showCard(content, files, 0, android.os.SystemClock.elapsedRealtime() + content.displaySeconds * 1_000L)
                    return
                }
            }
            DisplayKind.IMAGE -> files.firstOrNull()?.let { view.showImage(content, it) }
                ?: view.showText(content)
            DisplayKind.VIDEO -> (media.firstOrNull { it.first.role.equals("VIDEO", true) }?.second
                ?: files.firstOrNull())?.let { file ->
                videoPlaying = true
                view.showVideo(
                    content,
                    file,
                    onCompleted = {
                        videoPlaying = false
                        requestNext()
                    },
                    onError = {
                        videoPlaying = false
                        view.showConnectionState("영상 재생 오류", healthy = false)
                        reportStatus("ERROR", content, it)
                        schedule(RETRY_AFTER_ERROR_MS)
                    }
                )
            } ?: view.showText(content)
            DisplayKind.UNKNOWN -> if (files.isNotEmpty()) view.showImage(content, files.first())
                else view.showText(content)
        }
        schedule(content.displaySeconds * 1_000L)
    }

    private fun showCard(content: DisplayContent, files: List<File>, index: Int, deadline: Long) {
        view.showCardNews(content, files[index], index + 1, files.size)
        val remainingSlides = files.size - index
        val remainingMillis = deadline - android.os.SystemClock.elapsedRealtime()
        val slideMillis = (remainingMillis / remainingSlides).coerceAtLeast(MIN_TRANSITION_MS)
        schedule(slideMillis) {
            if (index + 1 < files.size) showCard(content, files, index + 1, deadline)
            else requestNext()
        }
    }

    private fun schedule(delayMillis: Long, action: () -> Unit = { requestNext() }) {
        transition?.let(mainHandler::removeCallbacks)
        val next = Runnable {
            transition = null
            if (active && !videoPlaying) action()
        }
        transition = next
        mainHandler.postDelayed(next, delayMillis.coerceAtLeast(MIN_TRANSITION_MS))
    }

    private fun onMain(action: () -> Unit) {
        mainHandler.post(action)
    }

    private fun reportStatus(status: String, content: DisplayContent?, error: String? = null) {
        currentStatus = status
        currentContent = content
        currentError = error
        statusWorker.execute {
            runCatching { repository.reportDeviceStatus(status, content, error) }
        }
    }

    companion object {
        private const val RETRY_NO_CONTENT_MS = 5_000L
        private const val RETRY_AFTER_ERROR_MS = 10_000L
        private const val ARRIVAL_REFRESH_MS = 10_000L
        private const val ARRIVAL_STALE_TIMEOUT_MS = 30_000L
        private const val HEARTBEAT_INTERVAL_MS = 30_000L
        private const val MIN_TRANSITION_MS = 1_000L
    }
}
