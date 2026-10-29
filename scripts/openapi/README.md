# Kotlin DTOs from the slock OpenAPI artifact

`gen_kotlin_dtos.py` (python3, stdlib only) turns the committed botiverse/slock
`packages/shared/openapi/openapi.json` into `@Serializable` Kotlin DTOs in
`compose/shared/.../data/generated/`. The output is committed.

DTO-only: data classes and enums. No client, no Ktor, no request code. Requests
still go through the shared `RaftNetworkClient`; hand-written shared parsers in
`data/` (for example `parseMarkConversationUnreadResult`) decode with the
generated types and map them to app models.

```sh
# regenerate (path defaults to $SLOCK_SOURCE_REPO/packages/shared/openapi/openapi.json)
python3 scripts/openapi/gen_kotlin_dtos.py [path/to/openapi.json]
# drift check: exit 1 with a diff if the committed file is stale
python3 scripts/openapi/gen_kotlin_dtos.py --check [path/to/openapi.json]
# generator self-test
python3 scripts/openapi/test_gen_kotlin_dtos.py
```

## Allow-list

`readstate.allowlist.json` names what is generated: `operations` (every
per-status response, and a request body if any, plus all schemas they reach
through `$ref`) and extra `schemas`. `compatOptional` lists fields the contract
marks required that an older server still in use omits; they become nullable
with a reason in the KDoc. A stale entry fails the run.

## Mapping

- Names: component `X` becomes `XDto`; properties keep their exact camelCase
  wire names (no `@SerialName` renames; a non-camelCase name fails).
- `string`/`integer`/`number`/`boolean` become `String`/`Long`/`Double`/`Boolean`;
  `array` of a supported item becomes `List<T>`; `$ref` becomes the referenced DTO.
- Required non-nullable fields are non-null with no default. Optional fields,
  and nullable ones (`nullable: true`, `type: [x, "null"]`,
  `anyOf`/`oneOf: [X, {type: "null"}]`), become `T? = null`.
- String enums get `@SerialName` wire values, a `wireValue`, and an `Unknown`
  fallback (custom serializer), so a newer server's value does not fail the
  answer. `Unknown` cannot be encoded.
- `const` is typed by its `type` and noted in the KDoc; it is not enforced.
  Validation keywords (`format`, `pattern`, `minimum`, ...) are ignored.

## Refused (the run fails)

Any other `oneOf`/`anyOf`, `allOf`, `discriminator`, inline objects or enums,
maps / open `additionalProperties`, `default`, multi-type `type` lists,
non-local or dangling `$ref`s, `default` responses, non-JSON bodies, and any
schema keyword not listed above. Extend the generator (with a test in
`test_gen_kotlin_dtos.py`) rather than hand-editing the output.

The header records the source path and slock commit; `--check` ignores the
commit line, so a slock bump that does not change the schema is not drift.
