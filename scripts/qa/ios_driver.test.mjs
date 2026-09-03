import test from "node:test";
import assert from "node:assert/strict";
import {
  agentDeviceTargetArgs,
  compactDumpUi,
  mapIosKeyToAgentDevice,
  parseAlertGet,
  parseJsonStdout,
  pickTapText,
  resolveIosQaDriver,
  snapshotToDescribeNodes,
  swipePoints,
  unwrapSnapshotPayload,
} from "./ios_driver.mjs";

test("default iOS QA driver is agent-device and qa-loop remains explicit", () => {
  const previous = process.env.IOS_QA_DRIVER;
  delete process.env.IOS_QA_DRIVER;
  try {
    assert.equal(resolveIosQaDriver(), "agent-device");
    assert.equal(resolveIosQaDriver("qa-loop"), "qa-loop");
    process.env.IOS_QA_DRIVER = "qa-loop";
    assert.equal(resolveIosQaDriver(), "qa-loop");
    assert.throws(() => resolveIosQaDriver("xctest"), /unknown iOS QA driver/);
  } finally {
    if (previous === undefined) delete process.env.IOS_QA_DRIVER;
    else process.env.IOS_QA_DRIVER = previous;
  }
});

test("snapshot JSON unwraps daemon and data envelopes into describe nodes", () => {
  const payload = unwrapSnapshotPayload({
    data: {
      nodes: [
        { ref: "@e1", label: "Email", role: "textField", value: "ada", focused: true, rect: { x: 10, y: 20, width: 100, height: 40 } },
        { ref: "@e2", identifier: "add", role: "button", hittable: true, rect: { x: 300, y: 8, width: 44, height: 44 } },
      ],
    },
  });
  const nodes = snapshotToDescribeNodes(payload.nodes);
  assert.equal(nodes[0].editable, true);
  assert.equal(nodes[0].focused, true);
  assert.deepEqual(nodes[0].frame, [10, 20, 100, 40]);
  assert.equal(nodes[1].clickable, true);
  assert.equal(nodes[1].id, "add");
});

test("dumpUi keeps pixel bounds and INPUT/BTN flags", () => {
  const nodes = snapshotToDescribeNodes([
    { label: "Email", role: "textField", value: "ada", rect: { x: 10, y: 20, width: 100, height: 40 } },
    { role: "button", hittable: true, rect: { x: 300, y: 8, width: 44, height: 44 } },
  ]);
  const dump = compactDumpUi(nodes, (v) => Math.round(v * 3));
  assert.match(dump, /\[INPUT\] "ada" \[30,60\]\[330,180\]/);
  assert.match(dump, /\[BTN\] "" \[900,24\]\[1032,156\]/);
});

test("tapText prefers the smallest clickable match and returns pixel centers", () => {
  const nodes = snapshotToDescribeNodes([
    { label: "Add", role: "other", rect: { x: 0, y: 0, width: 400, height: 400 } },
    { label: "Add", role: "button", hittable: true, rect: { x: 10, y: 10, width: 20, height: 20 } },
  ]);
  const picked = pickTapText(nodes, "Add", (v) => Math.round(v * 2));
  assert.equal(picked.x, 40);
  assert.equal(picked.y, 40);
  assert.match(picked.feedback, /2 matches; picked the smallest clickable/);
});

test("parseAlertGet treats notification permission copy as present", () => {
  assert.equal(parseAlertGet("").present, false);
  assert.equal(parseAlertGet("no alert").present, false);
  assert.match("alert not found", /alert not found|no (active )?alert|NO_ALERT/i);
  assert.equal(parseAlertGet('{"title":"Raft Would Like to Send You Notifications","buttons":["Don\'t Allow","Allow"]}').present, true);
  assert.equal(parseAlertGet("Raft Would Like to Send You Notifications\nAllow").present, true);
});

test("iOS key() maps esc/enter onto keyboard commands and rejects hardware keys", () => {
  assert.deepEqual(mapIosKeyToAgentDevice("esc"), { argv: ["keyboard", "dismiss"], optional: true });
  assert.deepEqual(mapIosKeyToAgentDevice("enter"), { argv: ["keyboard", "enter"], optional: false });
  assert.throws(() => mapIosKeyToAgentDevice("del"), /no hardware key/);
});

test("CI UDIDs go to --udid so duplicate simulator names cannot steal the lease", () => {
  assert.deepEqual(
    agentDeviceTargetArgs("7065B5F0-2834-4150-99DF-84E057A22FD0"),
    ["--udid", "7065B5F0-2834-4150-99DF-84E057A22FD0"],
  );
  assert.deepEqual(agentDeviceTargetArgs("iPhone 16e"), ["--device", "iPhone 16e"]);
});

test("coordinate swipes stay in points so left-edge can reach interactive-pop", () => {
  assert.deepEqual(swipePoints("left-edge", { w: 390, h: 844 }), [4, 422, 273, 422]);
  assert.deepEqual(swipePoints("up", { w: 390, h: 844 }), [195, 675, 195, 169]);
});

test("parseJsonStdout accepts a leading banner then a JSON object", () => {
  const parsed = parseJsonStdout("agent-device 0.20.10\n{\"nodes\":[]}\n");
  assert.deepEqual(parsed, { nodes: [] });
});
