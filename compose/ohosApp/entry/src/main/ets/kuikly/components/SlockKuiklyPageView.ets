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
        this.pageName = normalizeText(propValue);
        return true;
      case kPageData:
        this.pageData = normalizeText(propValue);
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
    if (this.viewDidAppear && this.pageDidAppear) {
      this.delegate.pageDidDisappear();
    }
  }

  onKuiklyControllerReady(controller: KRNativeRenderController): void {
    controller.registerExceptionCallback((_executeMode, stack) => {
      this.onPageLoadComplete(false, stack);
    });
    this.onPageLoadComplete(true);
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
          this.dispatchAppearIfReady();
          break;
        case 'didDisappear':
          this.viewDidAppear = false;
          if (this.pageDidAppear) {
            this.delegate.pageDidDisappear();
          }
          break;
        case 'viewDidAppear':
          this.pageDidAppear = true;
          this.dispatchAppearIfReady();
          break;
        case 'viewDidDisappear':
          this.pageDidAppear = false;
          if (this.viewDidAppear) {
            this.delegate.pageDidDisappear();
          }
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

  private dispatchAppearIfReady(): void {
    if (this.viewDidAppear && this.pageDidAppear) {
      this.delegate.pageDidAppear();
    }
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

@Builder
function createSlockKuiklyPageView(view: KuiklyRenderBaseView) {
  SlockKuiklyPageViewComponent({ renderView: view as SlockKuiklyPageView });
}
