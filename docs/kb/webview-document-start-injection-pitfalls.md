# WebView `injectedJavaScript` runs at document start: five ways that bites

**Area:** webview / platform
**Owner:** CC-Cata
**Verified at:** 2026-10-18
**Status:** current

> **Scope and lineage.** Written after the investigation settled, and authoritative for the viewport
> mechanism in §5. `numeric-criteria-that-pass-while-the-feature-is-broken.md` was written earlier
> from the same bug and originally disagreed with this section; it has been corrected to match, and
> its lineage note points here.

Injected script reaches the page at **document start** on all three platforms (Android
`SlockSandboxWebViewRenderView`, iOS `WKUserScript(...AtDocumentStart...)`, OHOS
`runJavaScriptOnDocumentStart`). That single fact produced five distinct defects on one feature, each
of which passed CI and needed a device to find. They are listed in the order they were hit, because
each hid the next.

## 1. Position in a pipeline is not the same as effect on the document

Changing the injected script while a page is already loaded does nothing. The script only runs when a
*document* starts. A toggle that only updated app state left the visible page untouched.

Tell-tale: **a page-owned counter keeps counting across the toggle.** A timer in the page is the
cheapest possible proof that no reload happened — far better than a screenshot.

Fix: reload on change. Then keep reading, because reload alone is not enough.

## 2. Verify the value reaches the platform, not just that a handler exists

A prop was plumbed from compose to the native view, and the native `setProp` had a branch for it —
but only in `resetProp`, not the live path. The value was built, delivered, and silently dropped. The
handler's existence was verified; its invocation never was.

Fix: for every prop, walk the whole chain — `remember` key list, update lambda, `viewInit`, native
`setProp` branch, and the apply call. "The consumer exists" is not "the value reaches the consumer".

## 3. Ordering between two delivery channels

The reload command travelled a command channel while the setting travelled the prop channel. Reload
could arrive first and reload the document with the **old** setting — which looks exactly like "the
reload did not happen".

Fix: apply the setting and trigger the reload in the same ordered step, at the point where the
setting is applied.

## 4. Document-start is earlier than the page's own `<meta name="viewport">`

At document start the page's viewport meta has not been parsed. A find-or-create approach creates a
second meta; the page's own one then lands and wins, or two metas coexist (undefined behaviour). Pages
may also insert their meta later from script.

Fix: rewrite **every** existing viewport meta, re-apply on `DOMContentLoaded`, and observe further
insertions with a `MutationObserver`. Never emit `user-scalable=no` / `maximum-scale` — layout is the
feature, zoom is the user's.

Testing note: this is verifiable without a device using a real DOM (jsdom) — construct each ordering
and assert the resulting meta. jsdom has no layout engine, so it can prove the meta rewriting but
**not** the resulting width.

## 5. Declaring a width is not the same as fitting it

Once the page declares `width=1280`, Android treats that as a self-declared width and draws it 1:1.
`useWideViewPort` + `loadWithOverviewMode` alone will **not** fit it. Fitting needs an explicit
`setInitialScale`, computed from the real view width (`viewPx / (1280 * density)` — 720/(1280·1.5) =
37% on a 720px phone at dpr 1.5).

The give-away was rendered text height identical to the adaptive baseline, i.e. no scaling at all.

## Meta-lesson

Every one of these produced the **same user-visible symptom** — "the toggle does nothing" — and every
one had a different cause. CI stayed green throughout, including compile and unit tests, because none
of them are compile errors or logic errors in an isolated function: they are all about **when** code
runs relative to state that already exists. Only a device run, reading a value the page itself
reports, distinguishes them.
