#!/usr/bin/env node
// Two-hourly consumer for the verified visual-drift backlog.
//
// This job deliberately stops before mutating product source. It claims a small,
// bounded batch of open+verified+unassigned issues, derives React truth, asks M3
// for a minimal Android patch, and submits that patch to three fresh adversarial
// reviewers. The review panel may be heterogeneous (PI_PANEL_MODELS, e.g.
// 2x MiniMax-M3.1-Flash-Preview + 1x qwen3.8-max-preview) so a same-model blind spot cannot
// carry a 2/3 consensus alone. REFUTED issues receive the concrete flaw.
// UNREFUTED issues receive an agent-ready handoff for targeted capture,
// visual_verify, and a draft PR.

import { resolvePiModel } from "../qa/pi_model.mjs";
import crypto from "node:crypto";
import fs from "node:fs";
import path from "node:path";
import { execFileSync } from "node:child_process";
import { fileURLToPath } from "node:url";

const PROCESSING_LABEL = "fix-queue:processing";
const REFUTED_LABEL = "fix-queue:refuted";
const AGENT_READY_LABEL = "fix-queue:agent-ready";
const TERMINAL_LABELS = new Set([REFUTED_LABEL, AGENT_READY_LABEL]);
const SOURCE_EXTENSIONS = new Set([".kt", ".kts", ".ts", ".tsx", ".js", ".jsx", ".css"]);
// Includes prompt input as well as bounded output across truth + patch + 3 reviews.
// Source collection is separately capped below so this remains a conservative
// admission reservation rather than an unbounded best-effort estimate.
const DEFAULT_CASE_TOKEN_RESERVATION = 75_000;

function parseArgs(argv = process.argv.slice(2)) {
  const value = (flag, fallback) => {
    const index = argv.indexOf(flag);
    return index >= 0 ? argv[index + 1] : fallback;
  };
  return {
    repo: value("--repo", "botiverse/mobile"),
    comparison: value("--comparison", "react__android"),
    caseCap: Math.max(1, Math.min(3, Number(value("--case-cap", "3")))),
    tokenCap: Math.max(DEFAULT_CASE_TOKEN_RESERVATION, Number(value("--token-cap", process.env.VISUAL_FIX_QUEUE_TOKEN_CAP || "1000000000000"))),
    voters: Math.max(3, Number(value("--voters", "3"))),
    threshold: Math.max(2, Number(value("--threshold", "2"))),
    mobileRoot: path.resolve(value("--mobile-root", ".")),
    slockRoot: path.resolve(value("--slock-root", "slock")),
    ruiRoot: path.resolve(value("--rui-root", "rui")),
    report: path.resolve(value("--report", "visual-fix-queue-report.json")),
    issue: value("--issue", ""),
    live: argv.includes("--live"),
  };
}

function gh(args, options = {}) {
  return execFileSync("gh", args, {
    encoding: "utf8",
    maxBuffer: 64 * 1024 * 1024,
    ...options,
  });
}

function gitSha(root) {
  return execFileSync("git", ["rev-parse", "HEAD"], { cwd: root, encoding: "utf8" }).trim();
}

function labelsOf(issue) {
  return (issue.labels || []).map((label) => typeof label === "string" ? label : label.name);
}

export function isEligibleIssue(issue) {
  const labels = labelsOf(issue);
  return issue.state === "OPEN"
    && labels.includes("visual-drift")
    && labels.includes("verified")
    && !labels.includes(PROCESSING_LABEL)
    && !labels.some((label) => TERMINAL_LABELS.has(label))
    && (issue.assignees || []).length === 0;
}

export function selectFixQueueIssues(issues, caseCap, tokenCap, reservation = DEFAULT_CASE_TOKEN_RESERVATION) {
  const eligible = issues
    .filter(isEligibleIssue)
    .sort((left, right) => String(left.createdAt).localeCompare(String(right.createdAt)) || left.number - right.number);
  const affordable = Math.floor(tokenCap / reservation);
  return eligible.slice(0, Math.min(caseCap, affordable));
}

function issueCaseId(issue) {
  return (/\[visual-drift\]\s+([^:]+):/.exec(issue.title || "") || [])[1]?.trim() || null;
}

function issueComparison(issue, fallback) {
  return (/\*\*Comparison:\*\*\s+`([^`]+)`/.exec(issue.body || "") || [])[1] || fallback;
}

function listIssues(opts) {
  if (opts.issue) {
    return [JSON.parse(gh(["issue", "view", opts.issue, "--repo", opts.repo, "--json",
      "number,title,body,state,labels,assignees,createdAt,url"]))];
  }
  return JSON.parse(gh(["issue", "list", "--repo", opts.repo, "--label", "visual-drift", "--label", "verified",
    "--state", "open", "--limit", "300", "--json", "number,title,body,state,labels,assignees,createdAt,url"]));
}

function ensureLabels(opts) {
  if (!opts.live) return;
  for (const [name, color, description] of [
    [PROCESSING_LABEL, "FBCA04", "Nightly visual fix queue currently owns this issue"],
    [REFUTED_LABEL, "D93F0B", "M3 patch was refuted; concrete flaw is recorded"],
    [AGENT_READY_LABEL, "0E8A16", "M3 patch is unrefuted and ready for agent CI/evidence/PR handoff"],
  ]) {
    gh(["label", "create", name, "--repo", opts.repo, "--color", color, "--description", description, "--force"]);
  }
}

function claimIssue(issue, opts, provenance) {
  if (!opts.live) return true;
  gh(["issue", "edit", String(issue.number), "--repo", opts.repo, "--add-label", PROCESSING_LABEL]);
  const current = JSON.parse(gh(["issue", "view", String(issue.number), "--repo", opts.repo, "--json", "state,labels,assignees"]));
  if (!isEligibleIssue({ ...current, labels: labelsOf(current).filter((label) => label !== PROCESSING_LABEL) })) {
    gh(["issue", "edit", String(issue.number), "--repo", opts.repo, "--remove-label", PROCESSING_LABEL]);
    return false;
  }
  gh(["issue", "comment", String(issue.number), "--repo", opts.repo, "--body",
    `<!-- visual-fix-queue:claim=${provenance.runKey} -->\nFix queue claimed this issue for bounded M3 truth/patch/panel processing. No product code or PR will be created automatically.`]);
  return true;
}

function releaseClaim(issue, opts) {
  if (!opts.live) return;
  try { gh(["issue", "edit", String(issue.number), "--repo", opts.repo, "--remove-label", PROCESSING_LABEL]); } catch {}
}

function loadAnalysis(opts) {
  try {
    const analysisPath = `visual-testing/latest/analysis/${opts.comparison}.json`;
    const text = execFileSync("git", ["-c", "submodule.recurse=false", "show",
      `origin/gh-pages:${analysisPath}`],
    { cwd: opts.mobileRoot, encoding: "utf8", maxBuffer: 128 * 1024 * 1024 });
    return {
      data: JSON.parse(text),
      provenance: {
        commit: execFileSync("git", ["rev-parse", "origin/gh-pages"], { cwd: opts.mobileRoot, encoding: "utf8" }).trim(),
        path: analysisPath,
        sha256: crypto.createHash("sha256").update(text).digest("hex"),
      },
    };
  } catch {
    return { data: { generatedAt: null, cases: [] }, provenance: null };
  }
}

export function manifestCase(caseId, opts) {
  const manifestPath = path.join(opts.slockRoot, "packages/visual-testing/shared/sharedCases.json");
  const manifest = JSON.parse(fs.readFileSync(manifestPath, "utf8"));
  return (manifest.cases || []).find((entry) => entry.id === caseId) || null;
}

function walkFiles(root, output = []) {
  if (!fs.existsSync(root)) return output;
  for (const entry of fs.readdirSync(root, { withFileTypes: true })) {
    if ([".git", "node_modules", ".gradle", "dist"].includes(entry.name)) continue;
    // Kotlin packages live under `.../kotlin/build/raft/...`; only skip a
    // Gradle output directory named build when its parent is a module root.
    if (entry.name === "build"
      && (fs.existsSync(path.join(root, "build.gradle.kts")) || fs.existsSync(path.join(root, "build.gradle")))) continue;
    const full = path.join(root, entry.name);
    if (entry.isDirectory()) walkFiles(full, output);
    else if (SOURCE_EXTENSIONS.has(path.extname(entry.name))) output.push(full);
  }
  return output;
}

// Full content is kept here so excerptSource can anchor on sections deep in
// monolith files (SettingsPanel.tsx puts LanguageRegionSection past char
// 25k); prompt size stays bounded by excerptSource + capSources downstream.
function sourceRecord(root, file) {
  if (!file || !fs.existsSync(file)) return null;
  return {
    path: path.relative(root, file),
    src: fs.readFileSync(file, "utf8").slice(0, 400_000),
  };
}

function referencedUiIdentifiers(sources, hints = []) {
  const identifiers = [];
  const hintNeedles = hints.flatMap((hint) => [String(hint || ""), ...String(hint || "").split(/[#:+/\s]/)])
    .filter((term) => term.length >= 4);
  for (const source of sources) {
    const windows = hintNeedles.map((needle) => source.src.indexOf(needle)).filter((index) => index >= 0)
      .map((index) => source.src.slice(Math.max(0, index - 300), index + 1_200));
    const searchable = windows.length ? windows.join("\n") : source.src;
    for (const match of searchable.matchAll(/\b([A-Z][A-Za-z0-9_]*(?:Content|Screen|Page))\b/g)) {
      identifiers.push(match[1]);
      if (identifiers.length >= 200) break;
    }
  }
  const unique = [...new Set(identifiers)];
  const terms = hints.flatMap((hint) => String(hint || "").split(/[^A-Za-z0-9]+/))
    .filter((term) => term.length >= 4)
    .map((term) => term.toLowerCase());
  const prioritized = unique.filter((identifier) => terms.some((term) => identifier.toLowerCase().includes(term)));
  return prioritized.length ? prioritized : unique.slice(0, 40);
}

function sourceMatches(root, needles, limit = 5) {
  const normalized = [...new Set(needles.filter(Boolean).flatMap((needle) => {
    const text = String(needle);
    return [text.split(/[/.]/).pop(), ...text.split(/[#:+/\s]/)].filter((part) => part && part.length >= 4);
  }))];
  if (!normalized.length) return [];
  // Files NAMED after a needle are the component itself; files whose name
  // merely embeds a needle (SettingsSegmentedControls.tsx) are close variants;
  // files only CONTAINING a needle are often harness/consumer noise. A single
  // walk-order pass let that noise exhaust the match limit before ever
  // reaching e.g. packages/web/src/components/ui/ProgressBar.tsx, so matches
  // are ranked exact-name > name-contains > content-contains.
  const files = walkFiles(root);
  const nameTier = (file) => {
    const basename = path.basename(file, path.extname(file));
    if (normalized.some((needle) => basename === needle)) return 1;
    if (normalized.some((needle) => basename.includes(needle))) return 2;
    return 0;
  };
  const matches = [];
  for (const tier of [1, 2]) {
    for (const file of files) {
      if (nameTier(file) !== tier) continue;
      matches.push({ source: sourceRecord(root, file), tier });
      if (matches.length >= limit) return matches.filter((match) => match.source);
    }
  }
  for (const file of files) {
    if (nameTier(file) !== 0) continue;
    const content = fs.readFileSync(file, "utf8");
    if (!normalized.some((needle) => content.includes(needle))) continue;
    matches.push({ source: sourceRecord(root, file), tier: 3 });
    if (matches.length >= limit) break;
  }
  return matches.filter((match) => match.source);
}

// Merge matches from several roots by tier so an exact-name component file in
// one repo outranks content-noise from another; within a tier the earlier
// root wins (stable sort). Without this, rui content matches consumed the
// prompt budget ahead of slock's exact ProgressBar.tsx.
function rankedSources(rootMatches) {
  return rootMatches.flat().sort((a, b) => a.tier - b.tier).map((match) => match.source);
}

export function isForbiddenSourcePath(sourcePath) {
  return /(^|\/)(?:test|tests|__tests__|commonTest|androidTest|iosTest|jvmTest|jsTest|desktopTest|nativeTest|wasmTest)(\/|$)|(?:Test|Tests)\.(?:kt|kts|java|ts|tsx|js|jsx)$/i.test(sourcePath)
    || /(^|\/)(?:baseline|baselines|snapshots?)(\/|$)|platformDivergences\.json$|sharedCases\.json$/i.test(sourcePath);
}

export function dedupeSources(sources) {
  const seen = new Set();
  return sources.filter((source) => {
    if (!source || isForbiddenSourcePath(source.path) || seen.has(source.path)) return false;
    seen.add(source.path);
    return true;
  });
}

// Per-file cap keeps one oversized file (e.g. SettingsPanel.tsx) from eating
// the whole budget and evicting the small RUI primitives collected after it —
// that eviction is how several fix-queue candidates ended refuted with
// "authoritative source missing" despite the file existing.
export function capSources(sources, maxChars = 60_000, perFileChars = 20_000) {
  const capped = [];
  let remaining = maxChars;
  for (const source of sources) {
    if (remaining <= 0) break;
    const src = source.src.slice(0, Math.min(remaining, perFileChars));
    if (!src) continue;
    capped.push({ ...source, src });
    remaining -= src.length;
  }
  return capped;
}

// Monolith files (SettingsPanel.tsx is ~274k chars) can never fit a sane
// per-file cap, and head-truncation always misses the relevant section (e.g.
// LanguageRegionSection at ~line 708). When a file is oversized, slice a
// window anchored on the needle's definition site instead of the file head.
export function excerptSource(source, needles, windowChars = 20_000) {
  const src = source?.src || "";
  if (src.length <= windowChars) return source;
  const words = [...new Set(needles.filter(Boolean).map(String).filter((word) => word.length >= 4))];
  if (!words.length) return { ...source, src: src.slice(0, windowChars) };
  const escaped = words.map((word) => word.replace(/[.*+?^${}()|[\]\\]/g, "\\$&"));
  // Candidate anchors: definition sites plus every needle occurrence. The
  // window with the highest total needle density wins — a case id's map
  // registration ("components.ui.segmented-control.states": {...}) is a
  // single hit, while the actual fixture/render block references the
  // components repeatedly, so density ranks the meaty region first.
  const candidates = new Set();
  const definitionRe = new RegExp(`(?:function|const|class|fun|val|object)\\s+(?:${escaped.join("|")})`, "g");
  let match;
  while ((match = definitionRe.exec(src)) && candidates.size < 64) candidates.add(match.index);
  for (const word of words) {
    let index = -1;
    for (let n = 0; n < 16 && (index = src.indexOf(word, index + 1)) >= 0; n += 1) candidates.add(index);
  }
  if (!candidates.size) return { ...source, src: src.slice(0, windowChars) };
  const occurrenceRe = new RegExp(escaped.join("|"), "g");
  const hits = [];
  while ((match = occurrenceRe.exec(src))) hits.push(match.index);
  let bestStart = 0;
  let bestScore = -1;
  for (const pos of candidates) {
    const start = Math.max(0, pos - Math.floor(windowChars / 5));
    const score = hits.filter((hit) => hit >= start && hit < start + windowChars).length;
    if (score > bestScore) { bestScore = score; bestStart = start; }
  }
  const excerpt = src.slice(bestStart, bestStart + windowChars);
  const marker = `/* fix-queue excerpt of ${source.path}: chars ${bestStart}-${bestStart + excerpt.length} of ${src.length}, anchored on: ${words.join(", ")} */\n`;
  return { ...source, src: marker + excerpt };
}

// Derive component-name needles from the case id itself so library primitives
// are found even when the manifest hints omit them (e.g.
// components.ui.segmented-control.states -> SegmentedControl in rui).
// Single generic words (states/page/panel/...) are excluded because they match
// half the tree and would exhaust the per-search file limit with noise.
const CASE_NEEDLE_STOPWORDS = new Set([
  "components", "screens", "states", "state", "page", "pages", "panel", "panels",
  "default", "empty", "full", "screen", "item", "items", "view", "views", "open",
  "closed", "home", "settings", "thread", "channel", "members", "results",
]);
export function caseIdNeedles(caseId) {
  const needles = new Set();
  for (const segment of String(caseId || "").split(".")) {
    const words = segment.split("-").filter(Boolean);
    const pascal = words.map((word) => word[0].toUpperCase() + word.slice(1)).join("");
    if (words.length >= 2 && pascal.length >= 4) needles.add(pascal);
    if (words.length === 1 && pascal.length >= 5 && !CASE_NEEDLE_STOPWORDS.has(words[0].toLowerCase())) needles.add(pascal);
  }
  return [...needles];
}

export function collectSources(entry, opts) {
  const react = entry?.providerMatrix?.react || {};
  const android = entry?.providerMatrix?.android || {};
  const reactHintPath = String(entry?.reactPathHint || "").split("#")[0];
  const reactPath = react.path || reactHintPath;
  const reactExact = sourceRecord(opts.slockRoot, reactPath ? path.join(opts.slockRoot, reactPath) : "");
  const androidExact = sourceRecord(opts.mobileRoot, android.path ? path.join(opts.mobileRoot, android.path) : "");
  const derivedNeedles = caseIdNeedles(entry?.id);
  // The part after "#" in a manifest path hint names the exact section inside
  // a monolith file (SettingsPanel.tsx#LanguageRegionSection) — it is the
  // highest-precision excerpt anchor available.
  const reactAnchor = String(entry?.reactPathHint || "").split("#")[1] || "";
  // Android fixture pages/composables are named after the case hint's page id
  // (compose_ui_avatar_list_row_visual -> ComposeUiAvatarListRowVisualPage in
  // VisualFixturePages.kt and UiAvatarListRowVisualContent in the fixture
  // file), so derive Pascal needles from it — the truth stage repeatedly
  // refuted candidates because these files never reached the evidence pack.
  const androidPageId = String(entry?.androidCaseHint || "").split(":")[0];
  const androidPageNeedles = [];
  if (androidPageId.includes("_")) {
    const pascal = androidPageId.split("_").filter(Boolean).map((word) => word[0].toUpperCase() + word.slice(1)).join("");
    if (pascal.length >= 4) androidPageNeedles.push(pascal, pascal.replace(/^Compose/, ""));
  }
  const reactComponents = [...(react.components || []), entry?.reactPathHint, ...derivedNeedles];
  const androidComponents = [...(android.components || []), entry?.androidCaseHint, ...androidPageNeedles, ...derivedNeedles];
  const reactExcerptNeedles = [entry?.id, reactAnchor, ...(react.components || []).map((component) => String(component).split("/").pop()), ...derivedNeedles].filter(Boolean);
  const androidExcerptNeedles = [
    entry?.id,
    ...(android.components || []).map((component) => String(component).split(".").pop()),
    ...androidPageNeedles,
    entry?.androidCaseHint,
    ...derivedNeedles,
  ].filter(Boolean);
  // Both sides' concrete token values (bg-brutal-yellow hex etc.) live in one
  // canonical file per repo; truth derivation needs them for every case.
  const reactTokens = sourceRecord(opts.slockRoot, path.join(opts.slockRoot, "packages/web/src/index.css"));
  const androidTokens = sourceRecord(opts.mobileRoot, path.join(opts.mobileRoot, "compose/shared/src/commonMain/kotlin/build/raft/app/RaftDesignTokens.kt"));
  const androidSeedSources = rankedSources([sourceMatches(path.join(opts.mobileRoot, "compose"), androidComponents, 6)]);
  const androidExpandedSources = rankedSources([sourceMatches(
    path.join(opts.mobileRoot, "compose"),
    referencedUiIdentifiers(androidSeedSources, androidComponents),
    8,
  )]);
  const androidComponentSources = [...androidSeedSources, ...androidExpandedSources]
    .map((source) => ({ ...source, path: `compose/${source.path}` }));
  return {
    // rui and slock matches merge by tier (exact-name first across both
    // repos, rui winning ties); the app-level exact manifest path still leads.
    react: capSources(dedupeSources([
      reactExact,
      reactTokens,
      ...rankedSources([
        sourceMatches(opts.ruiRoot, reactComponents, 5),
        sourceMatches(opts.slockRoot, reactComponents, 4),
      ]),
    ]).map((source) => excerptSource(source, reactExcerptNeedles))),
    android: capSources(dedupeSources([
      androidExact,
      androidTokens,
      ...androidComponentSources,
    ]).map((source) => excerptSource(source, androidExcerptNeedles))),
  };
}

export function sourceAuthorityProblem(candidate) {
  if (!candidate.manifest) return "case is absent from the canonical shared manifest";
  if (!candidate.analysis) return "case is absent from the exact published comparison analysis";
  if (!candidate.sources.react.length) return "no exact React/RUI source authority was located";
  if (!candidate.sources.android.length) return "no exact Android/KMP source authority was located";
  return null;
}

export function patchScopeProblem(patchResult, androidSources) {
  if (patchResult?.patchable === false) return null;
  if (!Array.isArray(patchResult?.patch) || patchResult.patch.length === 0) return "patch generator emitted no patch";
  const allowed = new Set(androidSources.map((source) => source.path));
  for (const entry of patchResult.patch) {
    const patchPath = String(entry?.path || "").replace(/^\.\//, "");
    if (!allowed.has(patchPath)) return `patch path is outside located Android authority: ${patchPath || "<missing>"}`;
    if (isForbiddenSourcePath(patchPath)) return `patch path is forbidden queue scope: ${patchPath}`;
    const diff = String(entry?.diff || "");
    const oldHeaders = [...diff.matchAll(/^---\s+([^\t\r\n]+).*$/gm)].map((match) => match[1]);
    const newHeaders = [...diff.matchAll(/^\+\+\+\s+([^\t\r\n]+).*$/gm)].map((match) => match[1]);
    const gitHeaders = [...diff.matchAll(/^diff --git a\/(\S+) b\/(\S+)$/gm)];
    if (oldHeaders.length !== 1 || newHeaders.length !== 1
      || oldHeaders[0] !== `a/${patchPath}` || newHeaders[0] !== `b/${patchPath}`) {
      return `patch diff headers do not bind to declared path: ${patchPath}`;
    }
    if (gitHeaders.length > 1 || (gitHeaders[0]
      && (gitHeaders[0][1] !== patchPath || gitHeaders[0][2] !== patchPath))) {
      return `patch contains a second or mismatched file header: ${patchPath}`;
    }
    if (/^(?:rename|copy) (?:from|to) |^Binary files |^GIT binary patch$/m.test(diff)) {
      return `patch uses forbidden rename/copy/binary form: ${patchPath}`;
    }
  }
  return null;
}

function loadModels() {
  return import(process.env.SLOCK_PI_AI_MODULE || "@earendil-works/pi-ai/providers/all").then((sdk) => sdk.builtinModels());
}

function redactModelText(value, limit = 8_000) {
  return String(value || "")
    .replace(/sk_(agent|machine)_[A-Za-z0-9_-]+/g, "sk_$1_<redacted>")
    .replace(/\b(api[_-]?key|authorization|token|password)\s*[:=]\s*[^\s,;]+/gi, "$1=<redacted>")
    .slice(0, limit);
}

function parseJsonReply(message) {
  if (message.stopReason === "error" || message.stopReason === "aborted") throw new Error(message.errorMessage || message.stopReason);
  const text = message.content.filter((block) => block.type === "text").map((block) => block.text).join("\n").trim();
  const match = /\{[\s\S]*\}/.exec(text);
  return { data: JSON.parse(match ? match[0] : text), raw: redactModelText(text) };
}

async function completeJson(models, model, prompt, maxTokens, callOpts = {}) {
  const attempts = [];
  // Cross-border providers (qwen-token-plan-cn from GitHub runners) fail with
  // immediate connection errors on some runners; extra bounded attempts with a
  // short backoff recover the transient cases without hiding hard failures.
  const maxAttempts = callOpts.attempts || 2;
  for (let attempt = 1; attempt <= maxAttempts; attempt += 1) {
    if (attempt > 1) await new Promise((resolve) => setTimeout(resolve, 4_000));
    const controller = new AbortController();
    const timeoutMs = 180_000;
    const timer = setTimeout(() => controller.abort(), timeoutMs);
    try {
      const message = await models.complete(model,
        { messages: [{ role: "user", content: [{ type: "text", text: prompt }], timestamp: Date.now() }] },
        {
          apiKey: callOpts.apiKey || process.env.MINIMAX_API_KEY || process.env.PI_API_KEY,
          maxTokens,
          signal: controller.signal,
          timeoutMs,
          ...(callOpts.reasoningEffort ? { reasoningEffort: callOpts.reasoningEffort } : {}),
        });
      const parsed = parseJsonReply(message);
      attempts.push({ attempt, ok: true, raw: parsed.raw });
      return { data: parsed.data, usage: message.usage || null, attempts };
    } catch (error) {
      attempts.push({ attempt, ok: false, error: redactModelText(error?.message || error) });
    } finally {
      clearTimeout(timer);
    }
  }
  const error = new Error(`strict JSON completion failed after ${maxAttempts} bounded attempts`);
  error.attempts = attempts;
  throw error;
}

function truthPrompt(candidate) {
  return [
    "Derive the exact React visual truth for this verified cross-platform drift from the supplied source.",
    "Do not estimate from prose. Trace component -> token/class -> concrete value and enumerate every theme/state variant.",
    "Classify whether the finding is safely patchable as a narrow style/token/fixture change. Semantic, structural,",
    "interaction, ambiguous-source, or geometry-without-exact-source findings are patchable=false with one precise flaw.",
    "Scope discipline: judge patchability ONLY against the specific drift named in the verified issue. If the sources",
    "give concrete values for that drift, set patchable=true even when unrelated variants, auxiliary fixtures, or",
    "surrounding context are absent — missing context that the reported drift does not depend on is NOT a flaw.",
    `Case: ${candidate.caseId} Comparison: ${candidate.comparison}`,
    "Verified issue:", candidate.issueBody,
    "React sources:",
    ...candidate.sources.react.map((source) => `--- ${source.path} ---\n${source.src}`),
    'Return strict JSON: {"patchable":bool,"classification":"token|style|fixture|geometry|semantic|ambiguous",',
    '"reactTruth":"exact target and derivation","values":[{"element":"","state":"","property":"","value":"","derivation":""}],"specificFlaw":string|null}',
  ].join("\n\n");
}

function patchPrompt(candidate, truth) {
  return [
    "Produce a minimal unified diff that makes Android/KMP match the authoritative React truth.",
    "Change only the verified divergence. Enumerate and cover all relevant theme/state variants. Do not apply the patch.",
    "Do not edit tests, registries, baselines, or unrelated properties. If exact source authority is missing, return patchable=false",
    "and a precise specificFlaw instead of guessing. CSS px is not automatically dp; preserve units explicitly.",
    "Scope discipline: the React truth above is authoritative — do not re-litigate it or withhold a patch because",
    "auxiliary context outside the reported drift is missing. If the Android sources contain the site controlling the",
    "reported drift and the truth gives its target value, you MUST emit the minimal diff for that site.",
    `Case: ${candidate.caseId}`,
    `React truth: ${truth.reactTruth}`,
    "Verified issue:", candidate.issueBody,
    "Android sources:",
    ...candidate.sources.android.map((source) => `--- ${source.path} ---\n${source.src}`),
    'Return strict JSON: {"patchable":bool,"trueValueChain":"","rationale":"","specificFlaw":string|null,',
    '"patch":[{"path":"repo-relative path","diff":"unified diff"}]}',
  ].join("\n\n");
}

function reviewPrompt(candidate, truth, patchResult, voter) {
  const patchEntries = Array.isArray(patchResult.patch) ? patchResult.patch : [];
  return [
    "You are an independent adversarial reviewer in a fresh context. Try to REFUTE this proposed visual patch.",
    "Demonstrate a concrete flaw: wrong source/value/unit, missing theme/state, excessive blast radius, test/baseline dodge,",
    "or a semantic/structural change masquerading as a token fix. Do not refute on vague uncertainty.",
    `Reviewer slot: ${voter} Case: ${candidate.caseId}`,
    `Authoritative React truth: ${truth.reactTruth}`,
    `True-value chain: ${patchResult.trueValueChain || "missing"}`,
    `Patch generator classification: patchable=${patchResult.patchable !== false}`,
    `Patch generator flaw: ${patchResult.specificFlaw || "none"}`,
    "Patch:", ...(patchEntries.length
      ? patchEntries.map((entry) => `--- ${entry.path} ---\n${entry.diff}`)
      : ["(no patch emitted)"]),
    'Return strict JSON: {"refuted":bool,"reason":"one concrete sentence","specificFlaw":string|null,"confidence":number}',
  ].join("\n\n");
}

// Panel voter specs. PI_PANEL_MODELS is a comma-separated provider/model list
// whose length must equal the voter count; empty means every voter uses the
// primary PI_PROVIDER/PI_MODEL. qwen-token-plan endpoints reject
// enable_thinking=false, and their reasoning tokens share the completion
// budget, so those voters run with reasoningEffort on and a larger cap.
export function resolvePanelSpec(voters, env = process.env) {
  const primary = {
    provider: String(env.PI_PROVIDER || "minimax"),
    modelName: String(env.PI_MODEL || "MiniMax-M3.1-Flash-Preview"),
  };
  const raw = String(env.PI_PANEL_MODELS || "").trim();
  const entries = raw
    ? raw.split(",").map((spec) => spec.trim()).filter(Boolean).map((spec) => {
      const slash = spec.indexOf("/");
      if (slash <= 0 || slash === spec.length - 1) throw new Error(`PI_PANEL_MODELS entry must be provider/model, got: ${spec}`);
      return { provider: spec.slice(0, slash), modelName: spec.slice(slash + 1) };
    })
    : Array.from({ length: voters }, () => ({ ...primary }));
  if (entries.length !== voters) throw new Error(`PI_PANEL_MODELS must list exactly ${voters} voters, got ${entries.length}`);
  return entries.map((entry) => ({
    ...entry,
    label: `${entry.provider}/${entry.modelName}`,
    reasoningEffort: entry.provider.startsWith("qwen-token-plan") ? "low" : null,
    maxTokens: entry.provider.startsWith("qwen-token-plan") ? 8_192 : 2_048,
  }));
}

export function panelApiKey(provider, env = process.env) {
  const dedicated = env[`PI_API_KEY_${provider.toUpperCase().replace(/-/g, "_")}`];
  if (dedicated) return dedicated;
  if (provider === "minimax" || provider === "minimax-cn") return env.MINIMAX_API_KEY || env.PI_API_KEY;
  return env.PI_API_KEY;
}

export function panelDecision(votes, threshold) {
  const valid = votes.filter((vote) => !vote.error);
  const refuted = valid.filter((vote) => vote.refuted).length;
  if (valid.length !== votes.length || valid.length < 3) return { verdict: "REVIEW", valid: valid.length, refuted };
  return { verdict: refuted >= threshold ? "REFUTED" : "UNREFUTED", valid: valid.length, refuted };
}

function usageTokens(usage) {
  if (!usage) return 0;
  return Number(usage.totalTokens || usage.total_tokens || 0)
    || Number(usage.inputTokens || usage.input_tokens || 0) + Number(usage.outputTokens || usage.output_tokens || 0);
}

async function processCandidate(candidate, opts, models, model, panel) {
  const usages = [];
  const audit = {};
  let truthCall;
  try {
    truthCall = await completeJson(models, model, truthPrompt(candidate), 3_072);
  } catch (error) {
    return { verdict: "REVIEW", stage: "truth", specificFlaw: error.message, audit: { truth: error.attempts || [] }, votes: [], tokenUsage: 0 };
  }
  audit.truth = truthCall.attempts;
  usages.push(truthCall.usage);
  const truth = truthCall.data;

  let patchCall;
  try {
    patchCall = await completeJson(models, model, patchPrompt(candidate, truth), 4_096);
  } catch (error) {
    return { verdict: "REVIEW", stage: "patch", specificFlaw: error.message, truth, audit: { ...audit, patch: error.attempts || [] }, votes: [], tokenUsage: usages.reduce((sum, usage) => sum + usageTokens(usage), 0) };
  }
  audit.patch = patchCall.attempts;
  usages.push(patchCall.usage);
  const patchResult = patchCall.data;
  const scopeProblem = patchScopeProblem(patchResult, candidate.sources.android);
  if (scopeProblem) {
    return { verdict: "REVIEW", stage: "patch_scope", specificFlaw: scopeProblem, truth, patch: patchResult, audit, votes: [], tokenUsage: usages.reduce((sum, usage) => sum + usageTokens(usage), 0) };
  }

  const votes = await Promise.all(panel.map(async (entry, index) => {
    try {
      const call = await completeJson(models, entry.model, reviewPrompt(candidate, truth, patchResult, index + 1), entry.maxTokens,
        { apiKey: entry.apiKey, reasoningEffort: entry.reasoningEffort, attempts: entry.provider === "minimax" ? 2 : 4 });
      usages.push(call.usage);
      return { voter: index + 1, model: entry.label, ...call.data, audit: call.attempts };
    } catch (error) {
      return { voter: index + 1, model: entry.label, error: String(error?.message || error).slice(0, 300), audit: error.attempts || [] };
    }
  }));
  const decision = panelDecision(votes, opts.threshold);
  const patchUsable = truth.patchable !== false
    && patchResult.patchable !== false
    && Array.isArray(patchResult.patch)
    && patchResult.patch.length > 0;
  const strongestFlaw = votes.find((vote) => vote.refuted && vote.specificFlaw)?.specificFlaw
    || votes.find((vote) => vote.refuted)?.reason
    || truth.specificFlaw
    || patchResult.specificFlaw
    || (decision.verdict === "REVIEW" ? "All three independent panel votes must complete with valid strict JSON." : null);
  return {
    ...decision,
    verdict: decision.verdict === "REVIEW" ? "REVIEW" : (patchUsable ? decision.verdict : "REFUTED"),
    stage: patchUsable ? "panel" : (truth.patchable === false ? "truth" : "patch"),
    specificFlaw: strongestFlaw || "M3 produced no safe minimal patch.",
    truth,
    patch: patchResult,
    votes,
    audit,
    tokenUsage: usages.reduce((sum, usage) => sum + usageTokens(usage), 0),
  };
}

function compactJson(value, limit = 18_000) {
  const text = JSON.stringify(value, null, 2);
  return text.length <= limit ? text : `${text.slice(0, limit)}\n…truncated in comment; full record is in workflow artifact.`;
}

export function resultComment(result, provenance) {
  const header = result.verdict === "UNREFUTED"
    ? "## Visual fix queue: UNREFUTED → agent handoff"
    : result.verdict === "REFUTED"
      ? "## Visual fix queue: REFUTED"
      : "## Visual fix queue: REVIEW / retry required";
  const next = result.verdict === "UNREFUTED"
    ? "An implementation agent may now apply the proposed patch on a fresh current-main branch, run targeted `Visual Testing` for this case, execute `scripts/visual_verify.py`, embed React | before | after evidence, and open a **draft** PR. No automatic product merge is authorized."
    : result.verdict === "REFUTED"
      ? `Do not apply this patch. Concrete flaw: ${result.specificFlaw || "panel refuted without a usable patch"}`
      : "The claim is released so a later bounded run may retry; do not apply or open a PR from this record.";
  return [
    `<!-- visual-fix-queue:result=${provenance.runKey};verdict=${result.verdict} -->`,
    header,
    "",
    next,
    "",
    `**Provenance:** mobile \`${provenance.mobileSha}\` · slock \`${provenance.slockSha}\` · RUI \`${provenance.ruiSha || "unavailable"}\` · analysis \`${provenance.analysisGeneratedAt || "unavailable"}\` (` +
      `${provenance.analysisSource?.commit || "no-commit"}:${provenance.analysisSource?.path || "no-path"} sha256=${provenance.analysisSource?.sha256 || "unavailable"}) · model \`${provenance.model}\` · workflow \`${provenance.runKey}\``,
    `**Budget:** reserved ${provenance.reservedTokens} tokens · reported usage ${result.tokenUsage || "unavailable"}`,
    "",
    "<details><summary>Truth / patch / independent votes</summary>",
    "",
    "```json", compactJson({ truth: result.truth, patch: result.patch, votes: result.votes }), "```",
    "</details>",
  ].join("\n");
}

function applyResult(issue, result, opts, provenance) {
  if (!opts.live) return;
  gh(["issue", "comment", String(issue.number), "--repo", opts.repo, "--body", resultComment(result, provenance)]);
  releaseClaim(issue, opts);
  if (result.verdict === "REFUTED") {
    gh(["issue", "edit", String(issue.number), "--repo", opts.repo, "--add-label", REFUTED_LABEL]);
  } else if (result.verdict === "UNREFUTED") {
    gh(["issue", "edit", String(issue.number), "--repo", opts.repo, "--add-label", AGENT_READY_LABEL]);
  }
}

function scriptSha() {
  return crypto.createHash("sha256").update(fs.readFileSync(fileURLToPath(import.meta.url))).digest("hex");
}

async function main() {
  const opts = parseArgs();
  if (!process.env.PI_API_KEY && !process.env.MINIMAX_API_KEY) throw new Error("PI_API_KEY/MINIMAX_API_KEY is required");
  ensureLabels(opts);
  const analysisRecord = loadAnalysis(opts);
  const analysis = analysisRecord.data;
  const analysisByCase = new Map((analysis.cases || []).map((entry) => [entry.id, entry]));
  const issues = listIssues(opts);
  const selected = selectFixQueueIssues(issues, opts.caseCap, opts.tokenCap);
  const models = await loadModels();
  const provider = String(process.env.PI_PROVIDER || "minimax");
  const modelName = String(process.env.PI_MODEL || "MiniMax-M3.1-Flash-Preview");
  const model = resolvePiModel(models, provider, modelName);
  if (!model) throw new Error(`${provider}/${modelName} not found`);
  // Fail closed before claiming any issue: a panel voter with a missing model
  // or key must abort the run, not silently degrade to a smaller/same-model panel.
  const panel = resolvePanelSpec(opts.voters).map((entry) => {
    const panelModel = resolvePiModel(models, entry.provider, entry.modelName);
    if (!panelModel) throw new Error(`panel model ${entry.label} not found`);
    const apiKey = panelApiKey(entry.provider);
    if (!apiKey) throw new Error(`no API key for panel provider ${entry.provider}; set PI_API_KEY_${entry.provider.toUpperCase().replace(/-/g, "_")}`);
    return { ...entry, model: panelModel, apiKey };
  });

  // Preflight non-primary voter endpoints BEFORE claiming issues or spending
  // primary-model truth/patch tokens: qwen-token-plan-cn is reachable from
  // some GitHub runners and hard-blocked from others, and a blocked runner
  // would otherwise burn the whole run's M3 budget only to end in REVIEW.
  // A failed preflight defers the run (exit 0, report status "deferred") so
  // the next scheduled run re-rolls onto a different runner.
  const preflights = [];
  for (const entry of panel) {
    if (entry.provider === provider) continue;
    if (preflights.some((record) => record.label === entry.label)) continue;
    const record = { label: entry.label, ok: false };
    try {
      const call = await completeJson(models, entry.model, 'Return strict JSON: {"ok":true}', 1_024,
        { apiKey: entry.apiKey, reasoningEffort: entry.reasoningEffort, attempts: 3 });
      record.ok = call.data?.ok === true || Boolean(call.data);
    } catch (error) {
      record.error = redactModelText(error?.message || error, 300);
    }
    preflights.push(record);
  }
  const unreachable = preflights.filter((record) => !record.ok);
  if (unreachable.length) {
    const report = {
      status: "deferred",
      reason: `panel voter endpoint unreachable from this runner: ${unreachable.map((record) => record.label).join(", ")}`,
      preflights,
      generatedAt: new Date().toISOString(),
      note: "No issue was claimed and no primary-model tokens were spent; the next scheduled run retries on a fresh runner.",
    };
    fs.writeFileSync(opts.report, `${JSON.stringify(report, null, 2)}\n`);
    console.log(JSON.stringify(report, null, 2));
    return;
  }

  const runKey = `${process.env.GITHUB_RUN_ID || "local"}.${process.env.GITHUB_RUN_ATTEMPT || "1"}`;
  const baseProvenance = {
    runKey,
    mobileSha: gitSha(opts.mobileRoot),
    slockSha: gitSha(opts.slockRoot),
    ruiSha: gitSha(opts.ruiRoot),
    analysisGeneratedAt: analysis.generatedAt || null,
    analysisSource: analysisRecord.provenance,
    model: `${provider}/${modelName}`,
    panelModels: panel.map((entry) => entry.label),
    panelPreflights: preflights,
    scriptSha256: scriptSha(),
    reservedTokens: DEFAULT_CASE_TOKEN_RESERVATION,
  };
  const results = [];
  for (const issue of selected) {
    const caseId = issueCaseId(issue);
    const provenance = { ...baseProvenance, issue: issue.number, caseId };
    if (!caseId || !claimIssue(issue, opts, provenance)) {
      results.push({ issue: issue.number, caseId, verdict: "SKIPPED", reason: caseId ? "claim lost" : "missing case id" });
      continue;
    }
    try {
      const entry = manifestCase(caseId, opts);
      const sources = collectSources(entry, opts);
      const candidate = {
        caseId,
        comparison: issueComparison(issue, opts.comparison),
        issueBody: issue.body || "",
        analysis: analysisByCase.get(caseId) || null,
        manifest: entry,
        sources,
      };
      const authorityProblem = sourceAuthorityProblem(candidate);
      if (authorityProblem) {
        const result = {
          verdict: "REVIEW",
          stage: "source_authority",
          specificFlaw: authorityProblem,
          manifestPresent: Boolean(entry),
          analysisPresent: Boolean(candidate.analysis),
          sourceCounts: { react: sources.react.length, android: sources.android.length },
          votes: [],
          tokenUsage: 0,
        };
        applyResult(issue, result, opts, provenance);
        results.push({ issue: issue.number, caseId, ...result, provenance });
        continue;
      }
      const result = await processCandidate(candidate, opts, models, model, panel);
      applyResult(issue, result, opts, provenance);
      results.push({ issue: issue.number, caseId, ...result, provenance });
    } catch (error) {
      releaseClaim(issue, opts);
      results.push({ issue: issue.number, caseId, verdict: "ERROR", error: String(error?.stack || error).slice(0, 2_000), provenance });
    }
  }

  const report = {
    mode: opts.live ? "LIVE" : "DRY_RUN",
    generatedAt: new Date().toISOString(),
    limits: { caseCap: opts.caseCap, tokenCap: opts.tokenCap, reservedPerCase: DEFAULT_CASE_TOKEN_RESERVATION },
    counts: {
      eligible: issues.filter(isEligibleIssue).length,
      selected: selected.length,
      refuted: results.filter((result) => result.verdict === "REFUTED").length,
      unrefuted: results.filter((result) => result.verdict === "UNREFUTED").length,
      review: results.filter((result) => result.verdict === "REVIEW").length,
      errors: results.filter((result) => result.verdict === "ERROR").length,
    },
    provenance: baseProvenance,
    results,
    note: "No product code, PR, baseline, or merge was created by this job.",
  };
  fs.writeFileSync(opts.report, `${JSON.stringify(report, null, 2)}\n`);
  console.log(JSON.stringify(report, null, 2));
  if (results.some((result) => result.verdict === "ERROR")) process.exitCode = 1;
}

const isMain = process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url);
if (isMain) main().catch((error) => {
  console.error(JSON.stringify({ error: String(error?.stack || error) }));
  process.exit(1);
});
