#!/usr/bin/env python3
"""Offline structural regression for the runtime side-boundary resolver."""

from __future__ import annotations

import argparse
import hashlib
import json
import struct
from dataclasses import dataclass
from pathlib import Path

from elftools.elf.elffile import ELFFile


PF_X = 1
PF_W = 2
PF_R = 4

SIDE_PROLOGUE = struct.pack(
    "<8I",
    0xD10603FF,
    0xFD008BEA,
    0x6D11A3E9,
    0xA912FBFD,
    0xF9009FFC,
    0xA91467FA,
    0xA9155FF8,
    0xA91657F6,
)
ENTRY_PREFIX = struct.pack(
    "<9I",
    0xD104C3FF,
    0xA90D7BFD,
    0xA90E6FFC,
    0xA90F67FA,
    0xA9105FF8,
    0xA91157F6,
    0xA9124FF4,
    0x910343FD,
    0xAA0803F9,
)

IMPORT_NAMES = (
    "input_MotionEvent_getActionMasked",
    "input_MotionEvent_getActionIndex",
    "input_MotionEvent_getRawX",
    "input_MotionEvent_getRawY",
    "Runtime_inc_strong",
    "Runtime_get_application_thread_binder",
    "Runtime_dec_strong",
    "Bundle_default",
    "Bundle_drop",
    "PackageManager_default",
    "PackageManager_has_system_feature",
    "malloc",
    "memcpy",
)


@dataclass(frozen=True)
class Segment:
    start: int
    end: int
    flags: int


@dataclass(frozen=True)
class Resolution:
    side: int
    edge: int
    runtime_pointer: int
    runtime_state: int
    runtime_confirmations: int
    rstring: int
    contextual_support: int
    contextual_invoke: int
    contextual_long_press: int


class LoadedElf:
    def __init__(self, path: Path):
        self.path = path
        file_bytes = path.read_bytes()
        self.digest = hashlib.sha256(file_bytes).hexdigest()
        with path.open("rb") as stream:
            elf = ELFFile(stream)
            loads = [
                segment
                for segment in elf.iter_segments()
                if segment["p_type"] == "PT_LOAD"
            ]
            self.segments = [
                Segment(
                    int(segment["p_vaddr"]),
                    int(segment["p_vaddr"] + segment["p_memsz"]),
                    int(segment["p_flags"]),
                )
                for segment in loads
            ]
            span = max(segment.end for segment in self.segments)
            self.image = bytearray(span)
            for segment in loads:
                start = int(segment["p_vaddr"])
                data = segment.data()
                self.image[start : start + len(data)] = data

            relocation_section = elf.get_section_by_name(".rela.plt")
            if relocation_section is None:
                raise ValueError("missing .rela.plt")
            symbol_section = elf.get_section(relocation_section["sh_link"])
            imports: dict[str, list[int]] = {name: [] for name in IMPORT_NAMES}
            for relocation in relocation_section.iter_relocations():
                name = symbol_section.get_symbol(
                    relocation["r_info_sym"]
                ).name
                if name in imports:
                    imports[name].append(int(relocation["r_offset"]))
            duplicate = {
                name: values for name, values in imports.items() if len(values) != 1
            }
            if duplicate:
                raise ValueError(f"missing or duplicate imports: {duplicate}")
            self.imports = {name: values[0] for name, values in imports.items()}

    def contains(self, offset: int, size: int, required: int, forbidden: int = 0) -> bool:
        return size > 0 and any(
            offset >= segment.start
            and offset + size <= segment.end
            and segment.flags & required == required
            and segment.flags & forbidden == 0
            for segment in self.segments
        )

    def u32(self, offset: int) -> int:
        if not self.contains(offset, 4, PF_R | PF_X):
            raise ValueError(f"instruction outside executable LOAD: 0x{offset:x}")
        return struct.unpack_from("<I", self.image, offset)[0]

    def executable_offsets(self, required_size: int):
        for segment in self.segments:
            if segment.flags & (PF_R | PF_X) != PF_R | PF_X:
                continue
            start = (segment.start + 3) & ~3
            for offset in range(start, segment.end - required_size + 1, 4):
                yield offset


def decode_adrp(instruction: int, pc: int, register: int) -> int | None:
    if instruction & 0x9F00001F != 0x90000000 | register:
        return None
    immediate = ((instruction >> 29) & 3) | (((instruction >> 5) & 0x7FFFF) << 2)
    if immediate & (1 << 20):
        immediate -= 1 << 21
    target = (pc & ~0xFFF) + (immediate << 12)
    return target if target >= 0 else None


def decode_add(instruction: int, destination: int, source: int) -> int | None:
    if instruction & 0xFFC003FF != 0x91000000 | source << 5 | destination:
        return None
    return (instruction >> 10) & 0xFFF


def decode_ldr(instruction: int, destination: int, source: int) -> int | None:
    if instruction & 0xFFC003FF != 0xF9400000 | source << 5 | destination:
        return None
    return ((instruction >> 10) & 0xFFF) * 8


def decode_address_pair(image: LoadedElf, offset: int, register: int) -> int | None:
    page = decode_adrp(image.u32(offset), offset, register)
    immediate = decode_add(image.u32(offset + 4), register, register)
    if page is None or immediate is None:
        return None
    return page + immediate


def decode_bl(image: LoadedElf, offset: int) -> int | None:
    instruction = image.u32(offset)
    if instruction & 0xFC000000 != 0x94000000:
        return None
    immediate = instruction & 0x03FFFFFF
    if immediate & (1 << 25):
        immediate -= 1 << 26
    target = offset + (immediate << 2)
    return target if image.contains(target, 16, PF_R | PF_X) else None


def decode_plt_got(image: LoadedElf, offset: int) -> int | None:
    page = decode_adrp(image.u32(offset), offset, 16)
    load = decode_ldr(image.u32(offset + 4), 17, 16)
    add = decode_add(image.u32(offset + 8), 16, 16)
    if (
        page is None
        or load is None
        or add is None
        or load != add
        or image.u32(offset + 12) != 0xD61F0220
    ):
        return None
    return page + load


def call_targets(image: LoadedElf, offset: int, import_name: str) -> bool:
    target = decode_bl(image, offset)
    return target is not None and decode_plt_got(image, target) == image.imports[import_name]


def resolve_side(image: LoadedElf) -> tuple[int, int, int]:
    matches: list[tuple[int, int]] = []
    for offset in image.executable_offsets(0x108):
        if image.image[offset : offset + len(SIDE_PROLOGUE)] != SIDE_PROLOGUE:
            continue
        edge_instruction = image.u32(offset + 0x30)
        edge = (edge_instruction >> 10) & 0xFFF
        if (
            edge_instruction & 0xFFC003FF != 0x39400014
            or edge < 0x40
            or edge > 0x400
            or edge & 3
            or image.u32(offset + 0x60) != 0xAA1503E0
            or not call_targets(image, offset + 0x64, IMPORT_NAMES[0])
            or image.u32(offset + 0xD8) != 0xAA1503E0
            or not call_targets(image, offset + 0xDC, IMPORT_NAMES[1])
            or image.u32(offset + 0xE4) != 0xAA1503E0
            or not call_targets(image, offset + 0xE8, IMPORT_NAMES[2])
            or image.u32(offset + 0xEC) != 0xAA1503E0
            or not call_targets(image, offset + 0xF8, IMPORT_NAMES[1])
            or image.u32(offset + 0x100) != 0xAA1503E0
            or not call_targets(image, offset + 0x104, IMPORT_NAMES[3])
        ):
            continue
        matches.append((offset, edge))
    if len(matches) != 1:
        raise ValueError(f"expected one side boundary, found {len(matches)}")
    return matches[0][0], matches[0][1], len(matches)


def resolve_runtime(image: LoadedElf) -> tuple[int, int, int]:
    matches: list[tuple[int, int]] = []
    for offset in image.executable_offsets(52):
        state = decode_address_pair(image, offset, 8)
        pointer_page = decode_adrp(image.u32(offset + 16), offset + 16, 20)
        pointer_immediate = decode_ldr(image.u32(offset + 20), 0, 20)
        repeated = decode_ldr(image.u32(offset + 28), 22, 20)
        if state is None or pointer_page is None or pointer_immediate is None:
            continue
        pointer = pointer_page + pointer_immediate
        if (
            image.u32(offset + 8) != 0x88DFFD08
            or image.u32(offset + 12) & 0xFF00001F != 0x35000008
            or not call_targets(image, offset + 24, IMPORT_NAMES[4])
            or repeated != pointer_immediate
            or image.u32(offset + 32) != 0xAA1603E0
            or not call_targets(image, offset + 36, IMPORT_NAMES[5])
            or image.u32(offset + 40) != 0xAA0003F4
            or image.u32(offset + 44) != 0xAA1603E0
            or not call_targets(image, offset + 48, IMPORT_NAMES[6])
            or state != pointer + 8
            or not image.contains(pointer, 8, PF_R | PF_W, PF_X)
            or not image.contains(state, 4, PF_R | PF_W, PF_X)
        ):
            continue
        matches.append((pointer, state))
    if not matches or len(set(matches)) != 1:
        raise ValueError(f"runtime graph is not unique: {matches}")
    return matches[0][0], matches[0][1], len(matches)


def resolve_rstring(image: LoadedElf) -> tuple[int, int]:
    matches: list[int] = []
    for offset in image.executable_offsets(0x84):
        if (
            not call_targets(image, offset, IMPORT_NAMES[7])
            or image.u32(offset + 4) != 0xAA0003FB
            or image.u32(offset + 8) != 0x52800120
            or not call_targets(image, offset + 12, "malloc")
            or image.u32(offset + 16) & 0xFF00001F != 0xB4000000
            or not call_targets(image, offset + 0x5C, "malloc")
            or not call_targets(image, offset + 0x70, "memcpy")
        ):
            continue
        first = decode_address_pair(image, offset + 0x44, 9)
        second = decode_address_pair(image, offset + 0x7C, 9)
        if (
            first is None
            or first != second
            or not image.contains(first, 8, PF_R, PF_X)
        ):
            continue
        matches.append(first)
    if len(matches) != 1:
        raise ValueError(f"expected one RString constructor, found {len(matches)}")
    return matches[0], len(matches)


def resolve_contextual_support(image: LoadedElf) -> int:
    prologue = (0xD10383FF, 0xA90C7BFD, 0xA90D4FF4, 0x910303FD)
    prologue_bytes = struct.pack("<4I", *prologue)
    matches: list[int] = []
    for offset in image.executable_offsets(0xD4):
        if image.image[offset : offset + len(prologue_bytes)] != prologue_bytes:
            continue
        first_page = decode_adrp(image.u32(offset + 0x14), offset + 0x14, 1)
        first_add = decode_add(image.u32(offset + 0x18), 1, 1)
        second_page = decode_adrp(image.u32(offset + 0xB4), offset + 0xB4, 1)
        second_add = decode_add(image.u32(offset + 0xB8), 1, 1)
        if (
            not call_targets(image, offset + 0x10, "PackageManager_default")
            or first_page is None
            or first_add is None
            or not image.contains(first_page + first_add, 33, PF_R, PF_X)
            or image.u32(offset + 0x1C) != 0x910143E8
            or image.u32(offset + 0x20) != 0x52800422
            or image.u32(offset + 0x24) != 0x2A1F03E3
            or image.u32(offset + 0x28) != 0xAA0003F3
            or not call_targets(
                image, offset + 0x2C, "PackageManager_has_system_feature"
            )
            or second_page is None
            or second_add is None
            or not image.contains(second_page + second_add, 44, PF_R, PF_X)
            or image.u32(offset + 0xC0) != 0x910143E8
            or image.u32(offset + 0xC4) != 0xAA1303E0
            or image.u32(offset + 0xC8) != 0x52800582
            or image.u32(offset + 0xCC) != 0x2A1F03E3
            or not call_targets(
                image, offset + 0xD0, "PackageManager_has_system_feature"
            )
        ):
            continue
        matches.append(offset)
    if len(matches) != 1:
        raise ValueError(f"expected one contextual support function, found {len(matches)}")
    return matches[0]


def resolve_contextual_invoke(image: LoadedElf, support: int) -> int:
    prologue = (
        0xD10543FF,
        0xA9117BFD,
        0xA9125FFC,
        0xA91357F6,
        0xA9144FF4,
        0x910443FD,
        0x9100C3F6,
        0xB90007E0,
    )
    prologue_bytes = struct.pack("<8I", *prologue)
    matches: list[int] = []
    for offset in image.executable_offsets(0x2C):
        if image.image[offset : offset + len(prologue_bytes)] != prologue_bytes:
            continue
        if (
            decode_bl(image, offset + 0x20) != support
            or image.u32(offset + 0x28) & 0xFFF8001F != 0x36000000
        ):
            continue
        matches.append(offset)
    if len(matches) != 1:
        raise ValueError(f"expected one contextual invoke function, found {len(matches)}")
    return matches[0]


def resolve_contextual_long_press(image: LoadedElf) -> int:
    prologue = (0xD102C3FF, 0xA9097BFD, 0xA90A4FF4, 0x910243FD)
    prologue_bytes = struct.pack("<4I", *prologue)
    exact = {
        0x14: 0xAA0003F3,
        0x58: 0x2A0103F4,
        0xE0: 0xD63F0100,
        0xE4: 0x2A1403E1,
        0xE8: 0xF9400268,
        0xEC: 0x52800029,
        0xF0: 0x91004108,
        0xF4: 0x089FFD09,
        0xF8: 0xF9400668,
        0x100: 0xF9400A69,
        0x104: 0xF940092A,
        0x108: 0xF9401529,
        0x10C: 0xD100054A,
        0x110: 0x927CED4A,
        0x114: 0x8B0A0108,
        0x118: 0x91004100,
        0x11C: 0xD63F0120,
    }
    matches: list[int] = []
    for offset in image.executable_offsets(0x140):
        if image.image[offset : offset + len(prologue_bytes)] != prologue_bytes:
            continue
        fallback = decode_bl(image, offset + 0x134)
        if (
            any(image.u32(offset + relative) != word for relative, word in exact.items())
            or image.u32(offset + 0xFC) & 0xFF00001F != 0xB4000008
            or fallback is None
            or fallback == offset
            or image.u32(offset + 0x138) & 0xFF00001F != 0xB4000000
            or not call_targets(image, offset + 0x13C, "Bundle_drop")
        ):
            continue
        matches.append(offset)
    if len(matches) != 1:
        raise ValueError(f"expected one contextual long-press Fn, found {len(matches)}")
    return matches[0]


def resolve(image: LoadedElf, entry: int) -> Resolution:
    if (
        not image.contains(entry, 48, PF_R | PF_X)
        or image.image[entry : entry + len(ENTRY_PREFIX)] != ENTRY_PREFIX
    ):
        raise ValueError("app_entry_point is outside the side-boundary family")
    side, edge, _ = resolve_side(image)
    pointer, state, confirmations = resolve_runtime(image)
    rstring, _ = resolve_rstring(image)
    support = resolve_contextual_support(image)
    invoke = resolve_contextual_invoke(image, support)
    long_press = resolve_contextual_long_press(image)
    return Resolution(
        side, edge, pointer, state, confirmations, rstring,
        support, invoke, long_press,
    )


def parse_int(value: str) -> int:
    return int(value, 0)


def verify(profile: dict, path: Path) -> None:
    if profile["hook_topology"] != "side_boundary_only":
        raise ValueError(f"{profile['id']} is not a side-boundary profile")
    image = LoadedElf(path)
    if image.digest.lower() != profile["library_sha256"].lower():
        raise ValueError(f"{profile['id']} immutable library hash mismatch")
    entry = parse_int(profile["entry_offset"])
    result = resolve(image, entry)
    contextual = profile.get("contextual_search")
    expected = Resolution(
        parse_int(profile["side_handler"]["offset"]),
        parse_int(profile["side_handler"]["edge_field_offset"]),
        parse_int(profile["abi"]["runtime_pointer_offset"]),
        parse_int(profile["abi"]["runtime_state_offset"]),
        result.runtime_confirmations,
        parse_int(profile["abi"]["rstring_vtable_offset"]),
        result.contextual_support,
        parse_int(contextual["invoke_offset"])
        if contextual else result.contextual_invoke,
        parse_int(contextual["long_press_handler_offset"])
        if contextual else result.contextual_long_press,
    )
    if result != expected:
        raise ValueError(f"{profile['id']} resolution mismatch: {result} != {expected}")

    original = image.image[result.side]
    image.image[result.side] ^= 1
    try:
        resolve_side(image)
    except ValueError:
        pass
    else:
        raise ValueError(f"{profile['id']} corrupted side boundary did not fail closed")
    finally:
        image.image[result.side] = original

    original = image.image[result.contextual_long_press + 0xF4]
    image.image[result.contextual_long_press + 0xF4] ^= 1
    try:
        resolve_contextual_long_press(image)
    except ValueError:
        pass
    else:
        raise ValueError(
            f"{profile['id']} corrupted contextual long-press Fn did not fail closed"
        )
    finally:
        image.image[result.contextual_long_press + 0xF4] = original

    print(
        f"{profile['id']}: PASS side=0x{result.side:x} edge=0x{result.edge:x} "
        f"rstring=0x{result.rstring:x} runtime=0x{result.runtime_pointer:x}/"
        f"0x{result.runtime_state:x} confirmations={result.runtime_confirmations} "
        f"contextual=0x{result.contextual_long_press:x}/"
        f"0x{result.contextual_invoke:x} support=0x{result.contextual_support:x}"
    )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--manifest",
        type=Path,
        default=Path(__file__).with_name("launcher-profiles.json"),
    )
    parser.add_argument("--library", action="append", required=True, metavar="ID=PATH")
    args = parser.parse_args()
    manifest = json.loads(args.manifest.read_text(encoding="utf-8"))
    profiles = {profile["id"]: profile for profile in manifest["profiles"]}
    for binding in args.library:
        profile_id, separator, raw_path = binding.partition("=")
        if not separator or profile_id not in profiles:
            raise ValueError(f"invalid --library binding: {binding}")
        verify(profiles[profile_id], Path(raw_path))


if __name__ == "__main__":
    main()
