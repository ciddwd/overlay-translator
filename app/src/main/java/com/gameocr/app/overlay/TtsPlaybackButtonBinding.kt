package com.gameocr.app.overlay

import android.view.View
import android.widget.ImageButton
import com.gameocr.app.R
import com.gameocr.app.tts.TtsPlaybackButtonMode
import com.gameocr.app.tts.ttsPlaybackButtonMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

internal fun ImageButton.bindTtsPlaybackState(
    action: TtsPlaybackAction,
    speakContentDescription: String,
) {
    fun render() {
        when (ttsPlaybackButtonMode(action.playbackState.value, action.playbackId)) {
            TtsPlaybackButtonMode.SPEAK -> {
                setImageResource(R.drawable.ic_volume_up)
                contentDescription = speakContentDescription
                isActivated = false
            }
            TtsPlaybackButtonMode.PAUSE -> {
                setImageResource(R.drawable.ic_pause)
                contentDescription = context.getString(R.string.word_card_pause_speech)
                isActivated = true
            }
            TtsPlaybackButtonMode.RESUME -> {
                setImageResource(R.drawable.ic_play_arrow)
                contentDescription = context.getString(R.string.word_card_resume_speech)
                isActivated = true
            }
        }
    }

    var collectionJob: Job? = null
    fun startCollecting() {
        collectionJob?.cancel()
        collectionJob = CoroutineScope(Dispatchers.Main.immediate).launch {
            action.playbackState.collect { render() }
        }
    }

    render()
    addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) = startCollecting()

        override fun onViewDetachedFromWindow(view: View) {
            collectionJob?.cancel()
            collectionJob = null
        }
    })
    if (isAttachedToWindow) startCollecting()
}
