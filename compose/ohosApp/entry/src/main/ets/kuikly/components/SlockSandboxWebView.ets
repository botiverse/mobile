import { KRAny, KuiklyRenderBaseView, KuiklyRenderCallback } from '@kuikly-open/render';
import { ComponentContent } from '@kit.ArkUI';
import { webview } from '@kit.ArkWeb';

const kSrc = 'src';
const kAllowScripts = 'allowScripts';
const kLoadSuccess = 'loadSuccess';
const kLoadFailure = 'loadFailure';
const kNavigationBlocked = 'navigationBlocked';

@Observed
export class SlockSandboxWebView extends KuiklyRenderBaseView {
  static readonly VIEW_NAME = 'SlockSandboxWebView';
  src: string = '';
  allowScripts: boolean = true;
  renderRevision: number = 0;
  readonly controller: webview.WebviewController = new webview.WebviewController();
  private loadSuccessCallback: KuiklyRenderCallback | null = null;
  private loadFailureCallback: KuiklyRenderCallback | null = null;
  private navigationBlockedCallback: KuiklyRenderCallback | null = null;

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
      case kLoadSuccess:
        this.loadSuccessCallback = propValue as KuiklyRenderCallback;
        return true;
      case kLoadFailure:
        this.loadFailureCallback = propValue as KuiklyRenderCallback;
        return true;
      case kNavigationBlocked:
        this.navigationBlockedCallback = propValue as KuiklyRenderCallback;
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

  notifyLoadSuccess(): void {
    this.loadSuccessCallback?.({});
  }

  notifyLoadFailure(reason: string): void {
    this.loadFailureCallback?.({ reason: reason.length > 0 ? reason : 'webview_load_failed' });
  }

  notifyNavigationBlocked(url: string): void {
    this.navigationBlockedCallback?.({ url });
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
          incognitoMode: true,
        })
          .width('100%')
          .height('100%')
          .javaScriptAccess(this.renderView.allowScripts)
          .domStorageAccess(false)
          .databaseAccess(false)
          .fileAccess(false)
          .geolocationAccess(false)
          .onlineImageAccess(true)
          .multiWindowAccess(false)
          .onPageEnd(() => {
            this.renderView.notifyLoadSuccess();
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
            if (this.renderView.isAllowedMainDocumentUrl(url)) {
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
  try {
    const leftUrl = new URL(left);
    const rightUrl = new URL(right);
    return leftUrl.protocol === rightUrl.protocol &&
      leftUrl.host === rightUrl.host &&
      leftUrl.pathname === rightUrl.pathname &&
      leftUrl.search === rightUrl.search;
  } catch (_error) {
    return false;
  }
}

@Builder
function createSlockSandboxWebView(view: KuiklyRenderBaseView) {
  SlockSandboxWebViewComponent({ renderView: view as SlockSandboxWebView });
}
