import { invoke } from '@kuiklybase/knoi';
import { prepareSlockOhosPkce, SLOCK_OHOS_MOBILE_OAUTH_SCHEME, SlockOhosMobileOAuthPreparation } from './SlockOhosMobileOAuth';

export const SLOCK_OHOS_APP_LOGIN_URI: string = `${SLOCK_OHOS_MOBILE_OAUTH_SCHEME}://login/callback`;
export interface SlockOhosAppLoginCallback { readonly requestId: string; readonly code: string; readonly error: string; }
export function prepareSlockOhosAppLogin(): SlockOhosMobileOAuthPreparation {
  return prepareSlockOhosPkce(SLOCK_OHOS_APP_LOGIN_URI);
}

export function parseSlockOhosAppLoginCallback(uri: string | undefined): SlockOhosAppLoginCallback | undefined {
  if (uri === undefined || !uri.startsWith(`${SLOCK_OHOS_APP_LOGIN_URI}?`) || uri.includes('#')) return undefined;
  const query = uri.substring(SLOCK_OHOS_APP_LOGIN_URI.length + 1);
  const values: Map<string, string> = new Map();
  for (const pair of query.split('&')) {
    const separator = pair.indexOf('=');
    if (separator < 1) return undefined;
    try {
      const name = decodeURIComponent(pair.substring(0, separator));
      const value = decodeURIComponent(pair.substring(separator + 1));
      if ((name !== 'requestId' && name !== 'code' && name !== 'error') || values.has(name) || value.length === 0) return undefined;
      values.set(name, value);
    } catch (_error) { return undefined; }
  }
  if (values.size !== 2 || !values.has('requestId') || values.has('code') === values.has('error')) return undefined;
  return { requestId: values.get('requestId') ?? '', code: values.get('code') ?? '', error: values.get('error') ?? '' };
}

export function completeSlockOhosAppLoginCallback(uri: string | undefined): boolean {
  const callback = parseSlockOhosAppLoginCallback(uri);
  if (callback === undefined) return false;
  return invoke<boolean>('slockOhosAppLoginCompleteCallback', SLOCK_OHOS_APP_LOGIN_URI, callback.requestId, callback.code, callback.error);
}
