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

If a target cannot crop from `commonMain/assets` directly, the platform bridge may copy/package the same files into the platform resource system. The source of truth remains the shared asset path and JSON schema above.

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
- Toggle: always send `sprite.apiValue` for quick reactions and the original `reaction.emoji` for existing message reaction chips unless the resolved sprite explicitly normalizes aliases to `apiValue`.

Alias normalization recommendation:

- For quick reactions, use `apiValue` directly.
- For incoming message reactions, display the resolved sprite for any alias, but preserve the original stored value when toggling an already-present chip. This avoids changing semantics for historical data until the backend confirms normalization.

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

1. Load/remember the manifest once per process or app shell.
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
2. Add a common manifest parser/validator with unit tests using the initial catalog.
3. Add a `ThreadReactionCatalogProvider` that exposes:
   - `resolve(value)`;
   - `quickReactions()`;
   - `label(value, locale)`.
4. Add `ThreadReactionSpriteIcon` and platform source-rect drawing support.
5. Replace `ThreadQuickReactionEmojis` with `catalog.quickReactions().map { it.apiValue }` or a typed quick reaction model.
6. Replace `ThreadQuickReactionGlyph` internals:
   - supported sprite -> atlas crop;
   - unsupported/missing asset -> current fallback.
7. Keep existing chip layout, counts, selected state, and click handling.
8. Remove hand-drawn Canvas glyph branches only after screenshots confirm atlas parity.

## Tests

Unit tests:

- Manifest parser rejects duplicate `name`, duplicate alias, missing `en`, out-of-bounds rect, and unsupported `schemaVersion`.
- Current quick reactions resolve to the expected `name` and `apiValue`.
- Existing aliases (`❤`, `☑️`, `✔️`, `✓`) resolve to the intended sprite.
- Locale lookup falls back exact -> script -> language -> `en` -> `name`.
- Quick reaction order is stable.
- Unknown reaction values fall back without crashing.

Rendering / smoke tests:

- Thread long-press menu renders atlas icons for all quick reactions.
- Existing reaction chips render atlas icons plus counts without changing chip dimensions.
- Selected chip background and count text remain unchanged.
- Missing/corrupt manifest falls back to text/legacy glyphs and reaction toggles still call the existing API.
- Android and OHOS shared compile pass after introducing the resource path and source-rect primitive.

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
- UI reaction add/remove behavior remains compatible with existing server emoji payloads.
- Quick reaction menu and reaction chips use the same catalog.
- Unsupported reactions still render and remain clickable.
- No per-icon coordinates are hard-coded in `ThreadPage.kt`.

## Open Questions

- Should the backend eventually accept stable reaction `name` instead of emoji `apiValue`? Initial answer: no, keep API stable.
- Should `labels` live entirely in JSON or reference KMM string resource keys? Initial answer: JSON owns reaction names because the catalog may grow with art assets; generic sentence grammar remains in KMM strings.
- Should the catalog include skin-tone variants? Initial answer: not for the first atlas; add explicit entries only if product supports them.
- Should atlas version be server-driven? Initial answer: not yet; static app asset keeps offline and cross-platform behavior deterministic.
