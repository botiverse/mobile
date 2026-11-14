// Artifact viewer reuse check for a RELEASE Android build (Hands dd109a01, fix #4547: a reused
// kept viewer page closed itself right after opening), run by release_auto_login.mjs after it
// signed the app in: `--scenario scripts/qa/scenarios/artifact_reuse.mjs --server-name <name>
// --channel <name> [--artifact-a pt_x --artifact-b pt_y] [--session-wait-min 18]`.
//
// The channel holds two Artifact link cards, "QA artifact A (可删)" / "QA artifact B (可删)"
// (release-auto-login.yml mode=agent_artifacts). Through the app's own UI, each open is
// screenshotted 5 s after the tap and records whether the viewer stayed (the app is in front,
// the channel is not, no `artifact_viewer_closed` since the tap) and which Artifact it shows
// (the page's marker text, else the viewer's `artifact_viewer_open artifact=<id>` log line):
//   pairs     A, Back, B, Back; then A -> B three more times
//   background open A, Home key 10 s, back to the app (viewer still A?), Back, open B
//   session   open A, Back (the viewer page is kept), wait until the app logs a token refresh
//             (auth_refresh*), then open B: must stay; `artifact_viewer_kept_dropped
//             reason=session_changed` is the evidence the reuse path saw the session change.
//             No refresh seen in time: NOT COVERED (the background step is not this).
// Viewer log lines (tags SlockArtifactViewer, RaftArtifactOpen) go to artifact-viewer-logcat.txt.
// Nothing is created or deleted.
const ARTIFACTS = {
  A: { title: "QA artifact A (可删)", marker: "QA-ARTIFACT-A-PAGE" },
  B: { title: "QA artifact B (可删)", marker: "QA-ARTIFACT-B-PAGE" },
};

export async function run(ctx) {
  const { step, nodes, find, tapNode, shell, sleep, log, label, option, web, PKG } = ctx;
  const serverName = option("server-name", "OranixSlockTest");
  const channelName = option("channel", "qa-test-create-7842").replace(/^#/, "");
  const ids = { A: option("artifact-a", ""), B: option("artifact-b", "") };
  const sessionWaitMin = Number(option("session-wait-min", "18"));
  const results = [];
  const report = { server_name: serverName, channel: channelName, artifact_ids: ids, opens: results };
  ctx.summary.scenario = report;

  const visible = (list = nodes()) => list.map(label).filter(Boolean).slice(0, 40).join(" | ");
  const hasText = (list, matcher) => find(list, (n) => n.pkg === PKG && (typeof matcher === "string" ? n.text.trim() === matcher || n.desc.trim() === matcher : matcher(n)));
  const answerPermissionPrompt = async (list) => {
    const allow = find(list, (n) => /permissioncontroller/.test(n.pkg) && n.text === "Allow");
    if (!allow) return false;
    tapNode(allow);
    await sleep(1000);
    return true;
  };
  async function waitUntil(description, test, { timeoutMs = 45_000, intervalMs = 1500 } = {}) {
    const deadline = Date.now() + timeoutMs;
    let list = [];
    while (Date.now() < deadline) {
      list = nodes();
      if (await answerPermissionPrompt(list)) continue;
      const hit = test(list);
      if (hit) return hit;
      await ctx.dismissSystemDialog(list);
      await sleep(intervalMs);
    }
    throw new Error(`timed out waiting for ${description}; on screen: ${visible(list) || "(nothing labelled)"}`);
  }
  const back = async () => { shell("input keyevent KEYCODE_BACK", { allowFailure: true }); await sleep(1200); };
  const swipeUp = () => shell("input swipe 540 1250 540 500 350", { allowFailure: true });
  const swipeDown = () => shell("input swipe 540 500 540 1250 350", { allowFailure: true });
  const channelReady = (l) => hasText(l, "Open Tasks") || hasText(l, "Close Tasks") || (hasText(l, "Chat") && hasText(l, (n) => n.text === "Tasks" && n.bounds[1] < 900));
  const atHome = (l) => Boolean(hasText(l, "Drafts")) && !channelReady(l);
  const hideKeyboard = async () => {
    const ime = String(shell("dumpsys input_method | grep -m1 mInputShown", { allowFailure: true }) ?? "");
    if (/mInputShown=true/.test(ime)) { shell("input keyevent KEYCODE_BACK", { allowFailure: true }); await sleep(1000); }
  };
  const deviceNow = () => Number(String(shell("date +%s.%N", { allowFailure: true }) ?? "").trim()) || 0;
  // logcat -v epoch: "  1731612345.123  1234  1234 I Tag: message"
  const logLines = (tags, since = 0) => String(shell(`logcat -d -v epoch${tags.length ? ` -s ${tags.map((t) => `${t}:V`).join(" ")}` : ""}`, { allowFailure: true, timeoutMs: 60_000 }) ?? "")
    .split("\n").filter((l) => { const t = Number(l.trim().split(/\s+/)[0]); return t && t >= since; });
  const viewerLog = (since) => logLines(["SlockArtifactViewer", "RaftArtifactOpen"], since);

  const showsWhich = (list, since) => {
    for (const key of ["A", "B"]) {
      if (find(list, (n) => n.text.includes(ARTIFACTS[key].marker) || n.desc.includes(ARTIFACTS[key].marker))) return { key, by: "page_marker" };
    }
    for (const key of ["A", "B"]) {
      if (find(list, (n) => !n.bounds || n.bounds[1] < 600 ? n.text.includes(`QA artifact ${key}`) || n.desc.includes(`QA artifact ${key}`) : false)) return { key, by: "title_text" };
    }
    const opened = viewerLog(since).filter((l) => /artifact_viewer_open artifact=/.test(l)).pop();
    if (opened) {
      const id = opened.match(/artifact=(\S+)/)?.[1] ?? "";
      for (const key of ["A", "B"]) if (ids[key] && id.includes(ids[key].replace(/^pt_/, ""))) return { key, by: "viewer_log", id };
      return { key: null, by: "viewer_log", id };
    }
    return { key: null, by: "none" };
  };

  async function goHome() {
    for (let i = 0; i < 8; i += 1) {
      const list = nodes();
      if (atHome(list)) return { via: i === 0 ? "already_home" : `back_x${i}` };
      if (!list.some((n) => n.pkg === PKG)) break;
      await back();
    }
    ctx.launchApp();
    await sleep(2500);
    await waitUntil("Home", atHome, { timeoutMs: 30_000 });
    return { via: "relaunch" };
  }
  async function openChannel() {
    const isRow = (n) => n.pkg === PKG && n.bounds && n.bounds[3] < 2250 && [channelName, `#${channelName}`, `# ${channelName}`].includes(n.text.trim());
    let row = null;
    for (let i = 0; i < 12 && !row; i += 1) {
      row = find(nodes(), isRow);
      if (!row) { if (i < 8) swipeUp(); else swipeDown(); await sleep(900); }
    }
    if (!row) throw new Error(`#${channelName} not found on Home; on screen: ${visible()}`);
    tapNode(row);
    await waitUntil(`#${channelName}`, channelReady, { timeoutMs: 45_000 });
    if (hasText(nodes(), "Close Tasks")) { tapNode(hasText(nodes(), "Close Tasks")); await sleep(1200); }
    await hideKeyboard();
  }
  // The card: the last node (drawn latest, lowest) whose text is the Artifact's title.
  async function findCard(key) {
    const title = ARTIFACTS[key].title;
    const match = (l) => { const hits = l.filter((n) => n.pkg === PKG && n.bounds && n.bounds[1] > 250 && n.bounds[3] < 2150 && (n.text.trim() === title || n.text.includes(title) || n.desc.includes(title))); return hits.length ? hits[hits.length - 1] : null; };
    await hideKeyboard();
    for (let i = 0; i < 16; i += 1) {
      const hit = match(nodes());
      if (hit) return hit;
      // Newest at the bottom: first make sure we are at the bottom, then look further up.
      if (i < 3) swipeUp(); else swipeDown();
      await sleep(900);
    }
    throw new Error(`the card "${title}" is not in #${channelName}; on screen: ${visible()}`);
  }
  // One open: tap the card, 5 s, screenshot, record.
  async function openAndCheck(name, key, { holdMs = 5000 } = {}) {
    const record = { step: name, expected: key };
    results.push(record);
    try {
      await step(name, async () => {
        const card = await findCard(key);
        const since = deviceNow();
        record.tapped_at = since;
        tapNode(card);
        await sleep(holdMs);
        const list = nodes();
        const inApp = list.some((n) => n.pkg === PKG);
        const inChannel = Boolean(channelReady(list));
        const lines = viewerLog(since);
        const closed = lines.filter((l) => /artifact_viewer_closed/.test(l));
        const shown = showsWhich(list, since);
        Object.assign(record, {
          viewer_stayed: inApp && !inChannel && closed.length === 0,
          shows: shown.key, shows_by: shown.by, ...(shown.id ? { viewer_log_artifact: shown.id } : {}),
          in_app: inApp, back_in_channel: inChannel, closed_lines: closed.length,
          kept_lines: lines.filter((l) => /artifact_viewer_kept_|artifact_viewer_left/.test(l)).map((l) => l.replace(/^.*?: /, "")).slice(0, 6),
          on_screen: visible(list).slice(0, 240),
        });
        if (!record.viewer_stayed) throw new Error(`the viewer did not stay ${holdMs / 1000} s (in app ${inApp}, channel showing ${inChannel}, closed lines ${closed.length})`);
        if (shown.key && shown.key !== key) throw new Error(`the viewer shows ${shown.key}, expected ${key}`);
        return { shows: shown.key, by: shown.by };
      });
      record.result = record.shows === key ? "pass" : "pass_unconfirmed_content";
    } catch (error) {
      record.result = "fail";
      record.error = String(error?.message ?? error).slice(0, 400);
    }
    return record;
  }
  async function backToChannel(name) {
    await step(name, async () => {
      await back();
      await waitUntil(`#${channelName} after Back`, channelReady, { timeoutMs: 15_000 }).catch(async () => { await goHome(); await openChannel(); });
      return { back_in_channel: Boolean(channelReady(nodes())) };
    }, { screenshot: false }).catch((error) => log(`back to channel: ${error.message}`));
  }

  try {
    await step("artifact-setup", async () => {
      const servers = await web("GET", "servers");
      const list = Array.isArray(servers.json) ? servers.json : servers.json?.servers ?? [];
      const server = list.find((s) => s.name === serverName) ?? list.find((s) => s.slug === serverName.toLowerCase());
      if (!server) throw new Error(`the QA account is not in ${serverName}`);
      shell(`pm grant ${PKG} android.permission.POST_NOTIFICATIONS`, { allowFailure: true });
      await sleep(500);
      await answerPermissionPrompt(nodes());
      const l = nodes();
      const row = find(l, (n) => n.pkg === PKG && n.text.trim() === serverName);
      if (find(l, (n) => n.text.startsWith("Signed in as ")) && row) {
        tapNode(row);
        await waitUntil("the server's home", (x) => hasText(x, "Drafts") || hasText(x, "Home"), { timeoutMs: 60_000 });
      }
      await goHome();
      await openChannel();
      const cards = {};
      for (const key of ["A", "B"]) cards[key] = Boolean(await findCard(key));
      return { server: server.name, cards };
    });

    // ---- A / B pairs: the first, then three more ----
    for (let round = 1; round <= 4; round += 1) {
      for (const key of ["A", "B"]) {
        await openAndCheck(`artifact-r${round}-open-${key}`, key);
        await backToChannel(`artifact-r${round}-back-${key}`);
      }
    }

    // ---- background 10 s while A is open ----
    const bgOpen = await openAndCheck("artifact-bg-open-A", "A");
    const bg = { step: "artifact-bg-return", expected: "A" };
    results.push(bg);
    try {
      await step("artifact-bg-return", async () => {
        const since = deviceNow();
        shell("input keyevent KEYCODE_HOME", { allowFailure: true });
        await sleep(10_000);
        ctx.launchApp();
        await sleep(5000);
        const list = nodes();
        const lines = viewerLog(since);
        const shown = showsWhich(list, bgOpen.tapped_at ?? since);
        Object.assign(bg, {
          viewer_still_open: list.some((n) => n.pkg === PKG) && !channelReady(list) && !atHome(list) && !lines.some((l) => /artifact_viewer_closed/.test(l)),
          shows: shown.key, shows_by: shown.by, closed_lines: lines.filter((l) => /artifact_viewer_closed/.test(l)).length, on_screen: visible(list).slice(0, 240),
        });
        if (!bg.viewer_still_open) throw new Error("after 10 s in the background the viewer is no longer open");
        return { shows: shown.key };
      });
      bg.result = "pass";
    } catch (error) {
      bg.result = "fail";
      bg.error = String(error?.message ?? error).slice(0, 400);
    }
    await backToChannel("artifact-bg-back");
    await openAndCheck("artifact-bg-then-open-B", "B");
    await backToChannel("artifact-bg-then-back-B");

    // ---- reopen after a session change (token refresh while the viewer page is kept) ----
    await openAndCheck("artifact-session-open-A", "A");
    await backToChannel("artifact-session-park-A");
    const parkedAt = deviceNow();
    const session = { step: "artifact-session-change", parked_at: parkedAt };
    report.session_change = session;
    const deadline = Date.now() + sessionWaitMin * 60_000;
    let refresh = [];
    while (Date.now() < deadline) {
      refresh = logLines([], parkedAt).filter((l) => /auth_refresh|token_refresh|access_token_rotat|session_rotat/i.test(l));
      if (refresh.some((l) => /applied=true|refreshed|rotated/i.test(l))) break;
      // Keep the app asking the server for something (a token past expiry is refreshed on use): Home and the channel again.
      await goHome().catch(() => {});
      await sleep(25_000);
      await openChannel().catch(() => {});
      await sleep(25_000);
    }
    session.refresh_lines = refresh.map((l) => l.replace(/^\s*\S+\s+\d+\s+\d+\s+/, "")).slice(0, 8);
    session.waited_s = Math.round(deviceNow() - parkedAt);
    if (!refresh.some((l) => /applied=true|refreshed|rotated/i.test(l))) {
      session.result = "not_covered";
      session.note = `no token refresh logged within ${sessionWaitMin} min of parking the viewer; the 10 s background step is not a session change`;
      await step("artifact-session-change-not-covered", async () => ({ note: session.note }), { screenshot: false }).catch(() => {});
    } else {
      if (!channelReady(nodes())) { await goHome().catch(() => {}); await openChannel().catch(() => {}); }
      const reopened = await openAndCheck("artifact-session-reopen-B", "B");
      const dropped = viewerLog(parkedAt).filter((l) => /artifact_viewer_kept_dropped reason=session_changed/.test(l));
      session.kept_dropped_session_changed = dropped.length > 0;
      session.reopen = { result: reopened.result, viewer_stayed: reopened.viewer_stayed, shows: reopened.shows };
      session.result = reopened.result === "fail" ? "fail" : session.kept_dropped_session_changed ? "pass" : "pass_without_session_evidence";
      await backToChannel("artifact-session-back-B");
    }
  } finally {
    try {
      const fs = await import("node:fs");
      fs.writeFileSync(`${ctx.OUT}/artifact-viewer-logcat.txt`, viewerLog(0).join("\n"));
      fs.writeFileSync(`${ctx.OUT}/auth-refresh-logcat.txt`, logLines([], 0).filter((l) => /auth_refresh|token_refresh/i.test(l)).join("\n"));
    } catch (error) {
      log(`logcat capture: ${error.message}`);
    }
    await goHome().catch(() => {});
    await step("artifact-end", async () => ({ on_screen: visible() })).catch(() => {});
  }
  const failed = results.some((r) => r.result === "fail") || report.session_change?.result === "fail";
  report.result = failed ? "fail" : "pass";
  return report;
}
