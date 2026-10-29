#!/usr/bin/env python3
"""Qualification of gen_kotlin_dtos.py on synthetic documents: nullable refs, required vs
optional, and the constructs it must refuse. Run: python3 scripts/openapi/test_gen_kotlin_dtos.py
"""

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from gen_kotlin_dtos import Generator, Unsupported  # noqa: E402

CONFIG = {"package": "p", "output": "x.kt", "classSuffix": "Dto", "operations": ["op"]}


def document(response_schema, extra_schemas=None):
    schemas = {"Answer": response_schema, "Kind": {"type": "string", "enum": ["a", "b-c"]}}
    schemas.update(extra_schemas or {})
    return {
        "openapi": "3.1.0",
        "paths": {"/x": {"post": {"operationId": "op", "responses": {
            "200": {"description": "", "content": {"application/json": {"schema": {"$ref": "#/components/schemas/Answer"}}}},
        }}}},
        "components": {"schemas": schemas},
    }


def render(response_schema, config=CONFIG, extra_schemas=None):
    return Generator(document(response_schema, extra_schemas), dict(config)).render("openapi.json", "sha", "c.json")


def answer(properties, required=()):
    return {"type": "object", "properties": properties, "required": list(required), "additionalProperties": False}


KIND = {"$ref": "#/components/schemas/Kind"}


class NullabilityTest(unittest.TestCase):
    def test_required_optional_and_every_nullable_spelling(self):
        out = render(answer({
            "req": {"type": "integer"},
            "opt": {"type": "string"},
            "list31": {"type": ["boolean", "null"]},
            "nullable30": {"type": "string", "nullable": True},
            "refOpt": KIND,
            "refReqNullable": {"anyOf": [KIND, {"type": "null"}]},
            "refOneOf": {"oneOf": [{"type": "null"}, KIND]},
            "items": {"type": "array", "items": KIND},
        }, required=["req", "list31", "refReqNullable", "items"]))
        for line in [
            "val req: Long,",
            "val opt: String? = null,",
            "val list31: Boolean? = null,",
            "val nullable30: String? = null,",
            "val refOpt: KindDto? = null,",
            "val refReqNullable: KindDto? = null,",
            "val refOneOf: KindDto? = null,",
            "val items: List<KindDto>,",
            "    BC(\"b-c\"),",
            "    Unknown(null),",
        ]:
            self.assertIn(line, out)

    def test_compat_optional_makes_a_required_field_nullable(self):
        config = dict(CONFIG, compatOptional={"Answer": {"req": "old servers omit it"}})
        out = render(answer({"req": {"type": "integer"}}, required=["req"]), config)
        self.assertIn("val req: Long? = null,", out)
        stale = dict(CONFIG, compatOptional={"Answer": {"gone": "x"}})
        with self.assertRaises(Unsupported):
            render(answer({"req": {"type": "integer"}}, required=["req"]), stale)


class RefusalTest(unittest.TestCase):
    def assert_refused(self, prop_schema, extra_schemas=None):
        with self.assertRaises(Unsupported):
            render(answer({"p": prop_schema}), extra_schemas=extra_schemas)

    def test_refuses_constructs_it_cannot_model(self):
        other = {"Other": answer({"q": {"type": "string"}})}
        other_ref = {"$ref": "#/components/schemas/Other"}
        for schema in [
            {"oneOf": [KIND, other_ref]},
            {"anyOf": [KIND, other_ref, {"type": "null"}]},
            {"allOf": [KIND]},
            {"type": ["string", "integer"]},
            {"type": "object", "properties": {}},
            {"type": "string", "enum": ["x"]},
            {"type": "string", "default": "x"},
            {"$ref": "#/components/schemas/Missing"},
            {"$ref": "other.json#/X"},
        ]:
            with self.subTest(schema=schema):
                self.assert_refused(schema, other)

    def test_refuses_maps_renames_and_unknown_fallback_collisions(self):
        with self.assertRaises(Unsupported):
            render({"type": "object", "properties": {"a": {"type": "string"}}, "additionalProperties": True})
        with self.assertRaises(Unsupported):
            render(answer({"snake_case": {"type": "string"}}))
        with self.assertRaises(Unsupported):
            render(answer({"k": KIND}), extra_schemas={"Kind": {"type": "string", "enum": ["unknown"]}})


if __name__ == "__main__":
    unittest.main()
