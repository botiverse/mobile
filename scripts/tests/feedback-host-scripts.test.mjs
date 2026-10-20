import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';
import test from 'node:test';

const root = new URL('../../', import.meta.url);
const read = path => readFileSync(new URL(path, root), 'utf8');
// Since the CMP switch both WebView hosts live in :shared (RaftSandboxWebView.{android,ios}.kt).
const androidNative = read('compose/shared/src/androidMain/kotlin/build/raft/app/RaftSandboxWebView.android.kt');
const iosNative = read('compose/shared/src/iosMain/kotlin/build/raft/app/RaftSandboxWebView.ios.kt');
const android = androidNative.match(/const val HOST_BRIDGE_SCRIPT = """([\s\S]*?)"""/)[1];
// The iOS bridge is a concatenation of Kotlin string literals: join and unescape them.
const ios = iosNative
  .match(/private const val RaftIosHostBridgeScript =([\s\S]*?)\n\n/)[1]
  .match(/"(?:\\.|[^"\\])*"/g)
  .map(literal => JSON.parse(literal))
  .join('');
const bootstrap = read('compose/shared/src/commonMain/kotlin/build/raft/app/SettingsStandaloneWebViewHost.kt')
  .split('internal fun feedbackWebViewBootstrapJavaScript(')[1]
  .match(/return """([\s\S]*?)"""/)[1]
  .replaceAll('$accountId', '"account-one"')
  .replaceAll('$sessionGeneration', '3')
  .replaceAll('$webViewGeneration', '4')
  .replaceAll('$accessToken', '"test-token"')
  .replaceAll('$api', '"https://example.test"')
  .replaceAll('$trustedUrl', '"https://example.test/feedback/"')
  .replaceAll('$clientKind', '"android"')
  .replaceAll('$clientVersion', '"1.0.11-alpha+test"')
  .replace('${json.encodeToString(raftFeedbackWebViewClientKind())}', '"android"');

for (const [platform, script] of [['android', android], ['ios', ios]]) {
  test(`${platform}: actual host and feedback scripts keep auth in memory and diagnostics payloads tokenless`, () => {
    const messages = [];
    const port = { postMessage: value => messages.push(JSON.parse(value)) };
    const window = platform === 'android'
      ? { raftHostPort: port }
      : { webkit: { messageHandlers: { raftHostPort: port } } };
    const localStorage = {
      setItem: () => { throw new Error('feedback bootstrap must not write storage'); },
      removeItem: () => { throw new Error('feedback bootstrap must not write storage'); },
    };
    window.top = window;
    window.self = window;
    const context = vm.createContext({
      window,
      navigator: { language: 'en' },
      localStorage,
      location: { origin: 'https://example.test', pathname: '/feedback/', search: '' },
      URL,
    });
    vm.runInContext(script, context, { filename: `${platform}-host.js` });
    vm.runInContext(bootstrap, context, { filename: 'feedback-bootstrap.js' });
    assert.equal(window.RaftFeedback.session.accessToken, 'test-token');
    assert.equal(window.RaftFeedback.session.apiOrigin, 'https://example.test');
    assert.equal(messages.some(message => JSON.stringify(message).includes('test-token')), false);
    window.RaftFeedback.diagnostics.start(
      { requestId: 'request-1' },
      { accountId: 'account-one', sessionGeneration: 3, webViewGeneration: 4 },
    );
    assert.deepEqual(messages, [{
      kind: 'diagnostics:start',
      payload: {
        requestId: 'request-1',
        binding: { accountId: 'account-one', sessionGeneration: 3, webViewGeneration: 4 },
      },
    }]);
    window.RaftFeedback.emit('ready');
    assert.deepEqual(messages, [
      {
        kind: 'diagnostics:start',
        payload: {
          requestId: 'request-1',
          binding: { accountId: 'account-one', sessionGeneration: 3, webViewGeneration: 4 },
        },
      },
      { kind: 'ready', payload: { accountId: 'account-one', sessionGeneration: 3, webViewGeneration: 4 } },
    ]);
    assert.ok(!window.RaftHost.capabilities?.includes('diagnostics-upload-v1'));
    assert.equal(window.RaftHost.diagnostics, undefined);
    assert.ok(window.RaftFeedback.capabilities?.includes('diagnostics-upload-v1'));
    assert.equal(typeof window.RaftFeedback.diagnostics?.start, 'function');
    assert.equal(typeof window.RaftFeedback.diagnostics?.cancel, 'function');
  });
}

test('feedback bootstrap maps navigator.language to the host locale contract', () => {
  // The Web host rejects any locale outside ['en', 'zh-CN'], so raw values like
  // 'en-US' or 'zh-cn' must be normalized by the bootstrap, not passed through.
  for (const [raw, expected] of [['en-US', 'en'], ['en', 'en'], ['zh-cn', 'zh-CN'], ['zh-CN', 'zh-CN'], ['zh-Hans-CN', 'zh-CN'], ['ja', 'en'], ['', 'en']]) {
    const window = { raftHostPort: { postMessage: () => {} } };
    window.top = window;
    window.self = window;
    const context = vm.createContext({
      window,
      navigator: { language: raw },
      localStorage: { setItem: () => {}, removeItem: () => {} },
      location: { origin: 'https://example.test', pathname: '/feedback/', search: '' },
      URL,
    });
    vm.runInContext(bootstrap, context, { filename: `feedback-bootstrap-locale-${raw || 'empty'}.js` });
    assert.equal(window.RaftFeedback.session.locale, expected, `navigator.language=${raw}`);
  }
});

test('feedback bootstrap does not materialize a session in a child frame', () => {
  const window = { top: {}, self: {}, raftHostPort: { postMessage: () => { throw new Error('child frame must not post'); } } };
  const context = vm.createContext({
    window,
    navigator: { language: 'en' },
    location: { origin: 'https://example.test', pathname: '/feedback/', search: '' },
    URL,
  });
  vm.runInContext(bootstrap, context, { filename: 'feedback-bootstrap-child.js' });
  assert.equal(window.RaftFeedback, undefined);
});

test('feedback bootstrap does not materialize a session on an untrusted path', () => {
  const window = { top: null, self: null, raftHostPort: { postMessage: () => { throw new Error('untrusted page must not post'); } } };
  window.top = window;
  window.self = window;
  const context = vm.createContext({
    window,
    navigator: { language: 'en' },
    location: { origin: 'https://example.test', pathname: '/account/', search: '' },
    URL,
  });
  vm.runInContext(bootstrap, context, { filename: 'feedback-bootstrap-untrusted-path.js' });
  assert.equal(window.RaftFeedback, undefined);
});

test('native hosts fence auth script and runtime updates to trusted documents', () => {
  assert.match(androidNative, /!allowNavigation && !isAllowedMainDocumentUrl\(activeUrl\)/);
  assert.match(androidNative, /Same-origin history routes must not keep access to the native bridge/);
  assert.match(androidNative, /allowedNavigationSchemes\.isNotEmpty\(\) && \(!allowNavigation/);
  assert.match(iosNative, /forMainFrameOnly = !allowNavigation/);
  assert.match(iosNative, /isSameOriginURL\(activeURL\)/);
  assert.match(iosNative, /!allowNavigation && \(activeURL == null \|\| !isAllowedMainDocumentURL\(activeURL\)\)/);
  assert.match(iosNative, /evaluateJavaScript\(script, completionHandler = null\)/);
});
