#!/usr/bin/env python3
"""Generate Kotlin @Serializable DTOs from the botiverse/slock OpenAPI artifact.

DTO-only: data classes and enums, no client, no Ktor, no request code. Requests
still go through the shared `RaftNetworkClient`; hand-written `parseSharedXxx`
functions in `compose/shared/.../data/` decode with these types.

Only the schemas reachable from an allow-list (operations and named schemas)
are generated. Anything this script does not understand fails the run instead
of being guessed. See README.md next to this file.

Usage:
  python3 scripts/openapi/gen_kotlin_dtos.py [--config FILE] [--check] [openapi.json]

The openapi.json path defaults to
`$SLOCK_SOURCE_REPO/packages/shared/openapi/openapi.json`.
"""

from __future__ import annotations

import argparse
import difflib
import json
import os
import re
import subprocess
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_CONFIG = Path(__file__).resolve().parent / "readstate.allowlist.json"
OPENAPI_RELATIVE = Path("packages/shared/openapi/openapi.json")
SOURCE_COMMIT_PREFIX = "// Source commit: "

# Schema keywords that change nothing about the Kotlin shape. Validation-only
# keywords are accepted (the server enforces them; the client does not).
IGNORED_KEYWORDS = {
    "description",
    "title",
    "format",
    "pattern",
    "minimum",
    "maximum",
    "exclusiveMinimum",
    "exclusiveMaximum",
    "minLength",
    "maxLength",
    "minItems",
    "maxItems",
    "examples",
    "example",
    "deprecated",
    "readOnly",
}
SCALAR_TYPES = {"string": "String", "integer": "Long", "number": "Double", "boolean": "Boolean"}
KOTLIN_HARD_KEYWORDS = {
    "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in",
    "interface", "is", "null", "object", "package", "return", "super", "this", "throw",
    "true", "try", "typealias", "typeof", "val", "var", "when", "while",
}
PROPERTY_NAME = re.compile(r"^[a-z][A-Za-z0-9]*$")
SCHEMA_NAME = re.compile(r"^[A-Z][A-Za-z0-9]*$")
ENUM_WIRE_VALUE = re.compile(r"^[a-z][a-z0-9]*([_-][a-z0-9]+)*$")
REF_PREFIX = "#/components/schemas/"


class Unsupported(Exception):
    """A construct the generator refuses to guess about."""


def fail(where: str, message: str) -> None:
    raise Unsupported(f"{where}: {message}")


# ---------------------------------------------------------------- type model


class TypeRef:
    def __init__(self, kotlin: str, nullable: bool, note: str | None = None):
        self.kotlin = kotlin
        self.nullable = nullable
        self.note = note


class Generator:
    def __init__(self, document: dict, config: dict):
        self.document = document
        self.config = config
        self.schemas: dict = document.get("components", {}).get("schemas", {})
        self.suffix: str = config.get("classSuffix", "")
        self.wanted: list[str] = []
        self.operations: list[tuple[str, str, str, list[tuple[str, str]]]] = []

    # ---- allow-list resolution

    def class_name(self, schema_name: str) -> str:
        return schema_name + self.suffix

    def ref_name(self, ref: str, where: str) -> str:
        if not ref.startswith(REF_PREFIX):
            fail(where, f"only local component refs are supported, got {ref!r}")
        name = ref[len(REF_PREFIX):]
        if name not in self.schemas:
            fail(where, f"dangling $ref {ref!r}")
        return name

    def want(self, name: str) -> None:
        if name not in self.wanted:
            self.wanted.append(name)
            self.collect_refs(self.schemas[name], f"schema {name}")

    def collect_refs(self, node, where: str) -> None:
        if isinstance(node, dict):
            if "$ref" in node:
                self.want(self.ref_name(node["$ref"], where))
            for key, value in node.items():
                self.collect_refs(value, f"{where}.{key}")
        elif isinstance(node, list):
            for index, value in enumerate(node):
                self.collect_refs(value, f"{where}[{index}]")

    def resolve_allow_list(self) -> None:
        by_id = {}
        for path, item in self.document.get("paths", {}).items():
            for method, operation in item.items():
                if isinstance(operation, dict) and "operationId" in operation:
                    by_id[operation["operationId"]] = (method.upper(), path, operation)
        for operation_id in self.config.get("operations", []):
            if operation_id not in by_id:
                fail("config", f"operation {operation_id!r} is not in the document")
            method, path, operation = by_id[operation_id]
            where = f"operation {operation_id}"
            mapping: list[tuple[str, str]] = []
            body = operation.get("requestBody")
            if body is not None:
                mapping.append(("request", self.media_schema(body, f"{where}.requestBody")))
            responses = operation.get("responses", {})
            if not responses:
                fail(where, "has no responses")
            for status in sorted(responses):
                if status == "default":
                    fail(where, "a `default` response union is not supported; use per-status schemas")
                mapping.append((status, self.media_schema(responses[status], f"{where}.responses.{status}")))
            self.operations.append((operation_id, method, path, mapping))
            for _, name in mapping:
                self.want(name)
        for name in self.config.get("schemas", []):
            if name not in self.schemas:
                fail("config", f"schema {name!r} is not in the document")
            self.want(name)
        for name, fields in self.config.get("compatOptional", {}).items():
            if name not in self.wanted:
                fail("config", f"compatOptional names {name!r}, which is not generated")
            required = set(self.schemas[name].get("required", []))
            for field in fields:
                if field not in required:
                    fail("config", f"compatOptional {name}.{field} is not a required property (stale entry?)")

    def media_schema(self, holder: dict, where: str) -> str:
        content = holder.get("content")
        if not isinstance(content, dict) or set(content) != {"application/json"}:
            fail(where, "only an application/json body is supported")
        schema = content["application/json"].get("schema")
        if not isinstance(schema, dict) or set(schema) != {"$ref"}:
            fail(where, "the body schema must be a bare $ref to a named component")
        return self.ref_name(schema["$ref"], where)

    # ---- schema -> Kotlin

    def check_keywords(self, schema: dict, allowed: set[str], where: str) -> None:
        unknown = set(schema) - allowed - IGNORED_KEYWORDS
        if unknown:
            fail(where, f"unsupported keyword(s) {sorted(unknown)}")

    def property_type(self, schema, where: str) -> TypeRef:
        if not isinstance(schema, dict):
            fail(where, "schema must be an object")
        for union in ("oneOf", "anyOf"):
            if union in schema:
                return self.nullable_union(schema, union, where)
        if "allOf" in schema:
            fail(where, "allOf is not supported")
        if "$ref" in schema:
            self.check_keywords(schema, {"$ref", "nullable"}, where)
            name = self.ref_name(schema["$ref"], where)
            return TypeRef(self.class_name(name), schema.get("nullable") is True)
        if "enum" in schema:
            fail(where, "inline enums are not supported; give the enum a component id")
        nullable = schema.get("nullable") is True
        kind = schema.get("type")
        if isinstance(kind, list):
            non_null = [k for k in kind if k != "null"]
            if len(non_null) != 1 or len(kind) - len(non_null) > 1:
                fail(where, f"type list {kind!r} is not a simple nullable type")
            nullable = nullable or len(non_null) != len(kind)
            kind = non_null[0]
        note = None
        if "const" in schema:
            const = schema["const"]
            if type(const) not in (str, bool, int) or (kind is None):
                fail(where, f"unsupported const {const!r}")
            note = f"Always `{json.dumps(const)}` on the wire."
        if kind in SCALAR_TYPES:
            self.check_keywords(schema, {"type", "nullable", "const"}, where)
            return TypeRef(SCALAR_TYPES[kind], nullable, note)
        if kind == "array":
            self.check_keywords(schema, {"type", "nullable", "items"}, where)
            item = self.property_type(schema.get("items"), f"{where}.items")
            if item.nullable:
                fail(where, "nullable array items are not supported")
            return TypeRef(f"List<{item.kotlin}>", nullable)
        if kind == "object":
            fail(where, "inline objects are not supported; give the object a component id")
        fail(where, f"unsupported type {kind!r}")
        raise AssertionError("unreachable")

    def nullable_union(self, schema: dict, union: str, where: str) -> TypeRef:
        self.check_keywords(schema, {union}, where)
        members = schema[union]
        nulls = [m for m in members if m == {"type": "null"}]
        rest = [m for m in members if m != {"type": "null"}]
        if len(members) != 2 or len(nulls) != 1:
            fail(where, f"{union} is only supported as [X, {{type: null}}] (a nullable X)")
        inner = self.property_type(rest[0], f"{where}.{union}[0]")
        return TypeRef(inner.kotlin, True, inner.note)

    def render_enum(self, name: str, schema: dict) -> list[str]:
        where = f"schema {name}"
        self.check_keywords(schema, {"type", "enum"}, where)
        if schema.get("type") != "string":
            fail(where, "only string enums are supported")
        values = schema["enum"]
        if not values or len(set(values)) != len(values):
            fail(where, "enum must be a non-empty list of distinct values")
        entries = []
        for value in values:
            if not isinstance(value, str) or not ENUM_WIRE_VALUE.match(value):
                fail(where, f"enum value {value!r} has no safe Kotlin entry name")
            entry = "".join(part.capitalize() for part in re.split(r"[_-]", value))
            if entry == "Unknown":
                fail(where, "enum value `unknown` collides with the generated Unknown fallback")
            entries.append((entry, value))
        kotlin = self.class_name(name)
        lines = [
            f"/**",
            f" * OpenAPI `{name}`. A value this build does not know (a newer server) decodes to",
            f" * [Unknown] instead of failing the whole response.",
            f" */",
            f"@Serializable(with = {kotlin}Serializer::class)",
            f"enum class {kotlin}(val wireValue: String?) {{",
        ]
        for entry, value in entries:
            lines.append(f"    @SerialName({json.dumps(value)})")
            lines.append(f"    {entry}({json.dumps(value)}),")
        lines += [
            "",
            "    /** Not in this build's contract. Never sent. */",
            "    Unknown(null),",
            "}",
            "",
            f"object {kotlin}Serializer : KSerializer<{kotlin}> {{",
            "    override val descriptor: SerialDescriptor =",
            f"        PrimitiveSerialDescriptor(\"{self.config['package']}.{kotlin}\", PrimitiveKind.STRING)",
            "",
            f"    override fun deserialize(decoder: Decoder): {kotlin} {{",
            "        val wire = decoder.decodeString()",
            f"        return {kotlin}.entries.firstOrNull {{ it.wireValue == wire }} ?: {kotlin}.Unknown",
            "    }",
            "",
            f"    override fun serialize(encoder: Encoder, value: {kotlin}) {{",
            f"        val wire = value.wireValue ?: throw SerializationException(\"{kotlin}.Unknown has no wire value\")",
            "        encoder.encodeString(wire)",
            "    }",
            "}",
        ]
        return lines

    def render_object(self, name: str, schema: dict) -> list[str]:
        where = f"schema {name}"
        self.check_keywords(schema, {"type", "properties", "required", "additionalProperties"}, where)
        if schema.get("type") != "object":
            fail(where, "expected type: object")
        if schema.get("additionalProperties", False) is not False:
            fail(where, "maps / open additionalProperties are not supported")
        properties: dict = schema.get("properties", {})
        if not properties:
            fail(where, "an object without properties is not supported")
        required = set(schema.get("required", []))
        missing = required - set(properties)
        if missing:
            fail(where, f"required names unknown properties {sorted(missing)}")
        compat = self.config.get("compatOptional", {}).get(name, {})
        lines = [
            "/**",
            f" * OpenAPI `{name}`. Required fields are non-null; optional and nullable",
            " * fields default to null, so an answer without them still parses. Unknown",
            " * keys are left to the decoder's `ignoreUnknownKeys`.",
            " */",
            "@Serializable",
            f"data class {self.class_name(name)}(",
        ]
        for prop, prop_schema in properties.items():
            prop_where = f"{where}.{prop}"
            if not PROPERTY_NAME.match(prop):
                fail(prop_where, "property name is not lower camelCase; no @SerialName renames are generated")
            kotlin_name = f"`{prop}`" if prop in KOTLIN_HARD_KEYWORDS else prop
            ref = self.property_type(prop_schema, prop_where)
            docs = []
            if ref.note:
                docs.append(ref.note)
            if prop in compat:
                docs.append(f"Required by the contract; nullable here because {compat[prop]}.")
            optional = prop not in required or prop in compat or ref.nullable
            if docs:
                lines.append(f"    /** {' '.join(docs)} */")
            if optional:
                lines.append(f"    val {kotlin_name}: {ref.kotlin}? = null,")
            else:
                lines.append(f"    val {kotlin_name}: {ref.kotlin},")
        lines.append(")")
        return lines

    def render(self, source_path: str, source_commit: str, config_path: str) -> str:
        self.resolve_allow_list()
        body: list[str] = []
        uses_enum = False
        for name in sorted(self.wanted):
            if not SCHEMA_NAME.match(name):
                fail(f"schema {name}", "component name is not UpperCamelCase")
            schema = self.schemas[name]
            body.append("")
            if "enum" in schema:
                uses_enum = True
                body += self.render_enum(name, schema)
            else:
                body += self.render_object(name, schema)
        header = [
            "// GENERATED FILE - DO NOT EDIT.",
            "// Generated by scripts/openapi/gen_kotlin_dtos.py (see scripts/openapi/README.md).",
            f"// Source: botiverse/slock {source_path}",
            f"{SOURCE_COMMIT_PREFIX}{source_commit}",
            f"// Allow-list: {config_path}",
            "//",
        ]
        for operation_id, method, path, mapping in self.operations:
            header.append(f"// {operation_id}: {method} {path}")
            for status, name in mapping:
                header.append(f"//   {status} -> {self.class_name(name)}")
        imports = ["kotlinx.serialization.Serializable"]
        if uses_enum:
            imports += [
                "kotlinx.serialization.KSerializer",
                "kotlinx.serialization.SerialName",
                "kotlinx.serialization.SerializationException",
                "kotlinx.serialization.descriptors.PrimitiveKind",
                "kotlinx.serialization.descriptors.PrimitiveSerialDescriptor",
                "kotlinx.serialization.descriptors.SerialDescriptor",
                "kotlinx.serialization.encoding.Decoder",
                "kotlinx.serialization.encoding.Encoder",
            ]
        lines = header + ["", f"package {self.config['package']}", ""]
        lines += [f"import {name}" for name in sorted(imports)]
        lines += body
        return "\n".join(lines) + "\n"


# ---------------------------------------------------------------- source info


def git(cwd: Path, *args: str) -> str | None:
    try:
        result = subprocess.run(["git", "-C", str(cwd), *args], capture_output=True, text=True, check=True)
    except (OSError, subprocess.CalledProcessError):
        return None
    return result.stdout.strip()


def source_info(openapi: Path) -> tuple[str, str]:
    top = git(openapi.parent, "rev-parse", "--show-toplevel")
    if top is None:
        return openapi.name, "unknown (not a git checkout)"
    relative = openapi.resolve().relative_to(Path(top).resolve()).as_posix()
    sha = git(openapi.parent, "rev-parse", "HEAD") or "unknown"
    if git(openapi.parent, "status", "--porcelain", "--", relative):
        sha += " (uncommitted changes)"
    return relative, sha


def without_commit_line(text: str) -> list[str]:
    return [line for line in text.splitlines() if not line.startswith(SOURCE_COMMIT_PREFIX)]


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("openapi", nargs="?", help="openapi.json (default: $SLOCK_SOURCE_REPO/" f"{OPENAPI_RELATIVE})")
    parser.add_argument("--config", default=str(DEFAULT_CONFIG), help="allow-list JSON")
    parser.add_argument("--check", action="store_true", help="fail if the committed output is stale")
    args = parser.parse_args(argv)

    if args.openapi:
        openapi = Path(args.openapi)
    else:
        repo = os.environ.get("SLOCK_SOURCE_REPO")
        if not repo:
            print("error: pass openapi.json or set SLOCK_SOURCE_REPO to a botiverse/slock checkout", file=sys.stderr)
            return 2
        openapi = Path(repo) / OPENAPI_RELATIVE
    if not openapi.is_file():
        print(f"error: {openapi} does not exist", file=sys.stderr)
        return 2

    config_path = Path(args.config).resolve()
    config = json.loads(config_path.read_text(encoding="utf-8"))
    output = REPO_ROOT / config["output"]
    document = json.loads(openapi.read_text(encoding="utf-8"))
    if not str(document.get("openapi", "")).startswith("3."):
        print("error: not an OpenAPI 3.x document", file=sys.stderr)
        return 2

    source_path, source_commit = source_info(openapi)
    try:
        rendered = Generator(document, config).render(
            source_path, source_commit, config_path.relative_to(REPO_ROOT).as_posix()
        )
    except Unsupported as error:
        print(f"error: unsupported OpenAPI construct: {error}", file=sys.stderr)
        return 1

    if args.check:
        current = output.read_text(encoding="utf-8") if output.is_file() else ""
        if without_commit_line(current) != without_commit_line(rendered):
            diff = difflib.unified_diff(
                without_commit_line(current), without_commit_line(rendered),
                fromfile=f"{config['output']} (committed)", tofile="regenerated", lineterm="",
            )
            print("\n".join(diff))
            print(f"error: {config['output']} is stale; rerun scripts/openapi/gen_kotlin_dtos.py", file=sys.stderr)
            return 1
        print(f"ok: {config['output']} matches {source_path} at {source_commit}")
        return 0

    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(rendered, encoding="utf-8")
    print(f"wrote {config['output']} from {source_path} at {source_commit}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
