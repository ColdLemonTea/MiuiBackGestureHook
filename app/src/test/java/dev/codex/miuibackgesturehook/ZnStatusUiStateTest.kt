package dev.codex.miuibackgesturehook

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ZnStatusUiStateTest {
    private fun classify(
        nativeReady: Boolean = true,
        drawerReady: Boolean = true,
        overviewReady: Boolean = true,
        dartStage: Int = 5,
    ): ZnStatusKind = classifyZnStatus(
        nativeResponse = true,
        systemUiReady = true,
        legacyMode = false,
        legacyReady = false,
        profileResolved = true,
        nativeReady = nativeReady,
        businessState = 3,
        bridgeState = 3,
        profileStage = 7,
        dartResolverStage = dartStage,
        drawerStateReady = drawerReady,
        overviewStateReady = overviewReady,
    )

    @Test
    fun readyRequiresBothFeatureHooks() {
        assertEquals(ZnStatusKind.Ready, classify())
        assertEquals(
            ZnStatusKind.NativeNotReady,
            classify(drawerReady = false),
        )
        assertEquals(
            ZnStatusKind.NativeNotReady,
            classify(overviewReady = false),
        )
    }

    @Test
    fun resolverRejectionPrecedesNativeReadiness() {
        assertEquals(
            ZnStatusKind.ProfileRejected,
            classify(nativeReady = false, dartStage = 104),
        )
    }

    @Test
    fun dartRuntimeResolvedIncludesInstalledHookHealth() {
        val healthy = ZnStatusUiState(
            kind = ZnStatusKind.Ready,
            dartResolverStage = 5,
            dartDrawerCandidates = 1,
            dartTransitionCandidates = 1,
            dartOverviewEnterCandidates = 1,
            dartOverviewExitCandidates = 1,
            drawerStateReady = true,
            overviewStateReady = true,
        )
        assertTrue(healthy.dartRuntimeResolved)
        assertFalse(healthy.copy(drawerStateReady = false).dartRuntimeResolved)
        assertFalse(healthy.copy(overviewStateReady = false).dartRuntimeResolved)
    }
}
