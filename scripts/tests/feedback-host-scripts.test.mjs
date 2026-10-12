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
  .replace('${json.encodeToString(slockFeedbackWebViewClientKind())}', '"android"');

for (const [platform, script] of [['android', android], ['ios', ios]]) {
  test(`${platform}: actual host and feedback scripts deliver legacy events without advertising an absent uploader`, () => {
    const messages = [];
    const port = { postMessage: value => messages.push(JSON.parse(value)) };
    const window = platform === 'android'
      ? { raftHostPort: port }
      : { webkit: { messageHandlers: { raftHostPort: port } } };
    const context = vm.createContext({ window, navigator: { language: 'en' } });
    vm.runInContext(script, context, { filename: `${platform}-host.js` });
    vm.runInContext(bootstrap, context, { filename: 'feedback-bootstrap.js' });
    window.RaftFeedback.emit('ready');
    assert.deepEqual(messages, [{ kind: 'ready', payload: { accountId: 'account-one', sessionGeneration: 3, webViewGeneration: 4 } }]);
    for (const host of [window.RaftHost, window.RaftFeedback]) {
      assert.ok(!host.capabilities?.includes('diagnostics-upload-v1'));
      assert.equal(host.diagnostics, undefined);
    }
  });
}
