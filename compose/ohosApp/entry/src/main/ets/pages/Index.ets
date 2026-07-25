import { KRNativeRenderController, KRRecord, Kuikly, KuiklyRenderBaseView } from '@kuikly-open/render';
import { common } from '@kit.AbilityKit';
import { HandsFeedbackClient } from '@botiverse/hands';
import deviceInfo from '@ohos.deviceInfo';
import hilog from '@ohos.hilog';
import router from '@ohos.router';
import { KuiklyViewDelegate } from '../kuikly/KuiklyViewDelegate';
import globalNativeManager from '../kuikly/MyNativeManager';
import slockOhosMotionBridge from '../kuikly/SlockOhosMotionBridge';
import slockOhosRealtimeBridge from '../kuikly/SlockOhosRealtimeBridge';
import { SlockOhosClipboard } from '../kuikly/SlockOhosClipboard';
import slockOhosAdaptivePaneBridge from '../kuikly/SlockOhosAdaptivePaneBridge';
import { SlockOhosDiagnostics } from '../kuikly/SlockOhosDiagnostics';
import { SlockOhosAppDiagnostics } from '../entryability/SlockOhosAppDiagnostics';
import {
  SLOCK_OHOS_EXTERNAL_SHARE_INTENT,
  SLOCK_OHOS_EXTERNAL_SHARE_STORAGE_KEY
} from '../entryability/SlockOhosExternalShareIntake';

const SAFE_AREA_TOP_PX = 'slockSafeAreaTopPx';
const SAFE_AREA_BOTTOM_PX = 'slockSafeAreaBottomPx';
const DEFAULT_PAGE_NAME = 'compose_slock_app_shell';
const HDS_MIN_API_VERSION = 23;
const HDS_TAB_BAR_SYSCAP = 'SystemCapability.UIDesign.HDSComponent.Core';
const SLOCK_BACK_PRESS_LOG_TAG = 'SlockBackPress';
const SLOCK_KUIKLY_EXCEPTION_LOG_TAG = 'SlockKuiklyException';
const SLOCK_ROUTE_HOST_LOG_TAG = 'SlockRouteHost';
const SLOCK_EXCEPTION_REPORT_IDLE = 'idle';
const SLOCK_EXCEPTION_REPORT_SUBMITTING = 'submitting';
const SLOCK_EXCEPTION_REPORT_SUCCESS = 'success';
const SLOCK_EXCEPTION_REPORT_ERROR = 'error';

@Entry
@Component
struct Index {
  kuiklyViewDelegate = new KuiklyViewDelegate();
  controller?: KRNativeRenderController;
  pageName?: string;
  pageData?: KRRecord;
  @StorageLink(SAFE_AREA_TOP_PX) safeAreaTopPx: number = 0;
  @StorageLink(SAFE_AREA_BOTTOM_PX) safeAreaBottomPx: number = 0;
  @StorageLink(SLOCK_OHOS_EXTERNAL_SHARE_STORAGE_KEY) externalShareLaunch: string = '';
  @State exception?: string = "";
  @State exceptionDetailsVisible: boolean = false;
  @State exceptionReportState: string = SLOCK_EXCEPTION_REPORT_IDLE;
  @State exceptionReportMessage: string = '';

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
          slockOhosAdaptivePaneBridge.setController(controller);
        },
      })
      if (this.exception) {
        this.exceptionFallback()
      }
    }
    .width('100%')
    .height('100%')
    // Extend the app background into the bottom navigation/system safe area so
    // the nav-indicator strip is painted by the app (SurfaceWhite) instead of
    // showing a disconnected system-painted band below the floating dock. Only
    // the bottom edge is expanded; the top status-bar inset stays handled by
    // the shared side's safeAreaTop padding. Interactive content is still kept
    // clear of the indicator by the shared bottomSafeArea padding.
    .expandSafeArea([SafeAreaType.SYSTEM, SafeAreaType.KEYBOARD], [SafeAreaEdge.BOTTOM])
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
    const externalShare = this.externalSharePageData();
    if (externalShare !== undefined) {
      pageData['intent'] = SLOCK_OHOS_EXTERNAL_SHARE_INTENT;
      pageData['externalShareToken'] = externalShare['externalShareToken'];
    }
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

  private externalSharePageData(): Record<string, Object> | undefined {
    if (this.externalShareLaunch.length === 0) return undefined;
    try {
      const parsed = JSON.parse(this.externalShareLaunch) as Record<string, Object>;
      const intent = parsed['intent'];
      const token = parsed['externalShareToken'];
      if (intent !== SLOCK_OHOS_EXTERNAL_SHARE_INTENT || typeof token !== 'string') return undefined;
      if (!/^[A-Za-z0-9-]{16,80}$/.test(token)) return undefined;
      // Return a deliberately reconstructed token-only object; never spread host intake metadata.
      return { 'externalShareToken': token };
    } catch (_error) {
      return undefined;
    }
  }

  private handleKuiklyExceptionStack(stack: string): void {
    const stackText = this.symbolicatedKuiklyStack(stack);
    this.exception = stackText;
    this.exceptionDetailsVisible = false;
    this.exceptionReportState = SLOCK_EXCEPTION_REPORT_IDLE;
    this.exceptionReportMessage = '';
    this.reportKuiklyExceptionToAppDiagnostics(stackText);
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

  private reportKuiklyExceptionToAppDiagnostics(stackText: string): void {
    const firstLine = stackText.split('\n').find((line: string) => line.length > 0) ?? 'Kuikly exception';
    SlockOhosAppDiagnostics.reportCaughtError(
      'KuiklyException',
      firstLine,
      stackText,
      JSON.stringify({
        source: 'kuikly_exception_callback',
        pageName: this.pageName ?? DEFAULT_PAGE_NAME,
      })
    );
  }

  @Builder
  private exceptionFallback() {
    Column({ space: 16 }) {
      Text('Raft 遇到启动错误')
        .fontSize(28)
        .fontWeight(FontWeight.Bold)
        .fontColor(Color.Black)
        .width('100%')
      Text('应用已保留脱敏诊断。你可以一键上报到 Hands，或复制详情后交给开发者。')
        .fontSize(16)
        .fontColor('#333333')
        .width('100%')
      Text(this.exceptionSummary())
        .fontSize(14)
        .fontColor('#8A1F11')
        .backgroundColor('#FFF1EC')
        .border({ width: 1, color: Color.Black })
        .padding(12)
        .width('100%')
      Button(this.exceptionReportButtonLabel())
        .width('100%')
        .height(48)
        .enabled(
          this.exceptionReportState !== SLOCK_EXCEPTION_REPORT_SUBMITTING &&
          this.exceptionReportState !== SLOCK_EXCEPTION_REPORT_SUCCESS
        )
        .backgroundColor(
          this.exceptionReportState === SLOCK_EXCEPTION_REPORT_SUCCESS ? '#B7F397' : '#FFD84D'
        )
        .fontColor(Color.Black)
        .onClick(() => {
          void this.submitExceptionReportToHands();
        })
      Row({ space: 12 }) {
        Button('复制详情')
          .layoutWeight(1)
          .height(44)
          .backgroundColor('#FFFFFF')
          .fontColor(Color.Black)
          .border({ width: 1, color: Color.Black })
          .onClick(() => {
            this.copyExceptionDetails();
          })
        Button(this.exceptionDetailsVisible ? '收起详情' : '查看详情')
          .layoutWeight(1)
          .height(44)
          .backgroundColor('#FFFFFF')
          .fontColor(Color.Black)
          .border({ width: 1, color: Color.Black })
          .onClick(() => {
            this.exceptionDetailsVisible = !this.exceptionDetailsVisible;
          })
      }
      .width('100%')
      if (this.exceptionReportMessage.length > 0) {
        Text(this.exceptionReportMessage)
          .fontSize(14)
          .fontColor(this.exceptionReportState === SLOCK_EXCEPTION_REPORT_ERROR ? '#8A1F11' : '#245C1B')
          .width('100%')
      }
      if (this.exceptionDetailsVisible) {
        Scroll() {
          Text(this.exception ?? '')
            .fontSize(12)
            .fontFamily('monospace')
            .fontColor(Color.Black)
            .width('100%')
        }
        .layoutWeight(1)
        .width('100%')
        .backgroundColor('#F5F5F5')
        .border({ width: 1, color: Color.Black })
        .padding(12)
      } else {
        Blank()
      }
    }
    .width('100%')
    .height('100%')
    .padding({ left: 24, right: 24, top: 48, bottom: 32 })
    .backgroundColor('#FFF8EA')
  }

  private exceptionSummary(): string {
    const stackText = this.exception ?? '';
    return stackText.split('\n').find((line: string) => line.trim().length > 0) ?? 'Kuikly exception';
  }

  private exceptionReportButtonLabel(): string {
    if (this.exceptionReportState === SLOCK_EXCEPTION_REPORT_SUBMITTING) {
      return '正在上报…';
    }
    if (this.exceptionReportState === SLOCK_EXCEPTION_REPORT_SUCCESS) {
      return '已上报 Hands';
    }
    if (this.exceptionReportState === SLOCK_EXCEPTION_REPORT_ERROR) {
      return '重试上报 Hands';
    }
    return '一键上报 Hands';
  }

  private copyExceptionDetails(): void {
    const context = getContext(this) as common.UIAbilityContext;
    const reportText = [
      'Raft OHOS Kuikly exception',
      `Page: ${this.pageName ?? DEFAULT_PAGE_NAME}`,
      '',
      SlockOhosAppDiagnostics.exportEnvironmentText(context),
      '',
      this.exception ?? '',
    ].join('\n');
    SlockOhosClipboard.copyText(SlockOhosDiagnostics.sanitize(reportText));
    SlockOhosDiagnostics.record(SLOCK_KUIKLY_EXCEPTION_LOG_TAG, 'exception_details_copied');
    this.exceptionReportMessage = '详情已复制。';
  }

  private async submitExceptionReportToHands(): Promise<void> {
    if (
      this.exceptionReportState === SLOCK_EXCEPTION_REPORT_SUBMITTING ||
      this.exceptionReportState === SLOCK_EXCEPTION_REPORT_SUCCESS ||
      !this.exception
    ) {
      return;
    }
    this.exceptionReportState = SLOCK_EXCEPTION_REPORT_SUBMITTING;
    this.exceptionReportMessage = '正在准备脱敏诊断并上传…';
    try {
      const context = getContext(this) as common.UIAbilityContext;
      const archivePath = await SlockOhosAppDiagnostics.prepareFeedbackArchivePath(context);
      const safeSummary = SlockOhosDiagnostics.sanitize(this.exceptionSummary()).slice(0, 240);
      const ticketId = await HandsFeedbackClient.submit(
        context,
        `OHOS Kuikly startup fallback on ${this.pageName ?? DEFAULT_PAGE_NAME}: ${safeSummary}`,
        'bug',
        [archivePath],
        [],
      );
      const shortTicketId = ticketId.slice(0, 8);
      this.exceptionReportState = SLOCK_EXCEPTION_REPORT_SUCCESS;
      this.exceptionReportMessage = `已上报 Hands：${shortTicketId}`;
      SlockOhosDiagnostics.record(
        SLOCK_KUIKLY_EXCEPTION_LOG_TAG,
        `exception_feedback_submitted ticket=${shortTicketId}`
      );
    } catch (error) {
      const safeError = SlockOhosDiagnostics.sanitize(String(error)).slice(0, 240);
      this.exceptionReportState = SLOCK_EXCEPTION_REPORT_ERROR;
      this.exceptionReportMessage = '上报失败，请检查网络后重试。';
      SlockOhosDiagnostics.record(
        SLOCK_KUIKLY_EXCEPTION_LOG_TAG,
        `exception_feedback_submit_failed ${safeError}`
      );
    }
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
