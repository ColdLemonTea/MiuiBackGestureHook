// SPDX-License-Identifier: Apache-2.0
package dev.codex.miuibackgesturehook.activity

import android.annotation.SuppressLint
import android.app.BroadcastOptions
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircleOutline
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.codex.miuibackgesturehook.BuildConfig
import dev.codex.miuibackgesturehook.ModuleApplication
import dev.codex.miuibackgesturehook.PredictiveBackPreferences
import dev.codex.miuibackgesturehook.R
import dev.codex.miuibackgesturehook.ZnStatusKind
import dev.codex.miuibackgesturehook.ZnStatusProtocol
import dev.codex.miuibackgesturehook.ZnStatusUiState
import io.github.libxposed.service.XposedService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.layerBackdrop
import dev.codex.miuibackgesturehook.util.miuixBlurEffect
import dev.codex.miuibackgesturehook.util.rememberMiuixBlurBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

class PredictiveBackSettingsActivity :
    ComponentActivity(),
    ModuleApplication.ServiceStateListener {
    private var xposedService: XposedService? by mutableStateOf(null)
    private var serviceStateObserved by mutableStateOf(false)
    private var znStatus by mutableStateOf(
        ZnStatusUiState.checking(Build.VERSION.SDK_INT < ANDROID_17_API_LEVEL),
    )
    private val statusHandler = Handler(Looper.getMainLooper())
    private var statusNonce = 0L
    private var systemUiResponseReceived = false
    private var systemUiReadyReported = false
    private var statusReceiverRegistered = false
    private val statusTimeout = Runnable {
        if (statusNonce != 0L) {
            statusNonce = 0L
            znStatus = if (!systemUiResponseReceived) {
                ZnStatusUiState.noResponse()
            } else if (!systemUiReadyReported) {
                ZnStatusUiState(ZnStatusKind.SystemUiNotReady)
            } else {
                ZnStatusUiState(
                    kind = ZnStatusKind.NativeNoResponse,
                    legacyMode = Build.VERSION.SDK_INT < ANDROID_17_API_LEVEL,
                )
            }
        }
    }
    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ZnStatusProtocol.ACTION_REPLY) {
                return
            }
            val senderUid = getSentFromUid()
            val senderPackage = getSentFromPackage()
            if (senderUid == Process.INVALID_UID
                || senderPackage != ZnStatusProtocol.SYSTEM_UI_PACKAGE
                || !isUidOwner(senderUid, ZnStatusProtocol.SYSTEM_UI_PACKAGE)
            ) {
                return
            }
            val nonce = intent.getLongExtra(ZnStatusProtocol.EXTRA_NONCE, 0L)
            if (nonce <= 0L || nonce != statusNonce) {
                return
            }
            znStatus = ZnStatusUiState.fromReply(intent)
            val nativeResponse = intent.getBooleanExtra(
                ZnStatusProtocol.EXTRA_NATIVE_RESPONSE,
                false,
            )
            if (!nativeResponse) {
                systemUiResponseReceived = true
                systemUiReadyReported = intent.getBooleanExtra(
                    ZnStatusProtocol.EXTRA_SYSTEMUI_READY,
                    false,
                )
            } else {
                statusHandler.removeCallbacks(statusTimeout)
                statusNonce = 0L
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val colors = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
            MiuixTheme(colors = colors) {
                PredictiveBackSettingsScreen(
                    service = xposedService,
                    serviceStateObserved = serviceStateObserved,
                    znStatus = znStatus,
                    onRefreshZnStatus = ::requestZnStatus,
                    onClose = { finish() },
                    onOpenGestureTriggerSettings = {
                        startActivity(
                            Intent(
                                this,
                                GestureTriggerSettingsActivity::class.java,
                            ),
                        )
                    },
                    onOpenAppList = {
                        startActivity(
                            Intent(
                                this,
                                PredictiveBackAppListActivity::class.java,
                            ),
                        )
                    },
                    onOpenContextualSearchSettings = {
                        startActivity(
                            Intent(
                                this,
                                ContextualSearchSettingsActivity::class.java,
                            ),
                        )
                    },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        ModuleApplication.addServiceStateListener(this, notifyImmediately = true)
        registerStatusReceiver()
        requestZnStatus()
    }

    override fun onStop() {
        unregisterStatusReceiver()
        ModuleApplication.removeServiceStateListener(this)
        super.onStop()
    }

    override fun onServiceStateChanged(service: XposedService?) {
        xposedService = service
        serviceStateObserved = true
    }

    private fun registerStatusReceiver() {
        if (statusReceiverRegistered) {
            return
        }
        val filter = IntentFilter(ZnStatusProtocol.ACTION_REPLY)
        registerReceiver(statusReceiver, filter, Context.RECEIVER_EXPORTED)
        statusReceiverRegistered = true
    }

    private fun unregisterStatusReceiver() {
        if (!statusReceiverRegistered) {
            return
        }
        statusHandler.removeCallbacks(statusTimeout)
        statusNonce = 0L
        systemUiResponseReceived = false
        systemUiReadyReported = false
        unregisterReceiver(statusReceiver)
        statusReceiverRegistered = false
    }

    private fun requestZnStatus() {
        if (!statusReceiverRegistered) {
            return
        }
        val nonce = SystemClock.elapsedRealtimeNanos().coerceAtLeast(1L)
        statusNonce = nonce
        systemUiResponseReceived = false
        systemUiReadyReported = false
        znStatus = ZnStatusUiState.checking(
            Build.VERSION.SDK_INT < ANDROID_17_API_LEVEL,
        )
        statusHandler.removeCallbacks(statusTimeout)
        statusHandler.postDelayed(statusTimeout, STATUS_TIMEOUT_MS)
        try {
            val query = Intent(ZnStatusProtocol.ACTION_QUERY)
                .setPackage(ZnStatusProtocol.SYSTEM_UI_PACKAGE)
                .putExtra(ZnStatusProtocol.EXTRA_NONCE, nonce)
                .putExtra("sender_uid", Process.myUid())
            val options = BroadcastOptions.makeBasic()
                .setShareIdentityEnabled(true)
                .toBundle()
            sendBroadcast(query, null, options)
        } catch (_: Throwable) {
            statusHandler.removeCallbacks(statusTimeout)
            statusNonce = 0L
            znStatus = ZnStatusUiState.noResponse()
        }
    }

    private fun isUidOwner(uid: Int, packageName: String): Boolean {
        return try {
            packageManager.getPackagesForUid(uid)?.contains(packageName) == true
        } catch (_: Throwable) {
            false
        }
    }

    companion object {
        private const val STATUS_TIMEOUT_MS = 2500L
        private const val ANDROID_17_API_LEVEL = 37
    }
}

@Composable
private fun ZnRuntimeStatusCard(
    state: ZnStatusUiState,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ready = state.kind == ZnStatusKind.Ready
    val warning = state.kind == ZnStatusKind.Checking
        || state.kind == ZnStatusKind.WaitingForNative
        || state.kind == ZnStatusKind.SystemUiNotReady
        || state.kind == ZnStatusKind.NativeNotReady
        || state.kind == ZnStatusKind.LegacyNotReady
    val title = when (state.kind) {
        ZnStatusKind.Checking -> stringResource(
            if (state.legacyMode) {
                R.string.zn_status_legacy_checking_title
            } else {
                R.string.zn_status_checking_title
            },
        )
        ZnStatusKind.WaitingForNative -> stringResource(
            if (state.legacyMode) {
                R.string.zn_status_legacy_waiting_title
            } else {
                R.string.zn_status_waiting_title
            },
        )
        ZnStatusKind.Ready -> stringResource(
            if (state.legacyMode) {
                R.string.zn_status_ready_legacy_title
            } else {
                R.string.zn_status_ready_title
            },
        )
        ZnStatusKind.SystemUiNotReady ->
            stringResource(R.string.zn_status_systemui_not_ready_title)
        ZnStatusKind.NativeNotReady ->
            stringResource(R.string.zn_status_native_not_ready_title)
        ZnStatusKind.LegacyNotReady ->
            stringResource(R.string.zn_status_legacy_not_ready_title)
        ZnStatusKind.NativeNoResponse ->
            stringResource(
                if (state.legacyMode) {
                    R.string.zn_status_legacy_no_response_title
                } else {
                    R.string.zn_status_native_no_response_title
                },
            )
        ZnStatusKind.ProfileRejected ->
            stringResource(R.string.zn_status_profile_rejected_title)
        ZnStatusKind.NoResponse -> stringResource(R.string.zn_status_no_response_title)
        ZnStatusKind.LsPosedUnavailable ->
            stringResource(R.string.zn_status_lsposed_unavailable_title)
    }
    val summary = when (state.kind) {
        ZnStatusKind.Checking -> stringResource(
            if (state.legacyMode) {
                R.string.zn_status_legacy_checking_summary
            } else {
                R.string.zn_status_checking_summary
            },
        )
        ZnStatusKind.WaitingForNative ->
            stringResource(
                if (state.legacyMode) {
                    R.string.zn_status_legacy_waiting_summary
                } else {
                    R.string.zn_status_waiting_summary
                },
            )
        ZnStatusKind.Ready -> stringResource(
            if (state.legacyMode) {
                R.string.zn_status_ready_legacy_summary
            } else if (state.profileDynamic) {
                R.string.zn_status_ready_runtime_summary
            } else {
                R.string.zn_status_ready_static_summary
            },
        )
        ZnStatusKind.SystemUiNotReady ->
            stringResource(R.string.zn_status_systemui_not_ready_summary)
        ZnStatusKind.NativeNotReady ->
            stringResource(R.string.zn_status_native_not_ready_summary)
        ZnStatusKind.LegacyNotReady ->
            stringResource(R.string.zn_status_legacy_not_ready_summary)
        ZnStatusKind.NativeNoResponse ->
            stringResource(
                if (state.legacyMode) {
                    R.string.zn_status_legacy_no_response_summary
                } else {
                    R.string.zn_status_native_no_response_summary
                },
            )
        ZnStatusKind.ProfileRejected ->
            stringResource(R.string.zn_status_profile_rejected_summary)
        ZnStatusKind.NoResponse -> stringResource(R.string.zn_status_no_response_summary)
        ZnStatusKind.LsPosedUnavailable ->
            stringResource(R.string.zn_status_lsposed_unavailable_summary)
    }
    val mode: String? = if (state.kind == ZnStatusKind.Ready) {
        when {
            state.legacyMode -> "LSPOSED"
            state.profileDynamic -> stringResource(R.string.zn_status_mode_runtime_profile)
            else -> stringResource(R.string.zn_status_mode_builtin_profile)
        }
    } else {
        null
    }
    val cardColor = when {
        ready && MiuixTheme.isDynamicColor -> MiuixTheme.colorScheme.secondaryContainer
        ready && isSystemInDarkTheme() -> Color(0xFF1A3825)
        ready -> Color(0xFFDFFAE4)
        warning && isSystemInDarkTheme() -> Color(0xFF3D3215)
        warning -> Color(0xFFFFF3CD)
        MiuixTheme.isDynamicColor -> MiuixTheme.colorScheme.secondaryContainer
        isSystemInDarkTheme() -> Color(0xFF3A1E22)
        else -> Color(0xFFFFE4E1)
    }
    val iconTint = when {
        ready && MiuixTheme.isDynamicColor ->
            MiuixTheme.colorScheme.primary.copy(alpha = 0.8f)
        ready -> Color(0xFF36D167)
        warning && isSystemInDarkTheme() -> Color(0xFFFFC107)
        warning -> Color(0xFFFFB300)
        MiuixTheme.isDynamicColor -> MiuixTheme.colorScheme.primary.copy(alpha = 0.8f)
        isSystemInDarkTheme() -> Color(0xFFFF8A80)
        else -> Color(0xFFD32F2F)
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.defaultColors(color = cardColor),
            onClick = onRefresh,
            showIndication = true,
            pressFeedbackType = PressFeedbackType.Tilt,
        ) {
            Box {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .offset(27.dp, 31.dp),
                    contentAlignment = Alignment.BottomEnd,
                ) {
                    Icon(
                        modifier = Modifier.size(110.dp),
                        imageVector = when {
                            ready -> Icons.Rounded.CheckCircleOutline
                            warning -> Icons.Rounded.WarningAmber
                            else -> Icons.Rounded.ErrorOutline
                        },
                        tint = iconTint,
                        contentDescription = null,
                    )
                }
                if (mode != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp, 10.dp),
                        contentAlignment = Alignment.BottomStart,
                    ) {
                        Text(
                            text = mode,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp, 14.dp),
                    contentAlignment = Alignment.TopStart,
                ) {
                    Column {
                        Text(
                            text = title,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(1.dp))
                        Text(
                            text = summary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
        }
    }
}

private data class SettingsStatusCardMessage(
    val text: String,
    val severity: SettingsCardSeverity,
)

private enum class SettingsCardSeverity {
    Info,
    Error,
}

@Composable
@SuppressLint("ApplySharedPref")
private fun PredictiveBackSettingsScreen(
    service: XposedService?,
    serviceStateObserved: Boolean,
    znStatus: ZnStatusUiState,
    onRefreshZnStatus: () -> Unit,
    onClose: () -> Unit,
    onOpenGestureTriggerSettings: () -> Unit,
    onOpenAppList: () -> Unit,
    onOpenContextualSearchSettings: () -> Unit,
) {
    val configurationErrorMessage = stringResource(R.string.predictive_back_config_error)
    val saveErrorMessage = stringResource(R.string.predictive_back_save_error)
    val serviceLoadingMessage = stringResource(R.string.predictive_back_service_loading)
    val serviceUnavailableMessage =
        stringResource(R.string.predictive_back_service_unavailable)
    val scope = rememberCoroutineScope()
    var preferences by remember { mutableStateOf<SharedPreferences?>(null) }
    var configurationLoading by remember { mutableStateOf(true) }
    var configurationError by remember { mutableStateOf<String?>(null) }
    var saveError by remember { mutableStateOf<String?>(null) }
    var hyperOsIndicator by remember { mutableStateOf(false) }
    var confirmedHyperOsIndicator by remember { mutableStateOf(false) }
    var hyperOsHaptics by remember { mutableStateOf(false) }
    var confirmedHyperOsHaptics by remember { mutableStateOf(false) }
    var hyperOsHapticsEnhanced by remember { mutableStateOf(false) }
    var confirmedHyperOsHapticsEnhanced by remember { mutableStateOf(false) }
    var hyperOsSlideAnimation by remember { mutableStateOf(false) }
    var confirmedHyperOsSlideAnimation by remember { mutableStateOf(false) }
    var oneUiCrossTaskAnimation by remember { mutableStateOf(false) }
    var confirmedOneUiCrossTaskAnimation by remember { mutableStateOf(false) }
    var moduleLogging by remember { mutableStateOf(true) }
    var confirmedModuleLogging by remember { mutableStateOf(true) }
    val writeMutex = remember(preferences) { Mutex() }
    val lazyListState = rememberLazyListState()
    val scrollBehavior = MiuixScrollBehavior()
    val layoutDirection = LocalLayoutDirection.current
    val horizontalSafeInsets = WindowInsets.safeDrawing
        .only(WindowInsetsSides.Horizontal)
        .asPaddingValues()
    val topBarBackdrop = rememberMiuixBlurBackdrop()

    LaunchedEffect(service, serviceStateObserved, configurationErrorMessage) {
        preferences = null
        configurationError = null
        saveError = null
        hyperOsIndicator = false
        confirmedHyperOsIndicator = false
        hyperOsHaptics = false
        confirmedHyperOsHaptics = false
        hyperOsHapticsEnhanced = false
        confirmedHyperOsHapticsEnhanced = false
        hyperOsSlideAnimation = false
        confirmedHyperOsSlideAnimation = false
        oneUiCrossTaskAnimation = false
        confirmedOneUiCrossTaskAnimation = false
        moduleLogging = PredictiveBackPreferences.DEFAULT_MODULE_LOGGING
        confirmedModuleLogging = PredictiveBackPreferences.DEFAULT_MODULE_LOGGING
        if (!serviceStateObserved) {
            configurationLoading = true
            return@LaunchedEffect
        }
        if (service == null) {
            configurationLoading = false
            return@LaunchedEffect
        }
        configurationLoading = true
        try {
            val loaded = withContext(Dispatchers.IO) {
                val remotePreferences =
                    service.getRemotePreferences(PredictiveBackPreferences.GROUP)
                val storedHyperOsHaptics = remotePreferences.getBoolean(
                    PredictiveBackPreferences.KEY_HYPEROS_HAPTICS,
                    PredictiveBackPreferences.DEFAULT_HYPEROS_HAPTICS,
                )
                val legacyAospHaptics = remotePreferences.getBoolean(
                    PredictiveBackPreferences.LEGACY_KEY_AOSP_HYPEROS_HAPTICS,
                    false,
                )
                val unifiedHyperOsHaptics = if (!legacyAospHaptics) {
                    storedHyperOsHaptics
                } else {
                    try {
                        val migrated = remotePreferences.edit()
                            .putBoolean(PredictiveBackPreferences.KEY_HYPEROS_HAPTICS, true)
                            .remove(PredictiveBackPreferences.LEGACY_KEY_AOSP_HYPEROS_HAPTICS)
                            .commit()
                        if (migrated) true else storedHyperOsHaptics
                    } catch (_: Throwable) {
                        storedHyperOsHaptics
                    }
                }
                val flags = booleanArrayOf(
                    remotePreferences.getBoolean(
                        PredictiveBackPreferences.KEY_HYPEROS_INDICATOR,
                        PredictiveBackPreferences.DEFAULT_HYPEROS_INDICATOR,
                    ),
                    unifiedHyperOsHaptics,
                    remotePreferences.getBoolean(
                        PredictiveBackPreferences.KEY_HYPEROS_HAPTICS_ENHANCED,
                        PredictiveBackPreferences.DEFAULT_HYPEROS_HAPTICS_ENHANCED,
                    ),
                    remotePreferences.getBoolean(
                        PredictiveBackPreferences.KEY_HYPEROS_SLIDE_ANIMATION,
                        PredictiveBackPreferences.DEFAULT_HYPEROS_SLIDE_ANIMATION,
                    ),
                    remotePreferences.getBoolean(
                        PredictiveBackPreferences.KEY_ONEUI_CROSS_TASK_ANIMATION,
                        PredictiveBackPreferences.DEFAULT_ONEUI_CROSS_TASK_ANIMATION,
                    ),
                    remotePreferences.getBoolean(
                        PredictiveBackPreferences.KEY_MODULE_LOGGING,
                        PredictiveBackPreferences.DEFAULT_MODULE_LOGGING,
                    ),
                )
                remotePreferences to flags
            }
            preferences = loaded.first
            hyperOsIndicator = loaded.second[0]
            confirmedHyperOsIndicator = loaded.second[0]
            hyperOsHaptics = loaded.second[1]
            confirmedHyperOsHaptics = loaded.second[1]
            hyperOsHapticsEnhanced = loaded.second[2]
            confirmedHyperOsHapticsEnhanced = loaded.second[2]
            hyperOsSlideAnimation = loaded.second[3]
            confirmedHyperOsSlideAnimation = loaded.second[3]
            oneUiCrossTaskAnimation = loaded.second[4]
            confirmedOneUiCrossTaskAnimation = loaded.second[4]
            moduleLogging = loaded.second[5]
            confirmedModuleLogging = loaded.second[5]
        } catch (_: Throwable) {
            configurationError = configurationErrorMessage
        } finally {
            configurationLoading = false
        }
    }

    val persistBooleanPreference: (
        String,
        Boolean,
        (Boolean) -> Unit,
        () -> Boolean,
        (Boolean) -> Unit,
    ) -> Unit = { key, requestedEnabled, setLocal, getConfirmed, setConfirmed ->
        val activePreferences = preferences
        if (activePreferences != null) {
            setLocal(requestedEnabled)
            saveError = null
            scope.launch {
                val saved = writeMutex.withLock {
                    val fallbackEnabled = getConfirmed()
                    val commitSucceeded = withContext(Dispatchers.IO) {
                        val succeeded = try {
                            activePreferences.edit()
                                .putBoolean(key, requestedEnabled)
                                .commit()
                        } catch (_: Throwable) {
                            false
                        }
                        if (!succeeded) {
                            try {
                                activePreferences.edit()
                                    .putBoolean(key, fallbackEnabled)
                                    .commit()
                            } catch (_: Throwable) {
                                // Restore the RemotePreferences cache where possible.
                            }
                        }
                        succeeded
                    }
                    if (preferences === activePreferences && commitSucceeded) {
                        setConfirmed(requestedEnabled)
                    }
                    commitSucceeded
                }
                if (preferences === activePreferences && !saved) {
                    setLocal(getConfirmed())
                    saveError = saveErrorMessage
                }
            }
        }
    }
    val persistHyperOsIndicator: (Boolean) -> Unit = { requestedEnabled ->
        persistBooleanPreference(
            PredictiveBackPreferences.KEY_HYPEROS_INDICATOR,
            requestedEnabled,
            { hyperOsIndicator = it },
            { confirmedHyperOsIndicator },
            { confirmedHyperOsIndicator = it },
        )
    }
    val persistHyperOsHaptics: (Boolean) -> Unit = { requestedEnabled ->
        persistBooleanPreference(
            PredictiveBackPreferences.KEY_HYPEROS_HAPTICS,
            requestedEnabled,
            { hyperOsHaptics = it },
            { confirmedHyperOsHaptics },
            { confirmedHyperOsHaptics = it },
        )
    }
    val persistHyperOsHapticsEnhanced: (Boolean) -> Unit = { requestedEnabled ->
        persistBooleanPreference(
            PredictiveBackPreferences.KEY_HYPEROS_HAPTICS_ENHANCED,
            requestedEnabled,
            { hyperOsHapticsEnhanced = it },
            { confirmedHyperOsHapticsEnhanced },
            { confirmedHyperOsHapticsEnhanced = it },
        )
    }
    val persistHyperOsSlideAnimation: (Boolean) -> Unit = { requestedEnabled ->
        persistBooleanPreference(
            PredictiveBackPreferences.KEY_HYPEROS_SLIDE_ANIMATION,
            requestedEnabled,
            { hyperOsSlideAnimation = it },
            { confirmedHyperOsSlideAnimation },
            { confirmedHyperOsSlideAnimation = it },
        )
    }
    val persistOneUiCrossTaskAnimation: (Boolean) -> Unit = { requestedEnabled ->
        persistBooleanPreference(
            PredictiveBackPreferences.KEY_ONEUI_CROSS_TASK_ANIMATION,
            requestedEnabled,
            { oneUiCrossTaskAnimation = it },
            { confirmedOneUiCrossTaskAnimation },
            { confirmedOneUiCrossTaskAnimation = it },
        )
    }
    val persistModuleLogging: (Boolean) -> Unit = { requestedEnabled ->
        persistBooleanPreference(
            PredictiveBackPreferences.KEY_MODULE_LOGGING,
            requestedEnabled,
            { moduleLogging = it },
            { confirmedModuleLogging },
            { confirmedModuleLogging = it },
        )
    }
    val statusMessage = when {
        configurationLoading -> SettingsStatusCardMessage(
            text = serviceLoadingMessage,
            severity = SettingsCardSeverity.Info,
        )

        configurationError != null -> SettingsStatusCardMessage(
            text = configurationError.orEmpty(),
            severity = SettingsCardSeverity.Error,
        )

        saveError != null -> SettingsStatusCardMessage(
            text = saveError.orEmpty(),
            severity = SettingsCardSeverity.Error,
        )

        serviceStateObserved && service == null -> SettingsStatusCardMessage(
            text = serviceUnavailableMessage,
            severity = SettingsCardSeverity.Error,
        )

        else -> null
    }
    val configurationEnabled = preferences != null

    Scaffold(
        topBar = {
            TopAppBar(
                modifier = Modifier
                    .miuixBlurEffect(topBarBackdrop)
                    .background(Color.Transparent),
                color = Color.Transparent,
                scrollBehavior = scrollBehavior,
                title = stringResource(R.string.predictive_back_title),
                subtitle = "v${BuildConfig.VERSION_NAME}",
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(
                            imageVector = MiuixIcons.Regular.Close,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
            )
        },
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .layerBackdrop(topBarBackdrop)
                .scrollEndHaptic()
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            state = lazyListState,
            contentPadding = PaddingValues(
                start = horizontalSafeInsets.calculateLeftPadding(layoutDirection),
                top = paddingValues.calculateTopPadding() + 8.dp,
                end = horizontalSafeInsets.calculateRightPadding(layoutDirection),
                bottom = paddingValues.calculateBottomPadding(),
            ),
            overscrollEffect = null,
        ) {
            item(key = "zn_runtime_status") {
                ZnRuntimeStatusCard(
                    state = if (serviceStateObserved && service == null) {
                        ZnStatusUiState(ZnStatusKind.LsPosedUnavailable)
                    } else {
                        znStatus
                    },
                    onRefresh = onRefreshZnStatus,
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 8.dp),
                )
            }
            item(key = "hyperos_switches") {
                HyperOsSwitchGroupCard(
                    hyperOsIndicator = hyperOsIndicator,
                    hyperOsHaptics = hyperOsHaptics,
                    hyperOsHapticsEnhanced = hyperOsHapticsEnhanced,
                    hyperOsSlideAnimation = hyperOsSlideAnimation,
                    oneUiCrossTaskAnimation = oneUiCrossTaskAnimation,
                    configurationEnabled = configurationEnabled,
                    onHyperOsIndicatorToggle = persistHyperOsIndicator,
                    onHyperOsHapticsToggle = persistHyperOsHaptics,
                    onHyperOsHapticsEnhancedToggle = persistHyperOsHapticsEnhanced,
                    onHyperOsSlideAnimationToggle = persistHyperOsSlideAnimation,
                    onOneUiCrossTaskAnimationToggle = persistOneUiCrossTaskAnimation,
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 8.dp),
                )
            }
            item(key = "module_logging") {
                ModuleLoggingCard(
                    moduleLogging = moduleLogging,
                    configurationEnabled = configurationEnabled,
                    onModuleLoggingToggle = persistModuleLogging,
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 8.dp),
                )
            }
            item(key = "contextual_search") {
                ContextualSearchNavigationCard(
                    onClick = onOpenContextualSearchSettings,
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 8.dp),
                )
            }
            item(key = "gesture_trigger_navigation") {
                GestureTriggerNavigationCard(
                    onClick = onOpenGestureTriggerSettings,
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 8.dp),
                )
            }
            item(key = "app_list_navigation") {
                AppListNavigationCard(
                    onClick = onOpenAppList,
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 8.dp),
                )
            }
            if (statusMessage != null) {
                item(key = "configuration_status") {
                    StatusCard(
                        message = statusMessage.text,
                        severity = statusMessage.severity,
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 8.dp),
                    )
                }
            }
            item(key = "navigation_bar_spacer") {
                Spacer(modifier = Modifier.navigationBarsPadding())
            }
        }
    }
}

@Composable
private fun StatusCard(
    message: String,
    severity: SettingsCardSeverity,
    modifier: Modifier = Modifier,
) {
    val accentColor = cardAccentColor(severity)
    Card(
        modifier = modifier.fillMaxWidth(),
        insideMargin = PaddingValues(16.dp),
        colors = CardDefaults.defaultColors(
            color = accentColor.copy(alpha = 0.2f),
            contentColor = accentColor,
        ),
    ) {
        Text(
            text = message,
            style = MiuixTheme.textStyles.body2,
        )
    }
}

@Composable
private fun cardAccentColor(severity: SettingsCardSeverity): Color = when (severity) {
    SettingsCardSeverity.Info -> MiuixTheme.colorScheme.primary
    SettingsCardSeverity.Error -> MiuixTheme.colorScheme.error
}

@Composable
private fun HyperOsSwitchGroupCard(
    hyperOsIndicator: Boolean,
    hyperOsHaptics: Boolean,
    hyperOsHapticsEnhanced: Boolean,
    hyperOsSlideAnimation: Boolean,
    oneUiCrossTaskAnimation: Boolean,
    configurationEnabled: Boolean,
    onHyperOsIndicatorToggle: (Boolean) -> Unit,
    onHyperOsHapticsToggle: (Boolean) -> Unit,
    onHyperOsHapticsEnhancedToggle: (Boolean) -> Unit,
    onHyperOsSlideAnimationToggle: (Boolean) -> Unit,
    onOneUiCrossTaskAnimationToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        insideMargin = PaddingValues(0.dp),
    ) {
        SwitchPreference(
            title = stringResource(R.string.hyperos_indicator_title),
            summary = stringResource(R.string.hyperos_indicator_summary),
            checked = hyperOsIndicator,
            enabled = configurationEnabled,
            onCheckedChange = onHyperOsIndicatorToggle,
        )
        SwitchPreference(
            title = stringResource(R.string.hyperos_haptics_title),
            summary = stringResource(R.string.hyperos_haptics_summary),
            checked = hyperOsHaptics,
            enabled = configurationEnabled,
            onCheckedChange = onHyperOsHapticsToggle,
        )
        SwitchPreference(
            title = stringResource(R.string.hyperos_haptics_enhanced_title),
            summary = stringResource(R.string.hyperos_haptics_enhanced_summary),
            checked = hyperOsHapticsEnhanced,
            enabled = configurationEnabled && hyperOsIndicator && hyperOsHaptics,
            onCheckedChange = onHyperOsHapticsEnhancedToggle,
        )
        SwitchPreference(
            title = stringResource(R.string.hyperos_slide_animation_title),
            summary = stringResource(R.string.hyperos_slide_animation_summary),
            checked = hyperOsSlideAnimation,
            enabled = configurationEnabled,
            onCheckedChange = onHyperOsSlideAnimationToggle,
        )
        SwitchPreference(
            title = stringResource(R.string.oneui_cross_task_animation_title),
            summary = stringResource(R.string.oneui_cross_task_animation_summary),
            checked = oneUiCrossTaskAnimation,
            enabled = configurationEnabled,
            onCheckedChange = onOneUiCrossTaskAnimationToggle,
        )
    }
}

@Composable
private fun AppListNavigationCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        insideMargin = PaddingValues(0.dp),
    ) {
        ArrowPreference(
            title = stringResource(R.string.predictive_back_apps_entry_title),
            summary = stringResource(R.string.predictive_back_apps_entry_summary),
            onClick = onClick,
        )
    }
}

@Composable
private fun GestureTriggerNavigationCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        insideMargin = PaddingValues(0.dp),
    ) {
        ArrowPreference(
            title = stringResource(R.string.gesture_trigger_entry_title),
            summary = stringResource(R.string.gesture_trigger_entry_summary),
            onClick = onClick,
        )
    }
}

@Composable
private fun ModuleLoggingCard(
    moduleLogging: Boolean,
    configurationEnabled: Boolean,
    onModuleLoggingToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        insideMargin = PaddingValues(0.dp),
    ) {
        SwitchPreference(
            title = stringResource(R.string.module_logging_title),
            summary = stringResource(R.string.module_logging_summary),
            checked = moduleLogging,
            enabled = configurationEnabled,
            onCheckedChange = onModuleLoggingToggle,
        )
    }
}

@Composable
private fun ContextualSearchNavigationCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        insideMargin = PaddingValues(0.dp),
    ) {
        ArrowPreference(
            title = stringResource(R.string.contextual_search_entry_title),
            summary = stringResource(R.string.contextual_search_entry_summary),
            onClick = onClick,
        )
    }
}
