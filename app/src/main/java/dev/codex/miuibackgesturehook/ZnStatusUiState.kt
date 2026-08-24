// SPDX-License-Identifier: Apache-2.0
package dev.codex.miuibackgesturehook

import android.content.Intent

enum class ZnStatusKind {
    Checking,
    WaitingForNative,
    Ready,
    SystemUiNotReady,
    NativeNotReady,
    LegacyNotReady,
    NativeNoResponse,
    ProfileRejected,
    NoResponse,
    LsPosedUnavailable,
}

internal fun classifyZnStatus(
    nativeResponse: Boolean,
    systemUiReady: Boolean,
    legacyMode: Boolean,
    legacyReady: Boolean,
    profileResolved: Boolean,
    nativeReady: Boolean,
    businessState: Int,
    bridgeState: Int,
    profileStage: Int,
    dartResolverStage: Int,
    drawerStateReady: Boolean,
    overviewStateReady: Boolean,
): ZnStatusKind = when {
    !nativeResponse && systemUiReady -> ZnStatusKind.WaitingForNative
    !systemUiReady -> ZnStatusKind.SystemUiNotReady
    !nativeResponse -> ZnStatusKind.WaitingForNative
    legacyMode && !legacyReady -> ZnStatusKind.LegacyNotReady
    legacyMode -> ZnStatusKind.Ready
    profileStage in 101..105 -> ZnStatusKind.ProfileRejected
    dartResolverStage in 101..104 -> ZnStatusKind.ProfileRejected
    !nativeReady || !profileResolved || businessState != 3 || bridgeState != 3 ||
        !drawerStateReady || !overviewStateReady -> ZnStatusKind.NativeNotReady
    else -> ZnStatusKind.Ready
}

data class ZnStatusUiState(
    val kind: ZnStatusKind,
    val profileDynamic: Boolean = false,
    val legacyMode: Boolean = false,
    val profileResolved: Boolean = false,
    val nativeReady: Boolean = false,
    val systemUiReady: Boolean = false,
    val businessState: Int = 0,
    val bridgeState: Int = 0,
    val profileStage: Int = 0,
    val dartResolverStage: Int = 0,
    val dartDrawerCandidates: Int = 0,
    val dartTransitionCandidates: Int = 0,
    val dartOverviewEnterCandidates: Int = 0,
    val dartOverviewExitCandidates: Int = 0,
    val drawerStateReady: Boolean = false,
    val overviewStateReady: Boolean = false,
    val reason: String = "",
) {
    val dartRuntimeResolved: Boolean
        get() = dartResolverStage == 5 &&
            dartDrawerCandidates == 1 &&
            dartTransitionCandidates == 1 &&
            dartOverviewEnterCandidates == 1 &&
            dartOverviewExitCandidates == 1 &&
            drawerStateReady &&
            overviewStateReady

    companion object {
        fun checking(legacyMode: Boolean = false) = ZnStatusUiState(
            kind = ZnStatusKind.Checking,
            legacyMode = legacyMode,
        )

        fun noResponse() = ZnStatusUiState(ZnStatusKind.NoResponse)

        fun fromReply(intent: Intent): ZnStatusUiState {
            val nativeResponse = intent.getBooleanExtra(
                ZnStatusProtocol.EXTRA_NATIVE_RESPONSE,
                false,
            )
            val systemUiReady = intent.getBooleanExtra(
                ZnStatusProtocol.EXTRA_SYSTEMUI_READY,
                false,
            )
            val legacyMode = intent.getBooleanExtra(
                ZnStatusProtocol.EXTRA_LEGACY_MODE,
                false,
            )
            val legacyReady = intent.getBooleanExtra(
                ZnStatusProtocol.EXTRA_LEGACY_READY,
                false,
            )
            val profileResolved = intent.getBooleanExtra(
                ZnStatusProtocol.EXTRA_NATIVE_PROFILE_RESOLVED,
                false,
            )
            val nativeReady = intent.getBooleanExtra(
                ZnStatusProtocol.EXTRA_NATIVE_READY,
                false,
            )
            val profileDynamic = intent.getBooleanExtra(
                ZnStatusProtocol.EXTRA_NATIVE_PROFILE_DYNAMIC,
                false,
            )
            val businessState = intent.getIntExtra(
                ZnStatusProtocol.EXTRA_NATIVE_BUSINESS_STATE,
                0,
            )
            val bridgeState = intent.getIntExtra(
                ZnStatusProtocol.EXTRA_NATIVE_BRIDGE_STATE,
                0,
            )
            val profileStage = intent.getIntExtra(
                ZnStatusProtocol.EXTRA_NATIVE_RUNTIME_PROFILE_STAGE,
                0,
            )
            val dartResolverStage = intent.getIntExtra(
                ZnStatusProtocol.EXTRA_NATIVE_DART_RESOLVER_STAGE,
                0,
            )
            val dartDrawerCandidates = intent.getIntExtra(
                ZnStatusProtocol.EXTRA_NATIVE_DART_DRAWER_CANDIDATES,
                0,
            )
            val dartTransitionCandidates = intent.getIntExtra(
                ZnStatusProtocol.EXTRA_NATIVE_DART_TRANSITION_CANDIDATES,
                0,
            )
            val dartOverviewEnterCandidates = intent.getIntExtra(
                ZnStatusProtocol.EXTRA_NATIVE_DART_OVERVIEW_ENTER_CANDIDATES,
                0,
            )
            val dartOverviewExitCandidates = intent.getIntExtra(
                ZnStatusProtocol.EXTRA_NATIVE_DART_OVERVIEW_EXIT_CANDIDATES,
                0,
            )
            val drawerStateReady = intent.getBooleanExtra(
                ZnStatusProtocol.EXTRA_NATIVE_DRAWER_STATE_READY,
                false,
            )
            val overviewStateReady = intent.getBooleanExtra(
                ZnStatusProtocol.EXTRA_NATIVE_OVERVIEW_STATE_READY,
                false,
            )
            val kind = classifyZnStatus(
                nativeResponse = nativeResponse,
                systemUiReady = systemUiReady,
                legacyMode = legacyMode,
                legacyReady = legacyReady,
                profileResolved = profileResolved,
                nativeReady = nativeReady,
                businessState = businessState,
                bridgeState = bridgeState,
                profileStage = profileStage,
                dartResolverStage = dartResolverStage,
                drawerStateReady = drawerStateReady,
                overviewStateReady = overviewStateReady,
            )
            return ZnStatusUiState(
                kind = kind,
                profileDynamic = profileDynamic,
                legacyMode = legacyMode,
                profileResolved = profileResolved,
                nativeReady = nativeReady,
                systemUiReady = systemUiReady,
                businessState = businessState,
                bridgeState = bridgeState,
                profileStage = profileStage,
                dartResolverStage = dartResolverStage,
                dartDrawerCandidates = dartDrawerCandidates,
                dartTransitionCandidates = dartTransitionCandidates,
                dartOverviewEnterCandidates = dartOverviewEnterCandidates,
                dartOverviewExitCandidates = dartOverviewExitCandidates,
                drawerStateReady = drawerStateReady,
                overviewStateReady = overviewStateReady,
                reason = intent.getStringExtra(ZnStatusProtocol.EXTRA_REASON).orEmpty(),
            )
        }
    }
}
