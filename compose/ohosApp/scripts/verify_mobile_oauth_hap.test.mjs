import { test } from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync, spawnSync } from 'node:child_process';
import { mkdtempSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

const verifier = fileURLToPath(new URL('./verify_mobile_oauth_hap.mjs', import.meta.url));
function verify(scheme, mutate = () => {}) {
  const ability = {
    name: 'EntryAbility', exported: true,
    skills: [{
      actions: ['ohos.want.action.viewData'], entities: ['entity.system.browsable'],
      uris: ['oauth', 'login'].map(host => ({ scheme, host, path: 'callback' })),
    }],
  };
  mutate(ability);
  const dir = mkdtempSync(join(tmpdir(), 'ohos-auth-callback-'));
  try {
    writeFileSync(join(dir, 'module.json'), JSON.stringify({ module: { abilities: [ability] } }));
    execFileSync('zip', ['-q', 'fixture.hap', 'module.json'], { cwd: dir });
    return spawnSync(process.execPath, [verifier, join(dir, 'fixture.hap'), scheme], { encoding: 'utf8' });
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
}
for (const scheme of ['raft', 'raft-debug']) {
  test(`${scheme}: normal single-slash browser callbacks are registered`, () => {
    const result = verify(scheme);
    assert.equal(result.status, 0, result.stderr);
  });
  test(`${scheme}: rejects leading slash which resolves to //callback`, () => {
    const result = verify(scheme, a => { a.skills[0].uris[1].path = '/callback'; });
    assert.notEqual(result.status, 0);
    assert.match(result.stderr, /Expected one OAuth and one login callback URI/);
  });
  test(`${scheme}: rejects callback for another build mode`, () => {
    const result = verify(scheme, a => { a.skills[0].uris[1].scheme = scheme === 'raft' ? 'raft-debug' : 'raft'; });
    assert.notEqual(result.status, 0);
    assert.match(result.stderr, /Unexpected packaged login callback URI/);
  });
}
test('rejects missing login route', () => {
  assert.notEqual(verify('raft', a => { a.skills[0].uris.pop(); }).status, 0);
});
test('rejects non-exported ability', () => {
  assert.notEqual(verify('raft', a => { a.exported = false; }).status, 0);
});
test('rejects non-browsable callback', () => {
  assert.notEqual(verify('raft', a => { a.skills[0].entities = []; }).status, 0);
});
