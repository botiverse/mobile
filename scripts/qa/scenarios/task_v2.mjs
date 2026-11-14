// Task v2 device check for a RELEASE Android build, run by release_auto_login.mjs after it has
// signed the app in (`--scenario scripts/qa/scenarios/task_v2.mjs --server-name <name>`).
// docs/qa-acceptance/release-auto-login.md "Scenarios" explains the hook.
//
// On a server with task v2 conversations (`task_v2_conversations_v0`), through the app's own UI
// (uiautomator), with a screenshot + UI dump for every step:
//   s1 channel Tasks tab -> open a task -> its task conversation (head + messages) -> reply there
//   s2 change the assignee (to the QA account itself) and see it on the head
//   s3 status todo -> in progress -> done; "In Review" must not be offered for a v2 task
//   s4 a Backlog task is listed and reads Backlog
//   s5 a task an old CLI created without a source (the server posted its title as the source
//      message): opened from that message, its task conversation opens
// Test data (s1-s4) is created by the QA account through the web API, titled "[QA test data]",
// and deleted at the end; the source messages the server posts for them stay (a person cannot
// delete messages through the API) and say they are test data. Each scenario records
// pass / fail / not_covered in summary.json `scenario.results`; one failing does not stop the next.
const REPLY_TEXT = "task v2 check, please ignore";
const TEST_PREFIX = "[QA test data, please ignore]";

export async function run(ctx) {
  const { step, nodes, find, tapNode, shell, sleep, log, label, option, web, account, PKG } = ctx;
  const serverName = option("server-name", "OranixSlockTest");
  const channelHint = option("channel", "");
  const runTag = `${process.env.GITHUB_RUN_ID ?? "local"}-${Date.now().toString(36)}`;
  const results = {};
  const created = { tasks: [], messages: [] };
  const report = { server_name: serverName, run_tag: runTag, results, test_data: created };
  ctx.summary.scenario = report;

  const mark = (id, result, note) => {
    results[id] = { result, ...(note ? { note } : {}), steps: results[id]?.steps ?? [] };
    log(`scenario ${id}: ${result}${note ? ` (${note})` : ""}`);
  };
  // A scenario step: screenshot + dump through the runner's step(); its failure fails the scenario.
  const sstep = async (id, name, body) => {
    results[id] ??= { result: "running", steps: [] };
    const index = ctx.nextStepIndex();
    results[id].steps.push(`step-${String(index).padStart(2, "0")}-${id}-${name}`);
    return step(`${id}-${name}`, body);
  };
  const scenario = async (id, body) => {
    try {
      const note = await body();
      if (results[id]?.result === "running" || !results[id]) mark(id, "pass", note);
    } catch (error) {
      if (error?.notCovered) mark(id, "not_covered", error.message);
      else mark(id, "fail", String(error?.message ?? error).slice(0, 600));
    }
  };
  const notCovered = (message) => Object.assign(new Error(message), { notCovered: true });

  // ---------- API (the QA account's web session) ----------
  let server = null;
  const api = async (method, path, body) => {
    const out = await web(method, path, { body, serverId: server?.id });
    return out;
  };
  const ok = (out, what) => {
    if (out.status < 200 || out.status >= 300) throw new Error(`${what}: HTTP ${out.status} ${JSON.stringify(out.json ?? "").slice(0, 300)}`);
    return out.json;
  };
  const listOf = (json, key) => (Array.isArray(json) ? json : Array.isArray(json?.[key]) ? json[key] : []);
  const getTask = async (taskId, channelId) => {
    const list = listOf(ok(await api("GET", `tasks/channel/${channelId}`), "list channel tasks"), "tasks");
    return list.find((t) => t.id === taskId) ?? null;
  };

  // ---------- UI helpers ----------
  const visible = (list = nodes()) => list.map(label).filter(Boolean).slice(0, 40).join(" | ");
  const mine = (list) => list.filter((n) => n.pkg === PKG);
  // Android 13+ asks for notifications on first launch; the run grants it, but a prompt already up stays.
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
  const hasText = (list, matcher) => find(list, (n) => n.pkg === PKG && (typeof matcher === "string" ? n.text.trim() === matcher || n.desc.trim() === matcher : matcher(n)));
  const deepLink = (url) => {
    shell(`am start -W -a android.intent.action.VIEW -c android.intent.category.BROWSABLE -d '${url}' ${PKG} >/dev/null 2>&1`, { allowFailure: true });
  };
  const channelUrl = (channelId, messageId) =>
    `https://app.raft.build/s/${server.slug}/channel/${channelId}${messageId ? `?msg=${messageId}` : ""}`;
  // `input text` drops characters from long strings in Compose fields: short chunks, %s for spaces.
  async function typeText(value) {
    for (const chunk of value.match(/.{1,6}/g) ?? []) {
      shell(`input text '${chunk.replace(/'/g, "").replace(/ /g, "%s")}'`);
      await sleep(200);
    }
  }
  const back = async () => {
    shell("input keyevent KEYCODE_BACK", { allowFailure: true });
    await sleep(1200);
  };
  // Above where the keyboard sits (the channel focuses its composer on open).
  const swipeUp = () => shell("input swipe 540 1250 540 500 350", { allowFailure: true });
  const swipeDown = () => shell("input swipe 540 500 540 1250 350", { allowFailure: true });
  // The status chip: contentDescription "Task status: <Status>" (TaskConversationHead).
  const statusChip = (list) => hasText(list, (n) => /^Task status: /.test(n.desc) || /^Task status: /.test(n.text));
  const statusOf = (chip) => (chip ? `${chip.desc || chip.text}`.replace(/^Task status: /, "").trim() : null);
  async function findTaskHead() {
    // The head may sit above the first message; scroll up to it if the conversation opened lower.
    for (let i = 0; i < 4; i += 1) {
      const list = nodes();
      const chip = statusChip(list);
      if (chip) return { list, chip };
      swipeDown();
      await sleep(900);
    }
    return { list: nodes(), chip: null };
  }
  const titleNode = (list, title) => hasText(list, (n) => n.text.trim() === title || n.text.includes(title) || n.desc.includes(title));
  // Task v2 channel header (1.20+): "Open Tasks" toggles a Tasks card that covers the conversation
  // (ConversationSideCardsCover; the toggle then reads "Close Tasks"). Older layouts: a Tasks tab.
  const channelReady = (l) => hasText(l, "Open Tasks") || hasText(l, "Close Tasks") || (hasText(l, "Chat") && hasText(l, (n) => n.text === "Tasks" && n.bounds[1] < 900));
  // The conversation under the cover stays in the UI dump: the card's row is the LAST match (drawn on top).
  const lastTitleNode = (list, title) => {
    const hits = list.filter((n) => n.pkg === PKG && n.bounds && (n.text.trim() === title || n.text.includes(title) || n.desc.includes(title)));
    return hits.length ? hits[hits.length - 1] : null;
  };
  async function openTasksCard() {
    await waitUntil("the channel's Open Tasks toggle", channelReady, { timeoutMs: 45_000 });
    const l = nodes();
    if (hasText(l, "Close Tasks")) return { card: "already_open" };
    const toggle = hasText(l, "Open Tasks") ?? hasText(l, (n) => n.text === "Tasks" && n.bounds[1] < 900);
    tapNode(toggle);
    await waitUntil("the Tasks card (Close Tasks)", (x) => hasText(x, "Close Tasks") || hasText(x, (n) => n.text === "Tasks" && n.bounds[1] < 400), { timeoutMs: 15_000 });
    return { card: "opened" };
  }
  // The channel focuses its composer on open; with the keyboard up the card's lower rows (and the
  // folded Backlog group) sit under it. Back with the IME shown only hides the IME.
  const hideKeyboard = async () => {
    const ime = shell("dumpsys input_method | grep -m1 mInputShown", { allowFailure: true }) ?? "";
    if (/mInputShown=true/.test(String(ime))) {
      shell("input keyevent KEYCODE_BACK", { allowFailure: true });
      await sleep(1000);
      return true;
    }
    return false;
  };
  // A Tasks card row: "#<channel>" and "#<number>" on one line (only the card draws that pair), the title under it.
  const cardRow = (list, channelName, number) => {
    const ref = list.find((n) => n.pkg === PKG && n.bounds && n.text.trim() === `#${number}` &&
      list.some((m) => m.text.trim() === `#${channelName}` && m.bounds && Math.abs(m.bounds[1] - n.bounds[1]) < 10));
    if (!ref) return null;
    const title = list.find((n) => n.pkg === PKG && n.bounds && n.text.trim() && n.bounds[1] >= ref.bounds[3] - 5 && n.bounds[1] < ref.bounds[3] + 120 && n.bounds[0] < 200);
    return { ref, title: title ?? null, tapTarget: title ?? ref };
  };
  async function findInTasksCard(channelName, number, { sectionToggle = false, tries = 10 } = {}) {
    await hideKeyboard();
    let tapped = 0;
    let lastTap = -5;
    for (let i = 0; i < tries; i += 1) {
      await sleep(1500);
      const list = nodes();
      const row = cardRow(list, channelName, number);
      if (row && row.tapTarget.bounds[3] < 2250) return { row: row.tapTarget, ref: row.ref, title: row.title, list };
      // Backlog is its own group after the active ones (SharedTasksPage listColumns), folded by default: open it.
      const section = sectionToggle ? list.find((n) => n.pkg === PKG && n.bounds && n.text.trim() === "Backlog" && n.bounds[1] > 450 && n.bounds[3] < 2250 && n.bounds[0] < 300) : null;
      if (section && tapped < 2 && i - lastTap > 2) {
        tapped += 1;
        lastTap = i;
        tapNode(section);
      } else if (i >= 1) swipeUp();
    }
    return { row: null, list: nodes() };
  }

  // ---------- 0. server, channel, test data ----------
  let setup = null;
  try {
    setup = await step("scenario-setup", async () => {
      const servers = listOf(ok(await web("GET", "servers"), "list servers"), "servers");
      server = servers.find((s) => s.name === serverName) ?? servers.find((s) => s.slug === serverName.toLowerCase());
      if (!server) throw new Error(`the QA account is not a member of "${serverName}" (its servers: ${servers.map((s) => s.name).join(", ")})`);
      const channels = listOf(ok(await api("GET", "channels"), "list channels"), "channels")
        .filter((c) => !["dm", "thread", "task"].includes(c.type) && !c.archived && !c.archivedAt);
      const joined = channels.filter((c) => c.isMember !== false && c.joined !== false && c.member !== false);
      const order = (c) => (channelHint && (c.name === channelHint || c.id === channelHint) ? 0 : /qa|test|sandbox/i.test(c.name ?? "") ? 1 : /^(general|all|random)$/i.test(c.name ?? "") ? 2 : 3);
      const candidates = [...joined].sort((a, b) => order(a) - order(b));
      let channel = null;
      let task = null;
      for (const candidate of candidates.slice(0, 6)) {
        const out = await api("POST", `tasks/channel/${candidate.id}`, {
          tasks: [{ title: `${TEST_PREFIX} task v2 mobile check ${runTag}`, description: "Created by the mobile release QA run (scripts/qa/scenarios/task_v2.mjs); deleted at the end of the run." }],
          status: "todo",
        });
        if (out.status >= 200 && out.status < 300) {
          channel = candidate;
          task = listOf(out.json, "tasks")[0] ?? null;
          break;
        }
        log(`cannot create a task in #${candidate.name}: HTTP ${out.status} ${JSON.stringify(out.json ?? "").slice(0, 200)}`);
      }
      if (!channel || !task) throw new Error(`could not create a test task in any of: ${candidates.slice(0, 6).map((c) => c.name).join(", ") || "(no joined channels)"}`);
      created.tasks.push({ id: task.id, channelId: channel.id, number: task.taskNumber, title: task.title });
      if (task.sourceMessageId && typeof task.sourceMessageId === "string") created.messages.push(task.sourceMessageId);
      const backlog = ok(await api("POST", `tasks/channel/${channel.id}`, {
        tasks: [{ title: `${TEST_PREFIX} backlog task ${runTag}`, description: "Backlog test task for the mobile release QA run; deleted at the end of the run." }],
        status: "backlog",
      }), "create the backlog test task");
      const backlogTask = listOf(backlog, "tasks")[0];
      created.tasks.push({ id: backlogTask.id, channelId: channel.id, number: backlogTask.taskNumber, title: backlogTask.title, status: backlogTask.status });
      // A seed message in the task conversation, so the page has messages to show.
      const conv = ok(await api("POST", `tasks/${task.id}/conversation`, {}), "open the task conversation");
      const conversationId = conv.conversationChannelId;
      const seed = ok(await api("POST", "messages", { channelId: conversationId, content: `${TEST_PREFIX} seed message ${runTag}` }), "post the seed message");
      return {
        server: { id: server.id, slug: server.slug, name: server.name },
        channel: { id: channel.id, name: channel.name },
        task: { id: task.id, number: task.taskNumber, title: task.title, status: task.status, v2: !task.isLegacy && Boolean(task.conversationChannelId || task.conversationAnchored), conversationId },
        backlog: { id: backlogTask.id, number: backlogTask.taskNumber, title: backlogTask.title, status: backlogTask.status },
        seed_message_id: seed?.id ?? seed?.message?.id ?? null,
        me: { id: account.userId, name: account.displayName },
      };
    }).catch((error) => {
      for (const id of ["s1", "s2", "s3", "s4"]) mark(id, "not_covered", `setup failed: ${error.message}`);
      return null;
    });

    if (setup) {
      const { channel, task, backlog } = setup;
      const seedText = `${TEST_PREFIX} seed message ${runTag}`;

      // ---------- choose the server in the app ----------
      await step("choose-server", async () => {
        shell(`pm grant ${PKG} android.permission.POST_NOTIFICATIONS`, { allowFailure: true });
        await sleep(500);
        await answerPermissionPrompt(nodes());
        const list = nodes();
        const row = find(list, (n) => n.pkg === PKG && n.text.trim() === serverName);
        if (find(list, (n) => n.text.startsWith("Signed in as ")) && row) {
          tapNode(row);
          await waitUntil("the server's home", (l) => hasText(l, "Drafts") || hasText(l, "Home"), { timeoutMs: 60_000 });
          return { via: "choose_server" };
        }
        return { via: "already_home" };
      }).catch((error) => log(`choose-server: ${error.message}; continuing (the channel link switches server)`));

      // ---------- s1 ----------
      let cardTap = null;
      await scenario("s1", async () => {
        await sstep("s1", "open-channel", async () => {
          // The channel by its row on Home when it is there, else its link (which also switches server).
          const list = nodes();
          const row = find(list, (n) => n.pkg === PKG && (n.text.trim() === channel.name || n.text.trim() === `#${channel.name}` || n.text.trim() === `# ${channel.name}`));
          if (row && hasText(list, "Drafts")) tapNode(row);
          else deepLink(channelUrl(channel.id));
          await waitUntil(`#${channel.name} with its Open Tasks toggle`, channelReady, { timeoutMs: 60_000 });
          return { via: row ? "home_row" : "link" };
        });
        await sstep("s1", "channel-tasks-tab", async () => {
          const opened = await openTasksCard();
          const { row, title } = await findInTasksCard(channel.name, task.number);
          if (row) {
            if (!title || !title.text.includes(task.title.slice(0, 30))) throw new Error(`card row #${task.number} does not read the task's title (${title?.text ?? "none"})`);
            cardTap = row;
            return { listed: true, ...opened };
          }
          throw new Error(`test task #${task.number} is not in the channel's Tasks card; on screen: ${visible()}`);
        });
        await sstep("s1", "open-task-conversation", async () => {
          tapNode(cardTap);
          const composer = await waitUntil("the task conversation (Message this task)", (l) => hasText(l, (n) => n.text === "Message this task" || n.desc === "Message this task" || n.text === "Join Channel to Reply"), { timeoutMs: 45_000 });
          await sleep(1500);
          const { list, chip } = await findTaskHead();
          const problems = [];
          if (!chip) problems.push("no status chip (Task status: …) on the task head");
          if (!titleNode(list, task.title)) problems.push("task title not on screen");
          if (!hasText(list, "Status")) problems.push('no "Status" fact');
          if (!hasText(list, "Assignee")) problems.push('no "Assignee" fact');
          if (problems.length) throw new Error(`${problems.join("; ")}; on screen: ${visible(list)}`);
          return { composer: composer.text || composer.desc, status: statusOf(chip) };
        });
        await sstep("s1", "conversation-shows-messages", async () => {
          for (let i = 0; i < 5; i += 1) {
            if (titleNode(nodes(), seedText)) return { seed_message_visible: true };
            swipeUp();
            await sleep(1000);
          }
          throw new Error(`the seed message is not shown in the task conversation; on screen: ${visible()}`);
        });
        await sstep("s1", "reply-in-task-conversation", async () => {
          const field = await waitUntil("the composer", (l) => find(l, (n) => n.pkg === PKG && n.cls === "android.widget.EditText"));
          tapNode(field);
          await sleep(600);
          await typeText(REPLY_TEXT);
          await sleep(800);
          const typed = find(nodes(), (n) => n.cls === "android.widget.EditText" && n.text.includes("task v2 check"));
          if (!typed) throw new Error(`the reply was not typed into the composer; on screen: ${visible()}`);
          const send = await waitUntil("Send", (l) => hasText(l, (n) => (n.desc === "Send" || n.text === "Send") && n.enabled), { timeoutMs: 10_000 });
          tapNode(send);
          await waitUntil("the reply in the conversation", (l) => find(l, (n) => n.pkg === PKG && n.cls !== "android.widget.EditText" && n.text.includes(REPLY_TEXT)), { timeoutMs: 30_000 });
          // The server has it, in the task's conversation.
          await sleep(2000);
          const messages = ok(await api("GET", `messages/channel/${task.conversationId}?limit=20`), "read the task conversation");
          const found = JSON.stringify(messages).includes(REPLY_TEXT);
          if (!found) throw new Error("the reply shows in the app but the server's task conversation does not have it");
          return { server_has_reply: true };
        });
      });

      // The task conversation page must be on screen for s2 / s3; reopen it if s1 stopped early.
      const ensureTaskOpen = async (id) => {
        if (statusChip(nodes())) return;
        if ((await findTaskHead()).chip) return;
        await sstep(id, "reopen-task-conversation", () => reopenTask());
      };
      const reopenTask = async () => {
        deepLink(channelUrl(channel.id));
        await sleep(3000);
        await openTasksCard();
        const { row } = await findInTasksCard(channel.name, task.number);
        if (!row) throw new Error(`task #${task.number} not in the Tasks card; on screen: ${visible()}`);
        tapNode(row);
        await waitUntil("the task conversation", (l) => statusChip(l) || hasText(l, "Message this task"), { timeoutMs: 45_000 });
        const { chip } = await findTaskHead();
        if (!chip) throw new Error(`the task head is not on screen; on screen: ${visible()}`);
        return { reopened: true };
      };

      // ---------- s2 ----------
      await scenario("s2", async () => {
        await ensureTaskOpen("s2");
        const before = await getTask(task.id, channel.id);
        await sstep("s2", "open-assignee-picker", async () => {
          const { list } = await findTaskHead();
          const trigger = hasText(list, (n) => n.text === "Unassigned" || (before?.claimedByName && n.text === before.claimedByName));
          if (!trigger) throw new Error(`no assignee picker trigger on the head; on screen: ${visible(list)}`);
          tapNode(trigger);
          await waitUntil("the assignee picker", (l) => hasText(l, (n) => n.text === "Search assignees" || n.desc === "Search assignees" || n.text === "Assignee"), { timeoutMs: 15_000 });
          return { before: before?.claimedByName ?? "Unassigned" };
        });
        await sstep("s2", "pick-qa-account", async () => {
          const name = setup.me.name;
          let row = find(nodes(), (n) => n.pkg === PKG && n.text.trim() === name);
          if (!row) {
            const search = find(nodes(), (n) => n.cls === "android.widget.EditText");
            if (search) {
              tapNode(search);
              await sleep(500);
              await typeText(name.slice(0, 12));
              await sleep(1500);
            }
            row = await waitUntil(`"${name}" in the picker`, (l) => find(l, (n) => n.pkg === PKG && n.cls !== "android.widget.EditText" && n.text.trim() === name), { timeoutMs: 15_000 });
          }
          tapNode(row);
          await sleep(2500);
          return { picked: name };
        });
        await sstep("s2", "assignee-reflected", async () => {
          const name = setup.me.name;
          const { list } = await findTaskHead();
          const onHead = hasText(list, (n) => n.text.trim() === name);
          const after = await getTask(task.id, channel.id);
          const problems = [];
          if (!onHead) problems.push(`"${name}" is not shown as the assignee on the head`);
          if (after?.claimedById !== setup.me.id) problems.push(`server assignee is ${after?.claimedByName ?? "none"}, expected the QA account`);
          if (problems.length) throw new Error(`${problems.join("; ")}; on screen: ${visible(list)}`);
          return { server_assignee: after.claimedByName, status_after_assign: after.status };
        });
      });

      // ---------- s3 ----------
      await scenario("s3", async () => {
        await ensureTaskOpen("s3");
        const offered = {};
        const moveTo = async (target) => {
          await sstep("s3", `menu-from-${(statusOf((await findTaskHead()).chip) ?? "unknown").toLowerCase().replace(/\s+/g, "-")}`, async () => {
            const { chip } = await findTaskHead();
            if (!chip) throw new Error(`no status chip; on screen: ${visible()}`);
            tapNode(chip);
            const menu = await waitUntil("the status menu", (l) => hasText(l, "Done") && hasText(l, (n) => n.text === "Todo" || n.text === "Reopen to Todo"), { timeoutMs: 10_000 });
            const list = nodes();
            const options = ["Backlog", "Todo", "In Progress", "In Review", "Done", "Closed", "Reopen to Todo"].filter((s) => hasText(list, (n) => n.text === s && n.bounds[1] > 0));
            offered[statusOf(chip)] = options;
            if (options.includes("In Review")) throw new Error(`"In Review" is offered for a task v2 task (menu: ${options.join(", ")})`);
            return { from: statusOf(chip), options, menu_seen: Boolean(menu) };
          });
          await sstep("s3", `pick-${target.toLowerCase().replace(/\s+/g, "-")}`, async () => {
            const option = await waitUntil(`"${target}" in the menu`, (l) => hasText(l, (n) => n.text === target), { timeoutMs: 5_000 });
            tapNode(option);
            await waitUntil(`the head to read ${target}`, (l) => statusOf(statusChip(l)) === target, { timeoutMs: 20_000 });
            const wire = { "In Progress": "in_progress", Done: "done", Todo: "todo" }[target];
            await sleep(1500);
            const after = await getTask(task.id, channel.id);
            if (after?.status !== wire) throw new Error(`the server says ${after?.status}, expected ${wire}`);
            return { server_status: after.status };
          });
        };
        // Assigning may already have started it (in_progress); bring it to Todo first through the same menu.
        const current = statusOf((await findTaskHead()).chip);
        if (current !== "Todo") await moveTo("Todo");
        await moveTo("In Progress");
        await moveTo("Done");
        return `menus: ${Object.entries(offered).map(([k, v]) => `${k} -> [${v.join(", ")}]`).join("; ")}`;
      });

      // ---------- s4 ----------
      await scenario("s4", async () => {
        await sstep("s4", "backlog-in-channel-tasks", async () => {
          deepLink(channelUrl(channel.id));
          await sleep(3000);
          // While the conversation is still uncovered: its inline task card for the backlog task.
          await waitUntil("the channel", channelReady, { timeoutMs: 45_000 });
          const inline = hasText(nodes(), (n) => n.desc.startsWith(`Open task #${backlog.number}:`));
          const opened = await openTasksCard();
          const { row, title, list } = await findInTasksCard(channel.name, backlog.number, { sectionToggle: true, tries: 30 });
          if (row) {
            if (!title || !title.text.includes(backlog.title.slice(0, 30))) throw new Error(`card row #${backlog.number} does not read the backlog task's title (${title?.text ?? "none"})`);
            // Its status chip, on the row under the title, reads Backlog; and it sits in the Backlog group.
            const chip = list.find((n) => n.pkg === PKG && n.bounds && n.text.trim() === "Backlog" && n.bounds[1] > title.bounds[3] - 5 && n.bounds[1] < title.bounds[3] + 300);
            const group = list.filter((n) => n.pkg === PKG && n.bounds && n.bounds[0] < 300 && /^(Backlog|Todo|In Progress|Done|Closed|In Review)$/.test(n.text.trim()) && n.bounds[3] <= row.bounds[1]).pop();
            if (!chip) throw new Error(`the backlog task is listed but its row does not read Backlog; on screen: ${visible(list)}`);
            cardTap = row;
            return { listed: true, row_status: chip.text, group: group?.text ?? null, inline_card_in_chat: Boolean(inline), ...opened };
          }
          throw new Error(`backlog test task #${backlog.number} not found in the channel's Tasks card (inline card in chat: ${Boolean(inline)}); on screen: ${visible()}`);
        });
        await sstep("s4", "backlog-task-opens-as-backlog", async () => {
          tapNode(cardTap);
          await waitUntil("the backlog task's conversation", (l) => statusChip(l) || hasText(l, "Message this task"), { timeoutMs: 45_000 });
          const { chip, list } = await findTaskHead();
          if (statusOf(chip) !== "Backlog") throw new Error(`the head reads ${statusOf(chip) ?? "no status"}, expected Backlog; on screen: ${visible(list)}`);
          return { head_status: statusOf(chip) };
        });
      });
    }

    // ---------- s5 ----------
    await scenario("s5", async () => {
      if (!server) throw notCovered("server not resolved");
      const candidate = await step("s5-find-old-cli-task", async () => {
        const json = ok(await api("GET", "tasks/server?detail=summary&limit=200&sort=created"), "list server tasks");
        const tasks = listOf(json, "tasks").length ? listOf(json, "tasks") : listOf(json, "items");
        const agentMade = tasks.filter((t) => t.createdByType === "agent" && t.sourceSenderType === "agent" && t.sourceSenderId === t.createdById && typeof t.sourceMessageId === "string" && !t.isLegacy);
        const checked = [];
        for (const t of agentMade.slice(0, 25)) {
          const out = await api("GET", `messages/context/${t.sourceMessageId}`);
          if (out.status !== 200) continue;
          const message = findMessage(out.json, t.sourceMessageId);
          if (!message) continue;
          const content = String(message.content ?? "").trim();
          const titled = content === String(t.title ?? "").trim() || content.startsWith(`${String(t.title ?? "").trim()}\n\n`) || content.split("\n").includes(`- ${String(t.title ?? "").trim()}`);
          const gap = Math.abs(Date.parse(message.createdAt ?? 0) - Date.parse(t.createdAt ?? 0));
          checked.push({ number: t.taskNumber, channel: t.channelName, titled, gap_ms: gap });
          if (titled && gap < 120_000) return { task: { id: t.id, number: t.taskNumber, title: t.title, channelId: t.channelId, channelName: t.channelName, sourceMessageId: t.sourceMessageId, createdByName: t.createdByName }, checked };
        }
        return { task: null, checked, agent_tasks_with_own_source: agentMade.length, tasks_scanned: tasks.length };
      });
      if (!candidate?.task) throw notCovered(`no task on ${serverName} looks like an old CLI's sourceless create (agent-created, source = the agent's own message whose text is the title, posted with the task); scanned ${candidate?.tasks_scanned ?? 0} tasks`);
      const t = candidate.task;
      await sstep("s5", "open-title-message", async () => {
        deepLink(channelUrl(t.channelId, t.sourceMessageId));
        await waitUntil("the source message", (l) => find(l, (n) => n.pkg === PKG && n.text.includes(String(t.title).slice(0, 40))), { timeoutMs: 60_000 });
        return { task: `#${t.number} ${t.title}`, channel: t.channelName, created_by: t.createdByName };
      });
      await sstep("s5", "open-task-from-message", async () => {
        // ThreadMessageSourceTaskList: "Open task #N: <title>" under the source message.
        const entry = await waitUntil(`"Open task #${t.number}" under the message`, (l) =>
          hasText(l, (n) => n.desc.startsWith(`Open task #${t.number}`) || n.text.startsWith(`Open task #${t.number}`) || (n.clickable && n.text.includes(`#${t.number}`))) ??
          hasText(l, (n) => n.text === `#${t.number}`), { timeoutMs: 20_000 });
        tapNode(entry);
        await waitUntil("the task conversation", (l) => statusChip(l) || hasText(l, "Message this task") || hasText(l, "Join Channel to Reply"), { timeoutMs: 45_000 });
        const { chip, list } = await findTaskHead();
        if (!chip) throw new Error(`the task conversation opened without its head; on screen: ${visible(list)}`);
        if (!titleNode(list, String(t.title).slice(0, 40))) throw new Error(`the task conversation does not show the title; on screen: ${visible(list)}`);
        return { head_status: statusOf(chip) };
      });
    });
  } finally {
    // Back to Home, where the runner's own Sign out starts.
    for (let i = 0; i < 8; i += 1) {
      const list = nodes();
      if (hasText(list, "Drafts") && hasText(list, "More")) break;
      if (!list.some((n) => n.pkg === PKG)) break;
      await back();
    }
    try {
      const crash = ctx.adb(["logcat", "-d", "-b", "crash"], { allowFailure: true });
      (await import("node:fs")).writeFileSync(`${ctx.OUT}/logcat-crash.txt`, String(crash ?? ""));
      report.app_crash_logged = /build\.raft\.app/.test(String(crash ?? ""));
    } catch (e) {
      log(`crash log: ${e.message}`);
    }
    ctx.launchApp();
    await sleep(2500);
    await answerPermissionPrompt(nodes());
    await step("scenario-end", async () => ({ on_screen: visible() })).catch(() => {});
    // ---------- cleanup: delete the test tasks this run created ----------
    const cleanup = [];
    for (const t of created.tasks) {
      const out = await api("DELETE", `tasks/${t.id}`).catch((e) => ({ status: 0, json: { error: e.message } }));
      cleanup.push({ task: t.number, status: out.status });
    }
    report.cleanup = { deleted_tasks: cleanup, left_messages: "the source messages the server posted for the test tasks (titled [QA test data, please ignore]) and the seed/reply in the deleted tasks' conversations" };
    log(`scenario cleanup: ${JSON.stringify(cleanup)}`);
  }
  const failed = Object.values(results).some((r) => r.result === "fail");
  report.result = failed ? "fail" : "pass";
  return report;
}

/** The message with [id] anywhere in a messages/context response (shape not assumed). */
function findMessage(json, id) {
  const seen = new Set();
  const walk = (value) => {
    if (!value || typeof value !== "object" || seen.has(value)) return null;
    seen.add(value);
    if (!Array.isArray(value) && value.id === id && "content" in value) return value;
    for (const child of Array.isArray(value) ? value : Object.values(value)) {
      const hit = walk(child);
      if (hit) return hit;
    }
    return null;
  };
  return walk(json);
}
