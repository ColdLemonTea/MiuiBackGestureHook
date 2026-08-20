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
    val reason: String = "",
) {
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
            val kind = when {
                !nativeResponse && systemUiReady -> ZnStatusKind.WaitingForNative
                !systemUiReady -> ZnStatusKind.SystemUiNotReady
                !nativeResponse -> ZnStatusKind.WaitingForNative
                legacyMode && !legacyReady -> ZnStatusKind.LegacyNotReady
                legacyMode -> ZnStatusKind.Ready
                profileStage in 101..105 -> ZnStatusKind.ProfileRejected
                !nativeReady || !profileResolved || businessState != 3 || bridgeState != 3 ->
                    ZnStatusKind.NativeNotReady
                else -> ZnStatusKind.Ready
            }
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
                reason = intent.getStringExtra(ZnStatusProtocol.EXTRA_REASON).orEmpty(),
            )
        }
    }
}
