package com.strumbum.app.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.HapticFeedbackConstants
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect

/** Record-audio permission state that refreshes when the user comes back from system settings. */
@Stable
class MicPermissionState internal constructor(private val activity: Activity) {
    var granted by mutableStateOf(check())
        internal set
    private var deniedOnce by mutableStateOf(false)
    internal var launcher: ManagedActivityResultLauncher<String, Boolean>? = null

    /** True after a denial the system won't ask about again; only app settings can grant it then. */
    val needsSystemSettings: Boolean
        get() = deniedOnce && !granted && !activity.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)

    fun request() {
        if (needsSystemSettings) {
            activity.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", activity.packageName, null)),
            )
        } else {
            launcher?.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    internal fun onResult(ok: Boolean) {
        granted = ok
        if (!ok) deniedOnce = true
    }

    internal fun refresh() {
        granted = check()
    }

    private fun check() =
        ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
}

@Composable
fun rememberMicPermission(): MicPermissionState {
    val activity = checkNotNull(LocalActivity.current)
    val state = remember(activity) { MicPermissionState(activity) }
    state.launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission(), state::onResult)
    LifecycleResumeEffect(state) {
        state.refresh()
        onPauseOrDispose { }
    }
    return state
}

/** Listen to the microphone only while [enabled] and the activity is in the foreground. */
@Composable
fun ListenWhile(enabled: Boolean, vm: MainViewModel) {
    LifecycleResumeEffect(enabled, vm) {
        if (enabled) vm.startListening()
        onPauseOrDispose { if (enabled) vm.stopListening() }
    }
}

@Composable
fun KeepScreenOn(on: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, on) {
        view.keepScreenOn = on
        onDispose { view.keepScreenOn = false }
    }
}

/** The short tick when a string locks in tune. It respects the system's touch-feedback setting. */
@Composable
fun HapticOnLock(vm: MainViewModel) {
    val view = LocalView.current
    LaunchedEffect(vm, view) {
        vm.lockEvents.collect {
            view.performHapticFeedback(
                if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY,
            )
        }
    }
}
