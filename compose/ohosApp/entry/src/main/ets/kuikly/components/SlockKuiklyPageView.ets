import {
  IKuiklyViewDelegate,
  KRAny,
  KRNativeRenderController,
  KRRecord,
  Kuikly,
  KuiklyRenderBaseView,
  KuiklyRenderCallback
} from '@kuikly-open/render';
import { ComponentContent } from '@kit.ArkUI';
import { KuiklyViewDelegate } from '../KuiklyViewDelegate';
import globalNativeManager from '../MyNativeManager';

const kPageName = 'pageName';
const kPageData = 'pageData';
const kGlassStyle = 'glassStyle';
const kActive = 'active';
const kLoadSuccess = 'loadSuccess';
const kLoadFailure = 'loadFailure';
const kSendEvent = 'sendEvent';
const kSlockKuiklyAppId = 1;

@Observed
export class SlockKuiklyPageView extends KuiklyRenderBaseView {
  static readonly VIEW_NAME = 'SlockKuiklyPageView';

  pageName: string = '';
  pageData: string = '{}';
  private loadSuccessCallback: KuiklyRenderCallback | null = null;
  private loadFailureCallback: KuiklyRenderCallback | null = null;
  private delegate: IKuiklyViewDelegate = new KuiklyViewDelegate();
  private viewDidAppear = false;
  private pageDidAppear = false;
  childActive: boolean = true;
  private childVisible = false;
  renderRevision: number = 0;

  createArkUIView(): ComponentContent<KuiklyRenderBaseView> {
    const uiContext = this.getUIContext() as UIContext;
    return new ComponentContent<KuiklyRenderBaseView>(
      uiContext,
      wrapBuilder<[KuiklyRenderBaseView]>(createSlockKuiklyPageView),
      this
    );
  }

  setProp(propKey: string, propValue: KRAny | KuiklyRenderCallback): boolean {
    switch (propKey) {
      case kPageName:
        this.updatePageName(normalizeText(propValue));
        return true;
      case kPageData:
        this.updatePageData(normalizePageData(propValue));
        return true;
      case kGlassStyle:
        return true;
      case kActive:
        this.updateChildActive(normalizeBoolean(propValue));
        return true;
      case kLoadSuccess:
        this.loadSuccessCallback = propValue as KuiklyRenderCallback;
        return true;
      case kLoadFailure:
        this.loadFailureCallback = propValue as KuiklyRenderCallback;
        return true;
      default:
        return super.setProp(propKey, propValue);
    }
  }

  call(method: string, params: KRAny, callback: KuiklyRenderCallback | null): KRAny {
    if (method === kSendEvent) {
      this.sendEventWithParams(normalizeText(params));
    }
    return null;
  }

  onDestroy(): void {
    this.viewDidAppear = false;
    this.pageDidAppear = false;
    this.syncChildVisibility();
  }

  onKuiklyControllerReady(controller: KRNativeRenderController): void {
    controller.registerExceptionCallback((_executeMode, stack) => {
      this.onPageLoadComplete(false, stack);
    });
    this.onPageLoadComplete(true);
    this.syncChildVisibility();
  }

  getPageData(): KRRecord {
    try {
      const data = JSON.parse(this.pageData) as KRRecord;
      data['appId'] = kSlockKuiklyAppId;
      return data;
    } catch (_error) {
      return { 'appId': kSlockKuiklyAppId };
    }
  }

  getDelegate(): IKuiklyViewDelegate {
    return this.delegate;
  }

  getRenderKey(): string {
    return `${this.renderRevision}:${this.pageName}`;
  }

  private updatePageName(next: string): void {
    if (this.pageName === next) {
      return;
    }
    this.pageName = next;
    this.resetChildForNewPage();
  }

  private updatePageData(next: string): void {
    if (this.pageData === next) {
      return;
    }
    this.pageData = next;
    this.resetChildForNewPage();
  }

  private resetChildForNewPage(): void {
    if (this.childVisible) {
      this.delegate.pageDidDisappear();
      this.childVisible = false;
    }
    this.delegate = new KuiklyViewDelegate();
    this.renderRevision = this.renderRevision >= 1000000 ? 1 : this.renderRevision + 1;
    this.updateArkUI();
  }

  private updateChildActive(next: boolean): void {
    if (this.childActive === next) {
      return;
    }
    this.childActive = next;
    this.syncChildVisibility();
    this.updateArkUI();
  }

  private onPageLoadComplete(isSucceed: boolean, errorReason?: string): void {
    if (isSucceed) {
      this.loadSuccessCallback?.({});
      return;
    }
    this.loadFailureCallback?.({
      reason: errorReason ?? 'unknown',
    });
  }

  private sendEventWithParams(params: string): void {
    try {
      const eventData = JSON.parse(params) as KRRecord;
      const event = normalizeText(eventData['event']);
      const data = (eventData['data'] as KRRecord) ?? {};
      switch (event) {
        case 'didAppear':
          this.viewDidAppear = true;
          this.syncChildVisibility();
          break;
        case 'didDisappear':
          this.viewDidAppear = false;
          this.syncChildVisibility();
          break;
        case 'viewDidAppear':
          this.pageDidAppear = true;
          this.syncChildVisibility();
          break;
        case 'viewDidDisappear':
          this.pageDidAppear = false;
          this.syncChildVisibility();
          break;
        case 'windowSizeDidChanged':
        case 'rootViewSizeDidChanged':
        case 'pageFirstFramePaint':
        case 'pageWillDestroy':
        case 'setNeedLayout':
        case 'onBackPressed':
          break;
        default:
          this.delegate.sendEvent(event, data);
          break;
      }
    } catch (_error) {
      // Invalid event payloads are ignored to match Kuikly render-view call semantics.
    }
  }

  private syncChildVisibility(): void {
    const shouldBeVisible = this.childActive && this.viewDidAppear && this.pageDidAppear;
    if (this.childVisible === shouldBeVisible) {
      return;
    }
    this.childVisible = shouldBeVisible;
    if (shouldBeVisible) {
      this.delegate.pageDidAppear();
    } else {
      this.delegate.pageDidDisappear();
    }
  }

  childRenderVisibility(): Visibility {
    return this.childActive ? Visibility.Visible : Visibility.Hidden;
  }
}

@Component
export struct SlockKuiklyPageViewComponent {
  @ObjectLink renderView: SlockKuiklyPageView;

  build() {
    Stack() {
      if (
        this.renderView.pageName.length > 0 &&
        this.renderView.getViewWidth() > 0 &&
        this.renderView.getViewHeight() > 0
      ) {
        ForEach([this.renderView.getRenderKey()], (_renderKey: string) => {
          Kuikly({
            pagerName: this.renderView.pageName,
            pagerData: this.renderView.getPageData(),
            delegate: this.renderView.getDelegate(),
            nativeManager: globalNativeManager,
            onControllerReadyCallback: (controller: KRNativeRenderController) => {
              this.renderView.onKuiklyControllerReady(controller);
            },
            initialSize: {
              width: this.renderView.getViewWidth(),
              height: this.renderView.getViewHeight(),
            },
          })
            .visibility(this.renderView.childRenderVisibility())
        }, (renderKey: string) => renderKey)
      }
    }
    .width('100%')
    .height('100%')
  }
}

function normalizeText(value: KRAny | KuiklyRenderCallback): string {
  if (value === null || value === undefined) {
    return '';
  }
  return String(value);
}

function normalizePageData(value: KRAny | KuiklyRenderCallback): string {
  const text = normalizeText(value);
  return text.length > 0 ? text : '{}';
}

function normalizeBoolean(value: KRAny | KuiklyRenderCallback): boolean {
  if (typeof value === 'boolean') {
    return value;
  }
  const text = normalizeText(value).toLowerCase();
  return text === 'true' || text === '1' || text === 'yes';
}

@Builder
function createSlockKuiklyPageView(view: KuiklyRenderBaseView) {
  SlockKuiklyPageViewComponent({ renderView: view as SlockKuiklyPageView });
}
