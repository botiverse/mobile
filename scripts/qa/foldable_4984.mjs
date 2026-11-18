#!/usr/bin/env node
// QA-only driver for botiverse/mobile PR #4984 (app shell composed once, outside Navigation 3) on a
// foldable emulator. Deterministic adb steps, no model. Records observations per QA step (screenshots,
// UI dumps, screenrecords, logcat markers) into <out>/foldable; it does not judge visuals.
// Never merged: lives only on the qa/4984-foldable branch.
//
// Usage: node scripts/qa/foldable_4984.mjs --package build.raft.app --out qa-evidence
import { execFileSync, spawn } from "node:child_process";
import fs from "node:fs";
import path from "node:path";
import { loginLike, parseUiNodes } from "./login_phase.mjs";

const argv = process.argv.slice(2);
const arg = (f, d) => { const i = argv.indexOf(f); return i >= 0 ? argv[i + 1] : d; };
const PKG = arg("--package", "build.raft.app");
const OUT = path.join(arg("--out", "qa-evidence"), "foldable");
const ITER = Number(arg("--iterations", "20"));
fs.mkdirSync(OUT, { recursive: true });

const results = {};
const t0 = Date.now();
const log = (...a) => { const line = `[${((Date.now() - t0) / 1000).toFixed(1)}s] ${a.join(" ")}`; console.log(line); fs.appendFileSync(path.join(OUT, "driver.log"), line + "\n"); };
const sleep = (ms) => execFileSync("sleep", [String(ms / 1000)]);
function adb(args, o = {}) {
  try { return execFileSync("adb", args, { encoding: "utf8", maxBuffer: 64 << 20, timeout: o.timeout ?? 60000 }); }
  catch (e) { if (o.throws) throw e; return `ERR ${e.message.split("\n")[0]} ${e.stdout ?? ""}`; }
}
const sh = (cmd, o) => adb(["shell", cmd], o);
const emu = (cmd) => {
  const r = adb(["emu", ...cmd.split(" ")]); log(`emu ${cmd} -> ${r.trim().replace(/\s+/g, " ").slice(0, 160)}`);
  // A fold may sleep/lock the device ("continue apps on fold"): wake it and dismiss the keyguard.
  if (/fold|posture|hinge/.test(cmd)) { sleep(600); sh("input keyevent 224"); sh("wm dismiss-keyguard"); }
  return r;
};
const note = (step, key, value) => { (results[step] ??= {})[key] = value; save(); };
const save = () => fs.writeFileSync(path.join(OUT, "results.json"), JSON.stringify(results, null, 1));

// ---- screen + ui ----
let shotN = 0;
// pixel_fold has two physical displays; screencap/screenrecord need the active one's id.
function displayIds() { return [...sh("dumpsys SurfaceFlinger --display-id").matchAll(/Display (\d+)/g)].map((m) => m[1]); }
function pngSize(buf) { return buf.length > 24 ? [buf.readUInt32BE(16), buf.readUInt32BE(20)] : [0, 0]; }
function activeDisplay() {
  const [W, H] = size(); let best = null;
  for (const id of displayIds()) {
    try { const b = execFileSync("adb", ["exec-out", "screencap", "-p", "-d", id], { maxBuffer: 64 << 20, timeout: 30000 }); const [w, h] = pngSize(b);
      if ((w === W && h === H) || (w === H && h === W)) return { id, b }; if (!best && w > 0) best = { id, b }; } catch {}
  }
  return best;
}
function shot(name) {
  const f = path.join(OUT, `${String(++shotN).padStart(3, "0")}-${name}.png`);
  const a = activeDisplay(); if (a) fs.writeFileSync(f, a.b); else log("shot failed", name);
  return path.basename(f);
}
// Saved dumps keep only this app's nodes, and nothing from a login-like screen (the evidence gate
// withholds everything if a credential shows up in any file).
function saveDump(name, xml) {
  if (loginLike(parseUiNodes(xml))) { log("dump not saved (login-like):", name); return; }
  const keep = [...xml.matchAll(/<node[^>]*>/g)].map((m) => m[0]).filter((t) => t.includes(`package="${PKG}"`));
  fs.writeFileSync(path.join(OUT, `${String(shotN).padStart(3, "0")}-${name}.app-nodes.xml`), keep.join("\n"));
}
function nodes(saveAs) {
  for (let i = 0; i < 3; i++) {
    const r = sh("uiautomator dump /data/local/tmp/qa_dump.xml >/dev/null 2>&1; cat /data/local/tmp/qa_dump.xml", { timeout: 40000 });
    if (r.includes("<hierarchy")) {
      if (saveAs) saveDump(saveAs, r);
      return [...r.matchAll(/<node[^>]*>/g)].map((m) => {
        const t = m[0]; const g = (k) => new RegExp(`${k}="([^"]*)"`).exec(t)?.[1] ?? "";
        const b = /bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/.exec(t)?.slice(1).map(Number) ?? [0, 0, 0, 0];
        return { text: g("text"), desc: g("content-desc"), id: g("resource-id"), cls: g("class"), pkg: g("package"),
          clickable: g("clickable") === "true", selected: g("selected") === "true", focused: g("focused") === "true",
          b, x: Math.round((b[0] + b[2]) / 2), y: Math.round((b[1] + b[3]) / 2), w: b[2] - b[0], h: b[3] - b[1] };
      });
    }
    sleep(800);
  }
  log("uiautomator dump failed");
  return [];
}
const label = (n) => `${n.text || n.desc || n.id}`;
function find(ns, re, extra = () => true) { return ns.find((n) => (re.test(n.text) || re.test(n.desc) || re.test(n.id)) && n.w > 0 && n.h > 0 && extra(n)); }
const tap = (x, y) => sh(`input tap ${x} ${y}`);
const back = () => sh("input keyevent 4");

// ---- device facts ----
function size() { const m = /(?:Override|Physical) size: (\d+)x(\d+)/g; const s = sh("wm size"); let last; for (const x of s.matchAll(m)) last = x; return last ? [Number(last[1]), Number(last[2])] : [1080, 2400]; }
function pid() { return sh(`pidof ${PKG}`).trim(); }
function foreground() { const r = sh("dumpsys activity activities | grep -E 'topResumedActivity|mResumedActivity' | head -2"); return r.includes(PKG); }
let logMark = 0; // lines of driver logcat consumed
function logcat() { return sh("logcat -d -v threadtime", { timeout: 60000 }); }
function newLog() { const all = logcat().split("\n"); const fresh = all.slice(logMark); logMark = all.length; return fresh; }
const CRASH = /was used multiple times|not found in RectList|FATAL EXCEPTION|AndroidRuntime: Process|ANR in /;
const crashes = []; // accumulated crash lines
function checkCrash(step) {
  const fresh = newLog();
  const hits = fresh.filter((l) => CRASH.test(l));
  const opens = fresh.filter((l) => /nav3_stack (open|close) page=/.test(l)).map((l) => l.replace(/^.*nav3_stack /, "").slice(0, 120));
  if (hits.length) { crashes.push({ step, lines: hits.slice(0, 20) }); log(`CRASH in ${step}:`, hits[0]); fs.appendFileSync(path.join(OUT, "crash-lines.txt"), `## ${step}\n${fresh.filter((l) => /AndroidRuntime|was used multiple|FATAL|Exception|\tat /.test(l)).join("\n")}\n`); }
  return { crashLines: hits.length, opens, fresh };
}
const stackOps = (fresh) => fresh.filter((l) => /nav3_stack (open|close) page=/.test(l));

// screenrecord segments (background on device)
let recN = 0;
function recStart(name, secs = 60) {
  const f = `/data/local/tmp/fold-rec-${String(++recN).padStart(2, "0")}-${name}.mp4`;
  const d = activeDisplay(); const disp = d ? `--display-id ${d.id} ` : "";
  const p = spawn("adb", ["shell", `screenrecord ${disp}--bit-rate 8000000 --time-limit ${secs} ${f}`], { stdio: "ignore" });
  sleep(1200);
  return { f, p };
}
function recStop(r) { sh("pkill -INT screenrecord"); sleep(1500); try { r.p.kill(); } catch {} adb(["pull", r.f, OUT]); sh(`rm -f ${r.f}`); log("recording", path.basename(r.f)); }

// ---- app navigation helpers ----
function launch() { sh(`monkey -p ${PKG} -c android.intent.category.LAUNCHER 1`); sleep(4000); }
// The server picker ("Choose server") can show "Loading your servers..." first: wait for the row.
function pickServerIfAsked(ns) {
  for (let i = 0; i < 15; i++) {
    if (!find(ns, /^Choose server$/)) return i > 0;
    const s = find(ns, /^OranixSlockTest$/);
    if (s) { log("server picker: tapping OranixSlockTest"); tap(s.x, s.y); sleep(5000); ns = nodes(); continue; }
    sleep(2000); ns = nodes();
  }
  log("server picker still shown"); return false;
}
// Wide layout: an icon rail whose items carry content-desc (Chat, Activity, Tasks, Artifacts, Members, ...).
// Narrow layout: bottom tabs (Home/Chat, Activity, ..., More).
const TAB_ALIASES = { Home: "(Home|Chat)", Chat: "(Chat|Home)" };
function tabNode(ns, name) { const re = new RegExp(`^${TAB_ALIASES[name] || name}$`); return ns.find((n) => (re.test(n.text) || re.test(n.desc)) && n.w > 0 && n.h > 0 && n.w < 400); }
function gotoTab(name) {
  let ns = nodes(); let t = tabNode(ns, name);
  if (!t) { const more = tabNode(ns, "More"); if (more) { tap(more.x, more.y); sleep(1800); ns = nodes(); t = find(ns, new RegExp(`^${TAB_ALIASES[name] || name}$`)); } }
  if (!t) { log("tab not found", name); return false; }
  tap(t.x, t.y); sleep(2000); return true;
}
function hideIme() { if (/mInputShown=true/.test(sh("dumpsys input_method | grep mInputShown"))) { sh("input keyevent 111"); sleep(600); if (/mInputShown=true/.test(sh("dumpsys input_method | grep mInputShown"))) { back(); sleep(600); } } }
function channelRows(ns) { const [W] = size(); return ns.filter((n) => n.text && n.w > 0 && n.h > 30 && n.b[0] > 150 && n.b[2] < W * 0.4 && n.y > 600 && !/^(Pinned|Joint Channels|Channels|Saved|Drafts|Direct Messages|\d+)$/.test(n.text)); }
function openChannel() {
  gotoTab("Chat"); hideIme();
  let ns = nodes("home");
  const [W] = size();
  const row = ns.find((n) => /^#?\s?all$/i.test(n.text) && n.x < W * 0.45 && n.w > 0) || channelRows(ns)[0];
  if (!row) { log("no channel row"); return null; }
  log("opening channel", label(row)); tap(row.x, row.y); sleep(3500); hideIme();
  return label(row);
}
// Push target for steps 2-10: an Artifact card on the Artifacts rail page opens the Artifact viewer
// (Hands 0ac26a92 path). Returns centre coordinates after navigating there.
let TARGET_KIND = "artifact";
function retarget() { return TARGET_KIND === "image" ? findFixtureImage(3) : findTarget(); }
function findTarget() {
  if (TARGET_KIND === "chip") { const c = composerChip(); if (c) return c; }
  for (let i = 0; i < 3 && !gotoTab("Artifacts"); i++) { shot(`artifacts-tab-missing-${i}`); nodes(`artifacts-tab-missing-${i}`); if (!foreground()) launch(); else back(); sleep(1500); hideIme(); }
  sleep(1500); hideIme();
  const ns = nodes("artifacts");
  const card = ns.find((n) => /, v\d+, /.test(n.desc) && n.w > 100 && n.h > 100);
  if (!card) { log("no artifact card"); return null; }
  // tap the upper part of the card (its preview), staying inside the visible area
  const [, H] = size();
  return { x: card.x, y: Math.min(card.b[1] + 150, Math.round((card.b[1] + Math.min(card.b[3], H)) / 2)), kind: "artifact", label: card.desc.split(",")[0] };
}
function composerChip() {
  const ns = nodes("composer-chip-search"); const [W, H] = size();
  const send = find(ns, /^Send$/); if (!send) return null;
  const KNOWN = /^(Add image|Take photo|Attach file|Send|Remove.*|Message .*)$/;
  const c = ns.find((n) => n.w > 40 && n.h > 40 && n.w < W * 0.3 && n.b[3] <= send.b[1] && n.b[1] > send.b[1] - 450 && n.x > W * 0.3 && !KNOWN.test(n.desc + n.text) && (/image|png|photo|qa-fixture|preview/i.test(n.desc + n.text) || /Image/.test(n.cls)));
  return c ? { x: c.x, y: c.y, kind: "chip", label: label(c) } : null;
}
// The seeded fixture message ([qa-fixture-image]) carries an image; find a tappable image near it.
function findFixtureImage(maxScrolls = 12) {
  const [W, H] = size();
  for (let i = 0; i <= maxScrolls; i++) {
    const ns = nodes(i === 0 ? "conversation" : undefined);
    const marker = ns.find((n) => /qa-fixture-image/.test(n.text + n.desc));
    const img = ns.find((n) => /qa-fixture-image\.png|^Image|image/i.test(n.desc) && !/^(Add|Attach|Insert)\b/i.test(n.desc) && n.y < H * 0.85 && n.w > 40 && n.h > 40 && n.x > W * 0.3);
    if (marker || img) {
      // Prefer an image node whose bounds sit below/near the marker text.
      const near = marker ? ns.filter((n) => n.w > 60 && n.h > 60 && n.w < W * 0.7 && n.b[1] >= marker.b[1] - 20 && n.b[1] < marker.b[3] + 500 && n.x > marker.b[0] - 50 && /image|png|photo/i.test(n.desc + n.cls + n.text) && !/^(Add|Attach|Insert)\b/i.test(n.desc)) : [];
      const target = near[0] || img;
      if (target) { log("fixture image at", target.x, target.y, label(target), target.cls); return target; }
      if (marker) {
        // Fall back to the area just below the marker text (image renders under the caption or above it).
        log("marker found without image node; candidates dumped"); nodes("marker-no-image");
        return { x: marker.x, y: Math.min(marker.b[3] + 120, H - 300), guess: true };
      }
    }
    // scroll the conversation (right pane on wide; whole screen on narrow) towards older messages
    const x = W > H * 0.9 ? Math.round(W * 0.7) : Math.round(W * 0.5);
    sh(`input swipe ${x} ${Math.round(H * 0.35)} ${x} ${Math.round(H * 0.8)} 250`); sleep(900);
  }
  return null;
}
// Open the image preview (a page pushed over the shell) and return whether a stack open was logged.
function openPreview(target, settleMs = 1800) {
  newLog();
  tap(target.x, target.y); sleep(settleMs);
  const f = newLog(); const ops = stackOps(f);
  if (f.some((l) => CRASH.test(l))) crashes.push({ step: "openPreview", lines: f.filter((l) => CRASH.test(l)) });
  return ops;
}
function topIsPage() { const ns = nodes(); return { ns, preview: !!find(ns, /Image preview|Loading image|Open with|Save to Photos|Close|Back/) && !tabNode(ns, "Home") }; }

// ---- gestures ----
function edgeDrag(points, holdMs = 0) {
  // input motionevent: DOWN/MOVE/UP (API 33+); one adb call per event keeps it simple.
  const [W, H] = size(); const y = Math.round(H / 2);
  sh(`input motionevent DOWN 1 ${y}`);
  for (const fx of points) { sh(`input motionevent MOVE ${Math.max(1, Math.round(W * fx))} ${y}`); }
  if (holdMs) sleep(holdMs);
}
const edgeUp = (fx) => { const [W, H] = size(); sh(`input motionevent UP ${Math.max(1, Math.round(W * fx))} ${Math.round(H / 2)}`); };

// ================= RUN =================
function main() {
  log("device", sh("getprop ro.product.model").trim(), "sdk", sh("getprop ro.build.version.sdk").trim(), "pkg", PKG);
  note("env", "model", sh("getprop ro.product.model").trim());
  note("env", "sdk", sh("getprop ro.build.version.sdk").trim());
  // Real animations (the workflow's emulator-runner zeroes them).
  sh("settings put system fold_lock_behavior_setting stay_awake_on_fold_key"); sh("svc power stayon true");
  for (const k of ["window_animation_scale", "transition_animation_scale", "animator_duration_scale"]) sh(`settings put global ${k} 1`);
  note("env", "gesture_nav", sh("cmd overlay enable-exclusive --category com.android.internal.systemui.navbar.gestural; cmd overlay list | grep -i navbar").trim());
  note("env", "wm_size_initial", sh("wm size").trim());
  emu("unfold"); sleep(3000);
  note("env", "wm_size_unfolded", sh("wm size").trim());
  note("env", "display_unfolded", sh("dumpsys display | grep -E 'mBaseDisplayInfo|DisplayDeviceInfo' | head -4").trim().slice(0, 1200));
  sh("settings put system accelerometer_rotation 0"); sh("settings put system user_rotation 0");

  // Relaunch to see the flag decision line.
  sh("logcat -G 16M"); sh(`am force-stop ${PKG}`); sh("logcat -c"); logMark = 0;
  launch(); sleep(4000);
  let ns = nodes("launch"); pickServerIfAsked(ns); hideIme(); ns = nodes("home0");
  const boot = newLog();
  const flagLine = boot.find((l) => /nav3_stack_on/.test(l));
  note("env", "nav3_flag_line", flagLine ? flagLine.replace(/^.*nav3_stack_on/, "nav3_stack_on") : "MISSING (flag OFF or not logged)");
  note("env", "pid", pid());
  shot("home-unfolded");
  const [W, H] = size(); note("env", "size_now", `${W}x${H}`);

  // ---- Separate scenario: Members list (mobile #4991, Hands 52b93946) ----
  try { membersScenario(); } catch (e) { log("members scenario error", e.stack); note("members", "driver_error", String(e.stack).slice(0, 1500)); }
  // Reset to a clean unfolded shell for the #4984 steps.
  emu("unfold"); sh("settings put system user_rotation 0"); sleep(2000);
  sh(`am force-stop ${PKG}`); launch(); sleep(3000); pickServerIfAsked(nodes()); hideIme(); newLog();

  // ---- Step 1a: image preview from the seeded image message, unfolded, 20x incl. double tap ----
  const ch = openChannel(); note("1", "channel", ch); shot("conversation-unfolded");
  const img = findFixtureImage(3); note("1", "fixture_image", img ? `${img.x},${img.y}${img.guess ? " (guess)" : ""}` : "NOT FOUND");
  if (img) {
    shot("before-preview");
    const ops = openPreview(img, 2500); note("1", "first_open_ops", ops.map((l) => l.replace(/^.*nav3_stack /, "")));
    shot("preview-open-unfolded"); nodes("preview-open");
    back(); sleep(1500); shot("after-first-pop");
    const r = recStart("step1-rapid", 170);
    let opened = 0, closed = 0;
    newLog();
    for (let i = 0; i < ITER; i++) {
      if (i % 4 === 1) sh(`input tap ${img.x} ${img.y} & input tap ${img.x} ${img.y}; wait`); // double tap
      else tap(img.x, img.y);
      sleep(i % 3 === 0 ? 150 : 700); // some pops land mid-push
      back(); sleep(i % 2 ? 400 : 900);
      // a double tap can leave a second page / or the back may have closed nothing
    }
    sleep(1500); back(); sleep(800);
    const f = newLog(); for (const l of f) { if (/nav3_stack open page=/.test(l)) opened++; if (/nav3_stack close page=/.test(l)) closed++; }
    recStop(r);
    note("1", "rapid_iterations", ITER); note("1", "rapid_opens_logged", opened); note("1", "rapid_closes_logged", closed);
    const hits = f.filter((l) => CRASH.test(l)); if (hits.length) crashes.push({ step: "1-rapid", lines: hits });
    note("1", "rapid_crash_lines", hits.slice(0, 10)); note("1", "pid_after", pid()); note("1", "foreground_after", foreground());
    shot("after-rapid");
    if (!foreground()) { launch(); }
  }

  // ---- Step 1b: Artifact viewer from the Artifacts rail page (Activity rows open conversations in place) ----
  {
    const t = findTarget(); shot("artifacts-page");
    note("1", "artifact_card", t ? t.label : "NOT FOUND");
    if (t) {
      let o = 0, c = 0; newLog();
      for (let i = 0; i < ITER; i++) { if (i % 5 === 2) sh(`input tap ${t.x} ${t.y} & input tap ${t.x} ${t.y}; wait`); else tap(t.x, t.y); sleep(i === 0 ? 3000 : (i % 3 === 0 ? 200 : 900)); if (i === 0) { shot("artifact-opened"); nodes("artifact-opened"); } back(); sleep(800); }
      sleep(1500); const ns2 = nodes(); if (!find(ns2, /^Artifacts$/)) { back(); sleep(1200); }
      const f = newLog(); for (const l of f) { if (/nav3_stack open page=/.test(l)) o++; if (/nav3_stack close page=/.test(l)) c++; }
      note("1", "artifact_pages_opened", [...new Set(f.filter((l) => /nav3_stack open page=/.test(l)).map((l) => l.replace(/^.*nav3_stack open page=(\S+).*$/, "$1")))]);
      note("1", "artifact_opens", o); note("1", "artifact_closes", c);
      const hits = f.filter((l) => CRASH.test(l)); if (hits.length) crashes.push({ step: "1-artifact", lines: hits });
      note("1", "artifact_crash_lines", hits.slice(0, 10)); note("1", "artifact_foreground_after", foreground());
      shot("artifact-after-rapid");
      if (!foreground()) launch();
    }
    openChannel();
  }

  // ---- Step 1c: composer attach photo -> preview of the attachment ----
  {
    let cn = nodes("composer-search");
    const attach = find(cn, /^Add image$/) || find(cn, /attach|add (file|photo|attachment)|^add$|plus|more options/i, (n) => n.y > H * 0.6);
    note("1", "composer_attach_button", attach ? label(attach) : "NOT FOUND");
    if (attach) {
      tap(attach.x, attach.y); sleep(2000); let an = nodes("attach-menu"); shot("attach-menu");
      const inPicker = an.some((n) => n.pkg && n.pkg !== PKG && /media|photopicker|documentsui|gallery|photos/i.test(n.pkg));
      const photo = inPicker ? { x: -1, y: -1, text: "(system picker opened directly)" } : (find(an, /^(Photos?|Gallery|Photo library|Images?|Choose photo|Photos & videos|Library)$/i) || find(an, /photo|gallery|image/i));
      note("1", "attach_menu_photo_entry", photo ? label(photo) : "NOT FOUND");
      if (photo) {
        if (photo.x >= 0) { tap(photo.x, photo.y); sleep(3500); } let pn = nodes("photo-picker"); shot("photo-picker");
        const pic = pn.find((n) => /Photo taken|qa-fixture|Image|photo/i.test(n.desc) && n.w > 80) || pn.find((n) => n.pkg && !n.pkg.includes(PKG) && n.clickable && n.w > 150 && n.w < W / 2 && n.y > H * 0.2);
        note("1", "picker_item", pic ? `${label(pic)} pkg=${pic.pkg}` : "NOT FOUND");
        if (pic) {
          tap(pic.x, pic.y); sleep(1500); let dn = nodes("picker-after-select");
          const done = find(dn, /^(Done|Add|Select|Add \(1\)|Open)$/i); if (done) { tap(done.x, done.y); }
          sleep(3500); let cn2 = nodes("composer-with-attachment"); shot("composer-with-attachment");
          const chip = composerChip();
          note("1", "composer_chip", chip ? label(chip) : "NOT FOUND");
          if (chip) {
            TARGET_KIND = "chip";
            let o = 0; newLog();
            for (let i = 0; i < ITER; i++) { if (i % 4 === 1) sh(`input tap ${chip.x} ${chip.y} & input tap ${chip.x} ${chip.y}; wait`); else tap(chip.x, chip.y); sleep(i === 0 ? 2500 : (i % 3 === 0 ? 150 : 800)); if (i === 0) { shot("composer-preview-open"); nodes("composer-preview-open"); } back(); sleep(700); }
            const f = newLog(); for (const l of f) if (/nav3_stack open page=/.test(l)) o++;
            note("1", "composer_preview_pages", f.filter((l) => /nav3_stack open page=/.test(l)).slice(0, 2).map((l) => l.replace(/^.*nav3_stack /, "").slice(0, 160)));
            note("1", "composer_preview_opens", o);
            const hits = f.filter((l) => CRASH.test(l)); if (hits.length) crashes.push({ step: "1-composer", lines: hits });
            note("1", "composer_crash_lines", hits.slice(0, 10)); note("1", "composer_foreground_after", foreground());
            shot("composer-after-rapid"); if (!foreground()) launch();
          }
        } else { back(); sleep(1000); }
      } else { back(); sleep(1000); }
    }
  }
  checkCrash("1-end");

  // Steps 2-10 push the composer attachment preview when it worked, else the Artifact viewer.
  if (img) TARGET_KIND = "image";
  const img2 = (TARGET_KIND === "image" ? findFixtureImage(3) || img : findTarget());
  note("env", "push_target", img2 ? `${TARGET_KIND} ${img2.label || ""}` : "NONE");
  if (!img2) { log("no push target"); finish(); return; }

  // ---- Step 2: recordings of push/pop, unfolded and folded ----
  {
    const r = recStart("step2-unfolded-pushpop", 40);
    for (let i = 0; i < 3; i++) { tap(img2.x, img2.y); sleep(2500); back(); sleep(2500); }
    recStop(r);
    note("2", "unfolded_recording", path.basename(r.f));
  }

  // ---- Step 3: predictive back (needs API 36 for a targetSdk 36 app without the manifest opt-in) ----
  {
    newLog(); tap(img2.x, img2.y); sleep(2500); shot("pb-page-on-top");
    const r = recStart("step3-predictive-back", 60);
    // Drag and hold mid-way -> screenshot (shell should show under the dragged page).
    edgeDrag([0.02, 0.06, 0.1, 0.15, 0.2, 0.25, 0.3], 400); shot("pb-mid-drag-1"); sleep(300); shot("pb-mid-drag-2");
    // Cancel: back to the edge and release.
    for (const fx of [0.25, 0.15, 0.08, 0.02]) sh(`input motionevent MOVE ${Math.max(1, Math.round(W * fx))} ${Math.round(H / 2)}`);
    edgeUp(0.0); sleep(1500); shot("pb-after-cancel");
    let f = newLog(); note("3", "after_cancel_stack_ops", stackOps(f).map((l) => l.replace(/^.*nav3_stack /, "")));
    let tn = nodes("pb-after-cancel"); note("3", "after_cancel_home_tab_visible", !!tabNode(tn, "Home"));
    // Page interactive after cancel: a second key back must close it.
    // Commit: drag far and release.
    edgeDrag([0.02, 0.1, 0.2, 0.3, 0.4, 0.5]); edgeUp(0.55); sleep(1800); shot("pb-after-commit");
    f = newLog(); note("3", "after_commit_stack_ops", stackOps(f).map((l) => l.replace(/^.*nav3_stack /, "")));
    tn = nodes("pb-after-commit"); note("3", "after_commit_home_tab_visible", !!tabNode(tn, "Home"));
    recStop(r); note("3", "recording", path.basename(r.f));
    if (f.some((l) => CRASH.test(l))) crashes.push({ step: "3", lines: f.filter((l) => CRASH.test(l)) });
    // If the cancel actually popped (no predictive support), make sure we are on the shell.
    if (!tabNode(tn, "Home")) { back(); sleep(1500); }

    // ---- Step 4: first-tap responsiveness right after the commit ----
    tn = nodes("s4-before");
    const act = tabNode(tn, "Activity"); const home = tabNode(tn, "Home");
    if (act && home) {
      tap(act.x, act.y); sleep(1200); let a = nodes("s4-after-activity-tap"); const actSel = tabNode(a, "Activity");
      note("4", "activity_tab_first_tap_selected", actSel ? actSel.selected : "tab node missing");
      shot("s4-activity-tab-after-first-tap");
      tap(home.x, home.y); sleep(1500); a = nodes("s4-after-home-tap");
      note("4", "home_tab_first_tap_selected", tabNode(a, "Home")?.selected ?? "missing");
    } else note("4", "tabs", "not found");
    // Composer first tap -> IME shown.
    let cn = nodes();
    const composer = cn.find((n) => /EditText/.test(n.cls) && n.y > H * 0.5);
    if (composer) {
      tap(composer.x, composer.y); sleep(1500);
      note("4", "composer_first_tap_ime", /mInputShown=true|isInputViewShown=true|mImeWindowVis=3/.test(sh("dumpsys input_method | grep -E 'mInputShown|isInputViewShown|mImeWindowVis'")));
      shot("s4-composer-ime"); back(); sleep(800);
    } else note("4", "composer", "EditText not found");
    // Channel row first tap after a pop (wide: switches the open conversation).
    tap(img2.x, img2.y); sleep(2500); back(); sleep(1500);
    cn = nodes("s4-after-pop");
    const rows = channelRows(cn);
    if (rows.length >= 2) {
      const other = rows.find((n) => !/^#\s?all$/i.test(n.text)) || rows[1];
      newLog(); tap(other.x, other.y); sleep(2500); const after = nodes("s4-after-channel-tap"); shot("s4-after-channel-tap");
      const f2 = newLog();
      note("4", "channel_first_tap", `${label(other)} -> ops=${JSON.stringify(stackOps(f2).map((l) => l.replace(/^.*nav3_stack /, "").slice(0, 80)))} header_has_name=${!!after.find((n) => n.text && other.text.replace(/^#\s?/, "") && n.text.includes(other.text.replace(/^#\s?/, "")) && n.x > W * 0.35)}`);
      // back to #all
      const all = channelRows(after).find((n) => /^#\s?all$/i.test(n.text)); if (all) { tap(all.x, all.y); sleep(2500); }
    } else note("4", "channel_rows", rows.length);
    // During a push slide, taps on the shell area are swallowed: tap image then immediately tap the Activity tab.
    cn = nodes(); const act2 = tabNode(cn, "Activity");
    if (act2) {
      newLog(); sh(`input tap ${img2.x} ${img2.y}; sleep 0.08; input tap ${act2.x} ${act2.y}`); sleep(2500);
      const n2 = nodes("s4-tap-during-push"); shot("s4-tap-during-push");
      note("4", "tap_during_push", `page_on_top=${!tabNode(n2, "Home")} activity_selected=${tabNode(n2, "Activity")?.selected ?? "n/a"}`);
      back(); sleep(1500); const n3 = nodes(); note("4", "after_pop_activity_selected", tabNode(n3, "Activity")?.selected ?? "n/a");
      const h = tabNode(n3, "Home"); if (h && !h.selected) { tap(h.x, h.y); sleep(1500); }
    }
  }
  checkCrash("4-end");

  // ---- Step 6 (partial): composer draft kept across push/pop ----
  {
    let cn = nodes(); const composer = cn.find((n) => /EditText/.test(n.cls) && n.y > H * 0.5);
    if (composer) {
      tap(composer.x, composer.y); sleep(800); sh("input text qa4984draft"); sleep(500); back(); sleep(600);
      tap(img2.x, img2.y); sleep(2500); note("6", "ime_after_push", /mInputShown=true/.test(sh("dumpsys input_method | grep mInputShown"))); back(); sleep(2000);
      cn = nodes("s6-after-pop"); shot("s6-draft-after-pop");
      note("6", "draft_kept", !!cn.find((n) => /qa4984draft/.test(n.text)));
      note("6", "ime_reopened_after_pop", /mInputShown=true/.test(sh("dumpsys input_method | grep mInputShown")));
      const c2 = cn.find((n) => /qa4984draft/.test(n.text)); if (c2) { tap(c2.x, c2.y); sleep(500); sh("input keyevent 123"); sh(`input keyevent ${Array(12).fill("67").join(" ")}`); back(); sleep(500); }
    }
  }

  // ---- Step 5: back order ----
  {
    let sn = nodes("s5-shell");
    const search = find(sn, /search/i, (n) => n.clickable || n.desc);
    if (search) {
      tap(search.x, search.y); sleep(1800); let a = nodes("s5-search-open"); shot("s5-search-open");
      const field = a.find((n) => /EditText/.test(n.cls) && n.focused) || a.find((n) => /EditText/.test(n.cls) && n.y < H * 0.4);
      note("5", "search_opened", !!field);
      back(); sleep(600); back(); sleep(1500); // first back may only close the IME
      a = nodes("s5-after-back"); shot("s5-after-back");
      note("5", "search_closed_and_app_foreground", `foreground=${foreground()} home_tab=${!!tabNode(a, "Home")} searchfield=${!!a.find((n) => /EditText/.test(n.cls) && n.y < H * 0.4)}`);
      if (!foreground()) launch();
    } else note("5", "search", "no search control found");
    // Page on top: Back pops it only.
    newLog(); tap(img2.x, img2.y); sleep(2500); back(); sleep(1800);
    let f = newLog(); note("5", "page_back_ops", stackOps(f).map((l) => l.replace(/^.*nav3_stack /, "").slice(0, 80)));
    note("5", "page_back_foreground", foreground());
    // Shell alone: Back moves the task to the background.
    // (Wide layout with a conversation open may first close it — record what happens.)
    const backs = [];
    for (let i = 0; i < 3 && foreground(); i++) { back(); sleep(1500); backs.push(foreground() ? "still-foreground" : "background"); shot(`s5-shell-back-${i + 1}`); }
    note("5", "shell_back_sequence", backs);
    note("5", "process_alive_after_background", pid() !== "");
    launch(); sleep(2000); nodes("s5-relaunch"); shot("s5-relaunch");
  }
  checkCrash("5-end");

  // ---- Step 7: fold/unfold and rotate with a page on top and mid-push ----
  {
    const r = recStart("step7-fold-rotate", 170);
    const img3 = retarget() || img2;
    newLog(); tap(img3.x, img3.y); sleep(2500); shot("s7-page-unfolded");
    emu("fold"); sleep(3500); shot("s7-page-folded"); note("7", "wm_size_folded", sh("wm size").trim());
    emu("unfold"); sleep(3500); shot("s7-page-unfolded-again");
    sh("settings put system user_rotation 1"); sleep(3500); shot("s7-page-rotated");
    sh("settings put system user_rotation 0"); sleep(3000);
    back(); sleep(2000); let a = nodes("s7-after-pop-unfolded"); shot("s7-after-pop-unfolded");
    note("7", "after_pop_unfolded_tabs", !!tabNode(a, "Home")); note("7", "after_pop_conversation_visible", !!a.find((n) => /qa-fixture-image/.test(n.text)) || !!a.find((n) => /EditText/.test(n.cls)));
    // Mid-push fold and rotate.
    tap(img3.x, img3.y); emu("fold"); sleep(3500); shot("s7-midpush-folded");
    back(); sleep(2500); a = nodes("s7-folded-after-pop"); shot("s7-folded-after-pop");
    note("7", "folded_after_pop", `home_tab=${!!tabNode(a, "Home")} channel_rows=${channelRows(a).length} composer=${!!a.find((n) => /EditText/.test(n.cls))}`);
    // In the folded (narrow) layout open the image again if visible, then unfold mid-push.
    const imgF = retarget();
    if (imgF) { tap(imgF.x, imgF.y); emu("unfold"); sleep(3500); shot("s7-midpush-unfolded"); back(); sleep(2500); }
    else { emu("unfold"); sleep(3500); }
    a = nodes("s7-unfolded-after"); shot("s7-unfolded-after");
    // Back out of a narrow-layout conversation page if it is still stacked.
    const img4 = retarget() || img3;
    tap(img4.x, img4.y); sh("settings put system user_rotation 1"); sleep(3500); shot("s7-midpush-rotated");
    back(); sleep(2000); sh("settings put system user_rotation 0"); sleep(3000);
    a = nodes("s7-final"); shot("s7-final");
    // Tabletop posture (hinge half-open), page on top.
    tap(img4.x, img4.y); sleep(2500);
    note("7", "tabletop_cmd", emu("posture 3").trim().slice(0, 120)); sleep(1500);
    emu("sensor set hinge-angle0 90"); sleep(2500); shot("s7-tabletop-page");
    back(); sleep(2000); shot("s7-tabletop-after-pop");
    emu("sensor set hinge-angle0 180"); emu("unfold"); sleep(2500);
    recStop(r); note("7", "recording", path.basename(r.f));
    const f = newLog(); const hits = f.filter((l) => CRASH.test(l)); if (hits.length) crashes.push({ step: "7", lines: hits });
    note("7", "crash_lines", hits.slice(0, 10)); note("7", "foreground", foreground()); note("7", "stack_ops", stackOps(f).map((l) => l.replace(/^.*nav3_stack /, "").slice(0, 80)));
    if (!foreground()) launch();
  }

  // ---- Step 2b: folded push/pop recording ----
  {
    emu("fold"); sleep(3500);
    const imgF = retarget();
    const r = recStart("step2-folded-pushpop", 40);
    if (imgF) for (let i = 0; i < 3; i++) { tap(imgF.x, imgF.y); sleep(2500); back(); sleep(2500); }
    recStop(r); note("2", "folded_recording", imgF ? path.basename(r.f) : "image not found in folded layout");
    shot("s2-folded-end");
    // pop any narrow-layout conversation page
    let a = nodes(); if (!tabNode(a, "Home")) { back(); sleep(1500); }
    emu("unfold"); sleep(3500);
  }
  checkCrash("2b-end");

  // ---- Step 9: process death with a page over the shell ----
  {
    const img5 = retarget() || img2;
    tap(img5.x, img5.y); sleep(2500); shot("s9-page-before-kill");
    const before = pid();
    sh("input keyevent 3"); sleep(2500);
    sh(`am kill ${PKG}`); sleep(2500);
    const afterKill = pid(); note("9", "pid_before_after_kill", `${before} -> ${afterKill || "(dead)"}`);
    fs.writeFileSync(path.join(OUT, "logcat-driver-part1.txt"), logcat());
    sh("logcat -c"); logMark = 0;
    launch(); sleep(5000); const a = nodes("s9-restored"); shot("s9-restored");
    const f = newLog();
    note("9", "restore_line", f.find((l) => /nav3_stack_restore/.test(l))?.replace(/^.*nav3_stack_restore/, "nav3_stack_restore") ?? "none");
    note("9", "flag_line", f.find((l) => /nav3_stack_on/.test(l))?.replace(/^.*nav3_stack_on/, "nav3_stack_on") ?? "none");
    note("9", "page_on_top_after_restore", !tabNode(a, "Home"));
    back(); sleep(2000); const b = nodes("s9-after-pop"); shot("s9-after-pop");
    note("9", "after_pop_shell", `home_tab=${!!tabNode(b, "Home")} home_selected=${tabNode(b, "Home")?.selected} conversation_composer=${!!b.find((n) => /EditText/.test(n.cls))}`);
    const hits = f.filter((l) => CRASH.test(l)); if (hits.length) crashes.push({ step: "9", lines: hits });
    note("9", "crash_lines", hits.slice(0, 10));
  }

  // ---- Step 10: resize (wm size) and freeform with a page on top ----
  {
    const r = recStart("step10-resize", 120);
    const img6 = retarget() || img2;
    newLog(); tap(img6.x, img6.y); sleep(2500);
    for (const s of ["1100x1840", "1600x1840", "2208x1000", "reset"]) { sh(`wm size ${s}`); sleep(3000); shot(`s10-page-wm-${s}`); }
    back(); sleep(2000); shot("s10-after-pop"); nodes("s10-after-pop");
    // Freeform / split: try; record whether the windowing mode changed.
    sh("settings put global enable_freeform_support 1"); sh("settings put global force_resizable_activities 1");
    const top = sh(`dumpsys activity activities | grep -E 'topResumedActivity' | head -1`).trim();
    const comp = /([\w.]+\/[\w.$]+)/.exec(top.split(" ").find((t) => t.includes(PKG)) || "")?.[1];
    note("10", "top_activity", top.slice(0, 200));
    tap(img6.x, img6.y); sleep(2500);
    const taskId = /#(\d+)/.exec(sh(`dumpsys activity activities | grep -E 'Task\\{|\\* Task' | grep ${PKG} | head -1`))?.[1];
    note("10", "task_id", taskId ?? "n/a");
    if (taskId) {
      const launcher = sh(`cmd package resolve-activity --brief -c android.intent.category.LAUNCHER ${PKG} | tail -1`).trim();
      note("10", "freeform_start", sh(`am start -n ${launcher} --windowingMode 5 -f 0x10000000 2>&1`).trim().slice(0, 200));
      sleep(3000); shot("s10-freeform-page");
      note("10", "task_resize", sh(`am task resize ${taskId} 100 200 1300 1500 2>&1`).trim().slice(0, 200)); sleep(3000); shot("s10-freeform-resized");
      note("10", "windowing_mode_now", sh(`dumpsys activity activities | grep -E 'mWindowingMode|windowingMode=|mBounds' | head -4`).trim().slice(0, 400));
      back(); sleep(2000); shot("s10-freeform-after-pop"); nodes("s10-freeform-after-pop");
      sh(`am start -n ${launcher} --windowingMode 1 -f 0x10000000 2>&1`); sleep(2500); shot("s10-fullscreen-again");
    }
    recStop(r);
    const f = newLog(); const hits = f.filter((l) => CRASH.test(l)); if (hits.length) crashes.push({ step: "10", lines: hits });
    note("10", "crash_lines", hits.slice(0, 10)); note("10", "foreground", foreground()); note("10", "stack_ops", stackOps(f).map((l) => l.replace(/^.*nav3_stack /, "").slice(0, 80)));
  }
  finish();
}

// Members page: rows with a subtitle and a badge (Bot / App Bot / Owner / Admin / You). Then background ->
// foreground, fold/unfold and rotate a few times each; logcat for "not found in RectList" / FATAL / ANR.
const BADGE = /^(App Bot|Bot|Agent|Owner|Admin|You)( · .*)?$/;
function openMembers() {
  let ns = nodes("m-before");
  let t = find(ns, /^Members$/);
  if (!t) { const more = find(ns, /^More$/); if (more) { tap(more.x, more.y); sleep(2000); ns = nodes("m-more"); shot("m-more"); t = find(ns, /^Members$/); } }
  if (!t) { log("Members entry not found"); return false; }
  tap(t.x, t.y); sleep(3500); hideIme(); return true;
}
function membersState(name) {
  const ns = nodes(`m-${name}`); const f = shot(`m-${name}`);
  const badges = ns.filter((n) => BADGE.test(n.text) && n.w > 0);
  return { shot: f, badges: badges.map((n) => n.text).slice(0, 8), texts: ns.filter((n) => n.text && n.w > 0).length, membersTitle: !!find(ns, /^Members$/), fg: foreground() };
}
function mCheck(label) {
  const f = newLog(); const hits = f.filter((l) => CRASH.test(l));
  if (hits.length) { crashes.push({ step: `members-${label}`, lines: hits.slice(0, 10) }); fs.appendFileSync(path.join(OUT, "crash-lines.txt"), `## members-${label}\n${f.filter((l) => /AndroidRuntime|RectList|FATAL|ANR|Exception|\tat /.test(l)).join("\n")}\n`); }
  return hits.slice(0, 5);
}
function membersScenario() {
  newLog();
  if (!openMembers()) { note("members", "opened", false); return; }
  const before = membersState("open-unfolded"); note("members", "open_unfolded", before);
  // Bring rows that carry a badge AND a subtitle (agents: model/description line) into view.
  { const [W, H] = size(); for (let i = 0; i < 2; i++) { sh(`input swipe ${Math.round(W * 0.25)} ${Math.round(H * 0.8)} ${Math.round(W * 0.25)} ${Math.round(H * 0.45)} 400`); sleep(1200); } shot("m-scrolled-to-agents"); }
  // Record the texts on each badge's line and the line below it (name | badge | subtitle).
  const ns = nodes("m-rows");
  const rows = ns.filter((n) => BADGE.test(n.text)).slice(0, 6).map((b) => ns.filter((n) => n.text && n.y > b.y - 60 && n.y < b.y + 110 && n.b[0] < b.b[2] + 600 && n.w > 0).map((n) => n.text.slice(0, 60)).join(" | "));
  note("members", "badge_rows", rows);
  note("members", "open_crash", mCheck("open"));
  const pid0 = pid();
  const rec = recStart("members", 170);
  const res = { background: [], fold: [], rotate: [] };
  for (let i = 0; i < 3; i++) {
    sh("input keyevent 3"); sleep(2000); launch(); sleep(1500);
    res.background.push({ i, ...membersState(`bg-${i + 1}`), crash: mCheck(`bg-${i + 1}`) });
  }
  for (let i = 0; i < 3; i++) {
    emu("fold"); sleep(3000); const a = membersState(`folded-${i + 1}`);
    emu("unfold"); sleep(3000); const b = membersState(`unfolded-${i + 1}`);
    res.fold.push({ i, folded: a, unfolded: b, crash: mCheck(`fold-${i + 1}`) });
  }
  for (let i = 0; i < 3; i++) {
    sh("settings put system user_rotation 1"); sleep(3000); const a = membersState(`rot90-${i + 1}`);
    sh("settings put system user_rotation 0"); sleep(3000); const b = membersState(`rot0-${i + 1}`);
    res.rotate.push({ i, rotated: a, back: b, crash: mCheck(`rotate-${i + 1}`) });
  }
  // Rows reused while scrolling after the size changes.
  const [W, H] = size(); for (let i = 0; i < 3; i++) { sh(`input swipe ${Math.round(W / 2)} ${Math.round(H * 0.75)} ${Math.round(W / 2)} ${Math.round(H * 0.3)} 300`); sleep(700); }
  for (let i = 0; i < 3; i++) { sh(`input swipe ${Math.round(W / 2)} ${Math.round(H * 0.3)} ${Math.round(W / 2)} ${Math.round(H * 0.75)} 300`); sleep(700); }
  res.scroll_crash = mCheck("scroll");
  recStop(rec);
  note("members", "cycles", res);
  note("members", "pid_start_end", `${pid0} -> ${pid()}`);
  note("members", "final", membersState("final"));
}

function finish() {
  checkCrash("final");
  note("summary", "crashes", crashes);
  note("summary", "pid_final", pid());
  note("summary", "elapsed_s", Math.round((Date.now() - t0) / 1000));
  fs.writeFileSync(path.join(OUT, "logcat-driver.txt"), logcat());
  log("done; crashes:", crashes.length);
}

try { main(); } catch (e) { log("driver error", e.stack); note("summary", "driver_error", String(e.stack).slice(0, 2000)); finish(); }
