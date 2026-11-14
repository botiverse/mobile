import {
  VPhoneError,
  elementCenter,
  findElements,
  flattenElements,
  sleep,
} from "./vphone-driver.mjs";

const KMP_TRANSPORT = "kmp_socketio";

export function assertMinimum126(appInfo) {
  const keys = new Set(["CFBundleShortVersionString", "short_version", "version_name", "versionName", "marketing_version", "version"]);
  let found = null;
  const visit = (value) => {
    if (found || !value || typeof value !== "object") return;
    for (const [key, item] of Object.entries(value)) {
      if (keys.has(key) && typeof item === "string" && /^\d+\.\d+(?:\.\d+)?/.test(item)) {
        found = item.match(/^\d+\.\d+(?:\.\d+)?/)[0];
        return;
      }
      if (item && typeof item === "object") visit(item);
    }
  };
  visit(appInfo);
  if (!found) throw new VPhoneError("1.26 cases require a marketing version in apps.info", { code: "version_unknown" });
  const [major, minor] = found.split(".").map(Number);
  if (major < 1 || (major === 1 && minor < 26)) {
    throw new VPhoneError(`1.26 cases require Raft >= 1.26, got ${found}`, { code: "version_too_old" });
  }
  return found;
}

export function assertSingleOpenRoundTrip({ destinationTree, returnedTree, isDestination, isOriginal }) {
  if (!isDestination(destinationTree)) throw new VPhoneError("single-open destination was not established", { code: "navigation_assertion" });
  if (!isOriginal(returnedTree)) {
    throw new VPhoneError("one Back did not return to the original conversation; a duplicate destination may remain on the stack", { code: "navigation_assertion" });
  }
  return { destinationOpened: true, oneBackReturnedToOriginal: true, duplicateDestinationOnBack: false };
}

export function avatarForMessage(tree, messageText) {
  const message = findElements(tree, { text: messageText, role: "text", match: "exact" }).filter((element) => elementCenter(element)).sort((a, b) => elementCenter(b).y - elementCenter(a).y)[0];
  const messageCenter = elementCenter(message);
  if (!messageCenter) return null;
  const candidates = findElements(tree, { role: "button", match: "exact" }).filter((element) => {
    const center = elementCenter(element);
    if (!center || center.y >= messageCenter.y || messageCenter.y - center.y > 160) return false;
    const label = String(element.label || "");
    if (!label || /^(Back|Save|Saved|Open Tasks|Open Files|Channel details and settings|Thread panel menu)$/.test(label)) return false;
    if (/\b(reply|replies)\b/.test(label) || /^\d{1,2}:\d{2}/.test(label) || /ago$|Exact time/.test(label)) return false;
    return true;
  });
  return candidates.sort((a, b) => elementCenter(b).y - elementCenter(a).y)[0] || null;
}

export function kmpRowEnabled(element) {
  if (!element) return undefined;
  if (typeof element.value === "boolean") return element.value;
  if (element.value === "true") return true;
  if (element.value === "false") return false;
  if (element.selected === true) return true;
  if (element.selected === false) return false;
  const label = String(element.label || "").trim();
  if (/(?:^|[,·\s])On$/.test(label)) return true;
  if (/(?:^|[,·\s])Off$/.test(label)) return false;
  if ((Number(element.traits) & 8) !== 0) return true;
  return undefined;
}

function realtimeLogLines(logs) {
  let payload = logs;
  if (payload && !Array.isArray(payload) && typeof payload === "object" && payload.result) payload = payload.result;
  const entries = Array.isArray(payload) ? payload : payload?.entries || payload?.lines || payload?.logs || [];
  if (!Array.isArray(entries)) return [];
  return entries.map((entry) => {
    if (typeof entry === "string") return entry;
    return String(entry?.message || entry?.line || entry?.text || JSON.stringify(entry));
  });
}

export function analyzeRealtimeLog(logs) {
  const lines = realtimeLogLines(logs);
  const starts = lines.map((line, index) => ({ line, index, match: /connect_start transport=(\S+).*?generation=(\d+)/.exec(line) })).filter((item) => item.match);
  const latest = starts.at(-1);
  if (!latest) return { ok: false, reason: "missing_connect_start", transport: null, connected: false, resumed: false };
  const transport = latest.match[1];
  const generation = Number(latest.match[2]);
  const after = lines.slice(latest.index + 1);
  const connected = after.some((line) => line.includes("kmp_socket_connected"));
  const resumed = after.some((line) => line.includes("sync_resume_response"));
  return {
    ok: transport === KMP_TRANSPORT && connected && resumed,
    reason: transport !== KMP_TRANSPORT ? "old_client_transport" : !connected ? "missing_kmp_connected" : !resumed ? "missing_sync_resume" : "kmp_connected_and_resumed",
    transport,
    generation,
    connected,
    resumed,
  };
}

export function isFreshKmpEvidence(analysis, afterGeneration = null) {
  if (!analysis?.ok) return false;
  if (afterGeneration === null || afterGeneration === undefined) return true;
  return Number.isFinite(analysis.generation) && analysis.generation > afterGeneration;
}

export function realtimeFixtureText(phase, nonce) {
  const safePhase = String(phase).replace(/[^a-z0-9-]/gi, "-");
  const safeNonce = String(nonce).replace(/[^a-z0-9-]/gi, "-");
  return `smoke realtime ${safePhase}, please ignore [qa-${safeNonce}]`;
}

export function chooseQaServers(servers, qaServerName) {
  const rows = (Array.isArray(servers) ? servers : servers?.servers || []).filter((server) => server?.id && server?.name);
  const qa = rows.find((server) => server.name === qaServerName);
  if (!qa) throw new VPhoneError(`QA server ${qaServerName} is not available to this account`, { code: "qa_server_missing" });
  return { qa, alternate: rows.find((server) => server.id !== qa.id) || null };
}

export function offlineIpv4Config(before) {
  const router = before?.router || before?.ipv4?.router || before?.config?.router;
  const subnetMask = before?.subnet_mask || before?.subnetMask || before?.ipv4?.subnet_mask || before?.config?.subnet_mask || "255.255.255.0";
  if (!router) throw new VPhoneError("cannot preserve the guest router for the offline fixture", { code: "network_config_missing" });
  return { interface: "en0", method: "manual", address: "192.0.2.1", subnet_mask: subnetMask, router, dns: [] };
}

function hasBack(tree) {
  return findElements(tree, { text: "Back", role: "button", match: "exact" }).length > 0;
}

function hasChannelComposer(tree, channel) {
  return findElements(tree, { text: `Message #${channel}`, match: "contains" }).length > 0;
}

async function waitPredicate(driver, predicate, description, timeoutMs = 12_000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const tree = await driver.uiTree();
    if (predicate(tree)) return tree;
    await sleep(200);
  }
  throw new VPhoneError(`timed out waiting for ${description}`, { code: "wait_timeout" });
}

async function require126(driver, evidence) {
  const info = await driver.appsInfo();
  const version = assertMinimum126(info);
  evidence.writeJson("build-1.26.json", { version });
  return version;
}

async function doubleTapAndReturn({ driver, config, evidence, name, target, isDestination, isOriginal }) {
  const center = elementCenter(target);
  if (!center) throw new VPhoneError(`${name} target has no AX frame`, { code: "navigation_assertion" });
  await evidence.capture(driver, `${name}-source`);
  await driver.input("double_tap", { x: center.x, y: center.y, interval: 0.08 });
  const destinationTree = await waitPredicate(driver, isDestination, `${name} destination`);
  await evidence.capture(driver, `${name}-destination`);
  await driver.tapElement({ text: "Back", role: "button", match: "exact" });
  const returnedTree = await waitPredicate(driver, isOriginal, `${name} original conversation after one Back`);
  const singleOpen = assertSingleOpenRoundTrip({ destinationTree, returnedTree, isDestination, isOriginal });
  evidence.writeJson(`${name}-single-open.json`, { ...singleOpen, doubleTapIntervalMs: 80 });
  await evidence.capture(driver, `${name}-returned`);
}

async function ensureReplyFixture(helpers, driver, config) {
  const { openChannel, sendMessage, longPressLatestText, smokeText } = helpers;
  await openChannel(driver, config);
  let tree = await driver.uiTree();
  let replies = flattenElements(tree).filter((element) => element.role === "button" && /\b\d+ replies?\b/.test(String(element.label || "")) && elementCenter(element));
  if (!replies.length) {
    await sendMessage(driver, config, smokeText);
    await longPressLatestText(driver, smokeText);
    await driver.waitFor({ text: "Reply in Thread", role: "button", match: "exact" });
    await driver.tapElement({ text: "Reply in Thread", role: "button", match: "exact" });
    await sendMessage(driver, config, smokeText, { reply: true });
    await driver.tapElement({ text: "Back", role: "button", match: "exact" });
    tree = await waitPredicate(driver, (current) => flattenElements(current).some((element) => element.role === "button" && /\b\d+ replies?\b/.test(String(element.label || ""))), "reply fixture");
    replies = flattenElements(tree).filter((element) => element.role === "button" && /\b\d+ replies?\b/.test(String(element.label || "")) && elementCenter(element));
  }
  return replies.sort((a, b) => elementCenter(b).y - elementCenter(a).y)[0];
}

async function doubleTapReplies(context, helpers) {
  const { driver, config, evidence } = context;
  await require126(driver, evidence);
  const replies = await ensureReplyFixture(helpers, driver, config);
  await doubleTapAndReturn({
    driver, config, evidence, name: "replies", target: replies,
    isDestination: (tree) => hasBack(tree) && findElements(tree, { text: "Reply in thread", match: "contains" }).length > 0,
    isOriginal: (tree) => hasChannelComposer(tree, config.channel),
  });
  return "double-tapped N replies; one destination opened and one Back returned to the original conversation";
}

async function doubleTapAvatar(context, helpers) {
  const { driver, config, evidence } = context;
  const { openChannel, sendMessage, smokeText } = helpers;
  await require126(driver, evidence);
  await openChannel(driver, config);
  let tree = await driver.uiTree();
  if (!findElements(tree, { text: smokeText, role: "text", match: "exact" }).length) {
    await sendMessage(driver, config, smokeText);
    tree = await driver.uiTree();
  }
  const avatar = avatarForMessage(tree, smokeText);
  if (!avatar) throw new VPhoneError("no AX avatar button associated with the QA message", { code: "navigation_assertion" });
  await doubleTapAndReturn({
    driver, config, evidence, name: "avatar", target: avatar,
    isDestination: (current) => hasBack(current) && (findElements(current, { text: "Profile", match: "contains" }).length > 0 || findElements(current, { text: "Message", role: "button", match: "exact" }).length > 0),
    isOriginal: (current) => hasChannelComposer(current, config.channel),
  });
  return "double-tapped the message avatar; one profile destination opened and one Back returned";
}

async function doubleTapMessageLink(context, helpers) {
  const { driver, config, evidence } = context;
  const { openChannel, sendMessage, longPressLatestText, smokeText } = helpers;
  await require126(driver, evidence);
  await openChannel(driver, config);
  const initial = await driver.uiTree();
  if (!findElements(initial, { text: smokeText, role: "text", match: "exact" }).length) await sendMessage(driver, config, smokeText);
  await longPressLatestText(driver, smokeText);
  await driver.waitFor({ text: "Copy Link", role: "button", match: "exact" });
  await driver.tapElement({ text: "Copy Link", role: "button", match: "exact" });
  const clipboard = await driver.clipboardGet();
  const link = String(clipboard?.text || "").trim();
  if (clipboard?.has_text !== true || !/^https:\/\/\S+$/.test(link)) throw new VPhoneError("Copy Link did not produce an HTTPS message link", { code: "navigation_assertion" });
  evidence.writeJson("message-link.json", { link });
  await sendMessage(driver, config, link);
  const tree = await driver.uiTree();
  const target = findElements(tree, { text: link, role: "button", match: "exact" }).at(-1);
  if (!target) throw new VPhoneError("posted message link is not an AX button", { code: "navigation_assertion" });
  await doubleTapAndReturn({
    driver, config, evidence, name: "message-link", target,
    isDestination: (current) => hasBack(current) && findElements(current, { text: smokeText, role: "text", match: "exact" }).length > 0,
    isOriginal: (current) => hasChannelComposer(current, config.channel) && findElements(current, { text: link, match: "exact" }).length > 0,
  });
  await driver.clipboardClear();
  return "double-tapped a message link; one destination opened and one Back returned to the linking conversation";
}

export const QA_FIXTURE_CONTRACT = Object.freeze({
  repository: "botiverse/slock",
  commit: "b0527e7cb6f798bef3dd09877ae13dfc977357c7",
});

export function qaChannelFromInbox(inbox, channelName) {
  const wanted = String(channelName).replace(/^#/, "");
  const group = (Array.isArray(inbox?.groups) ? inbox.groups : []).find((item) => String(item?.channelName || "").replace(/^#/, "") === wanted);
  if (!group?.channelId) throw new VPhoneError(`QA channel #${wanted} is unavailable through the fixture API`, { code: "qa_channel_missing" });
  return { id: group.channelId, name: group.channelName };
}

function createQaApi(config, driver) {
  const base = String(config.apiBase || "").replace(/\/$/, "");
  let accessToken = null;
  async function request(pathname, { method = "GET", token, serverId, body } = {}) {
    if (!base) throw new VPhoneError("QA_API_BASE is required for realtime fixtures", { code: "qa_api_missing" });
    const response = await fetch(`${base}${pathname}`, {
      method,
      headers: {
        ...(body ? { "content-type": "application/json" } : {}),
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
        ...(serverId ? { "X-Server-Id": serverId } : {}),
      },
      ...(body ? { body: JSON.stringify(body) } : {}),
      signal: AbortSignal.timeout(20_000),
    });
    if (!response.ok) throw new VPhoneError(`QA fixture request ${method} ${pathname} failed with HTTP ${response.status}`, { code: "qa_fixture_failed" });
    const text = await response.text();
    return text ? JSON.parse(text) : null;
  }
  async function login() {
    if (accessToken) return accessToken;
    if (!config.email || !config.password) throw new VPhoneError("realtime fixture cases require QA_EMAIL and QA_PASSWORD", { code: "credentials_required" });
    const result = await request("/auth/login", { method: "POST", body: { email: config.email, password: config.password } });
    if (!result?.accessToken) throw new VPhoneError("QA fixture login returned no access token", { code: "qa_fixture_failed" });
    accessToken = result.accessToken;
    driver.redact.add(accessToken);
    return accessToken;
  }
  async function servers() {
    const token = await login();
    return request("/servers", { token });
  }
  async function post(server, content) {
    const token = await login();
    const inbox = await request("/channels/inbox?filter=all&limit=50&offset=0", { token, serverId: server.id });
    const channel = qaChannelFromInbox(inbox, config.channel);
    const message = await request("/messages", { method: "POST", token, serverId: server.id, body: { channelId: channel.id, content } });
    return { id: message?.id || message?.message?.id || null, channelId: channel.id, serverId: server.id, content, contract: QA_FIXTURE_CONTRACT };
  }
  return { servers, post };
}

async function withNetworkOffline(driver, evidence, run) {
  const before = await driver.rpc("network.ipv4.get", { interface: "en0" });
  const offline = offlineIpv4Config(before);
  evidence.writeJson("network-before.json", { before, offline });
  await driver.rpc("network.ipv4.set", offline);
  evidence.writeJson("network-offline.json", await driver.rpc("network.ipv4.get", { interface: "en0" }));
  try {
    return await run();
  } finally {
    await driver.rpc("network.ipv4.set", { interface: "en0", method: "dhcp" });
    evidence.writeJson("network-restored.json", await driver.rpc("network.ipv4.get", { interface: "en0" }));
  }
}

async function selectServer(driver, serverName) {
  const tree = await driver.uiTree();
  const prefixed = findElements(tree, { text: "Current server:", role: "button", match: "contains" }).length > 0;
  await driver.tapElement(prefixed
    ? { text: "Current server:", role: "button", match: "contains" }
    : { text: serverName, role: "button", match: "exact" });
  await driver.waitFor({ text: serverName, role: "button", match: "exact" });
  await driver.tapElement({ text: serverName, role: "button", match: "exact" });
  await waitPredicate(driver, (current) => findElements(current, { text: `Current server: ${serverName}`, role: "button", match: "exact" }).length > 0 || findElements(current, { text: serverName, role: "button", match: "exact" }).length > 0, `server identity ${serverName}`, 20_000);
}

async function realtimeLogs(driver) {
  return driver.rpc("logs.syslog", { seconds: 45, process: "iosApp", level: "all", max_lines: 500 }, { timeoutMs: 60_000 });
}

async function requireKmpConnection(driver, evidence, name, afterGeneration = null) {
  await sleep(750);
  const logs = await realtimeLogs(driver);
  const analysis = analyzeRealtimeLog(logs);
  evidence.writeJson(`${name}-transport.json`, { analysis, requiredAfterGeneration: afterGeneration, logs });
  if (!analysis.ok) {
    throw new VPhoneError(`new realtime client evidence failed: ${analysis.reason}; transport=${analysis.transport || "unknown"}`, { code: "kmp_evidence_failed" });
  }
  if (!isFreshKmpEvidence(analysis, afterGeneration)) {
    throw new VPhoneError(`new realtime client evidence is stale: generation=${analysis.generation}, required > ${afterGeneration}`, { code: "stale_kmp_evidence" });
  }
  return analysis;
}

async function prepareKmp(context, helpers) {
  const { driver, config, evidence } = context;
  const { openSettings, openSettingsRow, openChannel } = helpers;
  await require126(driver, evidence);
  await openSettings(driver, config);
  await openSettingsRow(driver, "Developer");
  await driver.waitFor({ text: "New realtime connection (Beta)", match: "contains" });
  let tree = await driver.uiTree();
  let row = findElements(tree, { text: "New realtime connection (Beta)", role: "button", match: "contains" })[0];
  if (!row) throw new VPhoneError("this build does not expose the new realtime client switch", { code: "kmp_unsupported" });
  let enabled = kmpRowEnabled(row);
  if (enabled === undefined) throw new VPhoneError("new realtime switch state is not exposed through AX", { code: "kmp_state_unknown" });
  if (!enabled) {
    await driver.tapElement({ text: "New realtime connection (Beta)", role: "button", match: "contains" });
    await waitPredicate(driver, (current) => {
      row = findElements(current, { text: "New realtime connection (Beta)", role: "button", match: "contains" })[0];
      return kmpRowEnabled(row) === true;
    }, "new realtime switch On");
  }
  tree = await driver.uiTree();
  row = findElements(tree, { text: "New realtime connection (Beta)", role: "button", match: "contains" })[0];
  if (kmpRowEnabled(row) !== true) throw new VPhoneError("new realtime client did not remain enabled", { code: "kmp_state_unknown" });
  await evidence.capture(driver, "kmp-enabled");
  const baselineLogs = await realtimeLogs(driver);
  const baseline = analyzeRealtimeLog(baselineLogs);
  evidence.writeJson("pre-restart-transport.json", { analysis: baseline, logs: baselineLogs });
  await driver.appsTerminate();
  await openChannel(driver, config);
  return requireKmpConnection(driver, evidence, "initial", baseline.generation ?? null);
}

async function realtimeConnect(context, helpers) {
  const connection = await prepareKmp(context, helpers);
  await context.evidence.capture(context.driver, "kmp-connected");
  return `new realtime client connected and resumed (transport=${connection.transport}, generation=${connection.generation})`;
}

async function realtimeOffline(context, helpers) {
  const { driver, config, evidence } = context;
  const initial = await prepareKmp(context, helpers);
  const api = createQaApi(config, driver);
  const { qa } = chooseQaServers(await api.servers(), config.serverName);
  const content = realtimeFixtureText("offline", `${Date.now()}-${process.pid}`);
  await withNetworkOffline(driver, evidence, async () => {
    const posted = await api.post(qa, content);
    evidence.writeJson("offline-fixture.json", { postedWhileNetworkOffline: true, fixture: posted });
    await sleep(1000);
  });
  await helpers.openChannel(driver, config);
  await driver.waitFor({ text: content, role: "text", match: "exact" }, { timeoutMs: 30_000 });
  const reconnected = await requireKmpConnection(driver, evidence, "offline-reconnect", initial.generation);
  await evidence.capture(driver, "offline-caught-up");
  return `QA message posted while en0 was offline appeared after DHCP recovery with ${reconnected.transport} and sync resume`;
}

async function realtimeChannelSwitch(context, helpers) {
  const { driver, config, evidence } = context;
  await prepareKmp(context, helpers);
  const api = createQaApi(config, driver);
  const { qa } = chooseQaServers(await api.servers(), config.serverName);
  if (!config.altChannel || config.altChannel === config.channel) throw new VPhoneError("QA_ALT_CHANNEL must name a different visible QA channel", { code: "qa_channel_missing" });
  await helpers.openChannel(driver, { ...config, channel: config.altChannel });
  const content = realtimeFixtureText("channel-switch", `${Date.now()}-${process.pid}`);
  const posted = await api.post(qa, content);
  evidence.writeJson("channel-switch-fixture.json", { postedWhileOnAlternateChannel: config.altChannel, fixture: posted });
  await helpers.openChannel(driver, config);
  await driver.waitFor({ text: content, role: "text", match: "exact" }, { timeoutMs: 30_000 });
  await requireKmpConnection(driver, evidence, "channel-recovery");
  await evidence.capture(driver, "channel-caught-up");
  return `message posted to #${config.channel} while on #${config.altChannel} appeared after returning`;
}

async function realtimeBackground(context, helpers) {
  const { driver, config, evidence } = context;
  await prepareKmp(context, helpers);
  const api = createQaApi(config, driver);
  const { qa } = chooseQaServers(await api.servers(), config.serverName);
  await driver.appsLaunch("com.apple.mobilesafari");
  const background = await driver.appsForeground();
  if (background?.bundle_id !== "com.apple.mobilesafari") throw new VPhoneError("Raft did not move to the background", { code: "background_not_established" });
  const content = realtimeFixtureText("background", `${Date.now()}-${process.pid}`);
  const posted = await api.post(qa, content);
  evidence.writeJson("background-fixture.json", { foregroundDuringPost: background, fixture: posted });
  await sleep(10_000);
  await helpers.openChannel(driver, config);
  await driver.waitFor({ text: content, role: "text", match: "exact" }, { timeoutMs: 30_000 });
  await requireKmpConnection(driver, evidence, "background-recovery");
  await evidence.capture(driver, "background-caught-up");
  return "message posted while Safari was foreground appeared after returning to Raft";
}

async function realtimeServerSwitch(context, helpers) {
  const { driver, config, evidence } = context;
  await prepareKmp(context, helpers);
  const api = createQaApi(config, driver);
  const { qa, alternate } = chooseQaServers(await api.servers(), config.serverName);
  evidence.writeJson("server-fixtures.json", { qa: { id: qa.id, name: qa.name }, alternate: alternate ? { id: alternate.id, name: alternate.name } : null });
  if (!alternate) {
    const error = new Error("only the QA server is available; switching away/back is not covered, while the KMP transport was verified");
    error.status = "NOT_COVERED";
    throw error;
  }
  await selectServer(driver, alternate.name);
  const content = realtimeFixtureText("server-switch", `${Date.now()}-${process.pid}`);
  const posted = await api.post(qa, content);
  evidence.writeJson("away-fixture.json", { postedWhileAway: true, awayServer: alternate.name, fixture: posted });
  await evidence.capture(driver, "away-server");
  await selectServer(driver, qa.name);
  await helpers.openChannel(driver, config);
  await driver.waitFor({ text: content, role: "text", match: "exact" }, { timeoutMs: 30_000 });
  const recovered = await requireKmpConnection(driver, evidence, "server-recovery");
  await evidence.capture(driver, "server-recovered");
  return `QA message posted while on ${alternate.name} appeared after returning to ${qa.name}; ${recovered.transport} resume verified`;
}

export function createNavigationRealtimeCases(helpers) {
  return {
    doubleTapReplies: (context) => doubleTapReplies(context, helpers),
    doubleTapAvatar: (context) => doubleTapAvatar(context, helpers),
    doubleTapMessageLink: (context) => doubleTapMessageLink(context, helpers),
    realtimeConnect: (context) => realtimeConnect(context, helpers),
    realtimeOffline: (context) => realtimeOffline(context, helpers),
    realtimeChannelSwitch: (context) => realtimeChannelSwitch(context, helpers),
    realtimeBackground: (context) => realtimeBackground(context, helpers),
    realtimeServerSwitch: (context) => realtimeServerSwitch(context, helpers),
  };
}
