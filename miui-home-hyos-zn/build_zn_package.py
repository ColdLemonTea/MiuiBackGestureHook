#!/usr/bin/env python3
"""Cross-platform arm64-v8a builder and packager for the ZN module."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import platform
import re
import shutil
import subprocess
import sys
import zipfile
from datetime import datetime
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SOURCE_ROOT = Path(__file__).resolve().parent


def fail(message: str) -> "NoReturn":
    raise RuntimeError(message)


def run(command: list[str], *, cwd: Path | None = None,
        capture: bool = False) -> str:
    print("+", " ".join(str(item) for item in command), flush=True)
    result = subprocess.run(
        command,
        cwd=str(cwd) if cwd else None,
        check=False,
        text=True,
        stdout=subprocess.PIPE if capture else None,
        stderr=subprocess.STDOUT if capture else None,
    )
    if result.returncode != 0:
        output = result.stdout or ""
        fail(f"Command failed ({result.returncode}): {' '.join(command)}\n{output}")
    return result.stdout or ""


def executable(path_or_name: str | None, names: list[str]) -> Path:
    if path_or_name:
        candidate = Path(path_or_name)
        if candidate.is_dir():
            candidate = candidate / ("cmake.exe" if "cmake" in names else names[0])
        if candidate.is_file():
            return candidate.resolve()
        fail(f"Build tool does not exist: {candidate}")
    for name in names:
        found = shutil.which(name)
        if found:
            return Path(found).resolve()
    fail(f"Unable to find build tool: {', '.join(names)}")


def find_ndk(explicit: str | None) -> Path:
    candidates = [
        explicit,
        os.environ.get("ANDROID_NDK_HOME"),
        os.environ.get("ANDROID_NDK_ROOT"),
        os.environ.get("ANDROID_NDK"),
        r"D:\env\AndroidSDK\ndk\30.0.14904198",
    ]
    for value in candidates:
        if value and (candidate := Path(value)).is_dir():
            return candidate.resolve()
    fail("Unable to find the Android NDK; pass --ndk-path or set ANDROID_NDK_HOME.")


def host_tool(ndk: Path, name: str) -> Path:
    system = platform.system()
    tags = {
        "Windows": ["windows-x86_64"],
        "Linux": ["linux-x86_64"],
        "Darwin": ["darwin-x86_64", "darwin-arm64"],
    }.get(system, [])
    suffix = ".exe" if system == "Windows" else ""
    for tag in tags:
        candidate = ndk / "toolchains" / "llvm" / "prebuilt" / tag / "bin" / (name + suffix)
        if candidate.is_file():
            return candidate
    matches = list((ndk / "toolchains" / "llvm" / "prebuilt").glob(
        f"*/bin/{name}{suffix}"))
    if matches:
        return matches[0]
    return executable(None, [name + suffix, name])


def find_ninja(cmake: Path) -> Path:
    for name in ("ninja", "ninja.exe"):
        found = shutil.which(name)
        if found:
            return Path(found).resolve()
    sibling = cmake.parent / ("ninja.exe" if platform.system() == "Windows" else "ninja")
    if sibling.is_file():
        return sibling.resolve()
    fail("Unable to find Ninja; add it to PATH or place it beside CMake.")


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def parse_version() -> str:
    build_file = ROOT / "app" / "build.gradle.kts"
    if not build_file.is_file():
        build_file = ROOT / "app" / "build.gradle"
    text = build_file.read_text(encoding="utf-8")
    matches = re.findall(r'(?m)^\s*versionName\s*(?:=\s*)?"([^"]+)"\s*$', text)
    if len(matches) != 1:
        fail(f"Unable to resolve one canonical versionName from {build_file}.")
    return matches[0]


def validate_library(library: Path, readelf: Path, nm: Path, objdump: Path) -> dict[str, object]:
    header = run([str(readelf), "-h", str(library)], capture=True)
    if not re.search(r"Class:\s+ELF64", header) or not re.search(r"Machine:\s+AArch64", header):
        fail("Native output is not ELF64 AArch64.")

    exports_text = run(
        [str(nm), "-D", "--defined-only", "--extern-only", str(library)],
        capture=True,
    )
    exports = sorted({
        match.group(1)
        for line in exports_text.splitlines()
        if (match := re.search(r"\s[A-Za-z]\s+(\S+?)(?:@@\S+)?$", line))
    })
    if exports != ["zn_module"]:
        fail(f"Unexpected exports: {', '.join(exports)}")

    notes = run([str(readelf), "-n", str(library)], capture=True)
    if not re.search(r"aarch64 feature: BTI, PAC", notes):
        fail("Native output does not advertise BTI/PAC.")

    tail = run([
        str(objdump), "--disassemble-symbols=MiuiHomeHyosBroadcastOptionsTailHook",
        str(library),
    ], capture=True)
    if (
        "<MiuiHomeHyosBroadcastOptionsTailHook>:" not in tail
        or not re.search(r"\bbti\s+c\b", tail)
        or not re.search(r"\bbr\s+x16\b", tail)
        or re.search(r"\b(?:bl|blr|ret)\b", tail)
        or re.search(r"\b(?:add|sub)\s+sp\b", tail)
    ):
        fail("Private broadcast tail hook no longer preserves the raw call frame.")
    stack = [line for line in tail.splitlines() if re.search(r"\[(?:sp|wsp),", line)]
    if len(stack) != 2 or any("[sp, #0x58]" not in line for line in stack):
        fail("Private broadcast tail hook has an unexpected stack access.")
    if sum("str" in line for line in stack) != 1:
        fail("Private broadcast tail hook has an unexpected store count.")

    pilfer = run([
        str(objdump), "--disassemble-symbols=MiuiHomeHyosInputMonitorPilferHook",
        str(library),
    ], capture=True)
    if (
        "<MiuiHomeHyosInputMonitorPilferHook>:" not in pilfer
        or not re.search(r"\bbti\s+c\b", pilfer)
        or not re.search(r"\bmov\s+x1,\s*x30\b", pilfer)
        or not re.search(r"\bb\s+0x[0-9a-f]+\s+<MiuiHomeHyosInputMonitorPilferImpl>", pilfer)
        or re.search(r"\b(?:bl|blr|ret|paci|auti|xpac)\w*\b", pilfer)
        or re.search(r"\b(?:add|sub)\s+sp\b", pilfer)
    ):
        fail("InputMonitor pilfer hook no longer preserves the raw caller frame.")

    dynamic = run([str(readelf), "-d", str(library)], capture=True)
    if re.search(r"NEEDED.*(?:libc\+\+|libstdc\+\+)", dynamic):
        fail("Native output has an unexpected shared C++ runtime dependency.")
    return {"exports": exports, "sha256": sha256(library)}


def write_text(path: Path, value: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(value, encoding="utf-8", newline="\n")


def package(library: Path, version: str, version_code: str, nm: Path) -> Path:
    stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    stage = ROOT / "out" / "miui-home-hyos-zn" / f"package-{stamp}"
    stage_lib = stage / "lib" / "arm64"
    stage_bin = stage / "bin"
    stage_lib.mkdir(parents=True, exist_ok=True)
    stage_bin.mkdir(parents=True, exist_ok=True)
    shutil.copy2(library, stage_lib / "libmiui_home_hyos_zn.so")
    for name in ("README.md", "verify.sh", "uninstall.sh", "zn_modules.txt"):
        shutil.copy2(SOURCE_ROOT / name, stage / name)
    shutil.copytree(SOURCE_ROOT / "META-INF", stage / "META-INF")
    shutil.copy2(SOURCE_ROOT / "bin" / "hsctl", stage_bin / "hsctl")

    module_prop = (SOURCE_ROOT / "module.prop.in").read_text(encoding="utf-8")
    module_prop = (
        module_prop.replace("@MODULE_ID@", "miui-home-hyos-zn")
        .replace("@MODULE_NAME@", "MiuiHome Native Hook")
        .replace("@VERSION_NAME@", version)
        .replace("@VERSION_CODE@", version_code)
    )
    write_text(stage / "module.prop", module_prop)
    customize = (SOURCE_ROOT / "customize.sh.in").read_text(encoding="utf-8")
    write_text(stage / "customize.sh", customize)

    symbols = run([str(nm), "-a", "-n", str(library)], capture=True).splitlines()
    counter_specs = [
        ("hyos_runtime_registration", "g_hyos_runtime_registration_state", "u4"),
        ("hyos_runtime_type", "g_hyos_runtime_type", "u4"),
        ("hyos_runtime_api_version", "g_hyos_runtime_api_version", "u4"),
        ("hyos_specialize_count", "g_hyos_specialize_count", "u4"),
        ("hyos_specialize_rejected", "g_hyos_specialize_rejected_count", "u4"),
        ("hyos_launcher_specialized", "g_hyos_launcher_specialized", "u4"),
        ("hyos_lifecycle_sequence", "g_hyos_lifecycle_sequence", "u8"),
        ("hyos_specialize_sequence", "g_hyos_specialize_sequence", "u8"),
        ("launcher_library_observed", "g_launcher_library_observed_count", "u4"),
        ("launcher_library_after_specialize", "g_launcher_library_after_specialize_count", "u4"),
        ("launcher_library_sequence", "g_launcher_library_observed_sequence", "u8"),
        ("launcher_entry_observed", "g_launcher_entry_observed_count", "u4"),
        ("launcher_entry_after_specialize", "g_launcher_entry_after_specialize_count", "u4"),
        ("launcher_entry_sequence", "g_launcher_entry_observed_sequence", "u8"),
        ("send_count", "g_native_broadcast_send_count", "u4"),
        ("send_kind", "g_native_broadcast_send_kind", "u4"),
        ("send_state", "g_native_broadcast_send_state", "u4"),
        ("result_tag", "g_native_broadcast_result_tag", "u4"),
        ("options_consumed", "g_native_broadcast_options_consumed", "u4"),
        ("query_attempts", "g_arbiter_query_attempts", "u4"),
        ("state_marked", "g_arbiter_state_marked_count", "u4"),
        ("state_passthrough", "g_arbiter_state_passthrough_count", "u4"),
        ("accepted_count", "g_accepted_processor_down_count", "u4"),
        ("publish_count", "g_accepted_processor_publish_count", "u4"),
        ("processor_suppressed", "g_gesture_processor_suppressed_count", "u4"),
        ("processor_boundary_return", "g_gesture_processor_boundary_return_count", "u4"),
        ("processor_entry", "g_gesture_processor_entry_count", "u4"),
        ("inner_gesture_type_last", "g_inner_gesture_type_last", "u4"),
        ("inner_gesture_type_1", "g_inner_gesture_type_1_count", "u4"),
        ("inner_gesture_type_2", "g_inner_gesture_type_2_count", "u4"),
        ("outer_down_post_type_last", "g_outer_down_post_type_last", "u4"),
        ("outer_down_post_type_0", "g_outer_down_post_type_0_count", "u4"),
        ("outer_down_post_type_1", "g_outer_down_post_type_1_count", "u4"),
        ("outer_down_post_type_2", "g_outer_down_post_type_2_count", "u4"),
        ("outer_down_post_type_3", "g_outer_down_post_type_3_count", "u4"),
        ("ownership_enabled", "g_enable_systemui_ownership", "u4"),
        ("stub_back_count", "g_stub_back_handler_count", "u4"),
        ("stub_back_action_last", "g_stub_back_action_last", "u4"),
        ("stub_back_down", "g_stub_back_down_count", "u4"),
        ("stub_back_move", "g_stub_back_move_count", "u4"),
        ("stub_back_up", "g_stub_back_up_count", "u4"),
        ("stub_back_cancel", "g_stub_back_cancel_count", "u4"),
        ("stub_back_edge_last", "g_stub_back_edge_last", "u4"),
        ("pilfer_hook_count", "g_pilfer_hook_count", "u4"),
        ("owned_pilfer_suppressed", "g_owned_stream_pilfer_suppressed_count", "u4"),
        ("down_capture", "g_motion_down_capture_count", "u4"),
        ("contextual_search_enabled", "g_contextual_search_enabled", "u4"),
        ("contextual_feature_hook", "g_contextual_feature_hook_state", "u4"),
        ("contextual_feature_queries", "g_contextual_feature_query_count", "u4"),
        ("contextual_feature_overrides", "g_contextual_feature_override_count", "u4"),
        ("contextual_long_press_hook", "g_contextual_long_press_hook_state", "u4"),
        ("contextual_long_press_triggers", "g_contextual_long_press_trigger_count", "u4"),
        ("contextual_long_press_passthrough", "g_contextual_long_press_passthrough_count", "u4"),
        ("contextual_search_invokes", "g_contextual_search_invoke_count", "u4"),
        ("contextual_search_last_result", "g_contextual_search_invoke_last_result", "u4"),
        ("contextual_long_press_upward_cancel", "g_contextual_long_press_upward_cancel_count", "u4"),
        ("business_repair_attempts", "g_business_repair_attempt_count", "u4"),
        ("business_repair_successes", "g_business_repair_success_count", "u4"),
        ("business_repair_failures", "g_business_repair_failure_count", "u4"),
        ("business_repair_stage", "g_business_repair_stage", "u4"),
        ("dynamic_profile_state", "g_dynamic_profile_state", "u4"),
        ("dynamic_side_candidates", "g_dynamic_side_candidate_count", "u4"),
        ("dynamic_runtime_confirmations", "g_dynamic_runtime_confirmation_count", "u4"),
        ("dynamic_rstring_candidates", "g_dynamic_rstring_candidate_count", "u4"),
        ("dynamic_contextual_support_candidates", "g_dynamic_contextual_support_candidate_count", "u4"),
        ("dynamic_contextual_invoke_candidates", "g_dynamic_contextual_invoke_candidate_count", "u4"),
        ("dynamic_contextual_long_press_candidates", "g_dynamic_contextual_long_press_candidate_count", "u4"),
        ("dynamic_contextual_resolved", "g_dynamic_contextual_resolved", "u4"),
        ("dynamic_side_offset", "g_dynamic_side_handler_offset", "u8"),
        ("dynamic_runtime_pointer", "g_dynamic_runtime_pointer_offset", "u8"),
        ("dynamic_runtime_state", "g_dynamic_runtime_state_offset", "u8"),
        ("dynamic_rstring_vtable", "g_dynamic_rstring_vtable_offset", "u8"),
        ("dynamic_contextual_invoke_offset", "g_dynamic_contextual_search_invoke_offset", "u8"),
        ("dynamic_contextual_long_press_offset", "g_dynamic_contextual_long_press_handler_offset", "u8"),
        ("runtime_status_queries", "g_runtime_status_query_count", "u4"),
        ("runtime_status_responses", "g_runtime_status_response_count", "u4"),
        ("runtime_status_last_nonce", "g_runtime_status_last_nonce", "u8"),
        ("runtime_status_last_state", "g_runtime_status_last_state", "u4"),
        ("bridge_state", "g_arbiter_bridge_hook_state", "u4"),
        ("arbiter_ready", "g_systemui_arbiter_ready", "u4"),
        ("business_state", "g_business_hook_state", "u4"),
        ("generation", "g_systemui_arbiter_generation", "u8"),
    ]
    counter_lines = []
    for key, symbol, kind in counter_specs:
        matches = [
            line for line in symbols
            if re.search(rf"^([0-9a-fA-F]+)\s+\S\s+.*{re.escape(symbol)}", line)
        ]
        if len(matches) != 1:
            fail(f"Unable to resolve unique diagnostic counter: {key}")
        address = re.match(r"^([0-9a-fA-F]+)", matches[0]).group(1)
        counter_lines.append(f"{key} 0x{address} {kind}")
    write_text(stage / "diagnostics.map", "\n".join(counter_lines) + "\n")

    for file in list(stage.rglob("*")):
        if file.is_file():
            write_text(file.with_name(file.name + ".sha256"), sha256(file))

    output = ROOT / "out" / "packages" / f"miui-home-hyos-zn-{stamp}.zip"
    output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for file in sorted(stage.rglob("*")):
            if file.is_file():
                archive.write(file, file.relative_to(stage).as_posix())
    return output


def reset_stale_cmake_cache(build_root: Path) -> None:
    """Remove a generated build tree configured from a different source path."""
    cache_file = build_root / "CMakeCache.txt"
    if not cache_file.is_file():
        return
    cache = cache_file.read_text(encoding="utf-8", errors="replace")
    match = re.search(r"(?m)^CMAKE_HOME_DIRECTORY:INTERNAL=(.+)$", cache)
    if not match:
        return
    configured_source = Path(match.group(1).strip()).resolve()
    if configured_source != SOURCE_ROOT.resolve():
        print(f"Resetting stale CMake cache: {configured_source}", flush=True)
        shutil.rmtree(build_root)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--configuration", choices=("Debug", "Release", "RelWithDebInfo"),
                        default="Release")
    parser.add_argument("--ndk-path")
    parser.add_argument("--cmake-path")
    args = parser.parse_args()

    ndk = find_ndk(args.ndk_path)
    cmake = executable(args.cmake_path, ["cmake", "cmake.exe"])
    ninja = find_ninja(cmake)
    toolchain = ndk / "build" / "cmake" / "android.toolchain.cmake"
    if not toolchain.is_file():
        fail(f"Android toolchain does not exist: {toolchain}")
    readelf = host_tool(ndk, "llvm-readelf")
    nm = host_tool(ndk, "llvm-nm")
    objdump = host_tool(ndk, "llvm-objdump")
    build_root = ROOT / "out" / "miui-home-hyos-zn" / args.configuration
    reset_stale_cmake_cache(build_root)
    generated = build_root / "generated" / "launcher_profiles.generated.h"
    generated.parent.mkdir(parents=True, exist_ok=True)
    run([sys.executable, str(SOURCE_ROOT / "generate-launcher-profiles.py"),
         "--output", str(generated)])
    run([
        str(cmake), "-S", str(SOURCE_ROOT), "-B", str(build_root), "-G", "Ninja",
        f"-DCMAKE_MAKE_PROGRAM={ninja}",
        f"-DCMAKE_TOOLCHAIN_FILE={toolchain}",
        "-DANDROID_ABI=arm64-v8a",
        "-DANDROID_PLATFORM=android-35",
        "-DANDROID_STL=none",
        f"-DLAUNCHER_PROFILE_INCLUDE_DIR={generated.parent}",
        f"-DCMAKE_BUILD_TYPE={args.configuration}",
    ])
    run([str(cmake), "--build", str(build_root), "--parallel"])
    library = build_root / "libmiui_home_hyos_zn.so"
    if not library.is_file():
        fail(f"Expected native library was not produced: {library}")
    validation = validate_library(library, readelf, nm, objdump)
    package_path = package(
        library,
        parse_version(),
        run(["git", "-C", str(ROOT), "rev-list", "--count", "HEAD"],
            capture=True).strip(),
        nm,
    )
    print(json.dumps({
        "Library": str(library),
        "Sha256": validation["sha256"],
        "Exports": ", ".join(validation["exports"]),
        "Package": str(package_path),
        "PackageSha256": sha256(package_path),
        "Packaged": True,
        "Installed": False,
    }, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
