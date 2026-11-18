---
name: raft-i18n-strings
description: Use when adding or changing user-visible text in Raft Mobile (any label, toast, dialog, empty state) — adding the string resource in all eight locales (en base, zh-CN, ja, zh-TW, ko, es, pt-BR, ru), choosing canonical terms, and running the i18n checks instead of a visual capture.
---

# User-visible strings (en / zh-CN / ja / zh-TW / ko / es / pt-BR / ru)

Every user-visible string is a Compose Multiplatform string resource (`Res.string.<key>`,
package `build.raft.app.resources`); no English literals in production UI.

## 1. Add the key in all eight files

```
compose/shared/src/commonMain/composeResources/values/strings.xml    # en
compose/shared/src/commonMain/composeResources/values-zh-rCN/strings.xml
compose/shared/src/commonMain/composeResources/values-ja/strings.xml
compose/shared/src/commonMain/composeResources/values-zh-rTW/strings.xml
compose/shared/src/commonMain/composeResources/values-ko/strings.xml
compose/shared/src/commonMain/composeResources/values-es/strings.xml
compose/shared/src/commonMain/composeResources/values-pt-rBR/strings.xml
compose/shared/src/commonMain/composeResources/values-ru/strings.xml
```
- Every new key goes into all eight catalogs in the same PR, at the same position as in
  `values/` (next to its neighbours). CI enforces it: `ComposeResourcesCatalogParityTest`
  fails when any catalog lacks a key of `values/` or holds one `values/` lacks.
- Translate from the web catalog first: for the same English sentence, copy the web's wording
  for that language verbatim from `$SLOCK_SOURCE_REPO/packages/web/src/i18n/messages/`
  (`zh-tw.ts`, `ko.ts`, `es.ts`, `pt-br.ts`, `ru.ts`); otherwise translate with its terms.
- If you can't translate a language in the PR, say so in the PR description; the i18n 3-day
  sync (Hasaki, #proj-i18n) fills it. The key must still exist: put the English text there
  as an explicit temporary placeholder (the tests accept a value identical to the English).
  Never leave the key out.
- Key: `<area>_<thing>` in snake_case, following neighbours
  (`thread_audio_file_label`, `common_copied`). Reuse `common_*` keys.
- No ASCII leading/trailing spaces (Android trims them, iOS keeps them): use
  `&#160;`. Fix automatically with
  `python3 scripts/ci/check_string_edge_spaces.py --fix`.
- Placeholders: positional only (`%1$s`, `%2$d`), the same set in every locale
  (`ComposeResourcesCatalogParityTest` checks keys and placeholders).
- Plain XML text: write `'` and `"` as is (no Android `\'` escapes); `\n` is a newline.
- A label (button, menu item, tab) never ends with `…`, in any language: it says the action it
  leads to (New section, 自定义). Only progress text (Loading…) and placeholders keep one
  (Raft DS writing.md; `RaftLabelEllipsisTest` checks every catalog).

## 2. Use it

```kotlin
import build.raft.app.resources.*   // Res, the Res.string accessors, stringResource, StringDesc
RaftText(stringResource(Res.string.<key>), style = RaftTheme.typography.callout)
```
Use `build.raft.app.resources.stringResource`, not `org.jetbrains.compose.resources.stringResource`:
only the former follows the in-app language setting. Outside composition build a `StringDesc`
(`Res.string.<key>.desc()`, `.format(args)`) and call `.resolve()` (or `.localized()` in
composition).

## 3. Pick the words

- Shared Web/mobile concepts: copy the web catalog wording from
  `$SLOCK_SOURCE_REPO/packages/web/src/i18n/messages/`.
- Japanese: `docs/i18n-ja-glossary.md` is the source of truth; add new
  concepts there in the same PR.
- Current terms: zh-CN `回应`, `任务状态`, `处理中`; ja `リアクション`,
  `タスクステータス`, `処理中`. Don't expose implementation words (chip,
  spinner, badge) unless the UI shows them.
- User content (messages, names) renders with zh-Hans forms; UI text follows
  the UI language. Don't override locales per call site.

## 4. Check

```bash
python3 scripts/ci/check_string_edge_spaces.py
python3 scripts/i18n_code_literal_scan.py      # review scanner for stray English literals
```
`KmmResourceI18nTest` (commonTest) runs in hosted CI; add an assertion there
when a new key family must exist in every locale.

Pure copy/i18n changes are verified by these checks, not by visual captures
(owner directive). Capture a locale (`capture_locale`, see
`raft-visual-parity`) only when layout changes or the owner asks.
