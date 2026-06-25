# Thread Reaction Sprite Atlas Spec

Date: 2026-06-25
Owner: Android/App integration + KMP Thread
Status: Draft
Task: #391

## Problem

Thread reactions currently render from the raw server reaction string. Known quick reactions are either text emoji or hand-drawn Canvas glyphs in `ThreadPage.kt`; unknown reactions fall back to text. This makes visual parity hard to maintain across Android/OHOS/iOS/H5 because emoji fonts differ by platform, while hand-drawn glyphs are embedded in code and do not carry product metadata such as localized names.

We want reactions to render from a sprite atlas: one image contains all supported reaction icons, and a JSON manifest describes each icon's crop rectangle, stable name, server/API reaction value, aliases, and localized labels.

## Goals

- Replace the presentation of supported reaction icons with a shared sprite atlas lookup.
- Keep server/API/storage identity stable: current reaction add/remove payloads still use the existing emoji value unless the backend contract changes.
- Define a manifest schema that includes:
  - atlas image metadata;
  - per-icon `x`, `y`, `w`, `h`;
  - stable icon `name`;
  - API value / emoji aliases;
  - localized labels for supported languages.
- Use the same reaction catalog for quick reaction menu buttons and existing reaction chips.
- Provide deterministic fallback for unsupported or malformed reactions.
- Keep the first implementation local/static; no remote catalog fetch in this task.

## Non-Goals

- Do not change the backend reaction API route or payload shape.
- Do not redesign reaction chip layout, counts, selected state, or task/comment reaction semantics.
- Do not add animated reactions.
- Do not generate atlas artwork in code; artwork is an asset supplied to the repo.
- Do not move message reaction count/selection into the manifest. Counts and selection remain message state.

## Current State

Current Thread code has these relevant pieces:

- `ThreadQuickReactionEmojis = listOf("👍", "❤️", "🎉", "👀", "🔥", "😂", "✅")`
- `KuiklyThreadReactionItem.emoji` is the reaction identity used in UI and mutation calls.
- `ThreadReactionChip` renders a glyph plus count.
- `ThreadMessageReactionRow` renders quick reaction buttons in the long-press menu.
- `ThreadQuickReactionGlyph` renders text fallback or custom Canvas glyphs.
- `SharedThreadDataSource.setMessageReaction(messageId, emoji, selected)` sends the emoji string to `POST/DELETE /messages/:id/reactions`.
- SQLDelight stores reaction edge rows by `(messageId, reactorKind, reactorId, emoji)`.

The sprite work should replace `ThreadQuickReactionGlyph` internals and quick-reaction catalog source. It should not rename `emoji` fields in `KuiklyThreadReactionItem` as part of this slice.

## Asset Layout

Add assets under shared common resources so all Kuikly targets can consume the same catalog:

```text
compose/shared/src/commonMain/assets/reactions/reaction-atlas-v1.png
compose/shared/src/commonMain/assets/reactions/reaction-atlas-v1.json
```

Runtime URI convention:

```text
assets://reactions/reaction-atlas-v1.png
assets://reactions/reaction-atlas-v1.json
```

The source of truth is the shared asset path and JSON schema above. Platform packaging must preserve the `reactions/` relative path so common code never builds Android-only or OHOS-only resource strings.

Android packaging requirement:

- `compose/shared/src/commonMain/assets` must be included in the Android app assets.
- Atlas lookup should resolve `assets://reactions/reaction-atlas-v1.png` to the Android packaged asset.

OHOS packaging requirement:

- The same files must be copied or generated into `compose/ohosApp/entry/src/main/resources/resfile/reactions/`.
- Runtime lookup should resolve `assets://reactions/reaction-atlas-v1.png` to `resfile/reactions/reaction-atlas-v1.png`.
- `compileKotlinOhosArm64` alone is not sufficient proof. Implementation acceptance must include either an HAP/runtime smoke or a deterministic file-existence/package check for both atlas PNG and catalog under `resfile/reactions/`.

## Manifest Schema

Use one JSON file per atlas version:

```json
{
  "schemaVersion": 1,
  "atlas": {
    "image": "reaction-atlas-v1.png",
    "width": 512,
    "height": 512,
    "pixelRatio": 3,
    "defaultDisplaySizeDp": 18
  },
  "reactions": [
    {
      "name": "thumbs_up",
      "apiValue": "👍",
      "aliases": ["👍", "+1", "like"],
      "rect": { "x": 0, "y": 0, "w": 72, "h": 72 },
      "labels": {
        "en": "Thumbs up",
        "zh-Hans": "点赞",
        "zh-Hant": "讚"
      }
    },
    {
      "name": "heart",
      "apiValue": "❤️",
      "aliases": ["❤️", "❤", "heart"],
      "rect": { "x": 72, "y": 0, "w": 72, "h": 72 },
      "labels": {
        "en": "Heart",
        "zh-Hans": "爱心",
        "zh-Hant": "愛心"
      }
    }
  ]
}
```

Field contract:

| Field | Required | Contract |
| --- | --- | --- |
| `schemaVersion` | yes | Integer. Start at `1`; reject unsupported major versions. |
| `atlas.image` | yes | Relative filename in the same directory as the manifest. |
| `atlas.width` / `atlas.height` | yes | Pixel dimensions of the PNG. Used for bounds validation. |
| `atlas.pixelRatio` | yes | Source asset scale. Used only for diagnostics/export validation; UI still receives explicit dp size. |
| `atlas.defaultDisplaySizeDp` | yes | Default quick reaction glyph size. Chips may override with their current 15dp size. |
| `reactions[].name` | yes | Stable lowercase snake_case product name. Unique. |
| `reactions[].apiValue` | yes | Value sent to existing reaction API and stored in message state. Initially this is the canonical emoji. |
| `reactions[].aliases` | yes | Alternative incoming values that should resolve to this icon. Must include `apiValue`. |
| `reactions[].rect` | yes | Pixel crop rectangle in the atlas coordinate space. Must be positive and inside atlas bounds. |
| `reactions[].labels` | yes | BCP-47-like locale map for accessible names and future picker copy. Must include `en`. |

Validation rules:

- `name` must match `^[a-z0-9_]+$`.
- `apiValue` and every alias must map to at most one reaction.
- Rectangles must be within atlas bounds.
- Rectangles should not overlap unless explicitly allowed by a future `sharedRectGroup` field.
- Missing locale labels fall back by exact locale, language prefix, `en`, then `name`.
- Invalid manifest or missing atlas must disable sprite rendering and fall back to current text/Canvas behavior without breaking reaction toggles.

## Initial Catalog

The first manifest should cover every current quick reaction and current Canvas alias:

| Name | API value | Aliases | English label | zh-Hans label |
| --- | --- | --- | --- | --- |
| `thumbs_up` | `👍` | `👍`, `+1`, `like` | Thumbs up | 点赞 |
| `heart` | `❤️` | `❤️`, `❤`, `heart` | Heart | 爱心 |
| `party` | `🎉` | `🎉`, `party` | Celebrate | 庆祝 |
| `eyes` | `👀` | `👀`, `eyes` | Eyes | 关注 |
| `fire` | `🔥` | `🔥`, `fire` | Fire | 火 |
| `laugh` | `😂` | `😂`, `laugh` | Laugh | 大笑 |
| `done` | `✅` | `✅`, `☑`, `☑️`, `✔`, `✔️`, `✓`, `done` | Done | 完成 |

The quick reaction row should be derived from catalog order, not a hard-coded emoji list. If product wants different quick reactions later, update manifest order or add an explicit `quickOrder` array.

## Catalog Loading Boundary

The recommended first implementation is **build-time generated Kotlin catalog**:

1. Keep `reaction-atlas-v1.json` as the source of truth.
2. Add a Gradle or checked-in generation step that validates the JSON and emits a commonMain Kotlin catalog, for example `GeneratedThreadReactionCatalog.kt`.
3. Common UI reads the generated catalog synchronously and does not need runtime asset-text IO.
4. Unit tests parse the JSON source directly to verify it still matches the generated Kotlin catalog.

This avoids adding a new runtime asset-text bridge just for reactions. If implementation instead chooses runtime JSON loading, it must first add a common `BridgeModule.readAssetText(assetPath)` boundary and actual Android/OHOS implementations:

- Android must read app assets by relative path, e.g. `reactions/reaction-atlas-v1.json`.
- OHOS must read `resfile/reactions/reaction-atlas-v1.json`.
- Both platforms must expose the same failure semantics and low-cardinality diagnostics.

Do not implement a common parser that can only pass JVM/unit tests while the real common UI has no way to read the JSON on device.

## Runtime Model

Add commonMain catalog types near Thread presentation code or a small `reaction` package:

```kotlin
data class ThreadReactionSpriteCatalog(
    val schemaVersion: Int,
    val atlas: ThreadReactionAtlas,
    val reactions: List<ThreadReactionSprite>
) {
    fun resolve(value: String): ThreadReactionSprite?
    fun quickReactions(): List<ThreadReactionSprite>
}

data class ThreadReactionSprite(
    val name: String,
    val apiValue: String,
    val aliases: Set<String>,
    val rect: IntRect,
    val labels: Map<String, String>
)
```

Use this boundary:

- UI input: `KuiklyThreadReactionItem.emoji` or quick menu selected sprite.
- Lookup: `catalog.resolve(reaction.emoji)`.
- Render: if lookup succeeds, draw the atlas crop; otherwise use existing fallback.
- Toggle: resolved catalog reactions use the alias normalization rule below; unknown reactions keep current raw `reaction.emoji` behavior.

Alias normalization rule:

- `ThreadReactionSprite.name` is the presentation grouping key.
- Incoming message reactions that resolve to the same `name` must be merged into one visible chip.
- The visible chip count is the sum of alias-equivalent reaction counts.
- The visible chip `selected` state is true if any alias-equivalent raw reaction is selected by the current user.
- Quick reaction selected-state detection must compare by `name`, not raw emoji.
- Quick reaction toggle payload should use `apiValue`.
- If a visible alias group contains selected raw aliases and the user taps the quick reaction to remove their reaction, remove every selected raw alias in that group or call the canonical remove flow repeatedly for each selected raw value the local model can prove. If the API only supports one removal and selected alias identity is ambiguous, prefer removing the canonical `apiValue` and then refresh the message rather than leaving duplicate selected chips.
- Pending/rollback keys must be based on `(messageId, sprite.name)` for resolved catalog reactions, not `(messageId, rawEmoji)`. Unknown reactions keep the existing `(messageId, rawEmoji)` key.

This rule prevents a message with both `☑️` and `✅` from rendering two identical Done chips after atlas migration. It also prevents the quick row from adding canonical `✅` while an alias-equivalent selected `☑️` is already present.

## Rendering Contract

Introduce a shared composable:

```kotlin
@Composable
fun ThreadReactionSpriteIcon(
    value: String,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    fallback: @Composable (String) -> Unit
)
```

Expected behavior:

1. Load/remember the generated catalog once per process or app shell. If runtime JSON loading is chosen, read and validate the manifest through the explicit common bridge from the Catalog Loading Boundary section.
2. Resolve `value` through alias map.
3. Draw the atlas region into the given bounds with `ContentScale.Fit`.
4. Use transparent background; chip/menu background remains owned by existing components.
5. On unavailable manifest/image/crop support, call `fallback(value)`.

The drawing primitive can be one of:

- preferred: a Kuikly/common image primitive that supports source-rect drawing from one decoded bitmap;
- acceptable first implementation: a thin platform-backed `SlockSpriteImage` expect/actual or host module with source rect support;
- temporary fallback only if source-rect support is blocked: a build-time tool that slices the atlas into per-icon generated PNGs while keeping the JSON manifest as the source of truth. This fallback must be documented as an implementation compromise, not the product contract.

Do not render the atlas as a huge image clipped by negative offsets unless Kuikly measurement proves it does not inflate layout, overdraw, or pointer hit regions.

## Localization And Accessibility

The manifest labels are product metadata, not user-visible chip text in the current UI. They should be used for:

- `contentDescription` / accessibility labels;
- future reaction picker labels;
- diagnostics and tests.

Locale lookup:

1. exact app locale, e.g. `zh-Hans-CN`;
2. language/script fallback, e.g. `zh-Hans`;
3. language fallback, e.g. `zh`;
4. `en`;
5. `name`.

Suggested accessible labels:

- Quick reaction menu button: `<label> reaction`.
- Existing chip with count: `<label>, <count> reaction(s)`.
- Selected chip: `You reacted with <label>, <count> reaction(s)`.

KMM string resources can still own generic grammar such as "reaction" or "You reacted with"; the manifest owns the reaction names themselves.

## Migration Steps

1. Add the atlas PNG and JSON manifest under `commonMain/assets/reactions/`.
2. Add Android and OHOS packaging for `assets://reactions/*`; OHOS must place files under `entry/src/main/resources/resfile/reactions/`.
3. Add a manifest validator and generated commonMain Kotlin catalog, or add the explicit runtime `readAssetText` bridge before using a common parser on device.
4. Add a `ThreadReactionCatalogProvider` that exposes:
   - `resolve(value)`;
   - `quickReactions()`;
   - `label(value, locale)`.
5. Add alias-equivalent merge logic for message reaction chips and quick-row selected detection.
6. Add `ThreadReactionSpriteIcon` and platform source-rect drawing support.
7. Replace `ThreadQuickReactionEmojis` with `catalog.quickReactions().map { it.apiValue }` or a typed quick reaction model.
8. Replace `ThreadQuickReactionGlyph` internals:
   - supported sprite -> atlas crop;
   - unsupported/missing asset -> current fallback.
9. Keep existing chip layout, counts, selected state, and click handling.
10. Remove hand-drawn Canvas glyph branches only after screenshots confirm atlas parity.

## Tests

Unit tests:

- Manifest parser rejects duplicate `name`, duplicate alias, missing `en`, out-of-bounds rect, and unsupported `schemaVersion`.
- Current quick reactions resolve to the expected `name` and `apiValue`.
- Existing aliases (`❤`, `☑️`, `✔️`, `✓`) resolve to the intended sprite.
- Alias-equivalent incoming reactions merge into one visible chip by `ThreadReactionSprite.name`.
- Quick reaction selected detection treats alias-equivalent selected reactions as already selected.
- Pending and rollback keys use `(messageId, sprite.name)` for resolved catalog reactions and do not duplicate alias/canonical toggles.
- Locale lookup falls back exact -> script -> language -> `en` -> `name`.
- Quick reaction order is stable.
- Unknown reaction values fall back without crashing.

Rendering / smoke tests:

- Thread long-press menu renders atlas icons for all quick reactions.
- Existing reaction chips render atlas icons plus counts without changing chip dimensions.
- Selected chip background and count text remain unchanged.
- Missing/corrupt manifest falls back to text/legacy glyphs and reaction toggles still call the existing API.
- Missing OHOS `resfile/reactions/*` assets fall back and emit a diagnostic that identifies the missing asset path without crashing.
- A message containing both alias and canonical values, e.g. `☑️` plus `✅`, renders one Done chip with merged count/selected state.
- Android and OHOS shared compile pass after introducing the resource path and source-rect primitive.
- OHOS packaging check or HAP smoke proves both atlas PNG and catalog are available from `resfile/reactions/`; Kotlin compile alone is not enough.

Suggested commands for implementation PR:

```bash
cd compose
./gradlew :shared:testDebugUnitTest --tests '*Reaction*' --console=plain
./gradlew :shared:compileDebugKotlinAndroid :androidApp:compileDebugKotlin --console=plain
./gradlew -c settings.ohos.gradle.kts :shared:compileKotlinOhosArm64 --console=plain
```

## Acceptance Criteria

- A checked-in spec defines the atlas and manifest contract.
- Future implementation has one JSON manifest with stable names, crop rectangles, aliases, API values, and labels.
- Runtime catalog access is explicit: either generated commonMain Kotlin catalog from JSON source of truth, or a common `readAssetText` bridge implemented on Android and OHOS.
- Android and OHOS resolve the same `assets://reactions/...` URI convention, with OHOS packaging under `resfile/reactions/`.
- UI reaction add/remove behavior remains compatible with existing server emoji payloads.
- Alias-equivalent reactions use one visible chip and quick-row selected/toggle logic is keyed by catalog `name`.
- Quick reaction menu and reaction chips use the same catalog.
- Unsupported reactions still render and remain clickable.
- No per-icon coordinates are hard-coded in `ThreadPage.kt`.

## Open Questions

- Should the backend eventually accept stable reaction `name` instead of emoji `apiValue`? Initial answer: no, keep API stable.
- Should `labels` live entirely in JSON or reference KMM string resource keys? Initial answer: JSON owns reaction names because the catalog may grow with art assets; generic sentence grammar remains in KMM strings.
- Should the catalog include skin-tone variants? Initial answer: not for the first atlas; add explicit entries only if product supports them.
- Should atlas version be server-driven? Initial answer: not yet; static app asset keeps offline and cross-platform behavior deterministic.
