// patrol/lib.mjs — shared core for all "patrol" lenses (visual drift, i18n, code smell).
//
// A lens is just a DETECTOR that produces `findings`; this module owns the common
// machinery: dedupe against already-open issues, ensure labels, cap per run
// (worst-first, overflow logged not dropped), file GitHub issues with an embedded
// dedup marker, and emit a JSON report. Dry-run is the default at the callsite.
//
// A finding:
//   {
//     key: "stable-dedup-key",        // required; identity across runs (e.g. caseId, source path)
//     fingerprint: "p1:...",          // optional evidence fingerprint; never changes issue identity
//     rank: <number>,                 // higher = more severe; used for worst-first + cap
//     title: "...",                   // issue title
//     body: "...",                    // issue body (marker is appended by this module)
//     labels: ["...", ...],           // issue labels
//   }

import { execFileSync } from "node:child_process";
import { hasOpenLensIdentity, stableLensKey } from "./identity.mjs";

const MARKER_RE = /<!--\s*patrol:lens=([^;]+);key=([^;]+);fp=(\S+)\s*-->/;

export function marker(lens, key, fingerprint) {
  return `<!-- patrol:lens=${lens};key=${key};fp=${fingerprint || "none"} -->`;
}

// Fetch dedup markers from already-open issues carrying `label` (defaults to the lens label).
export function fetchOpenMarkers(repo, label) {
  try {
    const out = execFileSync("gh", ["issue", "list", "--repo", repo, "--label", label,
      "--state", "open", "--limit", "800", "--json", "number,body"],
      { encoding: "utf8", maxBuffer: 64 * 1024 * 1024 });
    return JSON.parse(out).map((i) => {
      const m = MARKER_RE.exec(i.body || "");
      return m ? { number: i.number, lens: m[1], key: stableLensKey(m[2]), fp: m[3] } : null;
    }).filter(Boolean);
  } catch (e) {
    console.error(`[patrol] could not list existing issues (${String(e.message).slice(0, 120)}); dedup skipped`);
    return [];
  }
}

function alreadyFiled(finding, lens, markers) {
  return hasOpenLensIdentity(markers, lens, finding.key);
}

const LABEL_COLORS = {
  "visual-drift": "d73a4a", "i18n-gap": "5319e7", "code-smell": "fbca04",
  "auto-triage": "0e8a16",
  "severity:critical": "b60205", "severity:high": "e99695", "severity:medium": "fbca04", "severity:low": "c2e0c6",
};

export function ensureLabels(repo, labels) {
  for (const name of [...new Set(labels)]) {
    try {
      execFileSync("gh", ["label", "create", name, "--repo", repo, "--force",
        "--color", LABEL_COLORS[name] || "ededed"], { stdio: "ignore" });
    } catch { /* exists or no perms — issue create will surface a real failure */ }
  }
}

// Run the shared filing pipeline. `opts`: { repo, lens, label, findings, cap, live }.
export function runPatrol(opts) {
  const { repo, lens, label, findings, cap = 10, live = false } = opts;
  const markers = fetchOpenMarkers(repo, label);

  const fresh = [];
  const deduped = [];
  for (const f of findings) {
    if (alreadyFiled(f, lens, markers)) { deduped.push(f.key); continue; }
    fresh.push(f);
  }
  fresh.sort((a, b) => (b.rank || 0) - (a.rank || 0));
  const toFile = fresh.slice(0, cap);
  const overflow = fresh.slice(cap);

  if (live && toFile.length) {
    ensureLabels(repo, toFile.flatMap((f) => f.labels || []));
  }

  const filed = toFile.map((f) => {
    const body = `${f.body}\n\n${marker(lens, f.key, f.fingerprint)}`;
    if (!live) return { dryRun: true, key: f.key, title: f.title, labels: f.labels };
    const out = execFileSync("gh", ["issue", "create", "--repo", repo, "--title", f.title,
      "--body", body, ...(f.labels || []).flatMap((l) => ["--label", l])], { encoding: "utf8" });
    return { dryRun: false, key: f.key, url: out.trim(), title: f.title };
  });

  const report = {
    lens, mode: live ? "LIVE" : "DRY_RUN",
    totals: { findings: findings.length, fresh: fresh.length, filed: filed.length, deduped: deduped.length, overflow: overflow.length },
    filed,
    overflow_capped: overflow.map((f) => ({ key: f.key, rank: f.rank })),
    deduped_sample: deduped.slice(0, 12),
    note: live ? "Issues created." : "DRY RUN — nothing created. Re-run with --live to file.",
  };
  if (overflow.length) report.overflow_note = `${overflow.length} finding(s) exceeded the per-run cap of ${cap} and were NOT filed (logged above).`;
  return report;
}
