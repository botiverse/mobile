#!/usr/bin/env python3
"""Check the patched foundation-android AAR against the official AAR it was made from.

Fails unless, compared with the official files:
  * the AAR has the same entries in the same order, with the same bytes, compression method and
    timestamp, except the content of classes.jar;
  * classes.jar has the same entries in the same order, with the same bytes, compression method and
    timestamp, except the two patched classes (TARGETS);
  * in each patched class file only these parts differ:
      - constant-pool entries appended for `<this class>.isAttached:()Z` (nothing else is appended,
        and the original entries keep their bytes and indices);
      - the Code attribute of the target method: the bytecode is the guard followed by the original
        bytecode, byte for byte; line numbers, local variables and exception ranges move by the
        guard's length (those that start at offset 0 keep covering the guard); the stack map table
        is the original one plus one same_frame right after the guard; max_stack is unchanged
        (the guard needs one slot);
    every other method, every field and every class attribute is byte-identical;
  * the `javap -c -p` listings of the two classes (written by the workflow) are identical outside
    the target method, and the target method lists the guard and then the original instructions,
    moved by the guard's length.

The guard is what kotlinc emits for `if (!isAttached) return` (`return null` for an object result):
    aload_0; invokevirtual <this class>.isAttached:()Z; ifne <body>; return | aconst_null, areturn

Usage: verify_patch.py OFFICIAL_AAR PATCHED_AAR JAVAP_DIR
         JAVAP_DIR holds <class>.official.txt and <class>.patched.txt (`javap -c -p <class>`)
       verify_patch.py --self-test OFFICIAL_AAR
         patches OFFICIAL_AAR in memory the way RaftAttachGuardPatch.java does, then checks that the
         result passes and that broken variants of it fail (javap parts only if javap is on PATH)
"""
from __future__ import annotations

import contextlib
import io
import re
import shutil
import struct
import subprocess
import sys
import tempfile
import zipfile
from collections import Counter
from pathlib import Path

CLASSES_JAR = "classes.jar"
# class file entry -> (method name, descriptor); the guard's result is `return` for ()V, else null.
TARGETS = {
    "androidx/compose/foundation/text/input/internal/TextFieldCoreModifierNode.class":
        ("startCursorJob", "()V"),
    "androidx/compose/foundation/text/modifiers/SelectableTextAnnotatedStringNode.class":
        ("retrievePinnableContainer", "()Landroidx/compose/ui/layout/PinnableContainer;"),
}
GUARD_NAME, GUARD_DESCRIPTOR = "isAttached", "()Z"
UTF8, CLASS, METHODREF, NAME_AND_TYPE = 1, 7, 10, 12
BRANCHES = {"ifeq", "ifne", "iflt", "ifge", "ifgt", "ifle", "if_icmpeq", "if_icmpne", "if_icmplt",
            "if_icmpge", "if_icmpgt", "if_icmple", "if_acmpeq", "if_acmpne", "goto", "jsr", "ifnull",
            "ifnonnull", "goto_w", "jsr_w"}


class Mismatch(Exception):
    pass


def guard_bytes(methodref: int, returns_void: bool, jump: int = 0x9A) -> bytes:
    """aload_0; invokevirtual #methodref; ifne +4|+5; return | aconst_null, areturn."""
    head = bytes([0x2A, 0xB6, methodref >> 8, methodref & 0xFF, jump])
    return head + (bytes([0x00, 0x04, 0xB1]) if returns_void else bytes([0x00, 0x05, 0x01, 0xB0]))


# ---------------------------------------------------------------- class files


class Reader:
    def __init__(self, data: bytes, pos: int = 0) -> None:
        self.data, self.pos = data, pos

    def take(self, n: int) -> bytes:
        if self.pos + n > len(self.data):
            raise Mismatch("truncated class file structure")
        chunk = self.data[self.pos:self.pos + n]
        self.pos += n
        return chunk

    def u1(self) -> int:
        return self.take(1)[0]

    def u2(self) -> int:
        return struct.unpack(">H", self.take(2))[0]

    def u4(self) -> int:
        return struct.unpack(">I", self.take(4))[0]


class ClassFile:
    """Constant pool entries (raw bytes) and the byte spans of members and attributes."""

    def __init__(self, data: bytes) -> None:
        self.data = data
        r = Reader(data)
        if r.u4() != 0xCAFEBABE:
            raise Mismatch("not a class file")
        r.take(4)  # minor, major
        count = r.u2()
        self.pool: list[bytes | None] = [None]
        while len(self.pool) < count:
            start, tag = r.pos, r.u1()
            if tag == UTF8:
                r.take(r.u2())
            elif tag in (3, 4, 9, 10, 11, 12, 17, 18):
                r.take(4)
            elif tag in (5, 6):
                r.take(8)
            elif tag in (7, 8, 16, 19, 20):
                r.take(2)
            elif tag == 15:
                r.take(3)
            else:
                raise Mismatch(f"unknown constant pool tag {tag}")
            self.pool.append(data[start:r.pos])
            if tag in (5, 6):
                self.pool.append(None)  # 8-byte constants take two slots
        self.pool_end = r.pos
        r.take(6)  # access_flags, this_class, super_class
        self.this_class = struct.unpack_from(">H", data, self.pool_end + 2)[0]
        r.take(2 * r.u2())  # interfaces
        for _ in range(r.u2()):  # fields
            r.take(6)
            self._attributes(r)
        self.methods_start = r.pos
        self.methods: list[tuple[str, str, bytes, list[tuple[str, bytes]]]] = []
        for _ in range(r.u2()):
            start = r.pos
            r.take(2)
            name, descriptor = self.utf8(r.u2()), self.utf8(r.u2())
            attributes = self._attributes(r)
            self.methods.append((name, descriptor, data[start:r.pos], attributes))
        self.methods_end = r.pos
        self._attributes(r)
        if r.pos != len(data):
            raise Mismatch("trailing bytes after the class file")

    def _attributes(self, r: Reader) -> list[tuple[str, bytes]]:
        return [(self.utf8(r.u2()), r.take(r.u4())) for _ in range(r.u2())]

    def entry(self, index: int, tag: int) -> bytes:
        raw = self.pool[index] if 0 < index < len(self.pool) else None
        if raw is None or raw[0] != tag:
            raise Mismatch(f"constant pool #{index} is not of tag {tag}")
        return raw

    def utf8(self, index: int) -> str:
        return self.entry(index, UTF8)[3:].replace(b"\xc0\x80", b"\0").decode("utf-8", "surrogateescape")

    def describe(self, index: int) -> tuple:
        """A readable key for the entries the guard may add."""
        raw = self.pool[index] if 0 < index < len(self.pool) else None
        if raw is None:
            return ("no constant", index)
        tag = raw[0]
        if tag == UTF8:
            return ("Utf8", self.utf8(index))
        if tag == CLASS:
            return ("Class", self.utf8(struct.unpack_from(">H", raw, 1)[0]))
        if tag == NAME_AND_TYPE:
            name, descriptor = struct.unpack_from(">HH", raw, 1)
            return ("NameAndType", self.utf8(name), self.utf8(descriptor))
        if tag == METHODREF:
            owner, nat = struct.unpack_from(">HH", raw, 1)
            return ("Methodref", self.describe(owner)[1], *self.describe(nat)[1:])
        return (f"tag {tag}", raw.hex())

    def class_name(self) -> str:
        return self.describe(self.this_class)[1]


def parse_code(cf: ClassFile, info: bytes):
    r = Reader(info)
    max_stack, max_locals = r.u2(), r.u2()
    code = r.take(r.u4())
    handlers = [struct.unpack(">HHHH", r.take(8)) for _ in range(r.u2())]
    attributes = cf._attributes(r)
    if r.pos != len(info):
        raise Mismatch("trailing bytes in the Code attribute")
    return max_stack, max_locals, code, handlers, attributes


def verification_type(r: Reader) -> tuple:
    tag = r.u1()
    if tag in (7, 8):  # Object (constant pool index), Uninitialized (offset of the `new`)
        return (tag, r.u2())
    if tag > 8:
        raise Mismatch(f"bad verification type tag {tag}")
    return (tag,)


def frames(info: bytes) -> list[tuple]:
    """StackMapTable -> [(bytecode offset, kind, items)], same/same_extended etc. unified."""
    r = Reader(info)
    out, offset = [], -1
    for _ in range(r.u2()):
        frame_type = r.u1()
        if frame_type < 64:
            kind, delta, items = "same", frame_type, ()
        elif frame_type < 128:
            kind, delta, items = "same_locals_1_stack_item", frame_type - 64, (verification_type(r),)
        elif frame_type < 247:
            raise Mismatch(f"reserved stack map frame type {frame_type}")
        elif frame_type == 247:
            delta = r.u2()
            kind, items = "same_locals_1_stack_item", (verification_type(r),)
        elif frame_type < 251:
            kind, delta, items = f"chop {251 - frame_type}", r.u2(), ()
        elif frame_type == 251:
            kind, delta, items = "same", r.u2(), ()
        elif frame_type < 255:
            delta = r.u2()
            kind, items = "append", tuple(verification_type(r) for _ in range(frame_type - 251))
        else:
            delta = r.u2()
            local_types = tuple(verification_type(r) for _ in range(r.u2()))
            kind, items = "full", (local_types, tuple(verification_type(r) for _ in range(r.u2())))
        offset += delta + 1
        out.append((offset, kind, items))
    if r.pos != len(info):
        raise Mismatch("trailing bytes in StackMapTable")
    return out


def shift_items(items, move):
    if isinstance(items, tuple) and len(items) == 2 and items[0] == 8 and isinstance(items[1], int):
        return (8, move(items[1]))
    if isinstance(items, tuple):
        return tuple(shift_items(item, move) if isinstance(item, tuple) else item for item in items)
    return items


def compare_class(official: bytes, patched: bytes, method: str, descriptor: str) -> tuple[int, int, int]:
    """Returns (guard methodref index, guard length, original instruction bytes)."""
    o, p = ClassFile(official), ClassFile(patched)
    if official[4:8] != patched[4:8]:
        raise Mismatch("class file version differs")
    if patched[10:o.pool_end] != official[10:o.pool_end]:
        raise Mismatch("the original constant pool entries changed")
    this = o.class_name()
    allowed = {("Utf8", GUARD_NAME), ("Utf8", GUARD_DESCRIPTOR), ("Utf8", "StackMapTable"),
               ("NameAndType", GUARD_NAME, GUARD_DESCRIPTOR), ("Methodref", this, GUARD_NAME, GUARD_DESCRIPTOR)}
    known = {o.describe(i) for i in range(1, len(o.pool))}
    added = [p.describe(i) for i in range(len(o.pool), len(p.pool))]
    for key in added:
        if key not in allowed or key in known or added.count(key) > 1:
            raise Mismatch(f"unexpected constant pool entry appended: {key}")
    if official[o.pool_end:o.methods_start] != patched[p.pool_end:p.methods_start]:
        raise Mismatch("access flags, class names, interfaces or fields differ")
    if official[o.methods_end:] != patched[p.methods_end:]:
        raise Mismatch("class attributes differ")
    if [m[:2] for m in o.methods] != [m[:2] for m in p.methods]:
        raise Mismatch("method list differs")
    hits = [i for i, m in enumerate(o.methods) if m[:2] == (method, descriptor)]
    if len(hits) != 1:
        raise Mismatch(f"{method}{descriptor} found {len(hits)} times")
    for i, (om, pm) in enumerate(zip(o.methods, p.methods)):
        if i != hits[0] and om[2] != pm[2]:
            raise Mismatch(f"method {om[0]}{om[1]} differs, but only {method} may change")
    _, _, o_raw, o_attributes = o.methods[hits[0]]
    _, _, p_raw, p_attributes = p.methods[hits[0]]
    if o_raw[:6] != p_raw[:6] or [a[0] for a in o_attributes] != [a[0] for a in p_attributes]:
        raise Mismatch(f"{method}: access flags or attribute list differ")
    for (name, o_info), (_, p_info) in zip(o_attributes, p_attributes):
        if name != "Code" and o_info != p_info:
            raise Mismatch(f"{method}: attribute {name} differs")
    o_code_info = dict(o_attributes)["Code"]
    p_code_info = dict(p_attributes)["Code"]
    o_stack, o_locals, o_code, o_handlers, o_sub = parse_code(o, o_code_info)
    p_stack, p_locals, p_code, p_handlers, p_sub = parse_code(p, p_code_info)

    returns_void = descriptor.endswith(")V")
    if len(p_code) < 4:
        raise Mismatch(f"{method}: bytecode too short")
    ref = struct.unpack_from(">H", p_code, 2)[0]
    if p.describe(ref) != ("Methodref", this, GUARD_NAME, GUARD_DESCRIPTOR):
        raise Mismatch(f"{method}: the guard calls #{ref} {p.describe(ref)}, not {this}.isAttached:()Z")
    guard = guard_bytes(ref, returns_void)
    if p_code != guard + o_code:
        raise Mismatch(f"{method}: bytecode is not the guard {guard.hex()} followed by the original bytecode")
    g = len(guard)

    def move(offset: int) -> int:
        return offset if offset == 0 else offset + g

    if (p_stack, p_locals) != (max(o_stack, 1), o_locals):
        raise Mismatch(f"{method}: max_stack/max_locals {p_stack}/{p_locals}, expected {max(o_stack, 1)}/{o_locals}")
    if p_handlers != [(move(s), move(e), move(h), t) for s, e, h, t in o_handlers]:
        raise Mismatch(f"{method}: exception table does not match the original moved by {g}")
    o_names = [n for n, _ in o_sub if n != "StackMapTable"]
    p_names = [n for n, _ in p_sub if n != "StackMapTable"]
    if o_names != p_names or [n for n, _ in p_sub].count("StackMapTable") != 1:
        raise Mismatch(f"{method}: Code attributes {[n for n, _ in p_sub]}, official {[n for n, _ in o_sub]}")
    o_frames = frames(dict(o_sub)["StackMapTable"]) if "StackMapTable" in dict(o_sub) else []
    if any(offset == 0 for offset, _, _ in o_frames) or any(0 in (s, h) for s, _, h, _ in o_handlers):
        raise Mismatch(f"{method}: offset 0 is a branch target or starts a try block in the official class")
    expected = [(g, "same", ())] + [(move(off), kind, shift_items(items, move)) for off, kind, items in o_frames]
    if frames(dict(p_sub)["StackMapTable"]) != expected:
        raise Mismatch(f"{method}: stack map frames are not [same_frame at {g}] + the original frames moved by {g}")
    for (name, o_info), (_, p_info) in zip([a for a in o_sub if a[0] != "StackMapTable"],
                                           [a for a in p_sub if a[0] != "StackMapTable"]):
        if name == "LineNumberTable":
            o_rows = struct.iter_unpack(">HH", o_info[2:])
            want = o_info[:2] + b"".join(struct.pack(">HH", move(pc), line) for pc, line in o_rows)
        elif name in ("LocalVariableTable", "LocalVariableTypeTable"):
            o_rows = struct.iter_unpack(">HHHHH", o_info[2:])
            want = o_info[:2] + b"".join(struct.pack(">HHHHH", move(pc), move(pc + n) - move(pc), a, b, c)
                                         for pc, n, a, b, c in o_rows)
        else:
            raise Mismatch(f"{method}: Code attribute {name} is not supported by this check")
        if p_info != want:
            raise Mismatch(f"{method}: {name} does not match the original moved by {g}")
    return ref, g, len(o_code)


# ---------------------------------------------------------------- javap listings

JAVAP_INSN = re.compile(r"^\s+(\d+): (\S+)(?: +(.*))?$")
JAVAP_HANDLER = re.compile(r"^(\d+)\s+(\d+)\s+(\d+)\s+(.+)$")


def javap_block(lines: list[str], method: str) -> tuple[int, int]:
    signature = re.compile(r"^  \S.* " + re.escape(method) + r"\(\);$")
    hits = [i for i, line in enumerate(lines) if signature.match(line)]
    if len(hits) != 1:
        raise Mismatch(f"javap: {len(hits)} listings of {method}()")
    end = hits[0] + 1
    while end < len(lines) and lines[end].strip() and lines[end] != "}":
        end += 1
    return hits[0], end


def javap_code(block: list[str]) -> tuple[list[tuple[int, str, str]], list[tuple]]:
    instructions, handlers, section = [], [], None
    for line in block[1:]:
        text = line.strip()
        if text in ("Code:", "Exception table:"):
            section = text
            continue
        if section == "Exception table:":
            match = JAVAP_HANDLER.match(text)
            if text.startswith("from"):
                continue
            if not match:
                raise Mismatch(f"javap: unexpected exception table line {line!r}")
            handlers.append((int(match[1]), int(match[2]), int(match[3]), " ".join(match[4].split())))
            continue
        match = JAVAP_INSN.match(line)
        if section != "Code:" or not match:
            raise Mismatch(f"javap: unexpected line {line!r}")
        if match[2] in ("tableswitch", "lookupswitch"):
            raise Mismatch("javap: switch instructions are not supported by this check")
        instructions.append((int(match[1]), match[2], " ".join((match[3] or "").split())))
    return instructions, handlers


def compare_javap(official: str, patched: str, method: str, ref: int, g: int, returns_void: bool) -> int:
    o_lines, p_lines = official.splitlines(), patched.splitlines()
    o_start, o_end = javap_block(o_lines, method)
    p_start, p_end = javap_block(p_lines, method)
    if o_lines[:o_start] != p_lines[:p_start] or o_lines[o_end:] != p_lines[p_end:]:
        raise Mismatch(f"javap: the listing differs outside {method}()")
    if o_lines[o_start] != p_lines[p_start]:
        raise Mismatch(f"javap: the signature of {method}() differs")
    o_insns, o_handlers = javap_code(o_lines[o_start:o_end])
    p_insns, p_handlers = javap_code(p_lines[p_start:p_end])
    guard = [(0, "aload_0", ""), (1, "invokevirtual", f"#{ref} // Method {GUARD_NAME}:{GUARD_DESCRIPTOR}"),
             (4, "ifne", str(g))]
    guard += [(7, "return", "")] if returns_void else [(7, "aconst_null", ""), (8, "areturn", "")]

    def move(offset: int) -> int:
        return offset if offset == 0 else offset + g

    expected = guard + [(offset + g, op, str(move(int(rest))) if op in BRANCHES else rest)
                        for offset, op, rest in o_insns]
    if p_insns != expected:
        first = next(i for i in range(max(len(p_insns), len(expected)))
                     if i >= min(len(p_insns), len(expected)) or p_insns[i] != expected[i])
        got = p_insns[first] if first < len(p_insns) else "end of listing"
        want = expected[first] if first < len(expected) else "end of listing"
        raise Mismatch(f"javap: {method}() instruction #{first} is {got}, expected {want}")
    if p_handlers != [(move(a), move(b), move(c), t) for a, b, c, t in o_handlers]:
        raise Mismatch(f"javap: {method}() exception table does not match the original moved by {g}")
    return len(o_insns)


# ---------------------------------------------------------------- archives


def compare_zip(label: str, official: zipfile.ZipFile, patched: zipfile.ZipFile,
                changed: set[str]) -> dict[str, tuple[bytes, bytes]]:
    o_infos, p_infos = official.infolist(), patched.infolist()
    o_names, p_names = [i.filename for i in o_infos], [i.filename for i in p_infos]
    if o_names != p_names:
        diff = next((i for i, (a, b) in enumerate(zip(o_names, p_names)) if a != b), min(len(o_names), len(p_names)))
        raise Mismatch(f"{label}: entry list differs ({len(o_names)} official, {len(p_names)} patched; "
                       f"first difference at #{diff})")
    if len(set(o_names)) != len(o_names):
        raise Mismatch(f"{label}: duplicate entry names")
    missing = changed - set(o_names)
    if missing:
        raise Mismatch(f"{label}: no {sorted(missing)}")
    errors, notes, out = [], Counter(), {}
    for oi, pi in zip(o_infos, p_infos):
        o_data, p_data = official.read(oi), patched.read(pi)
        if oi.filename in changed:
            if o_data == p_data:
                errors.append(f"{oi.filename} is not patched")
            out[oi.filename] = (o_data, p_data)
        elif o_data != p_data:
            errors.append(f"{oi.filename} differs")
        if (oi.compress_type, oi.date_time) != (pi.compress_type, pi.date_time):
            errors.append(f"{oi.filename}: compression method or timestamp differs")
        for attribute in ("external_attr", "create_system", "comment", "extra"):
            if getattr(oi, attribute) != getattr(pi, attribute):
                notes[attribute] += 1
    if official.comment != patched.comment:
        errors.append("archive comment differs")
    if errors:
        raise Mismatch(f"{label}: " + "; ".join(errors[:10]) + (f" (+{len(errors) - 10} more)" if len(errors) > 10 else ""))
    other = f"; zip metadata not preserved (not checked): {dict(notes)}" if notes else ""
    print(f"[ok  ] {label}: {len(o_names)} entries in the same order, all byte-identical except "
          f"{', '.join(sorted(changed))}{other}")
    return out


def verify(official_aar: bytes, patched_aar: bytes, javap: dict[str, tuple[str, str]] | None) -> list[str]:
    """javap: class entry -> (official listing, patched listing), or None to skip the javap check."""
    try:
        with zipfile.ZipFile(io.BytesIO(official_aar)) as o, zipfile.ZipFile(io.BytesIO(patched_aar)) as p:
            jars = compare_zip("AAR", o, p, {CLASSES_JAR})
        with zipfile.ZipFile(io.BytesIO(jars[CLASSES_JAR][0])) as o, \
                zipfile.ZipFile(io.BytesIO(jars[CLASSES_JAR][1])) as p:
            classes = compare_zip(CLASSES_JAR, o, p, set(TARGETS))
    except (Mismatch, zipfile.BadZipFile) as error:
        return [str(error)]
    errors = []
    for entry, (method, descriptor) in TARGETS.items():
        try:
            ref, g, size = compare_class(*classes[entry], method, descriptor)
            print(f"[ok  ] {entry}: {method}{descriptor} = {g}-byte guard (isAttached via #{ref}) + the "
                  f"original {size} bytes; frames, line numbers and locals moved by {g}; rest byte-identical")
            if javap is not None:
                count = compare_javap(*javap[entry], method, ref, g, descriptor.endswith(")V"))
                print(f"[ok  ] javap {entry}: only {method}() changed: the guard, then the original "
                      f"{count} instructions moved by {g}")
        except (Mismatch, KeyError, IndexError, ValueError, struct.error) as error:
            errors.append(f"{entry}: {type(error).__name__ + ': ' if not isinstance(error, Mismatch) else ''}{error}")
    return errors


def class_name(entry: str) -> str:
    return entry[:-len(".class")].replace("/", ".")


def main(argv: list[str]) -> int:
    if len(argv) == 3 and argv[1] == "--self-test":
        return self_test(Path(argv[2]).read_bytes())
    if len(argv) != 4:
        print(__doc__, file=sys.stderr)
        return 2
    javap_dir = Path(argv[3])
    javap = {}
    for entry in TARGETS:
        paths = [javap_dir / f"{class_name(entry)}.{kind}.txt" for kind in ("official", "patched")]
        missing = [str(path) for path in paths if not path.is_file()]
        if missing:
            print(f"FAIL missing javap listing(s): {', '.join(missing)}", file=sys.stderr)
            return 1
        javap[entry] = tuple(path.read_text() for path in paths)
    errors = verify(Path(argv[1]).read_bytes(), Path(argv[2]).read_bytes(), javap)
    for error in errors:
        print(f"FAIL {error}", file=sys.stderr)
    if errors:
        return 1
    print("OK: the patched AAR is the official one plus the two isAttached guards")
    return 0


# ---------------------------------------------------------------- self-test


def reference_patch(official: bytes, method: str, descriptor: str, *, jump: int = 0x9A, frame: bool = True,
                    frame_at: int = 0, move_debug: bool = True, extra_utf8: bytes = b"") -> bytes:
    """What RaftAttachGuardPatch.java writes (ASM ClassWriter(reader, 0)), plus knobs to break it."""
    cf = ClassFile(official)
    pool = list(cf.pool)
    appended = bytearray()

    def find_or_add(raw: bytes) -> int:
        nonlocal appended
        if raw in pool:
            return pool.index(raw)
        pool.append(raw)
        appended += raw
        return len(pool) - 1

    def utf8(text: str) -> int:
        data = text.encode()
        return find_or_add(bytes([UTF8]) + struct.pack(">H", len(data)) + data)

    name, desc = utf8(GUARD_NAME), utf8(GUARD_DESCRIPTOR)  # ASM: the owner Class exists (this_class)
    nat = find_or_add(struct.pack(">BHH", NAME_AND_TYPE, name, desc))
    ref = find_or_add(struct.pack(">BHH", METHODREF, cf.this_class, nat))
    index = next(i for i, m in enumerate(cf.methods) if m[:2] == (method, descriptor))
    _, _, raw, attributes = cf.methods[index]
    code_info = dict(attributes)["Code"]
    max_stack, max_locals, code, handlers, sub = parse_code(cf, code_info)
    guard = guard_bytes(ref, descriptor.endswith(")V"), jump)
    g = len(guard)

    def move(offset: int) -> int:
        return offset if offset == 0 or not move_debug else offset + g

    def put_type(item: tuple) -> bytes:
        return bytes([item[0]]) + (struct.pack(">H", move(item[1]) if item[0] == 8 else item[1]) if len(item) == 2 else b"")

    entries, previous = [], -1
    old = frames(dict(sub)["StackMapTable"]) if "StackMapTable" in dict(sub) else []
    for offset, kind, items in ([(g + frame_at, "same", ())] if frame else []) + \
            [(offset + g, kind, items) for offset, kind, items in old]:
        delta = offset - previous - 1
        previous = offset
        if kind == "same":
            entries.append(bytes([delta]) if delta < 64 else bytes([251]) + struct.pack(">H", delta))
        elif kind == "same_locals_1_stack_item":
            head = bytes([64 + delta]) if delta < 64 else bytes([247]) + struct.pack(">H", delta)
            entries.append(head + put_type(items[0]))
        elif kind.startswith("chop"):
            entries.append(bytes([251 - int(kind.split()[1])]) + struct.pack(">H", delta))
        elif kind == "append":
            entries.append(bytes([251 + len(items)]) + struct.pack(">H", delta) + b"".join(map(put_type, items)))
        else:
            local_types, stack = items
            entries.append(bytes([255]) + struct.pack(">HH", delta, len(local_types)) + b"".join(map(put_type, local_types))
                           + struct.pack(">H", len(stack)) + b"".join(map(put_type, stack)))
    out_sub = [(utf8("StackMapTable"), struct.pack(">H", len(entries)) + b"".join(entries))] if entries else []
    for sub_name, info in sub:
        if sub_name == "LineNumberTable":
            info = info[:2] + b"".join(struct.pack(">HH", move(pc), line) for pc, line in struct.iter_unpack(">HH", info[2:]))
        elif sub_name in ("LocalVariableTable", "LocalVariableTypeTable"):
            info = info[:2] + b"".join(struct.pack(">HHHHH", move(pc), move(pc + n) - move(pc), a, b, c)
                                       for pc, n, a, b, c in struct.iter_unpack(">HHHHH", info[2:]))
        elif sub_name == "StackMapTable":
            continue
        out_sub.append((pool.index(bytes([UTF8]) + struct.pack(">H", len(sub_name)) + sub_name.encode()), info))
    if extra_utf8:
        find_or_add(bytes([UTF8]) + struct.pack(">H", len(extra_utf8)) + extra_utf8)
    new_code = guard + code
    code_out = struct.pack(">HHI", max(max_stack, 1), max_locals, len(new_code)) + new_code
    code_out += struct.pack(">H", len(handlers)) + b"".join(
        struct.pack(">HHHH", move(s), move(e), move(h), t) for s, e, h, t in handlers)
    code_out += struct.pack(">H", len(out_sub)) + b"".join(struct.pack(">HI", n, len(i)) + i for n, i in out_sub)
    # Rebuild the method_info: same header, the Code attribute replaced.
    r = Reader(raw)
    method_out = r.take(6) + r.take(2)
    for attribute_name, info in attributes:
        r.take(6 + len(info))
        method_out += struct.pack(">HI", pool.index(bytes([UTF8]) + struct.pack(">H", len(attribute_name)) + attribute_name.encode()),
                                  len(code_out if attribute_name == "Code" else info))
        method_out += code_out if attribute_name == "Code" else info
    method_spans, pos = [], cf.methods_start + 2
    for _, _, m_raw, _ in cf.methods:
        method_spans.append((pos, pos + len(m_raw)))
        pos += len(m_raw)
    start, end = method_spans[index]
    return (official[:8] + struct.pack(">H", len(pool)) + official[10:cf.pool_end] + bytes(appended)
            + official[cf.pool_end:start] + method_out + official[end:])


def rezip(source: bytes, replace: dict[str, bytes], order: list[str] | None = None) -> bytes:
    out = io.BytesIO()
    with zipfile.ZipFile(io.BytesIO(source)) as zin, zipfile.ZipFile(out, "w") as zout:
        infos = {i.filename: i for i in zin.infolist()}
        for name in order or list(infos):
            info = infos[name]
            copy = zipfile.ZipInfo(info.filename, info.date_time)
            copy.compress_type, copy.external_attr = info.compress_type, info.external_attr
            zout.writestr(copy, replace.get(name, zin.read(info)))
    return out.getvalue()


def run_javap(jar: bytes, entry: str, tmp: Path) -> str:
    path = tmp / "classes.jar"
    path.write_bytes(jar)
    return subprocess.run(["javap", "-c", "-p", "-cp", str(path), class_name(entry)], check=True,
                          capture_output=True, text=True).stdout


def self_test(official_aar: bytes) -> int:
    with zipfile.ZipFile(io.BytesIO(official_aar)) as aar:
        jar = aar.read(CLASSES_JAR)
    with zipfile.ZipFile(io.BytesIO(jar)) as z:
        official = {entry: z.read(entry) for entry in TARGETS}
        names = z.namelist()
    use_javap = shutil.which("javap") is not None

    def build(knobs: dict | None = None, jar_replace: dict | None = None, aar_replace: dict | None = None,
              order: list[str] | None = None, broken: str | None = None) -> bytes:
        patched = {entry: reference_patch(official[entry], *TARGETS[entry], **(knobs if entry == broken else {}))
                   for entry in TARGETS}
        new_jar = rezip(jar, {**patched, **(jar_replace or {})}, order)
        return rezip(official_aar, {CLASSES_JAR: new_jar, **(aar_replace or {})})

    def listings(aar: bytes) -> dict | None:
        if not use_javap:
            return None
        with zipfile.ZipFile(io.BytesIO(aar)) as z:
            patched_jar = z.read(CLASSES_JAR)
        with tempfile.TemporaryDirectory() as tmp:
            return {entry: (run_javap(jar, entry, Path(tmp)), run_javap(patched_jar, entry, Path(tmp))) for entry in TARGETS}

    first = next(iter(TARGETS))
    second = list(TARGETS)[1]
    other_class = next(n for n in names if n.endswith(".class") and n not in TARGETS)
    with zipfile.ZipFile(io.BytesIO(jar)) as z:
        other_bytes = z.read(other_class)
    swapped = list(names)
    swapped[1], swapped[2] = swapped[2], swapped[1]
    tweaked_method = bytearray(official[first])
    # Flip the last byte of the first method's first instruction stream (a different, untouched method).
    cf = ClassFile(official[first])
    target_index = next(i for i, m in enumerate(cf.methods) if m[:2] == TARGETS[first])
    other_index = 0 if target_index != 0 else 1
    method_offset = cf.methods_start + 2 + sum(len(m[2]) for m in cf.methods[:other_index])
    code_offset = official[first].index(dict(cf.methods[other_index][3])["Code"], method_offset)
    tweaked_method[code_offset + 8] ^= 0x01  # first opcode of that method
    good = build()
    cases = [
        ("reference patch", good, True),
        ("guard jumps with ifeq", build({"jump": 0x99}, broken=first), False),
        ("guard without its stack map frame", build({"frame": False}, broken=second), False),
        ("stack map frame one byte late", build({"frame_at": 1}, broken=first), False),
        ("line numbers and locals not moved", build({"move_debug": False}, broken=first), False),
        ("unrelated constant appended", build({"extra_utf8": b"raft"}, broken=second), False),
        ("another method changed", build(jar_replace={first: reference_patch(bytes(tweaked_method), *TARGETS[first])}), False),
        ("another class changed", build(jar_replace={other_class: other_bytes + b"\0"}), False),
        ("a target class left unpatched", build(jar_replace={second: official[second]}), False),
        ("classes.jar entries reordered", build(order=swapped), False),
        ("another AAR entry changed", build(aar_replace={"R.txt": b"int id raft 0x0\n"}), False),
    ]
    failed = 0
    for name, aar, should_pass in cases:
        with contextlib.redirect_stdout(io.StringIO()):
            errors = verify(official_aar, aar, listings(aar) if name == "reference patch" else None)
        if (not errors) != should_pass:
            failed += 1
            print(f"self-test FAIL: {name}: expected {'pass' if should_pass else 'failure'}, got {errors or 'pass'}")
    if use_javap:
        javap = listings(good)
        o_text, p_text = javap[first]
        lines = p_text.splitlines()
        start, _ = javap_block(lines, TARGETS[first][0])
        lines[start + 4] = lines[start + 4].replace("ifne", "ifeq")  # signature, Code:, aload_0, invokevirtual, ifne
        broken = {**javap, first: (o_text, "\n".join(lines) + "\n")}
        broken_outside = {**javap, second: (javap[second][0], javap[second][1] + "// extra line\n")}
        for name, listing in (("javap guard edited", broken), ("javap differs outside the method", broken_outside)):
            with contextlib.redirect_stdout(io.StringIO()):
                errors = verify(official_aar, good, listing)
            if not errors:
                failed += 1
                print(f"self-test FAIL: {name}: expected failure, got pass")
    total = len(cases) + (2 if use_javap else 0)
    print(f"verify_patch self-test: {'FAILED' if failed else 'OK'} ({total} cases"
          f"{'' if use_javap else ', javap cases skipped: no javap on PATH'})")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
