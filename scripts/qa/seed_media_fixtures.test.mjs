import assert from "node:assert/strict";
import test from "node:test";
import {
  FIXTURE_MESSAGE_MARKER,
  fixturePngBytes,
  hasFixtureMessage,
  isPngSignature,
  pickChannel,
  pickServer,
  seedWorkspaceFixture,
} from "./seed_media_fixtures.mjs";

test("fixture image is a real deterministic PNG", () => {
  const bytes = fixturePngBytes();
  assert.ok(isPngSignature(bytes), "must start with the PNG signature");
  assert.ok(bytes.length > 100, "must be a renderable image, not a stub");
  assert.deepEqual(fixturePngBytes(), bytes, "must be byte-deterministic");
});

test("server and channel selection prefer the QA targets with fallback", () => {
  const servers = [
    { id: "s1", name: "Something Else" },
    { id: "s2", name: "OranixSlockTest" },
  ];
  assert.equal(pickServer(servers, "").id, "s2");
  assert.equal(pickServer(servers, "something").id, "s1");
  assert.equal(pickServer([{ id: "only", name: "x" }], "").id, "only");
  assert.equal(pickServer([], ""), null);

  const channels = [
    { id: "c1", name: "random" },
    { id: "c2", name: "all" },
  ];
  assert.equal(pickChannel(channels, "all").id, "c2");
  assert.equal(pickChannel(channels, "#all").id, "c2");
  assert.equal(pickChannel(channels, "missing").id, "c1", "falls back to first channel");
});

test("fixture presence requires the marker AND an attachment", () => {
  assert.ok(
    hasFixtureMessage([
      { content: `${FIXTURE_MESSAGE_MARKER} x`, attachments: [{ id: "a" }] },
    ]),
  );
  assert.ok(!hasFixtureMessage([{ content: `${FIXTURE_MESSAGE_MARKER} x`, attachments: [] }]));
  assert.ok(!hasFixtureMessage([{ content: "unrelated", attachments: [{ id: "a" }] }]));
  assert.ok(!hasFixtureMessage([]));
  assert.ok(!hasFixtureMessage(undefined));
});

function fakeFetchScript(script) {
  const calls = [];
  const fetchImpl = async (url, options = {}) => {
    calls.push({ url: String(url), method: options.method || "GET" });
    const step = script.shift();
    assert.ok(step, `unexpected request: ${url}`);
    assert.match(String(url), step.match);
    return {
      status: step.status,
      text: async () => JSON.stringify(step.body),
    };
  };
  return { fetchImpl, calls };
}

test("workspace seed uploads and posts exactly once when fixture is absent", async () => {
  const { fetchImpl, calls } = fakeFetchScript([
    { match: /auth\/login$/, status: 200, body: { accessToken: "tok" } },
    { match: /auth\/servers$/, status: 200, body: [{ id: "srv", name: "OranixSlockTest" }] },
    { match: /channels\/inbox/, status: 200, body: [{ id: "chan", name: "all" }] },
    { match: /messages\/channel\/chan/, status: 200, body: { messages: [] } },
    { match: /attachments\/upload$/, status: 201, body: { attachments: [{ id: "att-1" }] } },
    { match: /messages$/, status: 201, body: { id: "msg-1" } },
  ]);
  const result = await seedWorkspaceFixture({
    apiBase: "https://api.test/api",
    email: "qa@example.com",
    password: "pw",
    serverName: "",
    channelName: "all",
    fetchImpl,
    log: () => {},
  });
  assert.equal(result.seeded, true);
  assert.equal(result.attachmentId, "att-1");
  assert.equal(calls.length, 6, "no extra requests");
});

test("workspace seed is idempotent when the fixture message already exists", async () => {
  const { fetchImpl, calls } = fakeFetchScript([
    { match: /auth\/login$/, status: 200, body: { accessToken: "tok" } },
    { match: /auth\/servers$/, status: 200, body: [{ id: "srv", name: "OranixSlockTest" }] },
    { match: /channels\/inbox/, status: 200, body: [{ id: "chan", name: "all" }] },
    {
      match: /messages\/channel\/chan/,
      status: 200,
      body: {
        messages: [
          { content: `${FIXTURE_MESSAGE_MARKER} here`, attachments: [{ id: "old" }] },
        ],
      },
    },
  ]);
  const result = await seedWorkspaceFixture({
    apiBase: "https://api.test/api",
    email: "qa@example.com",
    password: "pw",
    serverName: "",
    channelName: "all",
    fetchImpl,
    log: () => {},
  });
  assert.equal(result.seeded, false);
  assert.equal(result.reason, "already_present");
  assert.equal(calls.length, 4, "must not upload or post again");
});

test("workspace seed fails closed on login failure without further requests", async () => {
  const { fetchImpl, calls } = fakeFetchScript([
    { match: /auth\/login$/, status: 401, body: { error: "unauthorized" } },
  ]);
  const result = await seedWorkspaceFixture({
    apiBase: "https://api.test/api",
    email: "qa@example.com",
    password: "bad",
    serverName: "",
    channelName: "all",
    fetchImpl,
    log: () => {},
  });
  assert.equal(result.seeded, false);
  assert.equal(result.reason, "login_failed");
  assert.equal(calls.length, 1);
});
