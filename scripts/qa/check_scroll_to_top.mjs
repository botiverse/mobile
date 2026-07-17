#!/usr/bin/env node
// qa/check_scroll_to_top — logcat coverage gate for the double-tap
// scroll-to-top fix (Hands fd9c0487 / mobile PR #1125, generation fence).
//
// A visual M3 agent can "pass" a scroll case without ever exercising the fix:
// it opens a thread that already shows all content, double-taps, sees nothing
// move, and calls it fine (runs 29549216469 / 29549886013 / 29550228483 all
// false-positived this way). This checker overrides that verdict from the
// production markers, the same way the #304 contract checker overrides the
// agent verdict from logcat.
//
// The production emission (release-1.5.0 ThreadPage.kt, only logged AFTER the
// request fence consumes the request):
//   thread_header_scroll_to_top request=<n> firstVisible=<idx> totalItems=<c>
//
// Gate (林舟/Cindy's acceptance conditions), all from logcat:
//   2. firstVisible > 0 on at least one consume  — the list was actually
//      scrolled down before the scroll-to-top (otherwise 回顶 is a no-op and
//      the case never reaches the fix's behaviour).
//   3. the marker appears at least once           — the command seam fired.
//   4. exactly once per distinct request=<n>      — the fence held; a replay
//      (same request consumed 2+ times) IS the regression this fix prevents.
//
// Condition 1 (surface=thread) is implied — the marker only exists on the
// ReplyThread surface. Condition 5 (after 回顶, drag down and don't snap back
// within 2s) is a behavioural assertion that needs the agent's post-scroll
// down-drag; the exactly-once fence (cond 4) is the *cause* whose absence
// produced the snap-back, so this gate proving no-replay + the agent's visual
// down-drag together cover it. This checker reports 5 as agent-verified.
//
// Exit: 0 = gate PASS (fix genuinely exercised, fence held)
//       1 = gate FAIL (replay detected — same request consumed >1x)
//       3 = coverage INVALID / no-verdict (marker absent, or never scrolled
//           down first) — NOT a product FAIL

import fs from "node:fs";

const arg = (f, d) => { const i = process.argv.indexOf(f); return i >= 0 ? process.argv[i + 1] : d; };
const logPath = arg("--log", "");
if (!logPath || !fs.existsSync(logPath)) {
  console.error("usage: check_scroll_to_top.mjs --log <logcat.txt>");
  process.exit(2);
}

const lines = fs.readFileSync(logPath, "utf8").split("\n");
const RE = /thread_header_scroll_to_top request=(\d+) firstVisible=(\d+) totalItems=(\d+)/;

// Track the source line number of every consume — 林舟 wants it to backfill
// Hands fd9c0487 and to tell "legit request 1 & 2 each once" apart from a
// same-request replay at a glance.
const consumes = [];
lines.forEach((line, i) => {
  const m = RE.exec(line);
  if (m) consumes.push({ request: Number(m[1]), firstVisible: Number(m[2]), totalItems: Number(m[3]), line: i + 1 });
});

// group consumes by request id
const byRequest = new Map();
for (const c of consumes) {
  if (!byRequest.has(c.request)) byRequest.set(c.request, []);
  byRequest.get(c.request).push(c);
}
const replayed = [...byRequest.entries()].filter(([, list]) => list.length > 1);
const scrolledDown = consumes.filter((c) => c.firstVisible > 0);

const report = {
  gate: "scroll_to_top_coverage",
  markerConsumes: consumes.length,
  distinctRequests: byRequest.size,
  requests: Object.fromEntries([...byRequest.entries()].map(([r, l]) => [r, {
    consumes: l.length,
    firstVisible: l.map((c) => c.firstVisible),
    totalItems: l[0]?.totalItems,
    logLines: l.map((c) => c.line),
  }])),
  condition2_scrolledDownBeforeTop: scrolledDown.length > 0,
  condition3_markerPresent: consumes.length >= 1,
  condition4_exactlyOncePerRequest: replayed.length === 0,
  condition5_noSnapBackAfterDrag: "REQUIRES agent evidence — checker cannot derive the post-drag gesture result from logcat; final PASS = this gate rc=0 AND agent verdict.json shows a deliberate down-drag after scroll-to-top with firstVisible growing and not returning to 0 within 2s",
  gateIsNecessaryNotSufficient: true,
};

let verdict, code;
if (consumes.length === 0) {
  verdict = "invalid/no-verdict";
  report.reason = "no thread_header_scroll_to_top marker — the scroll-to-top command seam was never exercised (thread not scrollable / never entered a real ReplyThread). Not a product FAIL.";
  code = 3;
} else if (scrolledDown.length === 0) {
  verdict = "invalid/no-verdict";
  report.reason = "marker present but firstVisible was 0 on every consume — the list was never scrolled down before scroll-to-top, so the case did not reach the fix's behaviour.";
  code = 3;
} else if (replayed.length > 0) {
  verdict = "FAIL";
  report.reason = `replay regression: request(s) ${replayed.map(([r]) => r).join(",")} consumed more than once — the generation fence did not hold (this is exactly the Hands fd9c0487 bug).`;
  code = 1;
} else {
  verdict = "PASS (gate) — still requires agent down-drag evidence for final accept";
  report.reason = "each distinct scroll-to-top request consumed exactly once with firstVisible>0 — command seam fired, fence held, no replay. This is NECESSARY, not sufficient: the reviewer must also confirm the agent evidence shows a deliberate down-drag after 回顶 that does not snap back within 2s (condition 5).";
  code = 0;
}
report.verdict = verdict;
console.log(JSON.stringify(report, null, 2));
process.exit(code);
