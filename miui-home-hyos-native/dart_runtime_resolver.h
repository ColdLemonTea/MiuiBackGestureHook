#pragma once

#include "launcher_profiles.h"

#include <stddef.h>
#include <stdint.h>

namespace miui_home_dart_profile {

enum class ResolveStage : uint32_t {
    kNotStarted = 0,
    kParsingElf = 1,
    kResolvingDrawer = 2,
    kResolvingTransition = 3,
    kResolvingOverview = 4,
    kComplete = 5,
    kRejectedElf = 101,
    kRejectedDrawer = 102,
    kRejectedTransition = 103,
    kRejectedOverview = 104,
};

struct ResolutionStorage {
    miui_home_profiles::LauncherProfile profile;
    uint8_t snapshot_build_id[32];
    uint8_t drawer_progress_end_prologue[64];
    uint8_t drawer_transition_complete_prologue[40];
    uint8_t overview_enter_prologue[32];
    uint8_t overview_exit_prologue[32];
};

struct ResolutionDiagnostics {
    ResolveStage stage;
    uint32_t drawer_candidate_count;
    uint32_t transition_candidate_count;
    uint32_t overview_enter_candidate_count;
    uint32_t overview_exit_candidate_count;
    uintptr_t drawer_progress_end_offset;
    uintptr_t drawer_transition_complete_offset;
    uintptr_t overview_enter_offset;
    uintptr_t overview_exit_offset;
    uintptr_t all_apps_state_slot_offset;
    uintptr_t home_state_slot_offset;
};

// Resolves the Dart AOT callback family directly from the currently mapped
// libapp.so. No manifest address or companion-process configuration is used.
// Every callback must be unique and must share the expected AOT call/pool
// relationships; otherwise no profile is published.
bool ResolveDartFeatureProfile(
        const uint8_t* base, const void* snapshot_instructions,
        const void* snapshot_build_id,
        const miui_home_profiles::LauncherProfile& launcher_profile,
        ResolutionStorage* storage, ResolutionDiagnostics* diagnostics);

}  // namespace miui_home_dart_profile
