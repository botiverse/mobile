// Minimum build gate (#4797) staging acceptance on a RELEASE Android build, run by
// release_auto_login.mjs after it signed the app in:
//
//   --scenario scripts/qa/scenarios/min_build_gate.mjs --phase banner|block|flag_off --threshold N
//     [--server-name NAME] [--wait-off-minutes 40]
//
// The installed app is build A (the runner's --apk). The workflow passes, with
// hands_serve_extra=true, build B as --extra-apk / --extra-code and B's Hands release
// (raft-android, channel debug, ACTIVE but scoped to ONE device group) as --hands-group /
// --hands-release / --device-id-file; this module puts the emulator's Hands installation id into
// that group, so Hands offers B to this emulator and to nothing else.
//
// The flag (mobile_min_build_android_v1, staging) is changed by the flag admin between runs; each
// phase first reads it through POST /feature-flags/evaluate as the app would and stops when it is
// not what the phase needs (flag on, default variant = --threshold).
//
//   banner   (flag ON, threshold above B, e.g. B+1): A shows only the dismissible banner, never the
//            block, although Hands offers B (B < threshold); Dismiss closes it.
//   block    (flag ON, threshold = B): A is blocked (Hands confirms B >= threshold); offline +
//            background + return drops it to the banner; back online + return blocks again; A
//            updated to B (adb install -r, same signer, session kept) is not gated at all.
//   flag_off (flag ON, threshold = B at start): A is blocked; the run then waits until the server
//            reports the flag OFF (the admin turns it off while this waits) and checks that the
//            block goes away without a relaunch (periodic read / features-changed push), else on
//            the next foreground; no banner either.
//
// Every step saves a screenshot + UI dump; the app's own lines (tags SlockMinBuildGate,
// SlockFeatureFlags) go to logcat-min-build-gate.txt and back the assertions:
//   min_build_gate effective threshold=<T> server=<id> servers=<n> reason=<r>
//   min_build_gate channel=hands threshold=<T> current=<A> latest=<available:B|up_to_date|unknown> reason=<r>
//   min_build_gate decision=<none|banner threshold=T|block threshold=T latest=B> reason=<r>
import fs from "node:fs";
import path from "node:path";
import { spawn, spawnSync } from "node:child_process";

const FLAG_KEY = "mobile_min_build_android_v1";
const BLOCK_TITLE = "Please update to the latest version";
const BLOCK_BODY_PREFIX = "This version of Raft is no longer supported. Install the latest version";
const BANNER_PREFIX = "This version of Raft is no longer supported. Please update";
const HANDS_BASE = process.env.HANDS_PUBLIC_BASE || "https://hands.build";

export async function run(ctx) {
  const { step, nodes, find, tapNode, shell, adb, sleep, log, option, web, PKG, OUT, redact } = ctx;
  const phase = option("phase", "");
  const threshold = Number(option("threshold", "0"));
  const serverName = option("server-name", "");
  const extraApk = option("extra-apk", "");
  const extraCode = Number(option("extra-code", "0"));
  const handsGroup = option("hands-group", "");
  const handsRelease = option("hands-release", "");
  const deviceIdFile = option("device-id-file", "");
  const waitOffMinutes = Number(option("wait-off-minutes", "40"));
  const results = {};
  const report = { phase, threshold, flag_key: FLAG_KEY, serving_b: Boolean(handsGroup), b_code: extraCode || null, results };
  ctx.summary.scenario = report;

  const mark = (id, result, note) => {
    results[id] = { result, ...(note ? { note } : {}) };
    log(`scenario ${id}: ${result}${note ? ` (${note})` : ""}`);
  };
  const check = async (id, name, body) => {
    try {
      const note = await step(`${id}-${name}`, body);
      mark(id, "pass", typeof note === "string" ? note : note ? JSON.stringify(note).slice(0, 400) : undefined);
      return true;
    } catch (error) {
      mark(id, "fail", String(error?.message ?? error).slice(0, 600));
      return false;
    }
  };

  // ---------- UI ----------
  const mine = (list) => list.filter((n) => n.pkg === PKG);
  const visible = (list = nodes()) => list.map((n) => `${n.text} ${n.desc}`.trim()).filter(Boolean).slice(0, 40).join(" | ");
  const screen = (list = nodes()) => {
    const own = mine(list);
    return {
      block: own.some((n) => n.text.trim() === BLOCK_TITLE || n.text.startsWith(BLOCK_BODY_PREFIX)),
      banner: own.some((n) => n.text.startsWith(BANNER_PREFIX)),
      home: own.some((n) => n.text === "Drafts"),
      chooser: own.some((n) => n.text.startsWith("Signed in as ")),
      list,
    };
  };
  const answerPermissionPrompt = async (list) => {
    const allow = find(list, (n) => /permissioncontroller/.test(n.pkg) && n.text === "Allow");
    if (!allow) return false;
    tapNode(allow);
    await sleep(1000);
    return true;
  };
  async function waitScreen(description, test, { timeoutMs = 60_000, intervalMs = 1500 } = {}) {
    const deadline = Date.now() + timeoutMs;
    let s = screen([]);
    while (Date.now() < deadline) {
      const list = nodes();
      if (await answerPermissionPrompt(list)) continue;
      await ctx.dismissSystemDialog(list);
      s = screen(list);
      if (test(s)) return s;
      await sleep(intervalMs);
    }
    throw new Error(`timed out after ${Math.round(timeoutMs / 1000)}s waiting for ${description}; on screen: ${visible(s.list) || "(nothing labelled)"}`);
  }
  // Stays true for the whole window (polled): "never shows X".
  async function holdScreen(description, test, { ms = 20_000, intervalMs = 2500 } = {}) {
    const deadline = Date.now() + ms;
    while (Date.now() < deadline) {
      const s = screen(nodes());
      if (!test(s)) throw new Error(`${description} broke; on screen: ${visible(s.list)}`);
      await sleep(intervalMs);
    }
  }
  const background = async (ms = 5000) => {
    shell("input keyevent KEYCODE_HOME", { allowFailure: true });
    await sleep(ms);
  };
  const foreground = async () => {
    ctx.launchApp();
    await sleep(2500);
  };
  const installedCode = () => Number(/versionCode=(\d+)/.exec(shell(`dumpsys package ${PKG}`, { allowFailure: true }))?.[1] ?? 0);

  // ---------- the app's own log lines ----------
  const logFile = path.join(OUT, "logcat-min-build-gate.txt");
  let logcat = null;
  const startLogcat = () => {
    try { logcat?.kill("SIGTERM"); } catch {}
    logcat = spawn("adb", ["logcat", "-v", "threadtime", "-s", "SlockMinBuildGate:*", "SlockFeatureFlags:*"], {
      stdio: ["ignore", fs.openSync(logFile, "a"), "ignore"],
    });
  };
  // Raw lines (threadtime stamp included, so a repeated message is a new line), from the streamed
  // file and a dump of the buffer, deduplicated; matching uses the part from "min_build_gate ".
  const rawGateLines = () => {
    const streamed = fs.existsSync(logFile) ? fs.readFileSync(logFile, "utf8") : "";
    const dumped = adb(["logcat", "-d", "-v", "threadtime", "-s", "SlockMinBuildGate:*"], { allowFailure: true });
    return [...new Set(`${streamed}\n${dumped}`.split("\n").map((l) => l.trimEnd()).filter((l) => l.includes("min_build_gate ")))];
  };
  const message = (l) => l.slice(l.indexOf("min_build_gate "));
  const gateLines = () => rawGateLines().map(message);
  let seenAtMark = new Set(); // a step reads only lines that were not there when it began
  const markLog = () => { seenAtMark = new Set(rawGateLines()); };
  const linesSinceMark = () => rawGateLines().filter((l) => !seenAtMark.has(l)).map(message);
  async function waitLog(description, regex, { timeoutMs = 60_000, since = true } = {}) {
    const deadline = Date.now() + timeoutMs;
    while (Date.now() < deadline) {
      const hit = (since ? linesSinceMark() : gateLines()).find((l) => regex.test(l));
      if (hit) return hit;
      await sleep(1500);
    }
    throw new Error(`the app never logged ${description} (${regex}); its min_build_gate lines: ${gateLines().slice(-8).join(" || ") || "none"}`);
  }

  // ---------- the flag, as the server evaluates it for this app ----------
  let server = null;
  async function readFlag() {
    const code = installedCode();
    const out = await web("POST", "feature-flags/evaluate", {
      serverId: server.id,
      body: {
        serverId: server.id,
        keys: [FLAG_KEY],
        platform: "mobile",
        os: "android",
        appVersion: /versionName=(\S+)/.exec(shell(`dumpsys package ${PKG}`, { allowFailure: true }))?.[1] ?? null,
        buildNumber: String(code),
        buildType: "release",
      },
    });
    if (out.status !== 200) throw new Error(`POST feature-flags/evaluate answered HTTP ${out.status}`);
    const e = (out.json?.evaluations ?? []).find((x) => x.key === FLAG_KEY) ?? {};
    return { enabled: e.enabled === true, variant: e.variant ?? null, reason: e.reason ?? null };
  }
  const flagIsThreshold = (f) => f.enabled && String(f.variant ?? "").trim() === String(threshold);

  // ---------- Hands: this emulator into B's device group ----------
  let deviceId = "";
  async function joinHandsGroup() {
    for (let i = 0; i < 40 && !deviceId; i += 1) {
      const prefs = shell(`cat /data/data/${PKG}/shared_prefs/quiver_update.xml`, { allowFailure: true });
      deviceId = /name="device_id">([^<]+)</.exec(prefs)?.[1] ?? "";
      if (!deviceId) await sleep(2000);
    }
    if (!deviceId) throw new Error("the app never created its Hands installation id (shared_prefs/quiver_update.xml)");
    redact.add(deviceId);
    if (deviceIdFile) fs.writeFileSync(deviceIdFile, deviceId, { mode: 0o600 });
    const add = spawnSync("bash", ["scripts/qa/android_update_hands_fixture.sh", "add-member", handsGroup, deviceId, `gha-run-${process.env.GITHUB_RUN_ID ?? "local"}-min-build-gate`], { encoding: "utf8" });
    if (add.status !== 0) throw new Error(`adding the emulator to the Hands device group failed: ${redact(String(add.stderr)).slice(0, 300)}`);
    const abi = shell("getprop ro.product.cpu.abi").trim();
    if (handsRelease) {
      const assetCheck = spawnSync("bash", ["scripts/qa/android_update_hands_fixture.sh", "assert-asset", handsRelease, abi], { encoding: "utf8" });
      if (assetCheck.status !== 0) throw new Error(`B's Hands release has no APK for ${abi}: ${String(assetCheck.stderr).trim().slice(-400)}`);
    }
    // Exactly the app's question: is B offered to this installation of A?
    const url = `${HANDS_BASE}/public/v2/apps/raft-android/updates/check?channel=debug&product_type=android-apk&current_version_code=${installedCode()}&platform=android&filetype=apk&arch=${encodeURIComponent(abi)}`;
    let offered = 0;
    let said = "";
    for (let i = 0; i < 10 && offered !== extraCode; i += 1) {
      const response = await fetch(url, { headers: { accept: "application/json", "X-Hands-Device-Id": deviceId } });
      const body = await response.json().catch(() => ({}));
      offered = body?.latest?.version_code ?? 0;
      said = `HTTP ${response.status} update_available=${body?.update_available} latest_version_code=${body?.latest_version_code ?? offered} error=${body?.error ?? ""}`;
      if (offered !== extraCode) await sleep(3000);
    }
    if (offered !== extraCode) throw new Error(`Hands does not offer B=${extraCode} to this emulator (${said})`);
    return { abi, hands_offers: offered };
  }

  const bannerOnly = (s) => s.banner && !s.block && s.home;
  const blocked = (s) => s.block && !s.home && !s.banner;
  const ungated = (s) => s.home && !s.block && !s.banner;

  try {
    // ---------- setup ----------
    const setup = await step("gate-setup", async () => {
      if (!["banner", "block", "flag_off"].includes(phase)) throw new Error("--phase must be banner, block or flag_off");
      if (!(threshold >= 10_000_000)) throw new Error("--threshold must be the flag's build number (>= 10000000)");
      if (phase !== "banner" && !handsGroup) throw new Error(`phase ${phase} needs B served on Hands (workflow input hands_serve_extra=true)`);
      if (phase === "block" && !extraApk) throw new Error("phase block needs --extra-apk (B) for the update check");
      const a = installedCode();
      if (!(a < threshold)) throw new Error(`installed build ${a} is not below the threshold ${threshold}`);
      if (phase === "banner" && extraCode && !(extraCode < threshold)) throw new Error(`banner phase: B=${extraCode} must be below the threshold ${threshold}`);
      if (phase !== "banner" && !(extraCode >= threshold)) throw new Error(`${phase} phase: B=${extraCode} must be at or above the threshold ${threshold}`);
      // Root adbd (google_apis image) to read the app's Hands installation id; it restarts adbd.
      adb(["root"], { allowFailure: true });
      await sleep(3000);
      adb(["wait-for-device"]);
      startLogcat();
      const out = await web("GET", "servers");
      const servers = Array.isArray(out.json) ? out.json : out.json?.servers ?? [];
      server = serverName ? servers.find((s) => s.name === serverName || s.slug === serverName.toLowerCase()) : servers[0];
      if (!server) throw new Error(`no server ${serverName || "(any)"} for the QA account (has: ${servers.map((s) => s.name).join(", ")})`);
      const flag = await readFlag();
      report.flag_at_start = flag;
      if (!flagIsThreshold(flag)) {
        throw new Error(`staging ${FLAG_KEY} reads enabled=${flag.enabled} variant=${flag.variant} (${flag.reason}); phase ${phase} needs it ON with default variant ${threshold}`);
      }
      return { a_code: a, b_code: extraCode || null, server: { id: server.id, name: server.name }, flag, rooted: shell("id -u").trim() === "0" };
    });
    report.server = setup.server;

    // ---------- open the server in the app ----------
    await step("gate-open-server", async () => {
      shell(`pm grant ${PKG} android.permission.POST_NOTIFICATIONS`, { allowFailure: true });
      const first = await waitScreen("Home, the server chooser or a gate", (s) => s.home || s.chooser || s.block || s.banner, { timeoutMs: 60_000 });
      if (first.chooser && !first.home) {
        const row = find(first.list, (n) => n.pkg === PKG && n.text.trim() === server.name);
        if (!row) throw new Error(`"${server.name}" is not in the server chooser; on screen: ${visible(first.list)}`);
        tapNode(row);
      }
      // A lands on Home with (at least) the banner: the first channel check runs before the
      // emulator is in B's group (Hands: up to date) or, in phase banner, B is below the threshold.
      const s = await waitScreen("the gate on Home (banner) or the block", (x) => x.banner || x.block, { timeoutMs: 120_000 });
      const effective = await waitLog(`"effective threshold=${threshold}"`, new RegExp(`^min_build_gate effective threshold=${threshold} server=`), { since: false, timeoutMs: 30_000 });
      return { first_gate: s.block ? "block" : "banner", effective };
    });

    if (handsGroup) await step("gate-hands-join-group", joinHandsGroup);

    // ---------- phases ----------
    if (phase === "banner") {
      await check("step1", "threshold-above-channel-banner-only", async () => {
        markLog();
        await background();
        await foreground();
        const latest = handsGroup ? `available:${extraCode}` : "up_to_date";
        const channelLine = await waitLog(`the channel answer latest=${latest}`, new RegExp(`^min_build_gate channel=hands threshold=${threshold} current=\\d+ latest=${latest} reason=foreground`), { timeoutMs: 60_000 });
        await waitScreen("the banner over Home", bannerOnly, { timeoutMs: 30_000 });
        await holdScreen("banner only, never the block", (s) => !s.block && s.home, { ms: 20_000 });
        const blockEver = gateLines().find((l) => /decision=block/.test(l));
        if (blockEver) throw new Error(`the app decided block at some point: ${blockEver}`);
        return { channel: channelLine };
      });
      await check("step1b", "banner-dismiss", async () => {
        const s = screen(nodes());
        const dismiss = find(s.list, (n) => n.pkg === PKG && (n.text === "Dismiss" || n.desc === "Dismiss") && n.bounds[1] < 900);
        if (!dismiss) throw new Error(`no Dismiss on the banner; on screen: ${visible(s.list)}`);
        tapNode(dismiss);
        await waitScreen("Home without the banner", ungated, { timeoutMs: 15_000 });
        await waitLog("action=dismiss_banner", new RegExp(`action=dismiss_banner threshold=${threshold}`), { since: false, timeoutMs: 15_000 });
      });
    }

    if (phase === "block" || phase === "flag_off") {
      await check("step2a", "below-threshold-blocked", async () => {
        markLog();
        await background();
        await foreground();
        const channelLine = await waitLog(`the channel offering B=${extraCode}`, new RegExp(`^min_build_gate channel=hands threshold=${threshold} current=\\d+ latest=available:${extraCode} reason=foreground`), { timeoutMs: 60_000 });
        await waitScreen("the block page", blocked, { timeoutMs: 30_000 });
        const s = screen(nodes());
        for (const label of ["Update", "Send feedback", "Sign out"]) {
          if (!find(s.list, (n) => n.pkg === PKG && n.text === label)) throw new Error(`the block page has no "${label}"`);
        }
        const decision = await waitLog("decision=block", new RegExp(`decision=block threshold=${threshold} latest=${extraCode}`), { timeoutMs: 10_000 });
        return { channel: channelLine, decision };
      });
    }

    if (phase === "block") {
      let offline = false;
      try {
        await check("step4", "offline-background-return-banner", async () => {
          if (!screen(nodes()).block) throw new Error("the block page is not showing at the start of step 4");
          markLog();
          shell("cmd connectivity airplane-mode enable", { allowFailure: true });
          shell("svc wifi disable; svc data disable", { allowFailure: true });
          offline = true;
          await sleep(4000);
          await background();
          await foreground();
          // Offline the Hands check fails: latest=unknown (or up_to_date if the client maps the error to
          // "no update"); either way not available:B, so the decision must fall back to the banner.
          const channelLine = await waitLog("the channel answer while offline", /^min_build_gate channel=hands .* latest=(unknown|up_to_date) reason=foreground/, { timeoutMs: 60_000 });
          await waitScreen("the banner (not the block) while offline", bannerOnly, { timeoutMs: 30_000 });
          const decision = await waitLog("decision=banner", new RegExp(`decision=banner threshold=${threshold}`), { timeoutMs: 10_000 });
          return { channel: channelLine, decision, airplane: shell("cmd connectivity airplane-mode", { allowFailure: true }).trim() };
        });
      } finally {
        shell("cmd connectivity airplane-mode disable", { allowFailure: true });
        shell("svc wifi enable; svc data enable", { allowFailure: true });
        if (offline) await sleep(8000);
      }

      await check("step4b", "online-again-blocks-again", async () => {
        markLog();
        await background();
        await foreground();
        await waitLog(`the channel offering B=${extraCode} again`, new RegExp(`latest=available:${extraCode} reason=foreground`), { timeoutMs: 90_000 });
        await waitScreen("the block page again", blocked, { timeoutMs: 30_000 });
      });

      await check("step2b", "at-or-above-threshold-not-gated", async () => {
        markLog();
        adb(["install", "-r", extraApk], { timeoutMs: 240_000 });
        const code = installedCode();
        if (code !== extraCode) throw new Error(`installed versionCode is ${code} after the update, expected B=${extraCode}`);
        startLogcat();
        await foreground();
        const s = await waitScreen("Home on B without any gate", ungated, { timeoutMs: 90_000 });
        if (s.chooser) throw new Error("B opened on the server chooser");
        await waitLog(`"effective threshold=${threshold}" on B`, new RegExp(`^min_build_gate effective threshold=${threshold} `), { timeoutMs: 60_000 });
        await holdScreen("no block and no banner on B", ungated, { ms: 30_000 });
        const gated = linesSinceMark().find((l) => /decision=(block|banner)/.test(l));
        if (gated) throw new Error(`B logged a gate decision: ${gated}`);
        const flag = await readFlag();
        if (!flagIsThreshold(flag)) throw new Error(`the flag changed during the check (now enabled=${flag.enabled} variant=${flag.variant})`);
        return { installed: code };
      });
    }

    if (phase === "flag_off") {
      await check("step3", "flag-off-block-goes-away", async () => {
        if (!screen(nodes()).block) throw new Error("the block page is not showing before the flag is turned off");
        console.log(`::notice title=min build gate::READY: ${FLAG_KEY} may be turned OFF now (block page is showing; waiting up to ${waitOffMinutes} min)`);
        log(`waiting up to ${waitOffMinutes} min for the server to report ${FLAG_KEY} OFF`);
        const deadline = Date.now() + waitOffMinutes * 60_000;
        // A features-changed push can reach the app before this poll sees the flag off: read the
        // app's lines from here on.
        markLog();
        let flag = await readFlag();
        while (flag.enabled && Date.now() < deadline) {
          await sleep(10_000);
          flag = await readFlag();
          // Keep the block on screen meanwhile; it must not drop by itself while the flag is on.
          const s = screen(nodes());
          if (flag.enabled && !s.block && (await readFlag()).enabled) {
            throw new Error(`the block went away while the flag was still on; on screen: ${visible(s.list)}`);
          }
        }
        if (flag.enabled) throw new Error(`${FLAG_KEY} still ON after ${waitOffMinutes} min`);
        const offAt = Date.now();
        let via = "read_or_push";
        try {
          await waitScreen("Home without the block, no app action", (s) => !s.block, { timeoutMs: 150_000, intervalMs: 3000 });
        } catch {
          via = "foreground";
          await background();
          await foreground();
          await waitScreen("Home without the block after a foreground", (s) => !s.block, { timeoutMs: 45_000 });
        }
        const seconds = Math.round((Date.now() - offAt) / 1000);
        await waitScreen("Home, no banner either", ungated, { timeoutMs: 20_000 });
        const cleared = await waitLog("effective threshold=none", /^min_build_gate effective threshold=none /, { timeoutMs: 15_000 });
        const decision = await waitLog("decision=none", /decision=none/, { timeoutMs: 10_000 });
        const flagLines = (fs.existsSync(logFile) ? fs.readFileSync(logFile, "utf8") : "").split("\n").filter((l) => /feature_flags (change_scheduled|change_coalesced)/.test(l)).slice(-3);
        report.flag_off = { via, seconds_after_server_off: seconds };
        return { via, seconds_after_server_off: seconds, cleared, decision, features_changed: flagLines.map((l) => l.slice(l.indexOf("feature_flags"))) };
      });
    }
  } catch (error) {
    report.error = String(error?.message ?? error).slice(0, 600);
    log(`scenario stopped: ${report.error}`);
  } finally {
    shell("cmd connectivity airplane-mode disable", { allowFailure: true });
    shell("svc wifi enable; svc data enable", { allowFailure: true });
    await step("gate-end", async () => ({ on_screen: visible(), gate_lines: gateLines().slice(-20) })).catch(() => {});
    try { logcat?.kill("SIGTERM"); } catch {}
    if (fs.existsSync(logFile)) fs.writeFileSync(logFile, redact(fs.readFileSync(logFile, "utf8")));
  }
  const wanted = { banner: ["step1", "step1b"], block: ["step2a", "step4", "step4b", "step2b"], flag_off: ["step2a", "step3"] }[phase] ?? [];
  for (const id of wanted) if (!results[id]) mark(id, "not_run", report.error ?? "an earlier step stopped the run");
  report.result = !report.error && wanted.every((id) => results[id]?.result === "pass") ? "pass" : "fail";
  return report;
}
