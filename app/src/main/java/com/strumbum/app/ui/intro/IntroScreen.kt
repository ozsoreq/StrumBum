package com.strumbum.app.ui.intro

import android.media.AudioManager
import android.net.Uri
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.strumbum.app.R

/** The near-black the intro video is drawn on; the letterbox bars use it so the edges disappear. */
private val IntroBackground = Color(0xFF0A0D11)

/**
 * The brand intro (res/raw/intro.mp4, 15 s), shown on every app launch.
 *
 * - A tap anywhere skips it.
 * - It also ends when playback completes, if the video can't be played, or if the app leaves the
 *   foreground mid-intro, so the user returns straight to the tuner.
 * - The landscape video is fitted, not cropped, so its edge-to-edge titles stay readable on a
 *   portrait screen.
 */
@Composable
fun IntroScreen(onFinished: () -> Unit) {
    val latestOnFinished by rememberUpdatedState(onFinished)
    val finish = remember {
        var done = false
        {
            if (!done) {
                done = true
                latestOnFinished()
            }
        }
    }
    LifecycleResumeEffect(Unit) {
        onPauseOrDispose { finish() }
    }

    Box(Modifier.fillMaxSize().background(IntroBackground)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val video = VideoView(ctx).apply {
                    // Don't pause music the user is already playing; the intro just mixes in.
                    setAudioFocusRequest(AudioManager.AUDIOFOCUS_NONE)
                    setOnCompletionListener { finish() }
                    setOnErrorListener { _, _, _ ->
                        finish()
                        true
                    }
                    setVideoURI(Uri.parse("android.resource://${ctx.packageName}/${R.raw.intro}"))
                    start()
                }
                FrameLayout(ctx).apply {
                    // MATCH_PARENT on both axes makes VideoView keep the video's aspect ratio; gravity centres it.
                    addView(
                        video,
                        FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            Gravity.CENTER,
                        ),
                    )
                }
            },
            onRelease = { (it.getChildAt(0) as VideoView).stopPlayback() },
        )
        // Transparent layer above the video so every tap reaches the skip handler, not the VideoView.
        Box(
            Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = finish,
                )
                .clearAndSetSemantics {
                    contentDescription = "StrumBum intro video"
                    onClick(label = "Skip intro") {
                        finish()
                        true
                    }
                },
        )
        Text(
            "Tap to skip",
            style = MaterialTheme.typography.labelLarge,
            color = Color.White.copy(alpha = 0.6f),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 24.dp),
        )
    }
}
