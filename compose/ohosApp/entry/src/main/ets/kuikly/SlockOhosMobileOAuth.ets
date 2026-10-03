import { cryptoFramework } from '@kit.CryptoArchitectureKit';
import util from '@ohos.util';
import { invoke } from '@kuiklybase/knoi';
import BuildProfile from 'BuildProfile';

export const SLOCK_OHOS_MOBILE_OAUTH_SCHEME: string = BuildProfile.DEBUG ? 'raft-debug' : 'raft';
export const SLOCK_OHOS_MOBILE_OAUTH_HOST: string = 'oauth';
export const SLOCK_OHOS_MOBILE_OAUTH_PATH: string = '/callback';
const SLOCK_OHOS_MOBILE_OAUTH_PKCE_PATTERN: RegExp = /^[A-Za-z0-9_-]{43,128}$/;
const SLOCK_OHOS_MOBILE_OAUTH_CALLBACK_PATTERN: RegExp = new RegExp(
  `^${SLOCK_OHOS_MOBILE_OAUTH_SCHEME}:\\/\\/oauth\\/callback(?:\\?([^#]*))?$`,
  'i'
);

export interface SlockOhosMobileOAuthPreparation {
  readonly generation: string;
  readonly codeVerifier: string;
  readonly codeChallenge: string;
  readonly returnUri: string;
}

export interface SlockOhosMobileOAuthCallback {
  readonly provider: string;
  readonly mode: string;
  readonly code: string;
  readonly error: string;
}

export type SlockOhosMobileOAuthCallbackInvoker = (callback: SlockOhosMobileOAuthCallback) => boolean;

export function prepareSlockOhosMobileOAuth(provider: string, mode: string): SlockOhosMobileOAuthPreparation {
  const normalizedProvider = provider.trim().toLowerCase();
  if (normalizedProvider !== 'google' && normalizedProvider !== 'github') {
    throw new Error('Unsupported mobile OAuth provider');
  }
  if (mode.trim().toLowerCase() !== 'link') {
    throw new Error('Unsupported mobile OAuth mode');
  }
  return prepareSlockOhosPkce(`${SLOCK_OHOS_MOBILE_OAUTH_SCHEME}://oauth/callback`);
}

export function prepareSlockOhosPkce(returnUri: string): SlockOhosMobileOAuthPreparation {
  const random = cryptoFramework.createRandom();
  const verifier = slockOhosMobileOAuthBase64Url(random.generateRandomSync(32).data);
  const generation = slockOhosMobileOAuthBase64Url(random.generateRandomSync(16).data);
  const digest = cryptoFramework.createMd('SHA256');
  digest.updateSync({ data: new util.TextEncoder().encodeInto(verifier) });
  const challenge = slockOhosMobileOAuthBase64Url(digest.digestSync().data);
  if (!SLOCK_OHOS_MOBILE_OAUTH_PKCE_PATTERN.test(verifier) ||
    !SLOCK_OHOS_MOBILE_OAUTH_PKCE_PATTERN.test(challenge) || generation.length === 0) {
    throw new Error('Mobile OAuth PKCE generation failed');
  }
  return {
    generation,
    codeVerifier: verifier,
    codeChallenge: challenge,
    returnUri,
  };
}

export function parseSlockOhosMobileOAuthCallback(uri: string | undefined): SlockOhosMobileOAuthCallback | undefined {
  const match = SLOCK_OHOS_MOBILE_OAUTH_CALLBACK_PATTERN.exec(uri?.trim() ?? '');
  if (match === null) return undefined;
  const values: Map<string, string> = new Map();
  const query = match[1] ?? '';
  if (query.length > 0) {
    const admitted = new Set<string>(['provider', 'mode', 'code', 'error', 'error_description']);
    const pairs = query.split('&');
    for (let index = 0; index < pairs.length; index += 1) {
      const pair = pairs[index];
      if (pair.length === 0) return undefined;
      const separator = pair.indexOf('=');
      const rawName = separator >= 0 ? pair.substring(0, separator) : pair;
      const rawValue = separator >= 0 ? pair.substring(separator + 1) : '';
      let name = '';
      let value = '';
      try {
        name = decodeURIComponent(rawName.replace(/\+/g, '%20'));
        value = decodeURIComponent(rawValue.replace(/\+/g, '%20'));
      } catch (_error) {
        return undefined;
      }
      if (!admitted.has(name) || values.has(name)) return undefined;
      values.set(name, value);
    }
  }
  if (values.has('error') && values.has('error_description')) return undefined;
  const code = values.get('code')?.trim() ?? '';
  const error = (values.get('error') ?? values.get('error_description') ?? '').trim();
  if (code.length === 0 && error.length === 0) return undefined;
  return {
    provider: values.get('provider')?.trim() ?? '',
    mode: values.get('mode')?.trim() ?? '',
    code,
    error,
  };
}

export function completeSlockOhosMobileOAuthCallback(uri: string | undefined): boolean {
  const accepted = dispatchSlockOhosMobileOAuthCallback(uri, (callback) => invoke<boolean>(
    'slockOhosMobileOAuthCompleteCallback',
    SLOCK_OHOS_MOBILE_OAUTH_SCHEME,
    SLOCK_OHOS_MOBILE_OAUTH_HOST,
    SLOCK_OHOS_MOBILE_OAUTH_PATH,
    callback.provider,
    callback.mode,
    callback.code,
    callback.error
  ));
  return accepted;
}

export function dispatchSlockOhosMobileOAuthCallback(
  uri: string | undefined,
  invokeCallback: SlockOhosMobileOAuthCallbackInvoker
): boolean {
  const callback = parseSlockOhosMobileOAuthCallback(uri);
  if (callback === undefined) return false;
  return invokeCallback(callback);
}

function slockOhosMobileOAuthBase64Url(bytes: Uint8Array): string {
  return new util.Base64Helper().encodeToStringSync(bytes)
    .replace(/\+/g, '-')
    .replace(/\//g, '_')
    .replace(/=+$/g, '');
}
