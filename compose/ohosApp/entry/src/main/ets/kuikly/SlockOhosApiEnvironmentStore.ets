import { common } from '@kit.AbilityKit';
import { MMKV } from '@tencent/mmkv';
import { SLOCK_API_BASE_URL, SLOCK_SOCKET_BASE_URL } from './SlockOhosApiConfig';

export interface SlockOhosApiEnvironment {
  key: string;
  label: string;
  apiBaseUrl: string;
  socketBaseUrl: string;
  webUrl: string;
}

export class SlockOhosApiEnvironmentStore {
  static readonly CUSTOM_KEY: string = 'custom';
  private static readonly PREFERENCES_NAME: string = 'slock_api_server_environment';
  private static readonly KEY_ENVIRONMENT: string = 'environment';
  private static readonly KEY_CUSTOM_LABEL: string = 'custom_label';
  private static readonly KEY_CUSTOM_API_BASE_URL: string = 'custom_api_base_url';
  private static readonly KEY_CUSTOM_SOCKET_BASE_URL: string = 'custom_socket_base_url';
  private static readonly KEY_CUSTOM_WEB_URL: string = 'custom_web_url';
  private static readonly AUTH_SESSION_MMKV_NAMESPACE: string = 'slockAuthSession';
  private static readonly AUTH_SESSION_MMKV_STORAGE_KEY: string = 'slockAuthSession:session';
  private static initialized: boolean = false;

  static readonly PRODUCTION: SlockOhosApiEnvironment = {
    key: 'production',
    label: 'Production',
    apiBaseUrl: SLOCK_API_BASE_URL,
    socketBaseUrl: SLOCK_SOCKET_BASE_URL,
    webUrl: 'https://app.raft.build'
  };

  static readonly STAGING: SlockOhosApiEnvironment = {
    key: 'staging',
    label: 'Staging',
    apiBaseUrl: 'https://api-aws-staging.botiverse.dev/api/',
    socketBaseUrl: 'https://api-aws-staging.botiverse.dev',
    webUrl: 'https://raft-app-staging.botiverse.dev/s/botiverse/'
  };

  static readonly PREDEFINED: Array<SlockOhosApiEnvironment> = [
    SlockOhosApiEnvironmentStore.PRODUCTION,
    SlockOhosApiEnvironmentStore.STAGING
  ];

  static current(owner?: Object): SlockOhosApiEnvironment {
    const store = SlockOhosApiEnvironmentStore.store(owner);
    return SlockOhosApiEnvironmentStore.currentEnvironment(
      store.decodeString(SlockOhosApiEnvironmentStore.namespaced(SlockOhosApiEnvironmentStore.KEY_ENVIRONMENT)) ?? '',
      store
    );
  }

  static currentForContext(context: common.Context): SlockOhosApiEnvironment {
    const store = SlockOhosApiEnvironmentStore.storeForContext(context);
    return SlockOhosApiEnvironmentStore.currentEnvironment(
      store.decodeString(SlockOhosApiEnvironmentStore.namespaced(SlockOhosApiEnvironmentStore.KEY_ENVIRONMENT)) ?? '',
      store
    );
  }

  static select(key: string, owner?: Object): SlockOhosApiEnvironment | null {
    const environment = SlockOhosApiEnvironmentStore.PREDEFINED.find((item) => item.key === key);
    if (environment === undefined) {
      return null;
    }
    SlockOhosApiEnvironmentStore.store(owner).encodeString(
      SlockOhosApiEnvironmentStore.namespaced(SlockOhosApiEnvironmentStore.KEY_ENVIRONMENT),
      environment.key
    );
    return environment;
  }

  static save(environment: SlockOhosApiEnvironment, owner?: Object): SlockOhosApiEnvironment | null {
    const predefined = SlockOhosApiEnvironmentStore.PREDEFINED.find((item) => item.key === environment.key);
    if (predefined !== undefined) {
      SlockOhosApiEnvironmentStore.store(owner).encodeString(
        SlockOhosApiEnvironmentStore.namespaced(SlockOhosApiEnvironmentStore.KEY_ENVIRONMENT),
        predefined.key
      );
      return predefined;
    }
    const apiBaseUrl = environment.apiBaseUrl.trim();
    const socketBaseUrl = environment.socketBaseUrl.trim();
    const webUrl = environment.webUrl.trim();
    if (!apiBaseUrl.startsWith('http') || !socketBaseUrl.startsWith('http')) {
      return null;
    }
    const custom: SlockOhosApiEnvironment = {
      key: SlockOhosApiEnvironmentStore.CUSTOM_KEY,
      label: environment.label.trim().length > 0 ? environment.label.trim() : 'Custom',
      apiBaseUrl,
      socketBaseUrl,
      webUrl
    };
    const store = SlockOhosApiEnvironmentStore.store(owner);
    store.encodeString(
      SlockOhosApiEnvironmentStore.namespaced(SlockOhosApiEnvironmentStore.KEY_ENVIRONMENT),
      SlockOhosApiEnvironmentStore.CUSTOM_KEY
    );
    store.encodeString(SlockOhosApiEnvironmentStore.namespaced(SlockOhosApiEnvironmentStore.KEY_CUSTOM_LABEL), custom.label);
    store.encodeString(SlockOhosApiEnvironmentStore.namespaced(SlockOhosApiEnvironmentStore.KEY_CUSTOM_API_BASE_URL), custom.apiBaseUrl);
    store.encodeString(SlockOhosApiEnvironmentStore.namespaced(SlockOhosApiEnvironmentStore.KEY_CUSTOM_SOCKET_BASE_URL), custom.socketBaseUrl);
    store.encodeString(SlockOhosApiEnvironmentStore.namespaced(SlockOhosApiEnvironmentStore.KEY_CUSTOM_WEB_URL), custom.webUrl);
    return custom;
  }

  static rewriteApiUrl(url: string, owner?: Object): string {
    const current = SlockOhosApiEnvironmentStore.current(owner);
    return SlockOhosApiEnvironmentStore.rewriteApiUrlForEnvironment(url, current);
  }

  static rewriteApiUrlForContext(url: string, context: common.Context): string {
    return SlockOhosApiEnvironmentStore.rewriteApiUrlForEnvironment(url, SlockOhosApiEnvironmentStore.currentForContext(context));
  }

  private static rewriteApiUrlForEnvironment(url: string, current: SlockOhosApiEnvironment): string {
    const productionBase = SlockOhosApiEnvironmentStore.PRODUCTION.apiBaseUrl;
    if (current.apiBaseUrl === productionBase) {
      return url;
    }
    if (!url.startsWith(productionBase)) {
      return url;
    }
    return `${SlockOhosApiEnvironmentStore.trimEndSlash(current.apiBaseUrl)}/${url.substring(productionBase.length).replace(/^\/+/, '')}`;
  }

  static resolveSocketUrl(socketUrl: string, owner?: Object): string {
    const current = SlockOhosApiEnvironmentStore.current(owner);
    const requested = socketUrl.trim();
    const productionSocket = SlockOhosApiEnvironmentStore.trimEndSlash(SlockOhosApiEnvironmentStore.PRODUCTION.socketBaseUrl);
    if (requested.length === 0 || SlockOhosApiEnvironmentStore.trimEndSlash(requested) === productionSocket) {
      return current.socketBaseUrl;
    }
    return requested;
  }

  static clearAuthSession(owner?: Object): void {
    SlockOhosApiEnvironmentStore.ensureInitialized(owner);
    MMKV.mmkvWithID(SlockOhosApiEnvironmentStore.AUTH_SESSION_MMKV_NAMESPACE)
      .removeValueForKey(SlockOhosApiEnvironmentStore.AUTH_SESSION_MMKV_STORAGE_KEY);
  }

  private static currentEnvironment(key: string, store: MMKV): SlockOhosApiEnvironment {
    const predefined = SlockOhosApiEnvironmentStore.PREDEFINED.find((item) => item.key === key);
    if (predefined !== undefined) {
      return predefined;
    }
    if (key === SlockOhosApiEnvironmentStore.CUSTOM_KEY) {
      const apiBaseUrl =
        store.decodeString(SlockOhosApiEnvironmentStore.namespaced(SlockOhosApiEnvironmentStore.KEY_CUSTOM_API_BASE_URL)) ?? '';
      const socketBaseUrl =
        store.decodeString(SlockOhosApiEnvironmentStore.namespaced(SlockOhosApiEnvironmentStore.KEY_CUSTOM_SOCKET_BASE_URL)) ?? '';
      if (apiBaseUrl.startsWith('http') && socketBaseUrl.startsWith('http')) {
        return {
          key: SlockOhosApiEnvironmentStore.CUSTOM_KEY,
          label: store.decodeString(SlockOhosApiEnvironmentStore.namespaced(SlockOhosApiEnvironmentStore.KEY_CUSTOM_LABEL)) || 'Custom',
          apiBaseUrl,
          socketBaseUrl,
          webUrl: store.decodeString(SlockOhosApiEnvironmentStore.namespaced(SlockOhosApiEnvironmentStore.KEY_CUSTOM_WEB_URL)) ?? ''
        };
      }
    }
    return SlockOhosApiEnvironmentStore.PRODUCTION;
  }

  private static store(owner?: Object): MMKV {
    SlockOhosApiEnvironmentStore.ensureInitialized(owner);
    return MMKV.mmkvWithID(SlockOhosApiEnvironmentStore.PREFERENCES_NAME);
  }

  private static storeForContext(context: common.Context): MMKV {
    SlockOhosApiEnvironmentStore.ensureInitializedForContext(context);
    return MMKV.mmkvWithID(SlockOhosApiEnvironmentStore.PREFERENCES_NAME);
  }

  private static ensureInitialized(owner?: Object): void {
    if (SlockOhosApiEnvironmentStore.initialized) {
      return;
    }
    try {
      if (owner !== undefined) {
        const context = getContext(owner) as common.UIAbilityContext;
        MMKV.initialize(context.getApplicationContext());
      }
    } catch (_error) {
      // MMKV may already be initialized by the host process or another Kuikly module.
    }
    SlockOhosApiEnvironmentStore.initialized = true;
  }

  private static ensureInitializedForContext(context: common.Context): void {
    if (SlockOhosApiEnvironmentStore.initialized) {
      return;
    }
    try {
      MMKV.initialize(context.getApplicationContext());
    } catch (_error) {
      // MMKV may already be initialized by the host process or another Kuikly module.
    }
    SlockOhosApiEnvironmentStore.initialized = true;
  }

  private static trimEndSlash(value: string): string {
    return value.replace(/\/+$/, '');
  }

  private static namespaced(key: string): string {
    return `${SlockOhosApiEnvironmentStore.PREFERENCES_NAME}:${key}`;
  }
}
