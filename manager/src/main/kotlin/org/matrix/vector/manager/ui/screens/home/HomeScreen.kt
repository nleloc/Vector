package org.matrix.vector.manager.ui.screens.home

import android.content.ActivityNotFoundException
import android.content.res.Configuration
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.AddToHomeScreen
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.material3.TextButton
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.util.Locale
import kotlinx.coroutines.launch
import org.matrix.vector.manager.ui.theme.LocalizedOverlay
import org.matrix.vector.manager.R
import org.matrix.vector.manager.ui.theme.CROWDIN_URL
import org.matrix.vector.manager.ui.theme.VectorLocaleController
import org.matrix.vector.ui.locale.LanguageSheet
import org.matrix.vector.manager.di.ServiceLocator
import org.matrix.vector.ui.SharedAlertDialog
import org.matrix.vector.ui.SharedSnackbarHost
import org.matrix.vector.ui.SnackbarTone
import org.matrix.vector.ui.copyToClipboard
import org.matrix.vector.ui.show
import org.matrix.vector.manager.ui.components.statusWordRes
import org.matrix.vector.manager.ui.components.toTone
import org.matrix.vector.manager.data.log.CrashRecorder
import org.matrix.vector.manager.data.repository.ManagerInstallStep
import org.matrix.vector.manager.BuildConfig
import org.matrix.vector.ui.R as UiR
import org.matrix.vector.manager.ui.screens.splash.WingedVictory

import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Scaffold

/**
 * Home is the front page of the *project*, not only of the app.
 *
 * A framework manager is opened by every user, and Vector is built by volunteers, so this screen
 * spends its space on the two questions that matter on opening it: is the framework healthy (one
 * line), and what has the project been doing (everything else).
 *
 * The activity window is a span of time rather than "the latest N commits" — six months by default,
 * and the reader's to change from the appearance sheet. In a quiet stretch the page honestly reads
 * *7 commits by 4 people*, which is real information about the project; a rolling N would hide that.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenUrl: (String) -> Unit,
    onOpenReport: () -> Unit,
    onOpenCrash: () -> Unit,
    viewModel: HomeViewModel = viewModel(factory = HomeViewModel.Factory),
) {
    val status by viewModel.status.collectAsStateWithLifecycle()
    val openExternally by viewModel.openLinksExternally.collectAsStateWithLifecycle()
    val presence by viewModel.presence.collectAsStateWithLifecycle()
    val promptDismissed by viewModel.launcherPromptDismissed.collectAsStateWithLifecycle()
    val hintStatus by viewModel.statusBadgeHint.collectAsStateWithLifecycle()
    val statusNotification by viewModel.statusNotification.collectAsStateWithLifecycle()
    val hiddenIcon by viewModel.hiddenIcon.collectAsStateWithLifecycle()
    val managerInstall by viewModel.managerInstall.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showSplash by rememberSaveable { mutableStateOf(false) }
    var showAppearance by rememberSaveable { mutableStateOf(false) }
    var showLanguage by rememberSaveable { mutableStateOf(false) }
    // Answered or waved away once per visit, not once per return to Home. Saved so that a rotation
    // does not put a dialog back in front of someone who has just dismissed it.
    var showLauncherPrompt by rememberSaveable { mutableStateOf(true) }

    // The status screen has its own copy of this ViewModel — a nav destination is its own store —
    // so a shortcut pinned or an app installed from there is invisible to this one until it is
    // asked again. Coming back to Home is when it is worth asking, and it is also the only moment
    // the badge's hint can start running again, so today's tally of it is re-cut here too.
    LaunchedEffect(Unit) {
        viewModel.refreshPresence()
        viewModel.refreshStatusBadgeHint()
    }

    val device = viewModel.device
    val daemonAlive = status.daemonUsable
    val sections = remember(status, device, context) { buildStatusSections(status, device, context) }
    // The same facts again, in English, for the clipboard — a report is read by a maintainer who
    // may not read the language this phone is set to.
    val englishSections =
        remember(status, device) {
            val english =
                context.createConfigurationContext(
                    Configuration(context.resources.configuration).apply {
                        setLocale(Locale.ENGLISH)
                    }
                )
            buildStatusSections(status, device, english)
        }
    // Read once per visit rather than watched: a crash cannot be recorded while this screen is on
    // screen, because the process that would record it is the one drawing it.
    var crash by remember { mutableStateOf(CrashRecorder.newest(context)) }
    val copied = stringResource(UiR.string.copied)
    val shortcutRefused = stringResource(R.string.launcher_shortcut_refused)
    val installDone = stringResource(R.string.launcher_install_done)

    // Four taps on the wordmark, with the remaining count announced from the second. Two taps
    // could be an accident; past that the reader is clearly poking at it, so the app plays along
    // rather than keeping a secret nobody would find.
    var brandTaps by remember { mutableStateOf(0) }
    var lastBrandTapAt by remember { mutableStateOf(0L) }
    val twoMore = stringResource(R.string.egg_two_more)
    val oneMore = stringResource(R.string.egg_one_more)
    val haptics = LocalHapticFeedback.current
    val snackbars = remember { SnackbarHostState() }
    val eggScope = rememberCoroutineScope()

    fun onBrandTap() {
        val now = System.currentTimeMillis()
        brandTaps = if (now - lastBrandTapAt > BRAND_TAP_WINDOW_MS) 1 else brandTaps + 1
        lastBrandTapAt = now
        when (brandTaps) {
            // The app's own snackbar, not a platform toast. A toast is drawn by the system in the
            // system's style and ignores the theme entirely, which on a screen whose whole point
            // is the surface underneath it reads as a message from another app.
            2 -> eggScope.launch { snackbars.show(twoMore) }
            3 -> eggScope.launch { snackbars.show(oneMore) }
            BRAND_TAPS_TO_SUMMON -> {
                brandTaps = 0
                lastBrandTapAt = 0L
                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                showSplash = true
            }
        }
    }


    fun open(url: String) {
        if (openExternally) {
            try {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: ActivityNotFoundException) {
                // Nothing on the device took the intent. Falling back to the built-in viewer beats
                // a link tap that does nothing at all.
                onOpenUrl(url)
            }
        } else {
            onOpenUrl(url)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold { padding ->
            val listState = rememberLazyListState()
            LazyColumn(
                state = listState,
                modifier = Modifier.padding(padding).fillMaxWidth(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 16.dp),
        ) {
            item {
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(bottom = 16.dp, start = 8.dp)
                        .clickable(
                            // Keep Easter Egg :D
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = ::onBrandTap,
                        )
                )

                StatusBanner(status = status)
                Spacer(Modifier.height(16.dp))
            }

            systemStatusSection(
                status = status,
                sections = sections,
                crash = crash,
                onOpenCrashTrace = onOpenCrash,
                onClearCrash = {
                    CrashRecorder.clear(context)
                    crash = null
                },
                statusNotification = statusNotification,
                hiddenIcon = hiddenIcon,
                daemonAlive = daemonAlive,
                presence = presence,
                managerInstall = managerInstall,
                onSetStatusNotification = viewModel::setStatusNotification,
                onSetForcedLauncherIcons = viewModel::setForcedLauncherIcons,
                onCreateShortcut = {
                    if (!viewModel.requestShortcut()) {
                        eggScope.launch { snackbars.show(shortcutRefused, SnackbarTone.Failure) }
                    }
                },
                onEnableNotification = { viewModel.setStatusNotification(true) },
                onInstall = viewModel::installManagerApp,
                onRemoveConflicting = viewModel::removeConflictingManager,
            )

            item {
                Spacer(Modifier.height(24.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    IconButton(onClick = { showLanguage = true }) {
                        Icon(Icons.Rounded.Translate, contentDescription = stringResource(R.string.language_title))
                    }

                    IconButton(onClick = { showAppearance = true }) {
                        Icon(Icons.Rounded.Palette, contentDescription = stringResource(R.string.appearance_title))
                    }

                    IconButton(
                        onClick = {
                            copyToClipboard(
                                context,
                                englishSections.joinToString("\n\n") { (heading, items) ->
                                    heading +
                                        items.joinToString("") {
                                            "\n  ${it.label}: ${it.value}${it.detail.orEmpty()}"
                                        }
                                },
                                BuildConfig.MANAGER_PACKAGE_NAME,
                            )
                            eggScope.launch { snackbars.show(copied, SnackbarTone.Success) }
                        }
                    ) {
                        Icon(
                            Icons.Rounded.ContentCopy,
                            contentDescription = stringResource(UiR.string.action_copy_all),
                        )
                    }
                }
            }
        }
    }

    Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 48.dp)
        ) {
            SharedSnackbarHost(snackbars)
        }
    }
 
    LaunchedEffect(managerInstall) {
        if (managerInstall !is ManagerInstallStep.Done) return@LaunchedEffect
        viewModel.acknowledgeManagerInstall()
        eggScope.launch { snackbars.show(installDone, SnackbarTone.Success) }
    }

    if (showLanguage) {
        LanguageSheet(
            controller = VectorLocaleController,
            onDismiss = { showLanguage = false },
            onHelpTranslate = { open(CROWDIN_URL) },
            onOpenUrl = ::open,
        )
    }

    if (showAppearance) {
        HomeAppearanceSheet(onDismiss = { showAppearance = false })
    }

    // Nothing in the launcher points at a parasitic manager, so someone who reached this screen
    // through the root manager's action button has no way of finding it again — which is what #815
    // reported. Asked once, on the first launch that could act on the answer, and never again after
    // "Don't ask again" or after either remedy has been applied. Dismissing it any other way means
    // "later": the offer stays on the status page and returns on the next launch.
    if (
        showLauncherPrompt &&
            presence.unreachable &&
            !promptDismissed &&
            // With no usable daemon there is no APK to install and bigger problems to report
            // first.
            status.daemonUsable
    ) {
        LauncherPrompt(
            shortcutSupported = presence.shortcutSupported,
            onCreateShortcut = {
                showLauncherPrompt = false
                viewModel.requestShortcut()
            },
            onInstall = {
                showLauncherPrompt = false
                viewModel.installManagerApp()
            },
            onNever = {
                showLauncherPrompt = false
                viewModel.dismissLauncherPrompt()
            },
            onLater = { showLauncherPrompt = false },
        )
    }

    // Summoned by four taps on the wordmark. A dialog rather than an overlay inside the content,
    // so it covers the navigation bar too — a splash framed by app chrome is not a splash.
    if (showSplash) {
        Dialog(
            onDismissRequest = { showSplash = false },
            properties =
                DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = true),
        ) {
LocalizedOverlay {

            Box(
                modifier =
                    Modifier.fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) {
                            showSplash = false
                        }
            ) {
                WingedVictory()
            }
            LaunchedEffect(Unit) {
                kotlinx.coroutines.delay(2800)
                showSplash = false
            }
        }
}
    }
}

/**
 * The one prompt Vector shows unasked, and only when there is genuinely no way back in.
 *
 * Two buttons rather than four: the primary is whichever remedy this device can actually apply, and
 * the other is the refusal. "Later" is the dialog's ordinary dismissal — tapping away or pressing
 * back — because that is already what dismissing a dialog means, and a third button spelling it out
 * would crowd out the two that do something.
 */
@Composable
private fun LauncherPrompt(
    shortcutSupported: Boolean,
    onCreateShortcut: () -> Unit,
    onInstall: () -> Unit,
    onNever: () -> Unit,
    onLater: () -> Unit,
) {
    SharedAlertDialog(
        onDismissRequest = onLater,
        icon = { Icon(Icons.AutoMirrored.Rounded.AddToHomeScreen, contentDescription = null) },
        title = { Text(stringResource(R.string.launcher_prompt_title)) },
        text = { Text(stringResource(R.string.launcher_prompt_body)) },
        confirmButton = {
            // A launcher that refuses pin requests leaves installing as the only remedy, so that is
            // what the button offers rather than one that would visibly do nothing.
            if (shortcutSupported) {
                TextButton(onClick = onCreateShortcut) {
                    Text(stringResource(R.string.launcher_shortcut_create))
                }
            } else {
                TextButton(onClick = onInstall) {
                    Text(stringResource(R.string.launcher_install_action))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onNever) { Text(stringResource(R.string.launcher_prompt_never)) }
        },
    )
}

/**
 * The framework's health, as a Material card rather than JingMatrix's animated ambience header.
 *
 * Tonal colour carries the verdict at a glance — a reader should know the framework is fine or is
 * not before reading a single word — and the actions that used to live in the header's own menu
 * (appearance, language, copy) sit in the same row, since there is no longer a separate header
 * surface to host them.
 */

@Composable
private fun StatusBanner(
  status: FrameworkStatus,
) {
    val healthy = status.issues.isEmpty() && status.daemonUsable
    val container =
        if (healthy) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.errorContainer
    val onContainer =
        if (healthy) MaterialTheme.colorScheme.onPrimaryContainer
        else MaterialTheme.colorScheme.onErrorContainer

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.defaultColors(
            color = container
        )
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (healthy) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline,
                    contentDescription = null,
                    tint = onContainer
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(status.state.statusWordRes()),
                    style = MaterialTheme.typography.titleMedium,
                    color = onContainer
                )
            }

            if (status.apiVersion != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "API ${status.apiVersion}",
                    color = onContainer.copy(alpha = 0.75f),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

/** Taps must land within this window of each other to count towards the same run. */
private const val BRAND_TAP_WINDOW_MS = 2600L

private const val BRAND_TAPS_TO_SUMMON = 4
