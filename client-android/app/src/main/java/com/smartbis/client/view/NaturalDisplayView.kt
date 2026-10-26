package com.smartbis.client.view

import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.VideoView
import androidx.core.view.isVisible
import com.smartbis.client.R
import com.smartbis.client.controller.DisplayView
import com.smartbis.client.model.DisplayContent
import com.smartbis.client.model.ArrivalSnapshot
import java.io.File

class NaturalDisplayView(private val root: View) : DisplayView {
    private val arrival = root.findViewById<View>(R.id.arrivalGroup)
    private val posterText = root.findViewById<View>(R.id.posterTextGroup)
    private val textOnly = root.findViewById<View>(R.id.textGroup)
    private val cardNews = root.findViewById<View>(R.id.cardNewsGroup)
    private val fullImage = root.findViewById<View>(R.id.fullImageGroup)
    private val disaster = root.findViewById<View>(R.id.disasterGroup)
    private val video = root.findViewById<View>(R.id.videoGroup)
    private val waiting = root.findViewById<View>(R.id.waitingGroup)
    private val status = root.findViewById<TextView>(R.id.connectionStatus)
    private val connectionError = root.findViewById<TextView>(R.id.connectionError)
    private val arrivalOverlayFrame = root.findViewById<View>(R.id.arrivalOverlayFrame)
    private val contentOverlay = root.findViewById<View>(R.id.contentOverlay)
    private val cardImageOne = root.findViewById<ImageView>(R.id.cardNewsImage)
    private val cardImageTwo = root.findViewById<ImageView>(R.id.cardNewsImageIncoming)
    private var visibleCardImage = cardImageOne
    private var incomingCardImage = cardImageTwo
    private var cardContentId: String? = null
    private var cardSequence: Int? = null

    init {
        root.findViewById<SvgAssetView>(R.id.brandCharacter).assetPath = "brand/charackter.svg"
        root.findViewById<SvgAssetView>(R.id.brandBi).assetPath = "brand/BI_h100.svg"
        root.findViewById<SvgAssetView>(R.id.brandCi).assetPath = "brand/CI_h100.svg"
    }

    fun updateClock(text: String) {
        root.findViewById<TextView>(R.id.clockText).text = text
    }

    fun setOperatorGesture(action: () -> Unit) {
        val brand = root.findViewById<View>(R.id.brandMark)
        val handler = Handler(Looper.getMainLooper())
        var triggered = false
        val openSettings = Runnable {
            triggered = true
            action()
        }
        brand.contentDescription = "운영자 설정을 열려면 장성군 표식을 2초간 누르세요"
        brand.setOnClickListener { }
        brand.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    triggered = false
                    handler.postDelayed(openSettings, OPERATOR_GESTURE_MS)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(openSettings)
                    if (triggered) view.performClick()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(openSettings)
                    true
                }
                else -> true
            }
        }
    }

    override fun showLoading() {
        showWaiting("안내 정보를 준비하고 있습니다")
    }

    override fun showWaiting(message: String) {
        activate(waiting)
        contentOverlay.isVisible = false
        root.findViewById<TextView>(R.id.waitingText).text = message
    }

    override fun showConnectionState(message: String, healthy: Boolean) {
        status.text = message
        status.background = pill(if (healthy) FOREST else ALERT)
    }

    override fun showConnectionError(message: String?) {
        // Keep connectivity diagnostics out of the public passenger display.
        connectionError.text = ""
        connectionError.isVisible = false
    }

    override fun showPersistentArrival(arrival: ArrivalSnapshot?) {
        setText(R.id.headerCurrentStop, "")
        setText(R.id.headerNextStop, com.smartbis.client.model.ArrivalHeader.text(arrival, System.currentTimeMillis()))
    }

    override fun showFullScreenArrival(arrival: ArrivalSnapshot, targetStopName: String, hasReachedTarget: Boolean) {
        activate(this.arrival)
        contentOverlay.isVisible = false
        setText(R.id.arrivalCurrent, when (arrival.arrivalStatusText) {
            "통과" -> "정류소 통과"
            "통과 추정" -> "정류소 통과 추정"
            else -> if (hasReachedTarget) "정류소 확인 중" else arrival.currentStop.ifBlank { "운행 중" }
        })
        setText(R.id.arrivalNext, targetStopName.ifBlank { "정류소 도착" })
        val distance = if (hasReachedTarget) {
            arrival.distanceFromCurrentStopMeters
        } else {
            arrival.distanceMeters
        }
        if (hasReachedTarget) {
            setText(R.id.arrivalDistance, "정류소 출발 후 ${distance.toInt()}m")
            val confirmed = arrival.arrivalStatusText?.takeIf { it == "통과" || it == "통과 추정" }
            setText(R.id.arrivalRouteText, "$targetStopName · ${confirmed ?: "출발 확인 중"}")
        } else {
            val eta = " · ${com.smartbis.client.model.ArrivalHeader.timingText(arrival, System.currentTimeMillis())}"
            setText(R.id.arrivalDistance, "정류소까지 ${distance.toInt()}m$eta")
            setText(R.id.arrivalRouteText, "${arrival.currentStop.ifBlank { "현재 위치" }}    →    $targetStopName")
        }
    }

    override fun showArrival(content: DisplayContent) {
        activate(arrival)
        showContentOverlay(content.title.ifBlank { "${content.currentStop ?: "이번 정류장"} → ${content.nextStop ?: "다음 정류장"}" })
        setText(R.id.arrivalCurrent, content.currentStop ?: "정보 확인 중")
        setText(R.id.arrivalNext, content.nextStop ?: "다음 정류장 안내")
        setText(R.id.arrivalDistance, content.distanceMeters?.let { "정류장까지 ${it.toInt()}m" } ?: "버스 도착 안내")
        setText(R.id.arrivalRouteText, "${content.currentStop ?: "이번 정류장"}    →    ${content.nextStop ?: "다음 정류장"}")
    }

    override fun showText(content: DisplayContent) {
        activate(textOnly)
        showContentOverlay(content.title)
        setText(R.id.textCategory, categoryLabel(content))
        setText(R.id.textTitle, content.title.ifBlank { categoryLabel(content) })
        setText(R.id.textBody, content.text)
        val accent = categoryAccent(content)
        root.findViewById<View>(R.id.textCategory).background = pill(accent)
        root.findViewById<View>(R.id.textAccent).setBackgroundColor(accent)
    }

    override fun showPosterText(content: DisplayContent, poster: File?) {
        activate(posterText)
        showContentOverlay(content.title)
        setText(R.id.posterCategory, categoryLabel(content))
        setText(R.id.posterTitle, content.title)
        setText(R.id.posterBody, content.text)
        root.findViewById<View>(R.id.posterCategory).background = pill(categoryAccent(content))
        val posterView = root.findViewById<ImageView>(R.id.posterImage)
        posterView.setImageBitmap(poster?.let { BitmapFactory.decodeFile(it.absolutePath) })
        root.findViewById<View>(R.id.posterPlaceholder).isVisible = poster == null
    }

    override fun showCardNews(content: DisplayContent, image: File, index: Int, count: Int) {
        activate(cardNews)
        showContentOverlay(content.title)
        val bitmap = BitmapFactory.decodeFile(image.absolutePath)
        if (cardContentId != content.contentId || index == 1 || cardSequence == null) {
            resetCardImages()
            visibleCardImage.setImageBitmap(bitmap)
            visibleCardImage.isVisible = true
            cardContentId = content.contentId
        } else if (cardSequence != index) {
            slideCardLeft(bitmap)
        }
        cardSequence = index
        setText(R.id.cardNewsCaption, "$index / $count")
    }

    override fun showImage(content: DisplayContent, image: File) {
        activate(fullImage)
        showContentOverlay(content.title)
        root.findViewById<ImageView>(R.id.fullImage).setImageBitmap(BitmapFactory.decodeFile(image.absolutePath))
    }

    override fun showDisaster(content: DisplayContent) {
        activate(disaster)
        showContentOverlay(content.title.ifBlank { "재난 안전 안내" })
        setText(R.id.disasterTitle, content.title.ifBlank { "재난 안전 안내" })
        setText(R.id.disasterBody, content.text)
    }

    override fun showVideo(content: DisplayContent, videoFile: File, onCompleted: () -> Unit, onError: (String) -> Unit) {
        activate(video)
        showContentOverlay(content.title)
        val player = root.findViewById<VideoView>(R.id.videoPlayer)
        player.setOnCompletionListener { onCompleted() }
        player.setOnErrorListener { _, what, extra ->
            onError("미디어 재생 오류 ($what/$extra)")
            true
        }
        player.setOnPreparedListener { mediaPlayer ->
            mediaPlayer.isLooping = false
            (player as? CenteredVideoView)?.updateSourceSize(mediaPlayer.videoWidth, mediaPlayer.videoHeight)
            mediaPlayer.start()
        }
        player.setVideoPath(videoFile.absolutePath)
    }

    override fun stopVideo() {
        root.findViewById<VideoView>(R.id.videoPlayer).runCatchingStop()
    }

    private fun activate(selected: View) {
        listOf(arrival, posterText, textOnly, cardNews, fullImage, disaster, video, waiting)
            .forEach { it.isVisible = it === selected }
        // Arrival information is a persistent header overlay for every content type,
        // not a part of the full-screen arrival content surface.
        arrivalOverlayFrame.isVisible = true
        contentOverlay.isVisible = selected !== waiting
    }

    private fun showContentOverlay(title: String) {
        setText(R.id.overlayTitle, title)
        contentOverlay.isVisible = true
    }

    private fun slideCardLeft(bitmap: android.graphics.Bitmap?) {
        val width = cardNews.width.takeIf { it > 0 } ?: root.width
        val distance = width.toFloat()
        val outgoing = visibleCardImage
        val incoming = incomingCardImage

        incoming.animate().cancel()
        outgoing.animate().cancel()
        incoming.setImageBitmap(bitmap)
        incoming.translationX = distance
        incoming.isVisible = true
        incoming.animate()
            .translationX(0f)
            .setDuration(CARD_SLIDE_DURATION_MS)
            .start()
        outgoing.animate()
            .translationX(-distance)
            .setDuration(CARD_SLIDE_DURATION_MS)
            .withEndAction {
                outgoing.isVisible = false
                outgoing.translationX = 0f
                outgoing.setImageDrawable(null)
            }
            .start()

        visibleCardImage = incoming
        incomingCardImage = outgoing
    }

    private fun resetCardImages() {
        listOf(cardImageOne, cardImageTwo).forEach { image ->
            image.animate().cancel()
            image.translationX = 0f
            image.setImageDrawable(null)
            image.isVisible = false
        }
        visibleCardImage = cardImageOne
        incomingCardImage = cardImageTwo
        cardSequence = null
    }

    private fun setText(id: Int, text: String) {
        root.findViewById<TextView>(id).text = text
    }

    private fun categoryLabel(content: DisplayContent): String = when (content.category.uppercase()) {
        "EVENT" -> "장성 소식  ·  행사"
        "NOTICE" -> "장성 소식  ·  공지"
        else -> content.displayType.ifBlank { "장성 소식" }
    }

    private fun categoryAccent(content: DisplayContent): Int =
        if (content.category.equals("EVENT", true)) TERRACOTTA else FOREST

    private fun pill(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 32f
        setColor(color)
    }

    private fun VideoView.runCatchingStop() {
        try {
            stopPlayback()
            setOnCompletionListener(null)
            setOnErrorListener(null)
            setOnPreparedListener(null)
        } catch (_: IllegalStateException) {
            // The underlying player can already be released when the activity stops.
        }
    }

    private companion object {
        const val OPERATOR_GESTURE_MS = 2_000L
        const val CARD_SLIDE_DURATION_MS = 600L
        val FOREST = Color.rgb(47, 82, 51)
        val ALERT = Color.rgb(154, 47, 44)
        val TERRACOTTA = Color.rgb(181, 86, 60)
    }
}
