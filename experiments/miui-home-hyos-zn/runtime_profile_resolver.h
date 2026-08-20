#pragma once

#include "launcher_profiles.h"

#include <stddef.h>
#include <stdint.h>

namespace miui_home_runtime_profile {

enum class ResolveStage : uint32_t {
    kNotStarted = 0,
    kParsingElf = 1,
    kResolvingImports = 2,
    kResolvingSideBoundary = 3,
    kResolvingRuntime = 4,
    kResolvingRString = 5,
    kComplete = 6,
    kRejectedElf = 101,
    kRejectedImports = 102,
    kRejectedSideBoundary = 103,
    kRejectedRuntime = 104,
    kRejectedRString = 105,
};

struct ResolutionStorage {
    miui_home_profiles::LauncherProfile profile;
    miui_home_profiles::CodeFingerprint identity_fingerprint;
    uint8_t entry_fingerprint[48];
    uint8_t side_prologue[32];
};

struct ResolutionDiagnostics {
    ResolveStage stage;
    uint32_t side_candidate_count;
    uint32_t runtime_confirmation_count;
    uint32_t rstring_candidate_count;
    uintptr_t side_handler_offset;
    uintptr_t runtime_pointer_offset;
    uintptr_t runtime_state_offset;
    uintptr_t rstring_vtable_offset;
};

// Resolves only the Android 17 side-boundary launcher family represented by
// the 5334 and 5402 profiles. Every discovered address must be backed by a
// mapped ELF segment and by the expected imported-call graph. No partial
// result is published on failure.
bool ResolveSideBoundaryProfile(const uint8_t* base, void* app_entry_point,
                                ResolutionStorage* storage,
                                ResolutionDiagnostics* diagnostics);

}  // namespace miui_home_runtime_profile
