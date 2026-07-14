import 'libentry.so';
import AbilityConstant from '@ohos.app.ability.AbilityConstant';
import { bundleManager } from '@kit.AbilityKit';
import fs from '@ohos.file.fs';
import hilog from '@ohos.hilog';
import display from '@ohos.display';
import UIAbility from '@ohos.app.ability.UIAbility';
import Want from '@ohos.app.ability.Want';
import window from '@ohos.window';
import { registerSingleServiceProvider, setup, init } from '@kuiklybase/knoi';
import { KuiklyRenderAdapterManager } from '@kuikly-open/render';
import { LogAdapter } from '../kuikly/adapter/LogAdapter';
import { RouterAdapter } from '../kuikly/adapter/RouterAdapter';
import { SlockKRVideoAdapter } from '../kuikly/adapter/SlockKRVideoAdapter';
import { initializeSlockOhosAppScopeMMKV } from '../kuikly/SlockOhosAppScopeMMKV';
import { initKmmResourceCompose } from '../kuikly/SlockOhosKmmResource';
import { SlockOhosDiagnostics } from '../kuikly/SlockOhosDiagnostics';
import { admitSlockOhosPreparedBatch } from '../kuikly/SlockOhosDiagnosticsQueue';
import slockOhosRealtimeBridge from '../kuikly/SlockOhosRealtimeBridge';
import slockOhosRealtimeKeepAlive from '../kuikly/SlockOhosRealtimeKeepAlive';
import { SlockOhosAppDiagnostics } from './SlockOhosAppDiagnostics';
import slockOhosAdaptivePaneBridge from '../kuikly/SlockOhosAdaptivePaneBridge';
import { Hands } from '@botiverse/hands';
import {
  SLOCK_OHOS_PLATFORM_FILE_READER_SERVICE,
  SlockOhosPlatformFileReader
} from './SlockOhosPlatformFileReader';
import BuildProfile from 'BuildProfile';

const SAFE_AREA_TOP_PX = 'slockSafeAreaTopPx';
const SAFE_AREA_BOTTOM_PX = 'slockSafeAreaBottomPx';
interface SlockFontResource {
  familyName: string;
  familySrc: Resource;
}

const SLOCK_FONTS: SlockFontResource[] = [
  { familyName: 'space_grotesk', familySrc: $rawfile('fonts/space_grotesk.ttf') },
  { familyName: 'space_grotesk_regular', familySrc: $rawfile('fonts/space_grotesk_regular.ttf') },
  { familyName: 'space_grotesk_medium', familySrc: $rawfile('fonts/space_grotesk_medium.ttf') },
  { familyName: 'space_grotesk_semibold', familySrc: $rawfile('fonts/space_grotesk_semibold.ttf') },
  { familyName: 'space_grotesk_bold', familySrc: $rawfile('fonts/space_grotesk_bold.ttf') },
  { familyName: 'space_grotesk_light', familySrc: $rawfile('fonts/space_grotesk_light.ttf') },
  { familyName: 'space_grotesk_variable', familySrc: $rawfile('fonts/space_grotesk_variable.ttf') },
  { familyName: 'space_mono_regular', familySrc: $rawfile('fonts/space_mono_regular.ttf') },
  { familyName: 'space_mono_bold', familySrc: $rawfile('fonts/space_mono_bold.ttf') },
];
const SLOCK_FONT_LOG_TAG = 'SlockFont';
const SLOCK_KNOI_LOG_TAG = 'SlockKnoi';
const SLOCK_SHARED_LOG_SINK_SERVICE = 'slockOhosSharedLogSink';

class SlockOhosSharedLogSink {
  record(level: string, tag: string, message: string): void {
    SlockOhosDiagnostics.record(tag, message, Date.now(), this.normalizeLevel(level));
  }

  recordBatch(payload: string): boolean {
    const admitted = admitSlockOhosPreparedBatch(
      payload,
      (entry) => SlockOhosDiagnostics.recordPreparedAsync(entry)
    );
    if (!admitted) {
      SlockOhosDiagnostics.record(
        SLOCK_KNOI_LOG_TAG,
        'slock_ohos_log_batch_admission_failed',
        Date.now(),
        'error'
      );
    }
    return admitted;
  }

  producerState(pending: boolean, healthy: boolean): void {
    SlockOhosDiagnostics.setUpstreamState(pending, healthy);
  }

  private normalizeLevel(level: string): string {
    const normalized = level.trim().toLowerCase();
    if (normalized === 'debug' || normalized === 'info' || normalized === 'warn' || normalized === 'error') {
      return normalized;
    }
    return 'info';
  }
}

export default class EntryAbility extends UIAbility {
  private static uiFontsRegistered: boolean = false;
  private mainWindow?: window.Window;
  private windowSizeCallback?: Callback<window.Size>;
  private foldStatusCallback?: Callback<display.FoldStatus>;
  private foldPosture: string = 'unknown';

  onCreate(want: Want, launchParam: AbilityConstant.LaunchParam): void {
    hilog.info(0x0000, 'testTag', '%{public}s', 'Ability onCreate');
    // Crash reporting must install BEFORE any native/KNOI initialization —
    // a startup-chain crash (like the task #19 resource-bridge SIGSEGV)
    // is otherwise invisible to Hands.
    // App-owned Hands reporting config (client key is DSN-class: it
    // identifies the app; rotate from the Hands console if leaked).
    Hands.install(
      {
        baseUrl: 'https://hands.build',
        appSlug: 'raft-ohos',
        channel: 'main',
        clientKey: 'qk_425c2f5b28aae3d46037634665b99bc72605bb6e',
      },
      this.context,
    );
    this.setupKnoi();
  }

  onDestroy(): void {
    hilog.info(0x0000, 'testTag', '%{public}s', 'Ability onDestroy');
  }

  onWindowStageCreate(windowStage: window.WindowStage): void {
    // Main window is created, set main page for this ability
    hilog.info(0x0000, 'testTag', '%{public}s', 'Ability onWindowStageCreate');
    const mainWindow = windowStage.getMainWindowSync();
    this.mainWindow = mainWindow;
    mainWindow.setWindowLayoutFullScreen(true);
    this.updateSafeAreaStorage(mainWindow);
    this.registerAdaptivePaneBridge(mainWindow);
    mainWindow.on('avoidAreaChange', (data: window.AvoidAreaOptions) => {
      if (data.type === window.AvoidAreaType.TYPE_SYSTEM ||
        data.type === window.AvoidAreaType.TYPE_NAVIGATION_INDICATOR) {
        this.updateSafeAreaStorage(mainWindow);
      }
    });

    KuiklyRenderAdapterManager.krLogAdapter = new LogAdapter();
    KuiklyRenderAdapterManager.krRouterAdapter = new RouterAdapter();
    KuiklyRenderAdapterManager.krVideoViewAdapter = new SlockKRVideoAdapter();
    this.registerSlockFonts(mainWindow);

    windowStage.loadContent('pages/Index', (err, data) => {
      if (err.code) {
        hilog.error(0x0000, 'testTag', 'Failed to load the content. Cause: %{public}s', JSON.stringify(err) ?? '');
        return;
      }
      this.registerSlockFonts(mainWindow);
      hilog.info(0x0000, 'testTag', 'Succeeded in loading the content. Data: %{public}s', JSON.stringify(data) ?? '');
    });
  }

  private registerSlockFonts(mainWindow: window.Window): void {
    if (EntryAbility.uiFontsRegistered) {
      return;
    }
    try {
      const uiFont = mainWindow.getUIContext().getFont();
      let hasRegistrationFailure = false;
      SLOCK_FONTS.forEach((fontResource: SlockFontResource) => {
        const registered = this.registerFontResource('ui', fontResource.familyName, () => {
          uiFont.registerFont({
            familyName: fontResource.familyName,
            familySrc: fontResource.familySrc,
          });
        });
        if (!registered) {
          hasRegistrationFailure = true;
        }
      });
      this.context.getApplicationContext().setFont('space_grotesk_regular');
      EntryAbility.uiFontsRegistered = !hasRegistrationFailure;
    } catch (err) {
      hilog.warn(0x0000, 'testTag', 'Skip UI font registration until UIContext is ready. Cause: %{public}s', JSON.stringify(err) ?? '');
    }
  }

  private registerFontResource(scope: string, familyName: string, register: () => void): boolean {
    try {
      register();
      return true;
    } catch (err) {
      const message = this.safeErrorMessage(err);
      hilog.warn(
        0x0000,
        SLOCK_FONT_LOG_TAG,
        'Failed to register %{public}s font %{public}s. Cause: %{public}s',
        scope,
        familyName,
        message
      );
      SlockOhosDiagnostics.record(
        SLOCK_FONT_LOG_TAG,
        `font_registration_failed scope=${scope} family=${familyName} cause=${message}`
      );
      return false;
    }
  }

  private safeErrorMessage(error: Object): string {
    try {
      const encoded = JSON.stringify(error);
      if (encoded !== undefined && encoded.length > 0 && encoded !== '{}') {
        return encoded;
      }
    } catch (_err) {
      // Fall back to String(error) below.
    }
    return String(error);
  }

  private setupKnoi(): void {
    initializeSlockOhosAppScopeMMKV(this.context);
    setup('libshared.so', BuildProfile.DEBUG);
    init();
    registerSingleServiceProvider(
      SLOCK_OHOS_PLATFORM_FILE_READER_SERVICE,
      new SlockOhosPlatformFileReader()
    );
    registerSingleServiceProvider(
      SLOCK_SHARED_LOG_SINK_SERVICE,
      new SlockOhosSharedLogSink()
    );
    // KmmResource contract: must complete after KNOI setup()/init() and
    // before any Kuikly/shared page exists — the first shared
    // stringResource() otherwise SIGSEGVs in native string lookup
    // (task #19). onCreate runs before onWindowStageCreate's loadContent,
    // so the ordering is structural.
    initKmmResourceCompose(this.context);
    hilog.info(0x0000, SLOCK_KNOI_LOG_TAG, '%{public}s', 'KNOI setup complete for libshared.so');
  }

  private updateSafeAreaStorage(mainWindow: window.Window): void {
    try {
      const systemArea = mainWindow.getWindowAvoidArea(window.AvoidAreaType.TYPE_SYSTEM);
      const navigationArea = mainWindow.getWindowAvoidArea(window.AvoidAreaType.TYPE_NAVIGATION_INDICATOR);
      AppStorage.setOrCreate(SAFE_AREA_TOP_PX, systemArea.topRect.height);
      AppStorage.setOrCreate(SAFE_AREA_BOTTOM_PX, navigationArea.bottomRect.height);
    } catch (err) {
      hilog.warn(0x0000, 'testTag', 'Failed to update safe area. Cause: %{public}s', JSON.stringify(err) ?? '');
    }
  }

  /** Sends host-only geometry through the root pager event channel, never route pageData. */
  private registerAdaptivePaneBridge(mainWindow: window.Window): void {
    this.publishAdaptivePane(mainWindow);
    this.windowSizeCallback = (size: window.Size) => {
      this.publishAdaptivePane(mainWindow, size.width, size.height);
    };
    mainWindow.on('windowSizeChange', this.windowSizeCallback);

    try {
      if (!display.isFoldable()) {
        return;
      }
      this.foldPosture = this.foldPostureName(display.getFoldStatus());
      this.foldStatusCallback = (status: display.FoldStatus) => {
        this.foldPosture = this.foldPostureName(status);
        this.publishAdaptivePane(mainWindow);
      };
      display.on('foldStatusChange', this.foldStatusCallback);
      this.publishAdaptivePane(mainWindow);
    } catch (err) {
      hilog.warn(0x0000, 'testTag', 'Failed to observe fold posture. Cause: %{public}s', JSON.stringify(err) ?? '');
    }
  }

  private unregisterAdaptivePaneBridge(mainWindow: window.Window): void {
    try {
      if (this.windowSizeCallback !== undefined) {
        mainWindow.off('windowSizeChange', this.windowSizeCallback);
      }
      if (this.foldStatusCallback !== undefined) {
        display.off('foldStatusChange', this.foldStatusCallback);
      }
    } catch (err) {
      hilog.warn(0x0000, 'testTag', 'Failed to stop adaptive pane observers. Cause: %{public}s', JSON.stringify(err) ?? '');
    } finally {
      this.windowSizeCallback = undefined;
      this.foldStatusCallback = undefined;
      this.foldPosture = 'unknown';
    }
  }

  private publishAdaptivePane(mainWindow: window.Window, width?: number, height?: number): void {
    try {
      const windowRect = mainWindow.getWindowProperties().windowRect;
      const displayInfo = display.getDefaultDisplaySync();
      slockOhosAdaptivePaneBridge.publish({
        widthPx: width ?? windowRect.width,
        heightPx: height ?? windowRect.height,
        density: displayInfo.densityPixels,
        posture: this.foldPosture,
      });
    } catch (err) {
      hilog.warn(0x0000, 'testTag', 'Failed to publish adaptive pane state. Cause: %{public}s', JSON.stringify(err) ?? '');
    }
  }

  private foldPostureName(status: display.FoldStatus): string {
    switch (status) {
      case display.FoldStatus.FOLD_STATUS_EXPANDED:
      case display.FoldStatus.FOLD_STATUS_EXPANDED_WITH_SECOND_EXPANDED:
        return 'expanded';
      case display.FoldStatus.FOLD_STATUS_FOLDED:
      case display.FoldStatus.FOLD_STATUS_FOLDED_WITH_SECOND_EXPANDED:
        return 'folded';
      case display.FoldStatus.FOLD_STATUS_HALF_FOLDED:
      case display.FoldStatus.FOLD_STATUS_HALF_FOLDED_WITH_SECOND_EXPANDED:
      case display.FoldStatus.FOLD_STATUS_EXPANDED_WITH_SECOND_HALF_FOLDED:
      case display.FoldStatus.FOLD_STATUS_FOLDED_WITH_SECOND_HALF_FOLDED:
      case display.FoldStatus.FOLD_STATUS_HALF_FOLDED_WITH_SECOND_HALF_FOLDED:
        return 'half_folded';
      default:
        return 'unknown';
    }
  }

  private restoreMainWindowLayout(): void {
    if (this.mainWindow === undefined) {
      return;
    }
    try {
      this.mainWindow.setWindowLayoutFullScreen(true);
      this.updateSafeAreaStorage(this.mainWindow);
    } catch (err) {
      hilog.warn(0x0000, 'testTag', 'Failed to restore main window layout. Cause: %{public}s', JSON.stringify(err) ?? '');
    }
  }

  onWindowStageDestroy(): void {
    // Main window is destroyed, release UI related resources
    hilog.info(0x0000, 'testTag', '%{public}s', 'Ability onWindowStageDestroy');
    if (this.mainWindow !== undefined) {
      this.unregisterAdaptivePaneBridge(this.mainWindow);
    }
    this.mainWindow = undefined;
  }

  onForeground(): void {
    // Ability has brought to foreground
    hilog.info(0x0000, 'testTag', '%{public}s', 'Ability onForeground');
    slockOhosRealtimeKeepAlive.stop(this.context, 'foreground');
    this.restoreMainWindowLayout();
    slockOhosRealtimeBridge.onPageShow();
  }

  onBackground(): void {
    // Ability has back to background
    hilog.info(0x0000, 'testTag', '%{public}s', 'Ability onBackground');
    slockOhosRealtimeKeepAlive.start(this.context, 'ability_background');
    slockOhosRealtimeBridge.onPageHide(true);
  }

}
