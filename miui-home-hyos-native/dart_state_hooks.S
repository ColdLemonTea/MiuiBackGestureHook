    .text
    .p2align 2
    .global MiuiHomeHyosDartDrawerTransitionCompleteHook
    .hidden MiuiHomeHyosDartDrawerTransitionCompleteHook
    .type MiuiHomeHyosDartDrawerTransitionCompleteHook, %function
MiuiHomeHyosDartDrawerTransitionCompleteHook:
    // This exact _AllAppsCategoryListPageState._onDrawerVisibilityChanged
    // callback is the sole ALL_APPS publication owner. Its visibility
    // argument is the Dart bool in entry x2.
    bti c
    sub sp, sp, #0x60
    stp x0, x1, [sp, #0x00]
    stp x2, x15, [sp, #0x10]
    stp x22, x26, [sp, #0x20]
    stp x27, x28, [sp, #0x30]
    str x30, [sp, #0x40]

    adrp x16, miui_home_hyos_dart_transition_complete_original
    add x16, x16, :lo12:miui_home_hyos_dart_transition_complete_original
    ldar x16, [x16]
    blr x16
    str x0, [sp, #0x48]

    ldr x2, [sp, #0x10]
    ldr x11, [sp, #0x20]
    add x12, x11, #0x20
    cmp w2, w12
    b.eq .Lcomplete_visible

    // Accept only the isolate's canonical false singleton. An unexpected
    // object preserves Xiaomi's result and publishes no launcher state.
    add x12, x11, #0x30
    cmp w2, w12
    b.ne .Lcomplete_restore
    mov w0, wzr
    b .Lcomplete_publish

.Lcomplete_visible:
    mov w0, #1
.Lcomplete_publish:
    bl MiuiHomeHyosDartDrawerStateObserved

.Lcomplete_restore:
    ldr x0, [sp, #0x48]
    ldr x15, [sp, #0x18]
    ldp x22, x26, [sp, #0x20]
    ldp x27, x28, [sp, #0x30]
    ldr x30, [sp, #0x40]
    add sp, sp, #0x60
    ret
    .size MiuiHomeHyosDartDrawerTransitionCompleteHook, .-MiuiHomeHyosDartDrawerTransitionCompleteHook

    .p2align 2
    .global MiuiHomeHyosDartOverviewEnterHook
    .hidden MiuiHomeHyosDartOverviewEnterHook
    .type MiuiHomeHyosDartOverviewEnterHook, %function
MiuiHomeHyosDartOverviewEnterHook:
    // sendEnterOverviewSignal is the Flutter owner's accepted transition
    // boundary. Preserve Dart's fixed registers while the C++ observer mirrors
    // the state only after Xiaomi has emitted its own signal.
    bti c
    sub sp, sp, #0x60
    stp x0, x1, [sp, #0x00]
    stp x2, x15, [sp, #0x10]
    stp x22, x26, [sp, #0x20]
    stp x27, x28, [sp, #0x30]
    str x30, [sp, #0x40]

    adrp x16, miui_home_hyos_dart_overview_enter_original
    add x16, x16, :lo12:miui_home_hyos_dart_overview_enter_original
    ldar x16, [x16]
    blr x16
    str x0, [sp, #0x48]

    mov w0, #1
    bl MiuiHomeHyosDartOverviewStateObserved

    ldr x0, [sp, #0x48]
    ldr x15, [sp, #0x18]
    ldp x22, x26, [sp, #0x20]
    ldp x27, x28, [sp, #0x30]
    ldr x30, [sp, #0x40]
    add sp, sp, #0x60
    ret
    .size MiuiHomeHyosDartOverviewEnterHook, .-MiuiHomeHyosDartOverviewEnterHook

    .p2align 2
    .global MiuiHomeHyosDartOverviewExitHook
    .hidden MiuiHomeHyosDartOverviewExitHook
    .type MiuiHomeHyosDartOverviewExitHook, %function
MiuiHomeHyosDartOverviewExitHook:
    // sendExitOverviewSignal is shared by every native Flutter Overview exit.
    bti c
    sub sp, sp, #0x60
    stp x0, x1, [sp, #0x00]
    stp x2, x15, [sp, #0x10]
    stp x22, x26, [sp, #0x20]
    stp x27, x28, [sp, #0x30]
    str x30, [sp, #0x40]

    adrp x16, miui_home_hyos_dart_overview_exit_original
    add x16, x16, :lo12:miui_home_hyos_dart_overview_exit_original
    ldar x16, [x16]
    blr x16
    str x0, [sp, #0x48]

    mov w0, wzr
    bl MiuiHomeHyosDartOverviewStateObserved

    ldr x0, [sp, #0x48]
    ldr x15, [sp, #0x18]
    ldp x22, x26, [sp, #0x20]
    ldp x27, x28, [sp, #0x30]
    ldr x30, [sp, #0x40]
    add sp, sp, #0x60
    ret
    .size MiuiHomeHyosDartOverviewExitHook, .-MiuiHomeHyosDartOverviewExitHook

    .p2align 2
    .global MiuiHomeHyosDartEditingQueryHook
    .hidden MiuiHomeHyosDartEditingQueryHook
    .type MiuiHomeHyosDartEditingQueryHook, %function
MiuiHomeHyosDartEditingQueryHook:
    // EditModeGetxController.isNeedEnableSystemBack is shared. Observe it only
    // for the two call sites inside LauncherOverlayStateManager's uniquely
    // resolved back-status refresh; every other caller stays invisible.
    bti c
    sub sp, sp, #0x60
    stp x0, x1, [sp, #0x00]
    stp x2, x15, [sp, #0x10]
    stp x22, x26, [sp, #0x20]
    stp x27, x28, [sp, #0x30]
    str x30, [sp, #0x40]

    adrp x16, miui_home_hyos_dart_editing_query_original
    add x16, x16, :lo12:miui_home_hyos_dart_editing_query_original
    ldar x16, [x16]
    blr x16
    str x0, [sp, #0x48]

    ldr x11, [sp, #0x40]
    adrp x16, miui_home_hyos_dart_editing_return_a
    add x16, x16, :lo12:miui_home_hyos_dart_editing_return_a
    ldar x12, [x16]
    cmp x11, x12
    b.eq .Lediting_validate
    adrp x16, miui_home_hyos_dart_editing_return_b
    add x16, x16, :lo12:miui_home_hyos_dart_editing_return_b
    ldar x12, [x16]
    cmp x11, x12
    b.ne .Lediting_restore

.Lediting_validate:
    ldr x11, [sp, #0x20]
    ldr x12, [sp, #0x48]
    add x13, x11, #0x20
    cmp w12, w13
    b.eq .Lediting_true
    add x13, x11, #0x30
    cmp w12, w13
    b.ne .Lediting_restore
    mov w0, wzr
    b .Lediting_publish

.Lediting_true:
    mov w0, #1
.Lediting_publish:
    bl MiuiHomeHyosDartEditingStateObserved

.Lediting_restore:
    ldr x0, [sp, #0x48]
    ldr x15, [sp, #0x18]
    ldp x22, x26, [sp, #0x20]
    ldp x27, x28, [sp, #0x30]
    ldr x30, [sp, #0x40]
    add sp, sp, #0x60
    ret
    .size MiuiHomeHyosDartEditingQueryHook, .-MiuiHomeHyosDartEditingQueryHook

    // Match the C++ object's BTI/PAC/GCS GNU property declaration.
    .section .note.gnu.property,"a",%note
    .p2align 3
    .long 4
    .long 16
    .long 5
    .asciz "GNU"
    .long 0xc0000000
    .long 4
    .long 7
    .long 0

    .section .note.GNU-stack,"",%progbits
