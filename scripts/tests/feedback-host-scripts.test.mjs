import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';
import test from 'node:test';

const root = new URL('../../', import.meta.url);
const read = path => readFileSync(new URL(path, root), 'utf8');
const android = read('compose/androidApp/src/main/java/build/raft/app/shared/SlockSandboxWebViewRenderView.kt')
  .match(/private const val hostBridgeScript = """([\s\S]*?)"""/)[1];
const iosLiteral = read('compose/iosApp/iosApp/KuiklyExpand/Components/SlockSandboxWebView.m')
  .match(/NSString \*bridge = @("(?:\\.|[^"\\])*");/)[1];
const ios = JSON.parse(iosLiteral);
const bootstrap = read('compose/shared/src/commonMain/kotlin/build/raft/app/SettingsStandaloneWebViewHost.kt')
  .split('internal fun feedbackWebViewBootstrapJavaScript(')[1]
  .match(/return """([\s\S]*?)"""/)[1]
  .replaceAll('$accountId', '"account-one"')
  .replaceAll('$sessionGeneration', '3')
  .replaceAll('$webViewGeneration', '4')
  .replaceAll('$accessToken', '"test-token"')
  .replaceAll('$api', '"https://example.test"')
  .replaceAll('$clientKind', '"android"')
  .replaceAll('$clientVersion', '"1.0.11-alpha+test"')
  .replace('${json.encodeToString(slockFeedbackWebViewClientKind())}', '"android"');

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
    const context = vm.createContext({ window, navigator: { language: 'en' }, localStorage });
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
