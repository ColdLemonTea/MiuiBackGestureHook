// SPDX-License-Identifier: Apache-2.0
#pragma once

#include <stdint.h>

#include "zygisk_next_api.h"

struct NativeAPIEntries {
    uint32_t version;
    int (*hookFunc)(void* target, void* replacement, void** backup);
    int (*unhookFunc)(void* target);
};

using NativeOnModuleLoaded = void (*)(const char* name, void* handle);

bool InitializeLsposedCompatibilityApi(const NativeAPIEntries* entries,
                                       ZygiskNextAPI* compatibility_api);
bool EnsureLsposedMadviseGuard();
