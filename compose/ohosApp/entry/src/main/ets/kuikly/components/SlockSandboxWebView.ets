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
const kLoadSuccess = 'loadSuccess';
const kLoadFailure = 'loadFailure';
const kNavigationBlocked = 'navigationBlocked';
const kUrlChange = 'urlChange';

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
  renderRevision: number = 0;
  readonly controller: webview.WebviewController = new webview.WebviewController();
  private loadSuccessCallback: KuiklyRenderCallback | null = null;
  private loadFailureCallback: KuiklyRenderCallback | null = null;
  private navigationBlockedCallback: KuiklyRenderCallback | null = null;
  private urlChangeCallback: KuiklyRenderCallback | null = null;

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
      default:
        return super.setProp(propKey, propValue);
    }
  }

  call(method: string, params: KRAny, callback: KuiklyRenderCallback | null): KRAny {
    return null;
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
    return this.allowNavigation || this.isAllowedMainDocumentUrl(url);
  }

  notifyLoadSuccess(): void {
    this.loadSuccessCallback?.({});
  }

  notifyLoadFailure(reason: string): void {
    this.loadFailureCallback?.({ reason: reason.length > 0 ? reason : 'webview_load_failed' });
  }

  notifyNavigationBlocked(url: string): void {
    this.navigationBlockedCallback?.({ url });
  }

  notifyUrlChange(url: string): void {
    this.urlChangeCallback?.({ url });
  }

  private updateSrc(nextSrc: string): void {
    const normalized = nextSrc.trim();
    if (this.src === normalized) {
      return;
    }
    this.src = normalized;
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

  private bumpRenderRevision(): void {
    this.renderRevision = this.renderRevision >= 1000000 ? 1 : this.renderRevision + 1;
    this.updateArkUI();
  }
}

@Component
export struct SlockSandboxWebViewComponent {
  @ObjectLink renderView: SlockSandboxWebView;

  build() {
    if (this.renderView.src.length > 0) {
      ForEach([`${this.renderView.renderRevision}:${this.renderView.src}`], (_renderKey: string) => {
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
          .onPageEnd((event) => {
            this.renderView.notifyUrlChange(event.url);
            this.renderView.notifyLoadSuccess();
          })
          .onPageBegin((event) => {
            this.renderView.notifyUrlChange(event.url);
          })
          .onErrorReceive((event) => {
            this.renderView.notifyLoadFailure(JSON.stringify(event));
          })
          .onDownloadStart((event) => {
            this.renderView.notifyNavigationBlocked(JSON.stringify(event));
          })
          .onWindowNew((event) => {
            this.renderView.notifyNavigationBlocked(JSON.stringify(event));
          })
          .onOverrideUrlLoading((request) => {
            const url = request.getRequestUrl();
            if (this.renderView.shouldAllowNavigation(url)) {
              this.renderView.notifyUrlChange(url);
              return false;
            }
            this.renderView.notifyNavigationBlocked(url);
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
