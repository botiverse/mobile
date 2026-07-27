#!/usr/bin/env node
// qa/ios_deterministic_repro — scripted (no-M3) deterministic drive for the
// iOS loop harness. The acceptance agent's M3 model keeps getting lost on
// navigation (permission dialogs, back-gesture exits, channel timeouts — three
// runs burned). This script drives ios_driver.mjs directly with a fixed,
// auditable step sequence:
//
//   waitReady → open #all → assert composer + image button → marker →
//   single tap on the image button → liveness + UI state → verdict
//
// Outcome branches:
//   - process died (launchctl no longer lists the bundle, or describe throws
//     repeatedly)  → verdict fail, naming the exact step of death
//   - a picker/permission UI appeared (photo-library markers in the dump) →
//     verdict pass ("reached picker gate" only — select/preview/send are NOT
//     asserted here; that is the case's remaining scope)
//   - app alive but no picker → verdict fail "picker did not open"
//
// Sampling modes (task #26; a sampling head NEVER emits a chain PASS):
//   --sampling       permission-bootstrap phase only: after the post-Allow
//                    auto-open check, record branch + dumps and stop.
//   --sampling-cell  cell-selection phase: on a PROVEN picker, issue exactly
//                    one cell tap, capture before/after STRUCTURED node
//                    snapshots (identifier/type/enabled/selected) + text dumps
//                    + screenshot + selection-sample.json with a field-level
//                    state diff, stop. Never taps Done, never judges the
//                    composer preview (iOS 26's picker is multi-select style;
//                    the selection-state contract must be reviewable before
//                    Done/preview is attempted).
//   --sampling-done  Done-transition discovery phase: freeze the plain-channel
//                    structured tree BEFORE the composer image tap, select one
//                    proven Photo cell, require the frozen ios26-selected
//                    structured contract, tap its unique enabled Add/Done
//                    button exactly once, then capture the after-Done tree,
//                    dump, screenshot, liveness, and full plain→after diff.
//                    Stops as sample: never sends and never calls a generic
//                    composer-preview matcher.
//
// Usage:
//   node scripts/qa/ios_deterministic_repro.mjs \
//     --ios-io-dir "$QA_LOOP_IO_DIR" --ios-udid "$UDID" \
//     --package build.raft.app --out qa-evidence

import { execFileSync } from "node:child_process";
import fs from "node:fs";
import path from "node:path";
import { createIosDriver } from "./ios_driver.mjs";
import {
  composerHasNewPreview,
  classifySelectedPhotoPicker,
  countThreadImageNodes,
  diffUiNodeStates,
  exactCellCandidates,
  findAllChannelTarget,
  findComposerImageTarget,
  findPhotoCellTarget,
  findSelectedPhotoDoneTarget,
  hasPhotoPickerGate,
  hasRealPhotoPicker,
  redactEditableValues,
  targetTrackingCandidates,
  uiNodeSignature,
} from "./ios_navigation_matcher.mjs";

const arg = (f, d) => { const i = process.argv.indexOf(f); return i >= 0 ? process.argv[i + 1] : d; };
const ioDir = arg("--ios-io-dir", process.env.QA_LOOP_IO_DIR || "");
const udid = arg("--ios-udid", "booted");
const bundle = arg("--package", "build.raft.app");
const outDir = arg("--out", "qa-evidence");
// --sampling: permission-bootstrap phase only. After the post-Allow auto-open
// check, record the branch + dumps/screenshots and stop with verdict
// "sample" — the unfinished preview/readback identity contracts must never
// emit a chain PASS from this head (XiShi static review).
const sampling = process.argv.includes("--sampling");
// --sampling-cell: cell-selection phase (task #26). Stops right after exactly
// one picker-cell tap with selection-state evidence; no Done tap, no preview
// judgment, no chain verdict.
const cellSampling = process.argv.includes("--sampling-cell");
// --sampling-done: selected-picker → exact Done transition discovery only.
// It records facts needed to define a future preview identity contract but
// does not itself decide preview PASS and cannot reach send/readback.
const doneSampling = process.argv.includes("--sampling-done");
if ([sampling, cellSampling, doneSampling].filter(Boolean).length > 1) {
  console.error("choose exactly one sampling mode: --sampling, --sampling-cell, or --sampling-done");
  process.exit(2);
}
if (!ioDir) { console.error("--ios-io-dir / QA_LOOP_IO_DIR required"); process.exit(2); }

fs.mkdirSync(outDir, { recursive: true });
const drv = createIosDriver({ ioDir, udid, bundle });
const steps = [];
const rec = (msg) => { steps.push({ ts: Date.now(), msg }); console.error(msg); };

const shot = async (name) => {
  const png = await drv.screenshotPng();
  fs.writeFileSync(path.join(outDir, name), png);
  rec(`captured ${name}`);
};

const processAlive = () => {
  try {
    const out = execFileSync("xcrun", ["simctl", "spawn", udid, "launchctl", "list"], {
      encoding: "utf8", maxBuffer: 4 * 1024 * 1024, timeout: 30000,
    });
    return out.includes(bundle);
  } catch { return null; } // spawn failed — unknown, treat as unknown not dead
};

// SpringBoard owns the initial Photos permission alert, so drv.dumpUi() (which
// is scoped to the app under test) can remain unchanged even though the gate is
// visibly open. Query only the short post-tap unified-log window and pass the
// text through the same pure marker detector used for app dumps.
const recentPhotoGateLog = () => {
  try {
    return execFileSync("xcrun", [
      "simctl", "spawn", udid, "log", "show",
      "--last", "30s",
      "--style", "compact",
      "--predicate", 'eventMessage CONTAINS[c] "Photo Library"',
    ], { encoding: "utf8", maxBuffer: 4 * 1024 * 1024, timeout: 30000 });
  } catch (error) {
    rec(`photo gate log query failed: ${String(error).slice(0, 120)}`);
    return "";
  }
};

function parseBounds(line) {
  const m = /\[(\d+),(\d+)\]\[(\d+),(\d+)\]/.exec(line);
  return m ? { x: Math.round((Number(m[1]) + Number(m[3])) / 2), y: Math.round((Number(m[2]) + Number(m[4])) / 2) } : null;
}

// Deterministic login for a cold simulator: Sign In screen → email/password →
// Sign In → (optional) server picker → home. Kuikly fields do not expose
// native editability consistently at the first snapshot, so resolve them by
// the stable label-relative position (field sits below Email/Password).
async function ensureLoggedIn() {
  let dump = await drv.dumpUi();
  fs.writeFileSync(path.join(outDir, "sign-in-dump.txt"), redactEditableValues(dump));
  if (!/Sign In|Email|Password/.test(dump)) { rec("already past sign-in screen"); return; }
  const email = process.env.QA_TEST_EMAIL;
  const password = process.env.QA_TEST_PASSWORD;
  if (!email || !password) throw new Error("QA_TEST_EMAIL/QA_TEST_PASSWORD not set for login step");
  rec("sign-in screen detected, logging in deterministically");

  const fieldUnder = (label) => {
    const line = dump.split("\n").find((l) => {
      const t = /"([^"]*)"/.exec(l)?.[1] ?? "";
      return t.trim() === label;
    });
    if (!line) throw new Error(`"${label}" label not found in sign-in dump`);
    const m = /\[(\d+),(\d+)\]\[(\d+),(\d+)\]/.exec(line);
    // tap the field below the label: label center-x, label bottom + ~45px
    return { x: Math.round((Number(m[1]) + Number(m[3])) / 2), y: Number(m[4]) + 45 };
  };

  let p = fieldUnder("Email");
  await drv.tap(p.x, p.y);
  await drv.text(email);
  rec(`email typed at [${p.x},${p.y}]`);
  p = fieldUnder("Password");
  await drv.tap(p.x, p.y);
  await drv.text(password);
  rec(`password typed at [${p.x},${p.y}]`);

  // Dismiss keyboard, re-dump, and submit with FRESH button bounds. Tapping
  // while the keyboard is visible targets the overlay instead of the shifted
  // app content (r3 evidence).
  await drv.key("esc");
  await new Promise((r2) => setTimeout(r2, 2500));
  dump = await drv.dumpUi();
  const btnLine = dump.split("\n").filter((l) => /"Sign In"/.test(l));
  if (btnLine.length) {
    const last = btnLine[btnLine.length - 1]; // the button is the last Sign In label on the page
    const p2 = parseBounds(last);
    rec(`submitting via fresh Sign In bounds [${p2.x},${p2.y}]`);
    await drv.tap(p2.x, p2.y);
  }
  await new Promise((r2) => setTimeout(r2, 8000));

  dump = await drv.dumpUi();
  const stillLogin = /"Email"/.test(dump) && /"Password"/.test(dump);
  if (stillLogin) {
    // Do NOT screenshot here: the form holds the typed email (secret hygiene —
    // r3 artifact was flagged for exactly this). Steps carry the failure.
    writeVerdict("fail", "login did not complete: Sign In page still visible after esc+fresh-bounds submit");
    process.exit(1);
  }

  dump = await drv.dumpUi();
  if (/Choose server|OranixSlockTest/.test(dump) && !dump.includes('"Message #')) {
    const rowLine = dump.split("\n").find((l) => /OranixSlockTest/.test(l));
    if (!rowLine) throw new Error("server picker shown but OranixSlockTest row not found");
    p = parseBounds(rowLine);
    await drv.tap(p.x, p.y);
    rec("server OranixSlockTest selected");
    await new Promise((r2) => setTimeout(r2, 6000));
  }
}

const verdict = { verdict: "error", reason: "", steps };
const writeVerdict = (v, reason) => {
  verdict.verdict = v;
  verdict.reason = reason;
  fs.writeFileSync(path.join(outDir, "steps.jsonl"), steps.map((s) => JSON.stringify(s)).join("\n") + "\n");
  fs.writeFileSync(path.join(outDir, "verdict.json"), JSON.stringify(verdict, null, 2));
  console.log(JSON.stringify(verdict, null, 2));
};

try {
  await drv.waitReady();
  rec("harness ready");
  await shot("step-00-launch.png");

  await ensureLoggedIn();
  await shot("step-01-loggedin.png");

  // Open #all deterministically: find the channel row whose label (space-
  // stripped) is "#all" and tap it. If we are already inside #all (composer
  // placeholder "Message #all"), skip navigation.
  let dump = await drv.dumpUi();
  fs.writeFileSync(path.join(outDir, "home-dump.txt"), redactEditableValues(dump));
  if (!dump.includes('"Message #all"')) {
    let target;
    try {
      target = findAllChannelTarget(dump);
    } catch (error) {
      writeVerdict("fail", `#all navigation precondition failed: ${String(error)}`);
      process.exit(1);
    }
    rec(`opening #all via ${target.mode} target at [${target.x},${target.y}]`);
    await drv.tap(target.x, target.y);
    await new Promise((r) => setTimeout(r, 4000));
    dump = await drv.dumpUi();
  }
  if (!dump.includes('"Message #all"')) { writeVerdict("fail", "#all composer placeholder not visible after open"); process.exit(1); }
  rec("#all open, composer visible");
  fs.writeFileSync(path.join(outDir, "channel-dump.txt"), redactEditableValues(dump));
  // The Done-transition discovery sample compares the post-Done tree against
  // the real plain channel/composer state that existed BEFORE opening Photos.
  // This is deliberately structured (not a text-only dump), and values from
  // editable nodes are already redacted by describeUiNodes().
  let plainChannelNodes = null;
  if (doneSampling) {
    plainChannelNodes = await drv.describeUiNodes();
    fs.writeFileSync(path.join(outDir, "plain-channel-nodes.json"), JSON.stringify(plainChannelNodes, null, 2));
  }
  await shot("step-01-channel.png");

  // Assert the image button and mark the process state before the single tap.
  let btn;
  try {
    btn = findComposerImageTarget(dump);
  } catch (error) {
    writeVerdict("fail", `composer image-button precondition failed: ${String(error)}`);
    process.exit(1);
  }
  const aliveBefore = processAlive();
  rec(`image button via ${btn.mode} at [${btn.x},${btn.y}]; process alive before tap: ${aliveBefore}`);

  // The single deterministic tap.
  await drv.tap(btn.x, btn.y);
  rec("single tap on image button issued");
  await new Promise((r) => setTimeout(r, 8000));
  await shot("step-02-after-tap.png");

  // Observe.
  const aliveAfter = processAlive();
  rec(`process alive after tap: ${aliveAfter}`);
  if (aliveAfter === false) {
    writeVerdict("fail", `process ${bundle} died after the single image-button tap (before=${aliveBefore}, after=false) — attribute via .ips`);
    process.exit(1);
  }
  let after = "";
  try { after = await drv.dumpUi(); } catch (e) { rec(`describe after tap failed: ${String(e).slice(0, 120)}`); }
  const systemPhotoGateLog = recentPhotoGateLog();

  // ── Permission handling + chain (task #26) ──────────────────────────────
  // iOS 26's PhotosTCCNotificationExtension consent flow ignores
  // `simctl privacy grant photos` (sampling-run evidence), so a SpringBoard
  // Photos alert is EXPECTED on a first-run simulator. Handle it through the
  // loop's XCUITest-native contract action — never coordinates/osascript.
  const alertSeen = hasPhotoPickerGate({ systemLog: systemPhotoGateLog });
  let alertResult = null;
  if (alertSeen) {
    rec("SpringBoard Photos permission alert detected — invoking system_alert_allow_photos_full_access");
    await shot("step-02b-permission-alert.png");
    try {
      alertResult = await drv.systemAlertAllowPhotosFullAccess();
    } catch (error) {
      writeVerdict("fail", `system permission bootstrap failed (fail-closed contract): ${String(error).slice(0, 250)}`);
      process.exit(1);
    }
    // Structured action evidence straight from the action result.
    fs.writeFileSync(path.join(outDir, "alert-result.json"), JSON.stringify({
      systemPermissionTap: 1,
      ...alertResult,
    }, null, 2));
    rec(`systemPermissionTap=1: allowed via "${alertResult.button}" ${alertResult.buttonBounds}; alertCount=${alertResult.alertCount}; dismissed=${alertResult.dismissed}; alertTitle="${alertResult.alertTitle}"`);
    await new Promise((r) => setTimeout(r, 4000));
    after = await drv.dumpUi();
  }

  // Picker presence is proven by the versioned chrome+cell combination only.
  const pickerOpen = hasRealPhotoPicker(after);

  // The sampling stop lives OUTSIDE the alert block (XiShi review): in
  // sampling mode the run must terminate here regardless of alertSeen —
  // otherwise a pre-authorized runner (no alert) would fall through into the
  // unfinished chain and could emit a misleading PASS.
  if (sampling) {
    fs.writeFileSync(path.join(outDir, "post-tap-dump.txt"), redactEditableValues(after));
    await shot("step-02c-post-tap.png");
    if (alertSeen) fs.writeFileSync(path.join(outDir, "post-allow-dump.txt"), redactEditableValues(after));
    fs.writeFileSync(path.join(outDir, "permission-sample.json"), JSON.stringify({
      alertSeen,
      systemPermissionTap: alertSeen ? 1 : 0,
      postAllowAutoOpened: pickerOpen,
      alertResult, // Swift action result verbatim: alertCount/alertTitle/button/buttonBounds/allButtons(array)/dismissed
      evidenceRefs: ["permission-sample.json", "post-tap-dump.txt", "step-02c-post-tap.png", ...(alertSeen ? ["alert-result.json", "post-allow-dump.txt", "step-02b-permission-alert.png"] : [])],
    }, null, 2));
    writeVerdict("sample", `alertSeen=${alertSeen} postAllowAutoOpened=${pickerOpen} — permission-bootstrap sampling ends here by contract (no chain verdict from a sampling head)`);
    process.exit(0);
  }

  if (!pickerOpen) {
    writeVerdict("fail", alertSeen
      ? "permission bootstrap completed (alert allowed + dismissed, systemPermissionTap=1) but the picker did NOT auto-open afterwards — no silent second composer tap per contract"
      : "picker did not open / real PhotosUI picker not proven (need the iOS 26 chrome set + independent Photo cells, or legacy Recents/All Photos header + chrome + tiles)");
    process.exit(1);
  }
  rec(alertSeen
    ? "picker auto-opened after Allow — continuing chain with composerTap=1 preserved"
    : "real PhotosUI picker proven without an alert (pre-authorized)");

  // ── Cell-selection sampling (task #26) ──────────────────────────────────
  // iOS 26's picker is multi-select style: a cell tap forms a SELECTION state
  // and does NOT close the picker (exact "Done" commits it). This head issues
  // exactly one cell tap on the proven picker and stops with STRUCTURED
  // selection-state evidence (identifier/type/enabled/selected before vs
  // after, field-level state diff — XiShi static review) plus human-review
  // text dumps + screenshot. It never taps Done, never judges the composer
  // preview, and never emits a chain PASS (selection-state contract pending).
  if (cellSampling) {
    const beforeCellText = redactEditableValues(after);
    fs.writeFileSync(path.join(outDir, "before-cell-dump.txt"), beforeCellText);
    const beforeNodes = await drv.describeUiNodes();
    fs.writeFileSync(path.join(outDir, "before-cell-nodes.json"), JSON.stringify(beforeNodes, null, 2));
    let sampleTarget;
    try {
      sampleTarget = findPhotoCellTarget(after);
    } catch (error) {
      writeVerdict("fail", `photo cell selection precondition failed: ${String(error)}`);
      process.exit(1);
    }
    const aliveBeforeCellTap = processAlive();
    // Bind the target cell in the STRUCTURED before-snapshot by EXACT
    // label+bounds and require uniqueness BEFORE issuing the tap (XiShi
    // static review): the smallest node containing the tap point must never
    // impersonate the target cell — a nested image/checkmark child may sit
    // inside it. Anything but exactly one exact candidate fails the sampling
    // precondition fail-closed.
    const exactBefore = exactCellCandidates(beforeNodes, sampleTarget);
    if (exactBefore.length !== 1) {
      writeVerdict("fail", `target cell identity precondition failed: expected exactly one structured node with exact label+bounds for "${sampleTarget.label}" ${JSON.stringify(sampleTarget.bounds)}, found ${exactBefore.length}`);
      process.exit(1);
    }
    const targetCellBeforeNode = exactBefore[0];
    rec(`cell-sampling: single tap on ${sampleTarget.variant} top-left photo cell at [${sampleTarget.x},${sampleTarget.y}] (alive before: ${aliveBeforeCellTap})`);
    await drv.tap(sampleTarget.x, sampleTarget.y);
    await new Promise((r) => setTimeout(r, 3000));
    const aliveAfterCellTap = processAlive();
    const afterNodes = await drv.describeUiNodes();
    fs.writeFileSync(path.join(outDir, "after-cell-nodes.json"), JSON.stringify(afterNodes, null, 2));
    const afterCellText = redactEditableValues(await drv.dumpUi());
    fs.writeFileSync(path.join(outDir, "after-cell-dump.txt"), afterCellText);
    await shot("step-04-after-cell.png");

    const nodeState = (n) => (n ? {
      identifier: n.identifier, type: n.type, label: n.label,
      bounds: n.bounds, enabled: n.enabled, selected: n.selected,
    } : null);
    const containsTap = (n) => n.bounds[0] <= sampleTarget.x && sampleTarget.x <= n.bounds[2]
      && n.bounds[1] <= sampleTarget.y && sampleTarget.y <= n.bounds[3];
    // After-tap tracking: a single filter over the raw after-nodes (never a
    // signature-keyed Map — that would fold duplicated nodes back into one
    // and bypass the ambiguity gate). A node qualifies by the exact
    // before-signature OR exact bounds (the union covers an anonymous-cell
    // label flip). Zero candidates means the cell is gone; MORE THAN ONE is
    // recorded as an explicit ambiguity — never a silent pick.
    const trackingPool = targetTrackingCandidates(afterNodes, targetCellBeforeNode);
    const targetTrackingAmbiguous = trackingPool.length > 1;
    const targetCellAfterNode = trackingPool.length === 1 ? trackingPool[0] : null;
    const doneNodes = (list) => list
      .filter((n) => n.label.trim().toLowerCase() === "done")
      .map(nodeState);

    fs.writeFileSync(path.join(outDir, "selection-sample.json"), JSON.stringify({
      cellTap: 1,
      aliveBeforeCellTap,
      aliveAfterCellTap,
      cellTarget: sampleTarget,
      pickerVariant: sampleTarget.variant,
      targetCellBefore: nodeState(targetCellBeforeNode),
      targetCellAfter: nodeState(targetCellAfterNode),
      targetTrackingAmbiguous,
      targetTrackingCandidates: trackingPool.map(nodeState),
      nodesAtTapBefore: beforeNodes.filter(containsTap).map(nodeState),
      nodesAtTapAfter: afterNodes.filter(containsTap).map(nodeState),
      doneNodesBefore: doneNodes(beforeNodes),
      doneNodesAfter: doneNodes(afterNodes),
      stateDiff: diffUiNodeStates(beforeNodes, afterNodes),
      evidenceRefs: [
        "selection-sample.json",
        "before-cell-nodes.json", "after-cell-nodes.json",
        "before-cell-dump.txt", "after-cell-dump.txt",
        "step-04-after-cell.png",
      ],
    }, null, 2));
    writeVerdict("sample", `single cell tap issued on proven ${sampleTarget.variant} picker at [${sampleTarget.x},${sampleTarget.y}] — cell-selection sampling ends here by contract (no Done tap, no preview judgment, no chain verdict)`);
    process.exit(0);
  }

  // ── Done-transition discovery sampling (task #26) ─────────────────────
  // This phase first performs the already-proven single cell selection, then
  // requires the independent STRUCTURED ios26-selected contract before it
  // derives the unique Add/Done target. The exact Done tap is the only new
  // action. The phase stops immediately after collecting post-transition
  // facts; it never calls composerHasNewPreview, sends, or reads back.
  if (doneSampling) {
    if (!plainChannelNodes) {
      writeVerdict("fail", "Done sampling invariant failed: missing pre-picker plain-channel structured snapshot");
      process.exit(1);
    }

    const beforeCellText = redactEditableValues(after);
    fs.writeFileSync(path.join(outDir, "before-done-cell-dump.txt"), beforeCellText);
    const beforeCellNodes = await drv.describeUiNodes();
    fs.writeFileSync(path.join(outDir, "before-done-cell-nodes.json"), JSON.stringify(beforeCellNodes, null, 2));

    let cellTarget;
    try {
      cellTarget = findPhotoCellTarget(after);
    } catch (error) {
      writeVerdict("fail", `Done sampling photo-cell precondition failed: ${String(error)}`);
      process.exit(1);
    }
    const exactBefore = exactCellCandidates(beforeCellNodes, cellTarget);
    if (exactBefore.length !== 1) {
      writeVerdict("fail", `Done sampling cell identity failed: expected exactly one structured node with exact label+bounds for "${cellTarget.label}" ${JSON.stringify(cellTarget.bounds)}, found ${exactBefore.length}`);
      process.exit(1);
    }

    const aliveBeforeCellTap = processAlive();
    rec(`done-sampling: single cell tap on proven ${cellTarget.variant} target at [${cellTarget.x},${cellTarget.y}] (alive before: ${aliveBeforeCellTap})`);
    await drv.tap(cellTarget.x, cellTarget.y);
    await new Promise((r) => setTimeout(r, 3000));

    const aliveAfterCellTap = processAlive();
    const selectedNodes = await drv.describeUiNodes();
    fs.writeFileSync(path.join(outDir, "selected-picker-nodes.json"), JSON.stringify(selectedNodes, null, 2));
    const selectedText = redactEditableValues(await drv.dumpUi());
    fs.writeFileSync(path.join(outDir, "selected-picker-dump.txt"), selectedText);
    await shot("step-04-selected-picker.png");

    const selectedPicker = classifySelectedPhotoPicker(selectedNodes);
    if (!selectedPicker) {
      writeVerdict("fail", "Done sampling selected-picker precondition failed: need original top chrome + exactly one selected Photo image + exactly one enabled Add/Done top button + bottom 1 Photo button");
      process.exit(1);
    }

    let doneTarget;
    try {
      doneTarget = findSelectedPhotoDoneTarget(selectedNodes);
    } catch (error) {
      writeVerdict("fail", `Done sampling target resolution failed: ${String(error)}`);
      process.exit(1);
    }
    // Bind the returned target back to the same STRUCTURED snapshot by exact
    // identity + label + bounds before issuing the tap. This makes the tap
    // auditable and fail-closed even if a later matcher refactor accidentally
    // returns a coordinate whose source node is missing or duplicated.
    const exactDoneCandidates = selectedNodes.filter((n) => (
      n.identifier === doneTarget.identifier
      && n.type === "button"
      && n.label === doneTarget.label
      && n.enabled === true
      && Array.isArray(n.bounds)
      && n.bounds.length === 4
      && n.bounds.every((v, i) => v === doneTarget.bounds[i])
    ));
    if (exactDoneCandidates.length !== 1) {
      writeVerdict("fail", `Done sampling exact target binding failed: expected 1 Add/Done label+bounds candidate, found ${exactDoneCandidates.length}`);
      process.exit(1);
    }
    const containsDoneTap = (n) => Array.isArray(n.bounds) && n.bounds.length === 4
      && n.bounds[0] <= doneTarget.x && doneTarget.x <= n.bounds[2]
      && n.bounds[1] <= doneTarget.y && doneTarget.y <= n.bounds[3];
    const nodesAtTapBefore = selectedNodes.filter(containsDoneTap);
    rec(`done-sampling: exactly-one structured Add/Done tap at [${doneTarget.x},${doneTarget.y}] from ${JSON.stringify(doneTarget.bounds)}`);
    const aliveBeforeDoneTap = processAlive();
    await drv.tap(doneTarget.x, doneTarget.y);
    await new Promise((r) => setTimeout(r, 4000));

    const aliveAfterDoneTap = processAlive();
    const afterDoneNodes = await drv.describeUiNodes();
    fs.writeFileSync(path.join(outDir, "after-done-nodes.json"), JSON.stringify(afterDoneNodes, null, 2));
    const afterDoneText = redactEditableValues(await drv.dumpUi());
    fs.writeFileSync(path.join(outDir, "after-done-dump.txt"), afterDoneText);
    await shot("step-05-after-done.png");

    const nodeState = (n) => ({
      identifier: n.identifier, type: n.type, label: n.label, value: n.value,
      bounds: n.bounds, enabled: n.enabled, selected: n.selected,
    });
    const plainToAfterDoneDiff = diffUiNodeStates(plainChannelNodes, afterDoneNodes);
    const doneTrackingPool = targetTrackingCandidates(afterDoneNodes, selectedPicker.doneNode);
    const doneTargetTrackingAmbiguous = doneTrackingPool.length > 1;
    const doneTargetAfter = doneTrackingPool.length === 1 ? doneTrackingPool[0] : null;
    const addedSignatures = new Set(plainToAfterDoneDiff.added);
    const isImageOrAttachmentDiscoveryNode = (n) => (
      n.type === "image"
      || /image|photo|attachment/i.test(`${n.identifier ?? ""} ${n.label ?? ""}`)
    );
    const imageOrAttachmentNodesAfterDone = afterDoneNodes
      .filter(isImageOrAttachmentDiscoveryNode)
      .map(nodeState);
    const addedImageOrAttachmentNodes = afterDoneNodes
      .filter((n) => addedSignatures.has(uiNodeSignature(n)) && isImageOrAttachmentDiscoveryNode(n))
      .map(nodeState);

    fs.writeFileSync(path.join(outDir, "done-transition-sample.json"), JSON.stringify({
      cellTap: 1,
      doneTap: 1,
      cellTarget,
      doneTarget,
      selectedPickerVariant: selectedPicker.variant,
      selectedCell: nodeState(selectedPicker.selectedCell),
      selectedDoneNode: nodeState(selectedPicker.doneNode),
      exactDoneCandidateCount: exactDoneCandidates.length,
      nodesAtTapBefore: nodesAtTapBefore.map(nodeState),
      nodesAtTapAfter: afterDoneNodes.filter(containsDoneTap).map(nodeState),
      doneTargetAfter: doneTargetAfter ? nodeState(doneTargetAfter) : null,
      doneTargetTrackingAmbiguous,
      doneTargetTrackingCandidates: doneTrackingPool.map(nodeState),
      aliveBeforeCellTap,
      aliveAfterCellTap,
      aliveBeforeDoneTap,
      aliveAfterDoneTap,
      // Discovery facts only. Neither boolean is promoted to preview PASS.
      selectedPickerContractAfterDone: classifySelectedPhotoPicker(afterDoneNodes) !== null,
      composerPlaceholderVisibleAfterDone: afterDoneText.includes('"Message #all"'),
      plainToAfterDoneDiff,
      imageOrAttachmentNodesAfterDone,
      addedImageOrAttachmentNodes,
      evidenceRefs: [
        "done-transition-sample.json",
        "plain-channel-nodes.json", "channel-dump.txt", "step-01-channel.png",
        "before-done-cell-nodes.json", "before-done-cell-dump.txt",
        "selected-picker-nodes.json", "selected-picker-dump.txt", "step-04-selected-picker.png",
        "after-done-nodes.json", "after-done-dump.txt", "step-05-after-done.png",
      ],
    }, null, 2));
    writeVerdict("sample", `exactly one cell tap + exactly one structured Add/Done tap completed; Done-transition discovery ends here (no generic preview verdict, send, or readback)`);
    process.exit(0);
  }

  // Select the staged image (most recent photo = top-left grid cell; matcher
  // is variant-gated, tile-size-bounded, ambiguity fail-closed).
  let cellTarget;
  try {
    cellTarget = findPhotoCellTarget(after);
  } catch (error) {
    writeVerdict("fail", `photo cell selection precondition failed: ${String(error)}`);
    process.exit(1);
  }
  rec(`selecting first photo cell at [${cellTarget.x},${cellTarget.y}]`);
  await drv.tap(cellTarget.x, cellTarget.y);
  await new Promise((r) => setTimeout(r, 4000));
  await shot("step-04-selected.png");

  // Preview proof: a NEW node inside the composer bounds that the plain
  // composer (pre-tap `dump`) did not contain. pickerClosed alone is NOT a
  // preview (that is exactly the no-attachment path we must catch).
  const afterSelect = await drv.dumpUi();
  fs.writeFileSync(path.join(outDir, "after-select-dump.txt"), redactEditableValues(afterSelect));
  if (!composerHasNewPreview(dump, afterSelect)) {
    writeVerdict("fail", "photo selected but no NEW preview/attachment node inside composer bounds — possible no-attachment product path");
    process.exit(1);
  }
  rec("composer preview proven (new node inside composer bounds)");
  await shot("step-05-preview.png");

  // Readback baseline: image nodes already in the thread before send.
  const imagesBefore = countThreadImageNodes(dump);
  rec(`thread image nodes before send: ${imagesBefore}`);

  // Send via the keyboard send/return key — the composer send button is a
  // known Kuikly icon-only control; the keyboard path is the deterministic
  // workaround (documented in the QA playbook).
  await drv.key("enter");
  rec("send issued via keyboard return");
  await new Promise((r) => setTimeout(r, 6000));

  // Readback: thread image-node count must INCREASE by exactly one, and the
  // composer returns to its placeholder.
  const readback = await drv.dumpUi();
  fs.writeFileSync(path.join(outDir, "readback-dump.txt"), redactEditableValues(readback));
  const composerBack = readback.includes('"Message #all"');
  const imagesAfter = countThreadImageNodes(readback);
  await shot("step-06-readback.png");
  rec(`thread image nodes after send: ${imagesAfter} (composer back: ${composerBack})`);
  if (composerBack && imagesAfter === imagesBefore + 1) {
    writeVerdict("pass", `full chain verified: resolver tap → real PhotosUI picker → photo cell select → new composer preview → keyboard send → image message read back in #all (thread images ${imagesBefore}→${imagesAfter})`);
    process.exit(0);
  }
  writeVerdict("fail", `readback incomplete: composerBack=${composerBack} threadImages ${imagesBefore}→${imagesAfter} (expected +1)`);
  process.exit(1);
} catch (e) {
  writeVerdict("error", `harness/script error: ${String(e).slice(0, 300)}`);
  process.exit(2);
}
