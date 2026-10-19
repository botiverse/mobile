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

## Related shape, not a sixth WebView defect: registering a reference before an async value exists

§1–§5 are five distinct defects of the same fact in the same component. This section is a different
component, recorded here because the mechanism is identical and the tell-tale symptoms match: **a
value that does not exist yet at the moment it is handed over.** Found on a window-level menu host,
not in a WebView.

A menu overlay was moved to a window-level host. The host was registered with a closure carrying the
reaction emoji atlas image. The atlas decodes asynchronously, so at registration time the reference was
`null` — and it stayed `null`, because the host captured the value once rather than observing it. The
emoji row rendered as empty boxes, in every layout, on every re-capture.

Two things had to change: the host decodes the atlas itself instead of receiving a bitmap across a page
boundary, and the emoji row renders from the host's own provider.

### The shape

| §1–§5 (document start) | this case (host registration) |
|---|---|
| viewport meta not parsed yet | atlas not decoded yet |
| capture the document state too early | capture the resource reference too early |
| rewrite on `DOMContentLoaded` + observe insertions | let the consumer own the resource |

Both are "the capture happened at a moment when the thing was not ready", and both look like "the
feature silently does nothing" rather than like an error. Neither produces a compile failure or a test
failure; the artefact is simply empty.

### Rule

When handing a resource across a boundary — page, host, coroutine, another owner — decide whether you
are passing a **value** or a **reference that will be filled later**. If it can be filled later, the
receiver must own the resolution (or observe it). Capturing it once converts "not ready yet" into
"never ready", which is indistinguishable from "the feature was never wired up".
