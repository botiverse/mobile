#!/usr/bin/env python3
"""Fail when a compiled JVM method gets close to the JVM's 64 KB method limit.

Incident: 1.25.1 (#4802). A cherry-pick pushed `RaftAppShellReadyContent`
(SettingsHomePage.kt) past the JVM limit of 65535 bytecode bytes per method,
and `:shared:compileDebugKotlinAndroid` failed with "Method too large" on
release day. Nothing showed how close the method was, so the next addition
would have failed the same way. This gate reads the class files the Android
compile has just written and fails while there is still room to split the
method calmly: at --max-bytes (default 50 KiB), well below the hard limit.
Methods that were already over the budget when the gate was added are held at
their size that day (CEILINGS, a ratchet) until they are split.

It reads each method's `Code` attribute `code_length` straight from the class
file (JVMS 4.7.3), so it needs no JDK. It prints the largest methods on every
run, so a PR shows how much room is left.

Usage:
  check_jvm_method_size.py [--max-bytes N] [--top N] CLASS_DIR [CLASS_DIR ...]
  check_jvm_method_size.py --self-test

Exit codes: 0 = every method is within the budget; 1 = a method is over it,
or no class file was found (fail-closed); 2 = usage error or unreadable class.
"""

from __future__ import annotations

import argparse
import struct
import sys
from pathlib import Path

JVM_METHOD_LIMIT = 65535
DEFAULT_MAX_BYTES = 50 * 1024

# Ratchet: methods that were already over the budget when this gate was added. Each may not grow
# past its size on that day (its ceiling); every other method must stay within the budget. Lower a
# ceiling, or drop the entry, once its method is split. Keyed by the full "class.method(descriptor)"
# that scan() reports, so a new overload with the same name keeps the normal budget.
CEILINGS: dict[str, tuple[int, str]] = {}


class ClassFormatError(Exception):
    pass


def method_code_sizes(data: bytes) -> tuple[str, list[tuple[str, str, int]]]:
    """(class name, [(method name, descriptor, code_length)]) for one class file."""
    class_name, methods, _ = read_class(data)
    return class_name, [(name, descriptor, length) for name, descriptor, length, _ in methods]


def read_class(data: bytes) -> tuple[str, list[tuple[str, str, int, list[tuple[int, int]]]], int | None]:
    """(class name, [(name, descriptor, code_length, [(start_pc, line)])], last line of the source file).

    The last real source line comes from Kotlin's SMAP (SourceDebugExtension): line numbers above it
    belong to inlined function bodies. None when the class has no SMAP.
    """
    pos = 0

    def take(fmt: str) -> tuple:
        nonlocal pos
        size = struct.calcsize(fmt)
        if pos + size > len(data):
            raise ClassFormatError("truncated class file")
        values = struct.unpack_from(fmt, data, pos)
        pos += size
        return values

    def skip(count: int) -> None:
        nonlocal pos
        if pos + count > len(data):
            raise ClassFormatError("truncated class file")
        pos += count

    (magic,) = take(">I")
    if magic != 0xCAFEBABE:
        raise ClassFormatError("not a class file")
    skip(4)  # minor, major
    (cp_count,) = take(">H")
    utf8: dict[int, str] = {}
    class_name_index: dict[int, int] = {}
    index = 1
    while index < cp_count:
        (tag,) = take(">B")
        if tag == 1:  # Utf8
            (length,) = take(">H")
            utf8[index] = data[pos : pos + length].decode("utf-8", errors="replace")
            skip(length)
        elif tag == 7:  # Class
            (class_name_index[index],) = take(">H")
        elif tag in (8, 16, 19, 20):  # String, MethodType, Module, Package
            skip(2)
        elif tag == 15:  # MethodHandle
            skip(3)
        elif tag in (3, 4, 9, 10, 11, 12, 17, 18):  # Integer..NameAndType, Dynamic, InvokeDynamic
            skip(4)
        elif tag in (5, 6):  # Long, Double take two slots
            skip(8)
            index += 1
        else:
            raise ClassFormatError(f"unknown constant pool tag {tag}")
        index += 1
    skip(2)  # access flags
    (this_class,) = take(">H")
    class_name = utf8.get(class_name_index.get(this_class, 0), "?")
    skip(2)  # super class
    (interfaces,) = take(">H")
    skip(2 * interfaces)

    def skip_attributes() -> None:
        (attribute_count,) = take(">H")
        for _ in range(attribute_count):
            _, length = take(">HI")
            skip(length)

    (field_count,) = take(">H")
    for _ in range(field_count):
        skip(6)
        skip_attributes()

    methods: list[tuple[str, str, int, list[tuple[int, int]]]] = []
    (method_count,) = take(">H")
    for _ in range(method_count):
        _, name_index, descriptor_index = take(">HHH")
        (attribute_count,) = take(">H")
        for _ in range(attribute_count):
            attribute_name_index, length = take(">HI")
            if utf8.get(attribute_name_index) == "Code":
                methods.append(
                    (utf8.get(name_index, "?"), utf8.get(descriptor_index, "?"), *_code_lines(data, pos, utf8))
                )
            skip(length)
    last_source_line = None
    (attribute_count,) = take(">H")
    for _ in range(attribute_count):
        attribute_name_index, length = take(">HI")
        if utf8.get(attribute_name_index) == "SourceDebugExtension":
            last_source_line = _smap_last_line(data[pos : pos + length].decode("utf-8", errors="replace"))
        skip(length)
    return class_name, methods, last_source_line


def _code_lines(data: bytes, pos: int, utf8: dict[int, str]) -> tuple[int, list[tuple[int, int]]]:
    """A Code attribute's code_length and its LineNumberTable entries."""
    _, _, code_length = struct.unpack_from(">HHI", data, pos)
    pos += 8 + code_length
    (exception_count,) = struct.unpack_from(">H", data, pos)
    pos += 2 + 8 * exception_count
    (attribute_count,) = struct.unpack_from(">H", data, pos)
    pos += 2
    lines: list[tuple[int, int]] = []
    for _ in range(attribute_count):
        name_index, length = struct.unpack_from(">HI", data, pos)
        if utf8.get(name_index) == "LineNumberTable":
            (count,) = struct.unpack_from(">H", data, pos + 6)
            lines += [struct.unpack_from(">HH", data, pos + 8 + 4 * i) for i in range(count)]
        pos += 6 + length
    return code_length, sorted(lines)


def _smap_last_line(smap: str) -> int | None:
    """`*L` section's first range `1#1,N:1`: lines 1..N are the source file's own."""
    lines = smap.splitlines()
    if "*L" not in lines:
        return None
    first = lines[lines.index("*L") + 1]
    if not first.startswith("1#1,"):
        return None
    try:
        return int(first[len("1#1,") :].split(":")[0])
    except ValueError:
        return None


def line_profile(
    code_length: int, lines: list[tuple[int, int]], last_source_line: int | None
) -> list[tuple[int, int]]:
    """[(source line, bytecode bytes)]: inlined code (a line past the file's own) counts for the line it was inlined at."""
    owner: dict[int, int] = {}
    current = None
    entries = lines + [(code_length, -1)]
    for (start, line), (end, _) in zip(entries, entries[1:]):
        if last_source_line is None or line <= last_source_line:
            current = line
        if current is not None:
            owner[current] = owner.get(current, 0) + end - start
    return sorted(owner.items())


def scan(roots: list[Path]) -> list[tuple[int, str, list[tuple[int, int]]]]:
    """(code_length, "class.method(descriptor)", line profile) for every method with code, largest first."""
    sizes: list[tuple[int, str, list[tuple[int, int]]]] = []
    for root in roots:
        for class_file in sorted(root.rglob("*.class")):
            try:
                class_name, methods, last_line = read_class(class_file.read_bytes())
            except (ClassFormatError, struct.error) as error:
                raise ClassFormatError(f"{class_file}: {error}") from None
            for name, descriptor, length, lines in methods:
                profile = line_profile(length, lines, last_line)
                sizes.append((length, f"{class_name.replace('/', '.')}.{name}{descriptor}", profile))
    sizes.sort(key=lambda item: (-item[0], item[1]))
    return sizes


def check(
    roots: list[Path],
    max_bytes: int,
    top: int,
    profile_out: Path | None = None,
    ceilings: dict[str, tuple[int, str]] = CEILINGS,
) -> int:
    missing = [str(root) for root in roots if not root.is_dir()]
    if missing:
        print(f"check_jvm_method_size: class directory not found: {', '.join(missing)}", file=sys.stderr)
        return 1
    try:
        sizes = scan(roots)
    except ClassFormatError as error:
        print(f"check_jvm_method_size: {error}", file=sys.stderr)
        return 2
    if not sizes:
        print(f"check_jvm_method_size: no compiled methods under {', '.join(map(str, roots))}", file=sys.stderr)
        return 1
    print(f"Largest JVM methods (budget {max_bytes} bytes, JVM limit {JVM_METHOD_LIMIT}):")
    for length, method, _ in sizes[:top]:
        print(f"  {length:6d}  {method}")
    if profile_out is not None:
        largest = sizes[0]
        profile_out.write_text("".join(f"{line}\t{size}\n" for line, size in largest[2]))
        print(f"Line profile of {largest[1]} written to {profile_out}")
    over = []
    for length, method, profile in sizes:
        ceiling, note = ceilings.get(method, (max_bytes, ""))
        if length > max(ceiling, max_bytes):
            over.append((length, method, profile, ceiling, note))
        elif ceiling > max_bytes:
            print(f"  ratchet: {method} {length}/{ceiling} bytes ({note})")
    if not over:
        return 0
    for length, method, profile, _, _ in over:
        print(f"\n{method}: source lines with the most bytecode (inlined code counted where it is inlined):")
        for line, size in sorted(profile, key=lambda item: -item[1])[:15]:
            print(f"  line {line:6d}  {size:5d} bytes")
    print(file=sys.stderr)
    for length, method, _, ceiling, note in over:
        limit = f"its ceiling of {ceiling} bytes ({note})" if ceiling > max_bytes else f"the {max_bytes}-byte budget"
        print(
            f"::error::{method} is {length} bytes of bytecode, over {limit} "
            f"(the JVM refuses methods over {JVM_METHOD_LIMIT}: 'Method too large'). Move cohesive "
            "blocks of it into separate functions before it stops compiling.",
            file=sys.stderr,
        )
    return 1


def _class_file(methods: list[tuple]) -> bytes:
    """A minimal class `T` whose methods have the given code lengths and optional descriptors (self-test only)."""
    pool: list[bytes] = []

    def add(entry: bytes) -> int:
        pool.append(entry)
        return len(pool)

    def utf8(text: str) -> int:
        raw = text.encode()
        return add(b"\x01" + struct.pack(">H", len(raw)) + raw)

    this_class = add(b"\x07" + struct.pack(">H", utf8("T")))
    code = utf8("Code")
    add(b"\x05" + struct.pack(">q", 1))  # a Long takes two slots
    pool.append(b"")
    method_bytes = b""
    for entry in methods:
        name, length = entry[0], entry[1]
        descriptor = utf8(entry[2] if len(entry) > 2 else "()V")
        code_attribute = struct.pack(">HHI", 1, 1, length) + b"\x00" * length + struct.pack(">HH", 0, 0)
        method_bytes += struct.pack(">HHHH", 0x9, utf8(name), descriptor, 1)
        method_bytes += struct.pack(">HI", code, len(code_attribute)) + code_attribute
    out = struct.pack(">IHHH", 0xCAFEBABE, 0, 52, len(pool) + 1) + b"".join(pool)
    out += struct.pack(">HHHHH", 0x21, this_class, 0, 0, 0)  # flags, this, super, interfaces, fields
    return out + struct.pack(">H", len(methods)) + method_bytes + struct.pack(">H", 0)


def self_test() -> int:
    import tempfile

    name, methods = method_code_sizes(_class_file([("small", 10), ("big", 60000)]))
    assert name == "T", name
    assert methods == [("small", "()V", 10), ("big", "()V", 60000)], methods
    # Lines past the file's own (100) are inlined code: counted for the line they were inlined at.
    assert line_profile(30, [(0, 10), (5, 12), (9, 250), (20, 13)], 100) == [(10, 5), (12, 15), (13, 10)]
    assert _smap_last_line("SMAP\nA.kt\nKotlin\n*S Kotlin\n*F\n+ 1 A.kt\nA\n*L\n1#1,100:1\n*E\n") == 100
    try:
        method_code_sizes(b"\x00\x00\x00\x00")
        raise AssertionError("a non-class file must be rejected")
    except ClassFormatError:
        pass
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        assert check([root], 100, 5) == 1, "no class files: fail-closed"
        assert check([root / "missing"], 100, 5) == 1, "missing directory: fail-closed"
        (root / "a").mkdir()
        (root / "a" / "T.class").write_bytes(_class_file([("small", 10), ("big", 600)]))
        assert check([root], 1000, 5) == 0, "within budget"
        assert check([root], 500, 5) == 1, "over budget"
        assert check([root], 500, 5, ceilings={"T.big()V": (600, "test")}) == 0, "at its ceiling"
        assert check([root], 500, 5, ceilings={"T.big()V": (599, "test")}) == 1, "grew past its ceiling"
        assert check([root], 5, 5, ceilings={"T.big()V": (600, "test")}) == 1, "others keep the budget"
        assert check([root], 500, 5, ceilings={"T.big": (600, "test")}) == 1, "a name without descriptor ratchets nothing"
        # A new overload with the same name must not inherit the ratcheted method's ceiling.
        (root / "a" / "T.class").write_bytes(_class_file([("big", 100), ("big", 520, "(I)V")]))
        assert check([root], 500, 5, ceilings={"T.big()V": (600, "test")}) == 1, "same-name overload keeps the budget"
        assert check([root], 500, 5, ceilings={"T.big(I)V": (600, "test")}) == 0, "ceiling binds its own descriptor"
    print("check_jvm_method_size self-test passed")
    return 0


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument("--max-bytes", type=int, default=DEFAULT_MAX_BYTES)
    parser.add_argument("--top", type=int, default=10)
    parser.add_argument(
        "--profile-out", type=Path, help="write the largest method's bytecode bytes per source line (TSV)"
    )
    parser.add_argument("class_dirs", nargs="*", type=Path)
    args = parser.parse_args(argv)
    if args.self_test:
        return self_test()
    if not args.class_dirs:
        parser.print_usage(sys.stderr)
        return 2
    return check(args.class_dirs, args.max_bytes, args.top, args.profile_out)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
