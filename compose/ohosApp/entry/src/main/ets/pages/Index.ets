import { KRNativeRenderController, KRRecord, Kuikly, KuiklyRenderBaseView } from '@kuikly-open/render';
import deviceInfo from '@ohos.deviceInfo';
import hilog from '@ohos.hilog';
import router from '@ohos.router';
import { KuiklyViewDelegate } from '../kuikly/KuiklyViewDelegate';
import globalNativeManager from '../kuikly/MyNativeManager';
import slockOhosMotionBridge from '../kuikly/SlockOhosMotionBridge';
import slockOhosRealtimeBridge from '../kuikly/SlockOhosRealtimeBridge';
import { SlockOhosDiagnostics } from '../kuikly/SlockOhosDiagnostics';
import { SlockOhosBuglyDiagnostics } from '../entryability/SlockOhosBuglyDiagnostics';

const SAFE_AREA_TOP_PX = 'slockSafeAreaTopPx';
const SAFE_AREA_BOTTOM_PX = 'slockSafeAreaBottomPx';
const DEFAULT_PAGE_NAME = 'compose_slock_app_shell';
const HDS_MIN_API_VERSION = 23;
const HDS_TAB_BAR_SYSCAP = 'SystemCapability.UIDesign.HDSComponent.Core';
const SLOCK_BACK_PRESS_LOG_TAG = 'SlockBackPress';
const SLOCK_KUIKLY_EXCEPTION_LOG_TAG = 'SlockKuiklyException';
const SLOCK_ROUTE_HOST_LOG_TAG = 'SlockRouteHost';

@Entry
@Component
struct Index {
  kuiklyViewDelegate = new KuiklyViewDelegate();
  controller?: KRNativeRenderController;
  pageName?: string;
  pageData?: KRRecord;
  @StorageLink(SAFE_AREA_TOP_PX) safeAreaTopPx: number = 0;
  @StorageLink(SAFE_AREA_BOTTOM_PX) safeAreaBottomPx: number = 0;
  @State exception?: string = "";

  aboutToAppear() {
    AppStorage.setOrCreate<UIContext>("context", this.getUIContext());

    const params = router.getParams() as Record<string, Object>;
    this.pageData = params?.pageData as KRRecord;
    const paramsPageName = this.stringValue(params?.pageName);
    const pageDataPageName = this.stringValue(this.pageData?.['pageName']);
    const resolvedPageName =
      paramsPageName.length > 0 ? paramsPageName :
        pageDataPageName.length > 0 ? pageDataPageName : DEFAULT_PAGE_NAME;
    this.pageName = resolvedPageName;
    const payloadBytes = this.stringValue(this.pageData?.['payload']).length;
    const instanceId = this.stringValue(this.pageData?.['instanceId']);
    SlockOhosDiagnostics.record(
      SLOCK_ROUTE_HOST_LOG_TAG,
      `appear pageName=${resolvedPageName} paramsPageName=${paramsPageName.length > 0} pageDataPageName=${pageDataPageName.length > 0} instanceId=${instanceId} payloadBytes=${payloadBytes}`
    );
  }

  build() {
    Stack() {
      Kuikly({
        pagerName: this.pageName ?? DEFAULT_PAGE_NAME,
        pagerData: this.pageDataWithSafeArea(),
        delegate: this.kuiklyViewDelegate,
        nativeManager: globalNativeManager,
        onControllerReadyCallback: (controller: KRNativeRenderController) => {
          this.controller = controller;
          controller.registerExceptionCallback((_executeMode, stack) => {
            this.handleKuiklyExceptionStack(stack);
          });
          slockOhosMotionBridge.setController(controller);
          slockOhosRealtimeBridge.setController(controller);
        },
      })
      if (this.exception) {
        Text(this.exception)
      }
    }
    .width('100%')
    .height('100%')
    .expandSafeArea([SafeAreaType.KEYBOARD])
  }

  onPageShow(): void {
    this.kuiklyViewDelegate.pageDidAppear();
    slockOhosRealtimeBridge.onPageShow();
  }

  onPageHide(): void {
    this.kuiklyViewDelegate.pageDidDisappear();
    slockOhosRealtimeBridge.onPageHide();
  }

  onBackPress(): boolean {
    const pageName = this.pageName ?? DEFAULT_PAGE_NAME;
    if (this.controller === undefined) {
      hilog.warn(
        0x0000,
        SLOCK_BACK_PRESS_LOG_TAG,
        'host_onBackPress result=no_controller page=%{public}s',
        pageName
      );
      return false;
    }
    const consumed = this.controller.onBackPress();
    hilog.info(
      0x0000,
      SLOCK_BACK_PRESS_LOG_TAG,
      'host_onBackPress consumed=%{public}s page=%{public}s',
      consumed ? 'true' : 'false',
      pageName
    );
    return consumed;
  }

  private pageDataWithSafeArea(): KRRecord {
    const pageData: KRRecord = {};
    const source = this.pageData ?? {};
    Object.keys(source).forEach((key: string) => {
      pageData[key] = source[key];
    });
    pageData['safeAreaTop'] = this.safeAreaTopPx;
    pageData['statusBarHeight'] = this.safeAreaTopPx;
    pageData['safeAreaBottom'] = this.safeAreaBottomPx;
    pageData['navigationBarHeight'] = this.safeAreaBottomPx;
    const now = new Date();
    pageData['localOffsetMinutes'] = -now.getTimezoneOffset();
    pageData['todayEpochDay'] = Math.floor(Date.UTC(now.getFullYear(), now.getMonth(), now.getDate()) / 86400000);
    pageData['nowEpochMillis'] = now.getTime();
    if (this.supportsHdsTabBar()) {
      pageData['slockNativeTabBarRenderer'] = 'ohosHdsImmersive';
    } else {
      pageData['slockNativeTabBarRenderer'] = 'sharedCompose';
      pageData['slockNativeTabBarFallbackReason'] = 'unsupported_ohos_hds';
    }
    return pageData;
  }

  private handleKuiklyExceptionStack(stack: string): void {
    const stackText = this.symbolicatedKuiklyStack(stack);
    this.exception = stackText;
    this.reportKuiklyExceptionToBugly(stackText);
    stackText.split('\n').forEach((line: string) => {
      if (line.length > 0) {
        hilog.error(0x0000, SLOCK_KUIKLY_EXCEPTION_LOG_TAG, '%{public}s', line);
      }
    });
  }

  private symbolicatedKuiklyStack(stack: string): string {
    try {
      const stackInfo: KRRecord = JSON.parse(stack) as KRRecord;
      const stackValue = stackInfo['stack'];
      if (typeof stackValue === 'string' && stackValue.length > 0) {
        return stackValue;
      }
    } catch (_error) {
      hilog.warn(
        0x0000,
        SLOCK_KUIKLY_EXCEPTION_LOG_TAG,
        'kuikly_exception_stack_parse_failed'
      );
    }
    return stack;
  }

  private reportKuiklyExceptionToBugly(stackText: string): void {
    const firstLine = stackText.split('\n').find((line: string) => line.length > 0) ?? 'Kuikly exception';
    SlockOhosBuglyDiagnostics.reportCaughtError(
      'KuiklyException',
      firstLine,
      stackText,
      JSON.stringify({
        source: 'kuikly_exception_callback',
        pageName: this.pageName ?? DEFAULT_PAGE_NAME,
      })
    );
  }

  private supportsHdsTabBar(): boolean {
    return this.currentApiVersion() >= HDS_MIN_API_VERSION && canIUse(HDS_TAB_BAR_SYSCAP);
  }

  private currentApiVersion(): number {
    const distributionApiVersion = deviceInfo.distributionOSApiVersion;
    if (distributionApiVersion > 0) {
      return distributionApiVersion;
    }
    return deviceInfo.sdkApiVersion;
  }

  private stringValue(value: Object | undefined): string {
    return typeof value === 'string' ? value : '';
  }
}
