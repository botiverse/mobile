import { ErrorReason, KRNativeRenderController, KRRecord, Kuikly, KuiklyRenderBaseView } from '@kuikly-open/render';
import deviceInfo from '@ohos.deviceInfo';
import hilog from '@ohos.hilog';
import router from '@ohos.router';
import { KuiklyViewDelegate } from '../kuikly/KuiklyViewDelegate';
import globalNativeManager from '../kuikly/MyNativeManager';
import slockOhosMotionBridge from '../kuikly/SlockOhosMotionBridge';
import slockOhosRealtimeBridge from '../kuikly/SlockOhosRealtimeBridge';

const SAFE_AREA_TOP_PX = 'slockSafeAreaTopPx';
const SAFE_AREA_BOTTOM_PX = 'slockSafeAreaBottomPx';
const DEFAULT_PAGE_NAME = 'compose_slock_app_shell';
const HDS_MIN_API_VERSION = 23;
const HDS_TAB_BAR_SYSCAP = 'SystemCapability.UIDesign.HDSComponent.Core';
const SLOCK_BACK_PRESS_LOG_TAG = 'SlockBackPress';

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
    this.pageName = params?.pageName as string;
    this.pageData = params?.pageData as KRRecord;
  }

  build() {
    Stack() {
      Kuikly({
        useOhSharedPreferences: true,
        pagerName: this.pageName ?? DEFAULT_PAGE_NAME,
        pagerData: this.pageDataWithSafeArea(),
        delegate: this.kuiklyViewDelegate,
        nativeManager: globalNativeManager,
        onControllerReadyCallback: (controller: KRNativeRenderController) => {
          this.controller = controller;
          slockOhosMotionBridge.setController(controller);
          slockOhosRealtimeBridge.setController(controller);
        },
        onRenderException: (exception: Error, reason: ErrorReason) => {
          this.exception = `${exception.name}:\n${exception.message}`;
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
}
