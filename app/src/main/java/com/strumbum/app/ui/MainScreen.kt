package com.strumbum.app.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.strumbum.app.ads.Ads
import com.strumbum.app.data.AppSettings
import com.strumbum.app.ui.settings.SettingsActions
import com.strumbum.app.ui.settings.SettingsScreen
import com.strumbum.app.ui.tuner.TunerScreen
import com.strumbum.app.ui.tunings.TuningsScreen
import kotlinx.coroutines.launch

private const val TUNINGS = 0
private const val TUNER = 1
private const val SETTINGS = 2
private val PAGE_NAMES = listOf("Tunings", "Tuner", "Settings")

/** The tuner is home; tunings and settings are one swipe away on either side. */
@Composable
fun MainScreen(vm: MainViewModel, settings: AppSettings) {
    val activity = checkNotNull(LocalActivity.current)
    val mic = rememberMicPermission()
    val pager = rememberPagerState(initialPage = TUNER) { PAGE_NAMES.size }
    val scope = rememberCoroutineScope()
    val tuner by vm.tuner.collectAsStateWithLifecycle()
    val privacyRequired by Ads.privacyOptionsRequired.collectAsStateWithLifecycle()
    val goTo: (Int) -> Unit = { page -> scope.launch { pager.animateScrollToPage(page) } }

    // Consent is asked after onboarding, never over the tuner's first reading.
    LaunchedEffect(Unit) { Ads.gatherConsent(activity) }

    val onTuner = pager.currentPage == TUNER
    ListenWhile(enabled = mic.granted && onTuner, vm = vm)
    KeepScreenOn(onTuner)
    HapticOnLock(vm)
    BackHandler(enabled = !onTuner) { goTo(TUNER) }

    val actions = remember(vm, activity) {
        SettingsActions(
            setA4 = { vm.setA4(it) },
            setThemeMode = { vm.setThemeMode(it) },
            setAmoledBlack = { vm.setAmoledBlack(it) },
            setHighContrast = { vm.setHighContrast(it) },
            setHaptics = { vm.setHaptics(it) },
            showPrivacyOptions = { Ads.showPrivacyOptions(activity) },
        )
    }

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        HorizontalPager(pager, Modifier.weight(1f), beyondViewportPageCount = 1) { page ->
            when (page) {
                TUNINGS -> TuningsScreen(
                    selectedId = settings.tuningId,
                    onSelect = {
                        vm.selectTuning(it.id)
                        goTo(TUNER)
                    },
                )
                TUNER -> TunerScreen(
                    state = tuner,
                    hasMicPermission = mic.granted,
                    onRequestMic = mic::request,
                    onRetry = {
                        vm.stopListening()
                        vm.startListening()
                    },
                    onOpenTunings = { goTo(TUNINGS) },
                    onToggleLock = vm::toggleLock,
                    onAuto = vm::unlock,
                    onPlayTone = vm::playTone,
                )
                SETTINGS -> SettingsScreen(settings, privacyRequired, actions)
            }
        }
        PageTabs(current = pager.currentPage, onSelect = goTo)
    }
}

@Composable
private fun PageTabs(current: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().selectableGroup()) {
        PAGE_NAMES.forEachIndexed { i, name ->
            val selected = i == current
            Text(
                name,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                color = if (selected) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .selectable(selected = selected, role = Role.Tab, onClick = { onSelect(i) })
                    .wrapContentHeight(Alignment.CenterVertically),
            )
        }
    }
}
