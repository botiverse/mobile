import AbilityConstant from '@ohos.app.ability.AbilityConstant';
import { bundleManager } from '@kit.AbilityKit';
import deviceInfo from '@ohos.deviceInfo';
import fs from '@ohos.file.fs';
import hilog from '@ohos.hilog';
import util from '@ohos.util';
import UIAbility from '@ohos.app.ability.UIAbility';
import Want from '@ohos.app.ability.Want';
import window from '@ohos.window';
import { KuiklyRenderAdapterManager } from '@kuikly-open/render';
import { AppVersionMode, Bugly, BuglyBuilder, ModuleName } from 'bugly';
import { LogAdapter } from '../kuikly/adapter/LogAdapter';
import { RouterAdapter } from '../kuikly/adapter/RouterAdapter';
import { SlockOhosDiagnostics } from '../kuikly/SlockOhosDiagnostics';
import slockOhosRealtimeBridge from '../kuikly/SlockOhosRealtimeBridge';
import { SlockBuglyCrashListener, SlockOhosBuglyDiagnostics } from './SlockOhosBuglyDiagnostics';
import { SlockOhosCrashReporter } from './SlockOhosCrashReporter';

const SAFE_AREA_TOP_PX = 'slockSafeAreaTopPx';
const SAFE_AREA_BOTTOM_PX = 'slockSafeAreaBottomPx';
const BUGLY_APP_ID = '00d93a6322';
const BUGLY_APP_KEY = '852c8bcc-3e5c-4447-88d9-b3f90abf5f57';
const BUGLY_APP_CHANNEL = 'ohos';
const BUGLY_DEBUG_MODE = false;
const BUGLY_DEVICE_DIR = 'bugly';
const BUGLY_DEVICE_ID_FILE = 'device_id.txt';
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
];
const SLOCK_FONT_LOG_TAG = 'SlockFont';

export default class EntryAbility extends UIAbility {
  private static buglyInitialized: boolean = false;
  private static buglyInitializing: boolean = false;
  private static uiFontsRegistered: boolean = false;
  private mainWindow?: window.Window;

  onCreate(want: Want, launchParam: AbilityConstant.LaunchParam): void {
    hilog.info(0x0000, 'testTag', '%{public}s', 'Ability onCreate');
    this.initBugly(launchParam);
    SlockOhosCrashReporter.install(this.context);
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
    mainWindow.on('avoidAreaChange', (data: window.AvoidAreaOptions) => {
      if (data.type === window.AvoidAreaType.TYPE_SYSTEM ||
        data.type === window.AvoidAreaType.TYPE_NAVIGATION_INDICATOR) {
        this.updateSafeAreaStorage(mainWindow);
      }
    });

    KuiklyRenderAdapterManager.krLogAdapter = new LogAdapter();
    KuiklyRenderAdapterManager.krRouterAdapter = new RouterAdapter();
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
    this.mainWindow = undefined;
  }

  onForeground(): void {
    // Ability has brought to foreground
    hilog.info(0x0000, 'testTag', '%{public}s', 'Ability onForeground');
    this.restoreMainWindowLayout();
    slockOhosRealtimeBridge.onPageShow();
  }

  onBackground(): void {
    // Ability has back to background
    hilog.info(0x0000, 'testTag', '%{public}s', 'Ability onBackground');
    slockOhosRealtimeBridge.onPageHide();
  }

  private initBugly(launchParam: AbilityConstant.LaunchParam): void {
    if (EntryAbility.buglyInitialized || EntryAbility.buglyInitializing) {
      return;
    }
    EntryAbility.buglyInitializing = true;
    try {
      const bundleInfo = bundleManager.getBundleInfoForSelfSync(bundleManager.BundleFlag.GET_BUNDLE_INFO_DEFAULT);
      const builder = new BuglyBuilder();
      const deviceId = this.resolveBuglyDeviceId();
      const deviceModel = `${deviceInfo.manufacture} ${deviceInfo.productModel}`.trim();
      builder.appId = BUGLY_APP_ID;
      builder.appKey = BUGLY_APP_KEY;
      builder.deviceId = deviceId;
      builder.platform = BuglyBuilder.PLATFORM_PRO;
      builder.appVersion = bundleInfo.versionName;
      builder.appVersionMode = BUGLY_DEBUG_MODE ? AppVersionMode.DEBUG : AppVersionMode.RELEASE;
      builder.buildNum = `${bundleInfo.versionCode}`;
      builder.appChannel = BUGLY_APP_CHANNEL;
      builder.deviceModel = deviceModel;
      builder.debugMode = BUGLY_DEBUG_MODE;
      builder.sdkLogMode = BUGLY_DEBUG_MODE;
      builder.initDelay = 0;
      builder.crashListener = new SlockBuglyCrashListener();
      builder.enableJsCrashProtect = false;
      builder.enablePrivacyInfoAuthorized = true;
      builder.enablePerfModules = ModuleName.AllModules;
      Bugly.init(this.context.getApplicationContext(), builder)
        .then((): void => {
          Bugly.setCrashMonitorAble(true, true);
          SlockOhosBuglyDiagnostics.markBuglyReady();
          SlockOhosBuglyDiagnostics.init(this.context, {
            appId: BUGLY_APP_ID,
            appKey: BUGLY_APP_KEY,
            deviceId,
            appVersion: bundleInfo.versionName,
            deviceModel,
            debugMode: BUGLY_DEBUG_MODE,
          });
          SlockOhosDiagnostics.setPersistentRecorder((tag: string, message: string): void => {
            SlockOhosBuglyDiagnostics.record(tag, message);
          });
          EntryAbility.buglyInitialized = true;
          EntryAbility.buglyInitializing = false;
          this.reportLastExitReason(launchParam);
          hilog.info(0x0000, 'testTag', '%{public}s', 'Bugly initialized');
        })
        .catch((err: Object): void => {
          this.handleBuglyInitFailure(err);
        });
    } catch (err) {
      this.handleBuglyInitFailure(err);
    }
  }

  private handleBuglyInitFailure(err: Object): void {
    const message = JSON.stringify(err) ?? '';
    EntryAbility.buglyInitialized = false;
    EntryAbility.buglyInitializing = false;
    SlockOhosBuglyDiagnostics.disable(`init_failed ${message}`);
    SlockOhosDiagnostics.setPersistentRecorder(null);
    hilog.error(0x0000, 'testTag', 'Failed to initialize Bugly. Cause: %{public}s', message);
  }

  private reportLastExitReason(launchParam: AbilityConstant.LaunchParam): void {
    if (!EntryAbility.buglyInitialized) {
      return;
    }
    try {
      Bugly.postLastExitReason('EntryAbility', launchParam);
    } catch (err) {
      hilog.warn(0x0000, 'testTag', 'Failed to report Bugly last exit reason. Cause: %{public}s', JSON.stringify(err) ?? '');
    }
  }

  private resolveBuglyDeviceId(): string {
    const buglyDir = `${this.context.filesDir}/${BUGLY_DEVICE_DIR}`;
    const deviceIdPath = `${buglyDir}/${BUGLY_DEVICE_ID_FILE}`;
    const savedDeviceId = this.readTextFile(deviceIdPath).trim();
    if (savedDeviceId.length > 0) {
      return savedDeviceId;
    }
    const generatedDeviceId = `ohos-${Date.now()}-${Math.floor(Math.random() * 0xFFFFFFFFFFFF).toString(16)}`;
    try {
      fs.mkdirSync(buglyDir, true);
      const file = fs.openSync(deviceIdPath, fs.OpenMode.CREATE | fs.OpenMode.TRUNC | fs.OpenMode.WRITE_ONLY);
      try {
        fs.writeSync(file.fd, generatedDeviceId);
      } finally {
        fs.closeSync(file);
      }
    } catch (err) {
      hilog.warn(0x0000, 'testTag', 'Failed to persist Bugly device ID. Cause: %{public}s', JSON.stringify(err) ?? '');
    }
    return generatedDeviceId;
  }

  private readTextFile(filePath: string): string {
    try {
      const file = fs.openSync(filePath, fs.OpenMode.READ_ONLY);
      try {
        const stat = fs.statSync(file.fd);
        if (stat.size <= 0) {
          return '';
        }
        const buffer = new ArrayBuffer(stat.size);
        const readLength = fs.readSync(file.fd, buffer);
        const decoder = new util.TextDecoder('utf-8');
        return decoder.decodeWithStream(new Uint8Array(buffer, 0, readLength));
      } finally {
        fs.closeSync(file);
      }
    } catch (err) {
      return '';
    }
  }
}
