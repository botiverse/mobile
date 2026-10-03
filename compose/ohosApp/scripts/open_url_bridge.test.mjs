import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import vm from 'node:vm';

// Execute the production methods, with only HarmonyOS services replaced.
// Set TYPESCRIPT_PATH when the compiler is not installed as a Node dependency.
const require = createRequire(import.meta.url);
const ts = require(process.env.TYPESCRIPT_PATH || 'typescript');
const source = readFileSync(process.env.BRIDGE_SOURCE ||
  new URL('../entry/src/main/ets/kuikly/modules/KRBridgeModule.ets', import.meta.url), 'utf8');
const ast = ts.createSourceFile('bridge.ts', source, ts.ScriptTarget.Latest, true);
const methods = [];
const fields = [];
function visit(node) {
  if (ts.isPropertyDeclaration(node) && node.name?.getText(ast).startsWith('openUrl')) {
    fields.push(node.getText(ast));
  }
  if (ts.isMethodDeclaration(node) && ['openUrl', 'parseRecord'].includes(node.name?.getText(ast))) {
    methods.push(node.getText(ast));
  }
  ts.forEachChild(node, visit);
}
visit(ast);
assert.equal(methods.length, 2, 'production bridge methods must exist');
const compiled = ts.transpileModule(
  'class Harness { ' + fields.join('\n') + methods.join('\n') + ' }; globalThis.Harness = Harness;',
  { compilerOptions: { target: ts.ScriptTarget.ES2020 } }).outputText;

async function invoke(payload, reject = false) {
  const wants = [], pages = [], diagnostics = [];
  const sandbox = {
    getContext: () => ({ startAbility: want => {
      wants.push(want);
      return reject ? Promise.reject({ code: 17700003, message: 'no ability' }) : Promise.resolve();
    } }),
    router: { pushUrl: page => pages.push(page) },
    SlockOhosDiagnostics: { record: (...args) => diagnostics.push(args) },
    console: { error() {} },
  };
  vm.runInNewContext(compiled, sandbox);
  new sandbox.Harness().openUrl(payload);
  await new Promise(resolve => setImmediate(resolve));
  return JSON.parse(JSON.stringify({ wants, pages, diagnostics }));
}
for (const encode of [JSON.stringify, value => value]) {
  test('launches legal URL from ' + (encode === JSON.stringify ? 'shared JSON string' : 'object'), async () => {
    const result = await invoke(encode({ url: 'https://raft.build/privacy', mimeType: 'text/html' }));
    assert.equal(result.wants.length, 1);
    assert.deepEqual(result.wants[0], {
      action: 'ohos.want.action.viewData', entities: ['entity.system.browsable'],
      uri: 'https://raft.build/privacy', type: 'text/html',
    });
    assert.deepEqual(result.pages, []);
  });
}
test('missing MIME type still launches the browser', async () => {
  const result = await invoke(JSON.stringify({ url: 'https://raft.build/terms' }));
  assert.equal(result.wants[0].uri, 'https://raft.build/terms');
  assert.equal(result.wants[0].type, undefined);
});
test('invalid payloads never launch or throw', async () => {
  for (const payload of [null, undefined, '{', 'null', '42', '[]', '{}',
    '{"url":false}', '{"url":12}', '{"url":""}', { url: false }]) {
    const result = await invoke(payload);
    assert.deepEqual(result.wants, []);
    assert.deepEqual(result.pages, []);
  }
});
test('browser rejection preserves HTTP fallback', async () => {
  const result = await invoke('{"url":"https://raft.build/privacy"}', true);
  assert.equal(result.pages.length, 1);
  assert.equal(result.pages[0].params.pageData.url, 'https://raft.build/privacy');
  assert.equal(result.pages[0].params.pageName, 'compose_in_app_browser');
});
test('non-HTTP schemes never enter web fallback', async () => {
  const result = await invoke('{"url":"mailto:test@example.com"}', true);
  assert.equal(result.wants.length, 1);
  assert.deepEqual(result.pages, []);
});

function createLaunchHarness() {
  const wants = [], pages = [], diagnostics = [];
  let now = 10000;
  let settle;
  const sandbox = {
    Date: { now: () => now },
    getContext: () => ({ startAbility: want => {
      wants.push(want);
      return new Promise((resolve, reject) => { settle = { resolve, reject }; });
    } }),
    router: { pushUrl: page => pages.push(page) },
    SlockOhosDiagnostics: { record: (...args) => diagnostics.push(args) },
    console: { error() {} },
  };
  vm.runInNewContext(compiled, sandbox);
  return { bridge: new sandbox.Harness(), wants, pages, diagnostics,
    advance: ms => { now += ms; }, settle: () => settle };
}
const legalPayload = '{"url":"https://raft.build/terms"}';
test('double tap while native launch is pending sends one request and no fallback', async () => {
  const h = createLaunchHarness();
  h.bridge.openUrl(legalPayload);
  h.advance(100);
  h.bridge.openUrl(legalPayload);
  assert.equal(h.wants.length, 1);
  h.settle().resolve();
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(h.pages.length, 0);
});
test('double tap after fast success is ignored; later intentional reopen works', async () => {
  const h = createLaunchHarness();
  h.bridge.openUrl(legalPayload);
  h.settle().resolve();
  await new Promise(resolve => setImmediate(resolve));
  h.advance(100);
  h.bridge.openUrl(legalPayload);
  assert.equal(h.wants.length, 1);
  h.advance(1500);
  h.bridge.openUrl(legalPayload);
  assert.equal(h.wants.length, 2);
});
test('real native failure retains one fallback and permits retry', async () => {
  const h = createLaunchHarness();
  h.bridge.openUrl(legalPayload);
  h.settle().reject({ code: 17700003, message: 'no ability' });
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(h.pages.length, 1);
  h.bridge.openUrl(legalPayload);
  assert.equal(h.wants.length, 2);
});
test('different URLs are not debounced and an unsettled launch does not block later retry', () => {
  const h = createLaunchHarness();
  h.bridge.openUrl(legalPayload);
  h.bridge.openUrl('{"url":"https://raft.build/privacy"}');
  assert.equal(h.wants.length, 2);
  h.advance(1500);
  h.bridge.openUrl('{"url":"https://raft.build/privacy"}');
  assert.equal(h.wants.length, 3);
});
