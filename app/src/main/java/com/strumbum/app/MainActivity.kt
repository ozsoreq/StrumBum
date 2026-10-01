package com.strumbum.app

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.strumbum.app.ui.MainScreen
import com.strumbum.app.ui.MainViewModel
import com.strumbum.app.ui.intro.IntroScreen
import com.strumbum.app.ui.onboarding.OnboardingScreen
import com.strumbum.app.ui.theme.StrumBumTheme
import com.strumbum.app.ui.theme.isDarkTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val vm: MainViewModel = viewModel()
            val loaded by vm.settings.collectAsStateWithLifecycle()
            // Saved across rotation, but a fresh launch always starts with the intro.
            var introDone by rememberSaveable { mutableStateOf(false) }
            val settings = loaded

            // The intro is dark whatever the theme; afterwards the bars follow the app theme.
            val dark = if (!introDone || settings == null) true else settings.isDarkTheme()
            DisposableEffect(dark) {
                val style = if (dark) {
                    SystemBarStyle.dark(Color.TRANSPARENT)
                } else {
                    SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose { }
            }

            if (!introDone) {
                // Python and DataStore keep loading underneath, so the tuner is ready when the intro ends.
                IntroScreen(onFinished = { introDone = true })
                return@setContent
            }
            if (settings == null) return@setContent // DataStore takes a few ms; the window background covers it.

            StrumBumTheme(settings) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    if (settings.onboardingDone) MainScreen(vm, settings) else OnboardingScreen(vm)
                }
            }
        }
    }
}
