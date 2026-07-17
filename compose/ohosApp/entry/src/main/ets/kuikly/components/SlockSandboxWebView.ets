import { KRAny, KuiklyRenderBaseView, KuiklyRenderCallback } from '@kuikly-open/render';
import { ComponentContent } from '@kit.ArkUI';
import { webview } from '@kit.ArkWeb';

const kSrc = 'src';
const kAllowScripts = 'allowScripts';
const kAllowNavigation = 'allowNavigation';
const kDomStorageEnabled = 'domStorageEnabled';
const kCacheEnabled = 'cacheEnabled';
const kEphemeral = 'ephemeral';
const kInjectedJavaScript = 'injectedJavaScript';
const kAllowedNavigationSchemes = 'allowedNavigationSchemes';
const kNavigationCommandMethod = 'navigationCommand';
const kLoadSuccess = 'loadSuccess';
const kLoadFailure = 'loadFailure';
const kNavigationBlocked = 'navigationBlocked';
const kUrlChange = 'urlChange';
const kNavigationStateChange = 'navigationStateChange';

const kCommandGoBack = 'goBack';
const kCommandGoForward = 'goForward';
const kCommandReload = 'reload';
const kCommandStop = 'stop';

@Observed
export class SlockSandboxWebView extends KuiklyRenderBaseView {
  static readonly VIEW_NAME = 'SlockSandboxWebView';
  src: string = '';
  allowScripts: boolean = true;
  allowNavigation: boolean = false;
  domStorageEnabled: boolean = false;
  cacheEnabled: boolean = false;
  ephemeral: boolean = true;
  injectedJavaScript: string = '';
  allowedNavigationSchemes: Set<string> = new Set<string>();
  renderRevision: number = 0;
  readonly controller: webview.WebviewController = new webview.WebviewController();
  private controllerAttached: boolean = false;
  private destroyed: boolean = false;
  private pendingNavigationCommands: string[] = [];
  private currentUrl: string = '';
  private currentTitle: string = '';
  private currentProgress: number = 0;
  private currentIsLoading: boolean = false;
  private lastNavigationStateKey: string = '';
  private lastSuccessfulUrl: string = '';
  private lastLoadFailureKey: string = '';
  private loadSuccessCallback: KuiklyRenderCallback | null = null;
  private loadFailureCallback: KuiklyRenderCallback | null = null;
  private navigationBlockedCallback: KuiklyRenderCallback | null = null;
  private urlChangeCallback: KuiklyRenderCallback | null = null;
  private navigationStateChangeCallback: KuiklyRenderCallback | null = null;

  createArkUIView(): ComponentContent<KuiklyRenderBaseView> {
    const uiContext = this.getUIContext() as UIContext;
    return new ComponentContent<KuiklyRenderBaseView>(
      uiContext,
      wrapBuilder<[KuiklyRenderBaseView]>(createSlockSandboxWebView),
      this
    );
  }

  setProp(propKey: string, propValue: KRAny | KuiklyRenderCallback): boolean {
    switch (propKey) {
      case kSrc:
        this.updateSrc(normalizeText(propValue));
        return true;
      case kAllowScripts:
        this.updateAllowScripts(normalizeBoolean(propValue, true));
        return true;
      case kAllowNavigation:
        this.updateAllowNavigation(normalizeBoolean(propValue, false));
        return true;
      case kDomStorageEnabled:
        this.updateDomStorageEnabled(normalizeBoolean(propValue, false));
        return true;
      case kCacheEnabled:
        this.updateCacheEnabled(normalizeBoolean(propValue, false));
        return true;
      case kEphemeral:
        this.updateEphemeral(normalizeBoolean(propValue, true));
        return true;
      case kInjectedJavaScript:
        this.updateInjectedJavaScript(normalizeText(propValue));
        return true;
      case kAllowedNavigationSchemes:
        this.updateAllowedNavigationSchemes(normalizeText(propValue));
        return true;
      case kLoadSuccess:
        this.loadSuccessCallback = propValue as KuiklyRenderCallback;
        return true;
      case kLoadFailure:
        this.loadFailureCallback = propValue as KuiklyRenderCallback;
        return true;
      case kNavigationBlocked:
        this.navigationBlockedCallback = propValue as KuiklyRenderCallback;
        return true;
      case kUrlChange:
        this.urlChangeCallback = propValue as KuiklyRenderCallback;
        return true;
      case kNavigationStateChange:
        this.navigationStateChangeCallback = propValue as KuiklyRenderCallback;
        this.notifyNavigationState(true);
        return true;
      default:
        return super.setProp(propKey, propValue);
    }
  }

  call(method: string, params: KRAny, callback: KuiklyRenderCallback | null): KRAny {
    if (method === kNavigationCommandMethod) {
      this.enqueueNavigationCommand(normalizeText(params));
    }
    return null;
  }

  onDestroy(): void {
    this.destroyed = true;
    this.controllerAttached = false;
    this.pendingNavigationCommands = [];
    try {
      this.controller.stop();
    } catch (_error) {
    }
    this.loadSuccessCallback = null;
    this.loadFailureCallback = null;
    this.navigationBlockedCallback = null;
    this.urlChangeCallback = null;
    this.navigationStateChangeCallback = null;
  }

  isAllowedMainDocumentUrl(url: string | null | undefined): boolean {
    const target = normalizeText(url);
    if (target.length === 0 || this.src.length === 0) {
      return false;
    }
    if (target === this.src) {
      return true;
    }
    return sameDocumentUrl(this.src, target);
  }

  shouldAllowNavigation(url: string | null | undefined): boolean {
    const target = normalizeText(url);
    return this.isNavigationSchemeAllowed(target) &&
      (this.allowNavigation || this.isAllowedMainDocumentUrl(target));
  }

  shouldBlockSubframeNavigation(url: string | null | undefined): boolean {
    return !this.isNavigationSchemeAllowed(normalizeText(url));
  }

  notifyLoadSuccess(): void {
    if (this.destroyed || this.lastSuccessfulUrl === this.currentUrl) {
      return;
    }
    this.lastSuccessfulUrl = this.currentUrl;
    this.lastLoadFailureKey = '';
    this.loadSuccessCallback?.({});
  }

  notifyLoadFailure(reason: string): void {
    if (this.destroyed) {
      return;
    }
    const normalizedReason = reason.length > 0 ? reason : 'webview_load_failed';
    const failureKey = `${this.currentUrl}|${normalizedReason}`;
    if (failureKey === this.lastLoadFailureKey) {
      return;
    }
    this.lastLoadFailureKey = failureKey;
    this.loadFailureCallback?.({ reason: normalizedReason });
  }

  notifyNavigationBlocked(url: string): void {
    if (this.destroyed) {
      return;
    }
    this.navigationBlockedCallback?.({ url });
  }

  notifyUrlChange(url: string): void {
    if (this.destroyed) {
      return;
    }
    this.urlChangeCallback?.({ url });
  }

  currentRenderKey(): string {
    return `${this.renderRevision}:${this.src}`;
  }

  isRenderKeyCurrent(renderKey: string): boolean {
    return !this.destroyed && renderKey === this.currentRenderKey();
  }

  shouldRenderWebView(): boolean {
    return this.src.length > 0 && this.isNavigationSchemeAllowed(this.src);
  }

  onControllerAttached(renderKey: string): void {
    if (!this.isRenderKeyCurrent(renderKey)) {
      return;
    }
    this.controllerAttached = true;
    this.executePendingNavigationCommands();
    this.notifyNavigationState(true);
  }

  onPageBegin(renderKey: string, url: string): void {
    if (!this.isRenderKeyCurrent(renderKey)) {
      return;
    }
    if (!this.shouldAllowNavigation(url)) {
      try {
        this.controller.stop();
      } catch (_error) {
      }
      this.onMainFrameNavigationBlocked(renderKey, url);
      return;
    }
    this.currentUrl = url;
    this.currentTitle = '';
    this.currentProgress = 0;
    this.currentIsLoading = true;
    this.lastSuccessfulUrl = '';
    this.lastLoadFailureKey = '';
    this.notifyUrlChange(url);
    this.notifyNavigationState();
    this.executePendingNavigationCommands();
  }

  onPageEnd(renderKey: string, url: string): void {
    if (!this.isRenderKeyCurrent(renderKey) || !this.shouldAllowNavigation(url)) {
      return;
    }
    this.currentUrl = url;
    this.currentProgress = 100;
    this.currentIsLoading = false;
    this.notifyUrlChange(url);
    this.notifyNavigationState();
    this.notifyLoadSuccess();
  }

  onProgressChange(renderKey: string, progress: number): void {
    if (!this.isRenderKeyCurrent(renderKey)) {
      return;
    }
    this.currentProgress = clampProgress(progress);
    this.notifyNavigationState();
    this.executePendingNavigationCommands();
  }

  onTitleReceive(renderKey: string, title: string): void {
    if (!this.isRenderKeyCurrent(renderKey)) {
      return;
    }
    this.currentTitle = title;
    this.notifyNavigationState();
  }

  onHistoryRefresh(renderKey: string, url: string): void {
    // History API / fragment updates are same-document state changes, not a new
    // main-frame navigation. Preserve their visible URL/history state even when
    // allowNavigation is false; scheme gating still guards malformed callbacks.
    if (!this.isRenderKeyCurrent(renderKey) || !this.isNavigationSchemeAllowed(url)) {
      return;
    }
    this.currentUrl = url;
    this.notifyUrlChange(url);
    this.notifyNavigationState();
  }

  onMainFrameNavigationBlocked(renderKey: string, url: string): void {
    if (!this.isRenderKeyCurrent(renderKey)) {
      return;
    }
    this.currentIsLoading = false;
    this.notifyNavigationState();
    this.notifyNavigationBlocked(url);
  }

  onMainFrameError(renderKey: string, url: string, errorCode: number): void {
    if (!this.isRenderKeyCurrent(renderKey)) {
      return;
    }
    this.currentUrl = url.length > 0 ? url : this.currentUrl;
    this.currentIsLoading = false;
    this.notifyNavigationState();
    this.notifyLoadFailure(`webview_error_${errorCode}`);
  }

  onSslError(renderKey: string, errorCode: number): void {
    if (!this.isRenderKeyCurrent(renderKey)) {
      return;
    }
    this.currentIsLoading = false;
    this.notifyNavigationState();
    this.notifyLoadFailure(`ssl_error_${errorCode}`);
  }

  private updateSrc(nextSrc: string): void {
    const normalized = nextSrc.trim();
    if (this.src === normalized) {
      return;
    }
    this.src = normalized;
    this.currentUrl = normalized;
    this.currentTitle = '';
    this.currentProgress = 0;
    this.currentIsLoading = false;
    this.lastSuccessfulUrl = '';
    this.lastLoadFailureKey = '';
    if (normalized.length > 0 && !this.isNavigationSchemeAllowed(normalized)) {
      this.notifyNavigationState();
      this.notifyNavigationBlocked(normalized);
    } else {
      this.notifyNavigationState();
    }
    this.bumpRenderRevision();
  }

  private updateAllowScripts(nextAllowScripts: boolean): void {
    if (this.allowScripts === nextAllowScripts) {
      return;
    }
    this.allowScripts = nextAllowScripts;
    this.bumpRenderRevision();
  }

  private updateAllowNavigation(nextAllowNavigation: boolean): void {
    if (this.allowNavigation === nextAllowNavigation) {
      return;
    }
    this.allowNavigation = nextAllowNavigation;
    this.bumpRenderRevision();
  }

  private updateDomStorageEnabled(nextDomStorageEnabled: boolean): void {
    if (this.domStorageEnabled === nextDomStorageEnabled) {
      return;
    }
    this.domStorageEnabled = nextDomStorageEnabled;
    this.bumpRenderRevision();
  }

  private updateCacheEnabled(nextCacheEnabled: boolean): void {
    if (this.cacheEnabled === nextCacheEnabled) {
      return;
    }
    this.cacheEnabled = nextCacheEnabled;
    this.bumpRenderRevision();
  }

  private updateEphemeral(nextEphemeral: boolean): void {
    if (this.ephemeral === nextEphemeral) {
      return;
    }
    this.ephemeral = nextEphemeral;
    this.bumpRenderRevision();
  }

  private updateInjectedJavaScript(nextInjectedJavaScript: string): void {
    this.injectedJavaScript = nextInjectedJavaScript;
  }

  private updateAllowedNavigationSchemes(nextAllowedNavigationSchemes: string): void {
    const normalized = normalizeNavigationSchemes(nextAllowedNavigationSchemes);
    if (sameStringSet(this.allowedNavigationSchemes, normalized)) {
      return;
    }
    this.allowedNavigationSchemes = normalized;
    if (this.src.length > 0 && !this.isNavigationSchemeAllowed(this.src)) {
      if (this.controllerAttached) {
        try {
          this.controller.stop();
        } catch (_error) {
        }
      }
      this.currentIsLoading = false;
      this.notifyNavigationState();
      this.notifyNavigationBlocked(this.src);
    }
    this.bumpRenderRevision();
  }

  private enqueueNavigationCommand(nextNavigationCommand: string): void {
    const command = nextNavigationCommand.trim();
    if (!isNavigationCommand(command) || this.destroyed) {
      return;
    }
    this.pendingNavigationCommands.push(command);
    this.executePendingNavigationCommands();
  }

  private executePendingNavigationCommands(): void {
    if (!this.controllerAttached || this.pendingNavigationCommands.length === 0 || this.destroyed) {
      return;
    }
    while (this.pendingNavigationCommands.length > 0 && this.controllerAttached && !this.destroyed) {
      const command = this.pendingNavigationCommands[0];
      try {
        if (command === kCommandGoBack) {
          if (this.controller.accessBackward()) {
            this.controller.backward();
          }
        } else if (command === kCommandGoForward) {
          if (this.controller.accessForward()) {
            this.controller.forward();
          }
        } else if (command === kCommandReload) {
          this.lastSuccessfulUrl = '';
          this.lastLoadFailureKey = '';
          this.currentProgress = 0;
          this.currentIsLoading = true;
          this.controller.refresh();
        } else if (command === kCommandStop) {
          this.controller.stop();
          this.currentIsLoading = false;
        }
        this.pendingNavigationCommands.splice(0, 1);
        this.notifyNavigationState(true);
      } catch (_error) {
        // The controller can attach before the first document is ready. Retain this command and retry on page events.
        return;
      }
    }
  }

  private isNavigationSchemeAllowed(url: string): boolean {
    if (this.allowedNavigationSchemes.size === 0) {
      return true;
    }
    const scheme = navigationScheme(url);
    return scheme.length > 0 && this.allowedNavigationSchemes.has(scheme);
  }

  private canGoBack(): boolean {
    if (!this.controllerAttached || this.destroyed) {
      return false;
    }
    try {
      return this.controller.accessBackward();
    } catch (_error) {
      return false;
    }
  }

  private canGoForward(): boolean {
    if (!this.controllerAttached || this.destroyed) {
      return false;
    }
    try {
      return this.controller.accessForward();
    } catch (_error) {
      return false;
    }
  }

  private notifyNavigationState(force: boolean = false): void {
    if (this.destroyed) {
      return;
    }
    const canGoBack = this.canGoBack();
    const canGoForward = this.canGoForward();
    const progress = clampProgress(this.currentProgress);
    const stateKey = JSON.stringify([
      this.currentUrl,
      this.currentTitle,
      canGoBack,
      canGoForward,
      this.currentIsLoading,
      progress
    ]);
    if (!force && stateKey === this.lastNavigationStateKey) {
      return;
    }
    this.lastNavigationStateKey = stateKey;
    this.navigationStateChangeCallback?.({
      url: this.currentUrl,
      title: this.currentTitle,
      canGoBack,
      canGoForward,
      isLoading: this.currentIsLoading,
      progress
    });
  }

  private bumpRenderRevision(): void {
    this.controllerAttached = false;
    this.renderRevision = this.renderRevision >= 1000000 ? 1 : this.renderRevision + 1;
    this.updateArkUI();
  }
}

@Component
export struct SlockSandboxWebViewComponent {
  @ObjectLink renderView: SlockSandboxWebView;

  build() {
    if (this.renderView.shouldRenderWebView()) {
      ForEach([this.renderView.currentRenderKey()], (renderKey: string) => {
        Web({
          src: this.renderView.src,
          controller: this.renderView.controller,
          incognitoMode: this.renderView.ephemeral,
        })
          .width('100%')
          .height('100%')
          .javaScriptAccess(this.renderView.allowScripts)
          .domStorageAccess(this.renderView.domStorageEnabled)
          .cacheMode(this.renderView.cacheEnabled ? CacheMode.Default : CacheMode.Online)
          .databaseAccess(false)
          .fileAccess(false)
          .geolocationAccess(false)
          .onlineImageAccess(true)
          .multiWindowAccess(false)
          .runJavaScriptOnDocumentStart(this.renderView.allowScripts && this.renderView.injectedJavaScript.length > 0
            ? [{ script: this.renderView.injectedJavaScript, scriptRules: ['*'] }]
            : [])
          .onControllerAttached(() => {
            this.renderView.onControllerAttached(renderKey);
          })
          .onPageEnd((event) => {
            this.renderView.onPageEnd(renderKey, event.url);
          })
          .onPageBegin((event) => {
            this.renderView.onPageBegin(renderKey, event.url);
          })
          .onProgressChange((event) => {
            this.renderView.onProgressChange(renderKey, event.newProgress);
          })
          .onTitleReceive((event) => {
            this.renderView.onTitleReceive(renderKey, event.title);
          })
          .onRefreshAccessedHistory((event) => {
            if (event.isMainFrame !== false) {
              this.renderView.onHistoryRefresh(renderKey, event.url);
            }
          })
          .onErrorReceive((event) => {
            if (event.request.isMainFrame()) {
              this.renderView.onMainFrameError(
                renderKey,
                event.request.getRequestUrl(),
                event.error.getErrorCode()
              );
            }
          })
          .onSslErrorEventReceive((event) => {
            event.handler.handleCancel(true);
            this.renderView.onSslError(renderKey, Number(event.error));
          })
          .onDownloadStart((event) => {
            if (this.renderView.isRenderKeyCurrent(renderKey)) {
              this.renderView.notifyNavigationBlocked(event.url);
            }
          })
          .onWindowNew((event) => {
            if (this.renderView.isRenderKeyCurrent(renderKey)) {
              this.renderView.notifyNavigationBlocked(event.targetUrl);
            }
          })
          .onOverrideUrlLoading((request) => {
            if (!this.renderView.isRenderKeyCurrent(renderKey)) {
              return true;
            }
            const url = request.getRequestUrl();
            if (!request.isMainFrame()) {
              return this.renderView.shouldBlockSubframeNavigation(url);
            }
            if (this.renderView.shouldAllowNavigation(url)) {
              this.renderView.notifyUrlChange(url);
              return false;
            }
            this.renderView.onMainFrameNavigationBlocked(renderKey, url);
            return true;
          })
      }, (renderKey: string) => renderKey)
    }
  }
}

function normalizeText(value: KRAny | KuiklyRenderCallback | null | undefined): string {
  if (value === null || value === undefined) {
    return '';
  }
  return String(value);
}

function normalizeBoolean(value: KRAny | KuiklyRenderCallback, fallback: boolean): boolean {
  if (typeof value === 'boolean') {
    return value;
  }
  if (typeof value === 'number') {
    return value !== 0;
  }
  if (typeof value === 'string') {
    return value.toLowerCase() === 'true';
  }
  return fallback;
}

function normalizeNavigationSchemes(value: string): Set<string> {
  const result = new Set<string>();
  value.split(',').forEach((rawScheme: string) => {
    let scheme = rawScheme.trim().toLowerCase();
    while (scheme.endsWith(':')) {
      scheme = scheme.substring(0, scheme.length - 1);
    }
    if (scheme.length > 0) {
      result.add(scheme);
    }
  });
  return result;
}

function sameStringSet(left: Set<string>, right: Set<string>): boolean {
  if (left.size !== right.size) {
    return false;
  }
  for (const value of left) {
    if (!right.has(value)) {
      return false;
    }
  }
  return true;
}

function navigationScheme(url: string): string {
  const normalized = url.trim();
  const separator = normalized.indexOf(':');
  if (separator <= 0) {
    return '';
  }
  return normalized.substring(0, separator).toLowerCase();
}

function isNavigationCommand(command: string): boolean {
  return command === kCommandGoBack || command === kCommandGoForward ||
    command === kCommandReload || command === kCommandStop;
}

function clampProgress(progress: number): number {
  return Math.max(0, Math.min(100, Math.round(progress)));
}

function sameDocumentUrl(left: string, right: string): boolean {
  const leftUrl = parseDocumentUrl(left);
  const rightUrl = parseDocumentUrl(right);
  if (leftUrl === null || rightUrl === null) {
    return false;
  }
  return leftUrl.protocol === rightUrl.protocol &&
    leftUrl.host === rightUrl.host &&
    leftUrl.pathname === rightUrl.pathname &&
    leftUrl.search === rightUrl.search;
}

interface SlockParsedDocumentUrl {
  protocol: string;
  host: string;
  pathname: string;
  search: string;
}

function parseDocumentUrl(value: string): SlockParsedDocumentUrl | null {
  const trimmed = value.trim();
  const protocolSeparator = trimmed.indexOf('://');
  if (protocolSeparator <= 0) {
    return null;
  }
  const protocol = `${trimmed.substring(0, protocolSeparator)}:`;
  const hostStart = protocolSeparator + 3;
  const pathStart = firstIndexOfUrlSuffix(trimmed, hostStart);
  const host = pathStart < 0 ? trimmed.substring(hostStart) : trimmed.substring(hostStart, pathStart);
  const suffix = pathStart < 0 ? '' : trimmed.substring(pathStart);
  const hashIndex = suffix.indexOf('#');
  const withoutHash = hashIndex < 0 ? suffix : suffix.substring(0, hashIndex);
  const searchIndex = withoutHash.indexOf('?');
  const pathname = searchIndex < 0 ? withoutHash : withoutHash.substring(0, searchIndex);
  const search = searchIndex < 0 ? '' : withoutHash.substring(searchIndex);
  return {
    protocol: protocol.toLowerCase(),
    host: host.toLowerCase(),
    pathname: pathname.length > 0 ? pathname : '/',
    search: search
  };
}

function firstIndexOfUrlSuffix(value: string, fromIndex: number): number {
  const slashIndex = value.indexOf('/', fromIndex);
  const queryIndex = value.indexOf('?', fromIndex);
  const hashIndex = value.indexOf('#', fromIndex);
  let result = -1;
  if (slashIndex >= 0) {
    result = slashIndex;
  }
  if (queryIndex >= 0 && (result < 0 || queryIndex < result)) {
    result = queryIndex;
  }
  if (hashIndex >= 0 && (result < 0 || hashIndex < result)) {
    result = hashIndex;
  }
  return result;
}

@Builder
function createSlockSandboxWebView(view: KuiklyRenderBaseView) {
  SlockSandboxWebViewComponent({ renderView: view as SlockSandboxWebView });
}
