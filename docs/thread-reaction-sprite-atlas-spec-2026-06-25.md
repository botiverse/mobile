# Thread Reaction Sprite Atlas Spec

Date: 2026-06-25
Owner: Android/App integration + KMP Thread
Status: Implemented (2026-07-05, task #204 closed)
Task: #391 → #204

> **Resolved (2026-07-05, task #204):** device-side atlas rendering landed and
> was device-confirmed by artin — the asset-URL fix (PR #28) unblocked it, and
> PR #32 (29b17b5d) deleted the legacy hand-drawn Canvas glyphs entirely:
> known reactions draw from the atlas, everything else falls back to plain
> text emoji, matching React's ReactionGlyph. The "stable source-rect
> primitive re-land" framing below is historical.

> **Update (2026-07-04, task #335):** the source contract below is superseded.
> The web repo now defines reactions canonically in
> `packages/web/assets/reactions/reactions.json` + `source/*.svg`
> (botiverse/slock), and `scripts/generate_thread_reaction_atlas.mjs` consumes
> those directly (set `SLOCK_SOURCE_REPO` to a slock checkout). The local
> `scripts/assets/thread-reactions/` copies were removed; ids, labels, and
> aliases (e.g. `❤` → `❤️`) now come from the canonical JSON, and the generator
> writes both the atlas PNG and the manifest.

## Problem

Thread reactions currently render from the raw server reaction string. Known quick reactions are either text emoji or hand-drawn Canvas glyphs in `ThreadPage.kt`; unknown reactions fall back to text. This makes visual parity hard to maintain across Android/OHOS/iOS/H5 because emoji fonts differ by platform, while hand-drawn glyphs are embedded in code and do not carry product metadata such as localized names.

We want reactions to render from a sprite atlas: one image contains all supported reaction icons, and a JSON manifest describes each icon placeholder's crop rectangle, stable icon name, exact reaction value, and localized labels.

## Source Contract

React and server both treat reaction identity as an exact reaction value, currently named `emoji`.

React source facts:

- `third_party/slock-source/packages/web/src/components/message/reactionConstants.ts` defines quick reactions as raw values: `["👍", "❤️", "🎉", "👀", "🔥", "😂", "✅"]`.
- `MessageItem.tsx` optimistic update finds rows by exact value: `reaction.emoji === emoji`.
- `MessageItem.tsx` selected-state checks exact value: `reaction.emoji === emoji && reaction.reactorIds.includes(viewerUserId)`.
- `MessageItem.tsx` pending state is `pendingReactionEmojisRef: Set<string>` keyed by the exact value.
- `MessageItem.tsx` toggles with `data: { emoji }` and `method: alreadyReacted ? "delete" : "post"`.
- Existing chips render with `key={reaction.emoji}`, `ReactionGlyph emoji={reaction.emoji}`, and click `handleToggleReaction(reaction.emoji)`.

Server source facts:

- `packages/server/src/db/schema.ts` defines `messageReactions` with primary key `(messageId, reactorType, reactorId, emoji)`.
- `packages/server/drizzle/0061_big_thunderbolt_ross.sql` creates the same primary key.
- `packages/server/src/routes/internal.ts` and `internalAgentApi.ts` parse `req.body.emoji` as a trimmed string, then insert/delete by exact `messageReactions.emoji`.
- DELETE does not normalize or expand values. A request to delete `✅` does not delete `☑️`.

Therefore the Android/KMP sprite atlas must not invent value grouping, canonicalization, or equivalent-reaction semantics. A supported reaction placeholder is one exact value. If product wants `☑️`, `✔️`, or `✓`, each is a separate manifest entry and separate reaction identity unless the backend contract changes.

## Goals

- Replace the presentation of supported reaction icons with a shared sprite atlas lookup.
- Keep server/API/storage identity stable: reaction add/remove payloads still use the exact current reaction value.
- Define a manifest schema that includes:
  - atlas image metadata;
  - per-icon `x`, `y`, `w`, `h`;
  - stable icon `name`;
  - exact reaction `value`;
  - localized labels for supported languages.
- Use the same reaction catalog for quick reaction menu buttons and existing reaction chips.
- Provide deterministic fallback for unsupported or malformed reactions.
- Keep the first implementation local/static; no remote catalog fetch in this task.

## Non-Goals

- Do not change the backend reaction API route or payload shape.
- Do not redesign reaction chip layout, counts, selected state, or task/comment reaction semantics.
- Do not add animated reactions.
- Do not redraw or approximate reaction artwork from screenshots. Source art must come from the existing checked-in path data or React's real production SVGs.
- Do not merge, normalize, alias, or canonicalize reaction values.
- Do not move message reaction count/selection into the manifest. Counts and selection remain message state.

## Current KMP State

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

### Source Artwork

The atlas PNG is generated output. The artwork source of truth is a set of
checked-in SVG files, one file per reaction icon. These SVGs must be extracted
from one of the existing product sources:

- current KMP/Android code paths that are hard-coded in reaction Canvas/vector
  drawing; or
- React's real production SVG assets for the same reaction placeholder.

Do not create a new hand-drawn combined atlas SVG as the source. The generator
may compose an intermediate SVG internally, but reviewers must be able to trace
each atlas cell back to an individual source SVG file that matches the existing
product path/SVG.

Recommended source layout:

```text
scripts/assets/thread-reactions/sources/thumbs_up.svg
scripts/assets/thread-reactions/sources/heart.svg
scripts/assets/thread-reactions/sources/party.svg
scripts/assets/thread-reactions/sources/eyes.svg
scripts/assets/thread-reactions/sources/fire.svg
scripts/assets/thread-reactions/sources/laugh.svg
scripts/assets/thread-reactions/sources/done.svg
scripts/generate_thread_reaction_atlas.mjs
```

Each source SVG must:

- use a transparent background;
- keep product path geometry intact except for deterministic viewBox/size
  normalization needed for atlas packing;
- include no screenshot-derived raster traces;
- be regenerated into the atlas by script, not manually pasted into the PNG.

The generation script should fail if a quick reaction in the manifest has no
matching source SVG, if a source SVG is not parseable, or if the generated PNG
and manifest differ from checked-in output after rerun.

Use KMM Resource as the source-of-truth asset pipeline. The project already applies
`com.tencent.kuiklybase.resource.generator` in `compose/shared/build.gradle.kts`,
with input rooted at `compose/shared/src/commonMain/composeResources`; the reaction
atlas must extend that existing pipeline instead of adding a separate
`commonMain/assets` + per-platform copy path.

Add generated atlas outputs under shared MR assets:

```text
compose/shared/src/commonMain/composeResources/files/reactions/reaction_atlas_v1.png
compose/shared/src/commonMain/composeResources/files/reactions/reaction_atlas_v1.json
```

Generated common code should reference the generated resource handle, for example:

```kotlin
MR.assets.reactions.reaction_atlas_v1
```

The exact generated accessor shape must be verified against the generator output;
the contract is that common code depends on the generated resource, not on a
literal platform path.

Do not require checked-in duplicate files under Android or OHOS app resource
folders. Platform-specific lookup belongs in one small adapter, for example
`ThreadReactionAtlasResource`, that converts the generated KMM resource to the
image/asset form required by the renderer.

Android packaging requirement:

- The KMM resource generator output must package the PNG and JSON so Android
  can read both through the generated resource handle or the approved adapter.
- If the source-rect renderer ultimately needs an `assets://...` or drawable URI,
  only the adapter may construct that URI; Thread UI/catalog code must not
  hard-code `assets://reactions/...`.

OHOS packaging requirement:

- The KMM resource generator must package the same files into the generated
  OHOS resource output, expected under the generator's `rawfile/assets/...`
  resource layout rather than a checked-in `entry/src/main/resources/resfile`
  duplicate.
- If Kuikly `ImageUri` only accepts OHOS-specific paths, hide the
  `AssetResource -> ImageUri` mapping behind the same adapter used by Android.
- `compileKotlinOhosArm64` alone is not sufficient proof. Implementation
  acceptance must include either an HAP/runtime smoke or a deterministic
  generated-resource/package check for both atlas PNG and catalog JSON on OHOS.

Required spike before implementation lands:

- Add one tiny PNG and one JSON fixture under `composeResources/files/reactions/`.
- Confirm the generated `MR` exposes both resources to common code.
- Confirm Android can read both generated resources at runtime/package level.
- Confirm OHOS can read both generated resources at runtime/HAP package level.
- Document the exact generated accessor and adapter API in the implementation PR.

## Manifest Schema

Use one JSON file per atlas version:

```json
{
  "schemaVersion": 1,
  "atlas": {
    "image": "reaction_atlas_v1.png",
    "width": 512,
    "height": 512,
    "pixelRatio": 3,
    "defaultDisplaySizeDp": 18
  },
  "reactions": [
    {
      "name": "thumbs_up",
      "value": "👍",
      "rect": { "x": 0, "y": 0, "w": 72, "h": 72 },
      "labels": {
        "en": "Thumbs up",
        "zh-Hans": "点赞",
        "zh-Hant": "讚"
      }
    },
    {
      "name": "heart",
      "value": "❤️",
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
| `reactions[].name` | yes | Stable lowercase snake_case icon name. Unique. Used only for catalog/art/debug identity. |
| `reactions[].value` | yes | Exact reaction value sent to the existing API, stored in message state, and used as chip/toggle identity. Unique. |
| `reactions[].rect` | yes | Pixel crop rectangle in the atlas coordinate space. Must be positive and inside atlas bounds. |
| `reactions[].labels` | yes | BCP-47-like locale map for accessible names and future picker copy. Must include `en`. |

Validation rules:

- `name` must match `^[a-z0-9_]+$`.
- `value` must be non-blank, contain no whitespace, and be at most the server's current reaction length limit.
- Each `value` maps to exactly one manifest entry.
- Rectangles must be within atlas bounds.
- Rectangles should not overlap unless explicitly allowed by a future `sharedRectGroup` field.
- Missing locale labels fall back by exact locale, language prefix, `en`, then `name`.
- Invalid manifest or missing atlas must disable sprite rendering and fall back to current text/Canvas behavior without breaking reaction toggles.

## Initial Catalog

The first manifest should cover every current quick reaction. Current Canvas glyph cases that are not quick reactions should not be silently treated as equivalent values; add them only if product wants those exact placeholders supported.

| Name | Exact value | English label | zh-Hans label |
| --- | --- | --- | --- |
| `thumbs_up` | `👍` | Thumbs up | 点赞 |
| `heart` | `❤️` | Heart | 爱心 |
| `party` | `🎉` | Celebrate | 庆祝 |
| `eyes` | `👀` | Eyes | 关注 |
| `fire` | `🔥` | Fire | 火 |
| `laugh` | `😂` | Laugh | 大笑 |
| `done` | `✅` | Done | 完成 |

The quick reaction row should be derived from catalog order, not a hard-coded emoji list. If product wants different quick reactions later, update manifest order or add an explicit `quickOrder` array of exact `value` strings.

## Catalog Loading Boundary

The recommended first implementation is **KMM Resource + build-time generated
Kotlin catalog**:

1. Keep `composeResources/files/reactions/reaction_atlas_v1.json` as the manifest source of truth.
2. Keep `composeResources/files/reactions/reaction_atlas_v1.png` as the image source of truth.
3. Add a Gradle or checked-in generation step that validates the JSON and emits a commonMain Kotlin catalog, for example `GeneratedThreadReactionCatalog.kt`.
4. Common UI reads the generated Kotlin metadata synchronously and receives the generated image resource through a `ThreadReactionAtlasResource` wrapper.
5. Unit tests parse the JSON source directly to verify it still matches the generated Kotlin catalog.

This avoids adding a new runtime asset-text bridge just for reactions. If
implementation instead chooses runtime JSON loading, it must first add a common
KMM-resource text loading boundary and actual Android/OHOS implementations:

- Android must read the generated `MR.assets.reactions.reaction_atlas_v1_json`
  resource, or the exact generated equivalent.
- OHOS must read the same generated resource from the OHOS packaged output.
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
    val value: String,
    val rect: IntRect,
    val labels: Map<String, String>
)
```

Use this boundary:

- UI input: `KuiklyThreadReactionItem.emoji` or quick menu selected sprite.
- Lookup: `catalog.resolve(reaction.emoji)`.
- Render: if lookup succeeds, draw the atlas crop; otherwise use existing fallback.
- Toggle: always send and track the exact reaction value:
  - quick reaction buttons call `setMessageReaction(messageId, sprite.value, selected)`;
  - existing chips call `setMessageReaction(messageId, reaction.emoji, selected)`;
  - pending keys stay `(messageId, exactValue)` or the existing `$messageId:$emoji` equivalent;
  - rollback reconciles by exact value.

No presentation grouping is allowed. If a message contains both `☑️` and `✅`, Android must show two chips if both have `count > 0`, exactly as React would for two distinct `reaction.emoji` rows. The atlas may draw them with different art or fall one back to text, but it must not merge their counts or selected state.

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
2. Resolve `value` through exact `value` map.
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

KMM string resources can still own generic grammar such as "reaction" or "You reacted with"; the manifest owns the reaction labels themselves.

## Migration Steps

1. Extract each quick reaction's real source SVG from existing hard-coded paths or React production SVG assets. Check them in as per-icon source files under `scripts/assets/thread-reactions/sources/`.
2. Add or update `scripts/generate_thread_reaction_atlas.mjs` so it composes those source SVGs into a transparent PNG atlas and emits/validates the JSON manifest.
3. Add the generated atlas PNG and JSON manifest under `commonMain/composeResources/files/reactions/`.
4. Run the KMM Resource spike and document the generated accessors for PNG and JSON on Android/OHOS.
5. Add a `ThreadReactionAtlasResource` adapter that maps the generated KMM resource to the renderer's image/asset input on Android/OHOS.
6. Add a manifest validator and generated commonMain Kotlin catalog, or add the explicit runtime KMM-resource text reader before using a common parser on device.
7. Add a `ThreadReactionCatalogProvider` that exposes:
   - `resolve(value)`;
   - `quickReactions()`;
   - `label(value, locale)`.
8. Add `ThreadReactionSpriteIcon` and platform source-rect drawing support.
9. Replace `ThreadQuickReactionEmojis` with `catalog.quickReactions().map { it.value }` or a typed quick reaction model.
10. Replace `ThreadQuickReactionGlyph` internals:
   - supported sprite -> atlas crop;
   - unsupported/missing asset -> current fallback.
11. Keep existing exact-value chip layout, counts, selected state, pending state, rollback, and click handling.
12. Remove hand-drawn Canvas glyph branches only after screenshots confirm atlas parity.

## Tests

Unit tests:

- Generator/source tests prove every quick reaction manifest entry has a per-icon source SVG and that rerunning the generator produces no tracked diff.
- Manifest parser rejects duplicate `name`, duplicate `value`, missing `en`, out-of-bounds rect, blank value, whitespace value, and unsupported `schemaVersion`.
- Current quick reactions resolve to the expected `name` and exact `value`.
- Quick reaction order is stable.
- Unknown reaction values fall back without crashing.
- `☑️` and `✅` remain separate exact values if both appear in message reactions.
- Pending and rollback keys use exact reaction value and do not group by catalog `name`.
- Locale lookup falls back exact -> script -> language -> `en` -> `name`.

Rendering / smoke tests:

- Thread long-press menu renders atlas icons for all quick reactions.
- Existing reaction chips render atlas icons plus counts without changing chip dimensions.
- Selected chip background and count text remain unchanged.
- Missing/corrupt manifest falls back to text/legacy glyphs and reaction toggles still call the existing API with the exact value.
- Missing generated KMM resource / OHOS packaged resource falls back and emits a diagnostic that identifies the missing resource without crashing.
- A message containing `☑️` plus `✅` renders two separate chips if both have count, or one sprite chip plus one text fallback if only `✅` is in the atlas.
- Android and OHOS shared compile pass after introducing the resource path and source-rect primitive.
- Android and OHOS packaging checks or runtime smokes prove both atlas PNG and catalog JSON are available through the generated KMM resources; Kotlin compile alone is not enough.

Suggested commands for implementation PR:

```bash
cd compose
./gradlew :shared:testDebugUnitTest --tests '*Reaction*' --console=plain
./gradlew :shared:compileDebugKotlinAndroid :androidApp:compileDebugKotlin --console=plain
./gradlew -c settings.ohos.gradle.kts :shared:compileKotlinOhosArm64 --console=plain
```

## Acceptance Criteria

- A checked-in spec defines the atlas and manifest contract.
- Future implementation has traceable per-icon source SVG files extracted from current hard-coded paths or React production SVGs; it must not use a hand-redrawn screenshot-derived atlas.
- Future implementation has one generated transparent PNG atlas and one JSON manifest with stable icon names, exact reaction values, crop rectangles, and labels.
- The atlas generator can be rerun locally with no tracked diff after outputs are committed.
- Runtime catalog access is explicit: generated commonMain Kotlin catalog from JSON source of truth, or a common KMM-resource text bridge implemented on Android and OHOS.
- Android and OHOS consume the same checked-in KMM Resource source files under `commonMain/composeResources/files/reactions/`; no checked-in double-copy under app-specific resource folders is required.
- Any platform URI/path conversion is centralized in `ThreadReactionAtlasResource` or an equivalent adapter, not scattered through Thread UI code.
- UI reaction add/remove behavior remains compatible with existing server emoji payloads and exact-value React behavior.
- Quick reaction menu and reaction chips use the same exact-value catalog.
- Unsupported reactions still render and remain clickable.
- No per-icon coordinates are hard-coded in `ThreadPage.kt`.

## Open Questions

- Should the backend eventually accept stable reaction `name` instead of exact value? Initial answer: no, keep API stable.
- Should `labels` live entirely in JSON or reference KMM string resource keys? Initial answer: JSON owns reaction labels because the catalog may grow with art assets; generic sentence grammar remains in KMM strings.
- Should the catalog include skin-tone variants? Initial answer: not for the first atlas; add explicit entries only if product supports them as exact values.
- Should atlas version be server-driven? Initial answer: not yet; static app asset keeps offline and cross-platform behavior deterministic.
