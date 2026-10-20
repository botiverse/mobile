---
name: raft-i18n-strings
description: Use when adding or changing user-visible text in Raft Mobile (any label, toast, dialog, empty state) — adding the MR string in all three locales (en base, zh-CN, ja), choosing canonical terms, and running the i18n checks instead of a visual capture.
---

# User-visible strings (en / zh-CN / ja)

Every user-visible string is a moko resource; no English literals in
production UI.

## 1. Add the key in all three files

```
compose/shared/src/commonMain/resources/MR/base/strings.xml    # en
compose/shared/src/commonMain/resources/MR/zh-rCN/strings.xml
compose/shared/src/commonMain/resources/MR/ja/strings.xml
```
- Key: `<area>_<thing>` in snake_case, following neighbours
  (`thread_audio_file_label`, `common_copied`). Reuse `common_*` keys.
- No ASCII leading/trailing spaces (Android trims them, iOS keeps them): use
  `&#160;`. Fix automatically with
  `python3 scripts/ci/check_string_edge_spaces.py --fix`.
- Placeholders: same count and order in every locale.

## 2. Use it

```kotlin
import com.tencent.tmm.kmmresource.compose.stringResource
RaftText(stringResource(MR.strings.<key>), style = RaftTheme.typography.callout)
```
Outside composition use the `StringDesc` helpers already used nearby.

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
python3 scripts/ci/check_source_constraints.py
python3 scripts/i18n_code_literal_scan.py      # review scanner for stray English literals
```
`KmmResourceI18nTest` (commonTest) runs in hosted CI; add an assertion there
when a new key family must exist in every locale.

Pure copy/i18n changes are verified by these checks, not by visual captures
(owner directive). Capture a locale (`capture_locale`, see
`raft-visual-parity`) only when layout changes or the owner asks.
