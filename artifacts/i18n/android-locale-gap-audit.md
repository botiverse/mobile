# Android Locale Gap Audit

Task #86 first pass establishes the locale data boundary and Chinese resource baseline.

## Added boundary
- `AppLocale`: supported locales are English and Simplified Chinese.
- `LocalePreferenceStore`: persistent preference boundary for the selected app locale.
- `PersistentLocaleRepository`: exposes `LocaleSnapshot(current, supported)` and `selectLocale`.

## Current resource coverage
- `values/strings.xml`: existing default English resources.
- `values-zh-rCN/strings.xml`: Simplified Chinese translations for the current resource-backed Auth and Settings detail/billing strings.

## Remaining UI/resource gaps
- `MainActivity.kt`: bottom tabs, app shell loading/error copy, create-server modal, Settings route labels.
- `feature/home` / structural home shell strings.
- `feature/inbox`: header/filter/action/item state strings.
- `feature/tasks`: page title, filters, board/list labels, empty/error strings.
- `feature/members`: page title, detail tabs, sections, empty/error strings.
- `feature/thread`: header actions, composer placeholder/actions, modals, tab labels.
- `feature/settings`: Settings home/account/sessions/danger strings still include hardcoded copy outside the previously resource-backed detail/billing screens.
- `ui/components`: shared modal/composer/workspace component strings and preview-only labels.

## Handoff
- Android-App-Integrator should apply the persisted locale at startup before first page render.
- Android-Developer should add the Settings Language entry and migrate page-level hardcoded strings in focused follow-up passes.
