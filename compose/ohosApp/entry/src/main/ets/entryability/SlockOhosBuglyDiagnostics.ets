import { bundleManager, common } from '@kit.AbilityKit';
import { BusinessError } from '@kit.BasicServicesKit';
import deviceInfo from '@ohos.deviceInfo';
import fs from '@ohos.file.fs';
import hilog from '@ohos.hilog';
import util from '@ohos.util';
import zlib from '@ohos.zlib';
import {
  Bugly,
  LogLevel,
  LogUploadListener,
  TDDiag,
  TDDiagBuilder,
  TDDiagUploadParams,
  TDLog,
  TDLogBuilder,
  TDLogMode,
} from 'bugly';
import { SlockOhosDiagnostics } from '../kuikly/SlockOhosDiagnostics';

const TAG: string = 'SlockOhosBuglyDiagnostics';
const BUGLY_LOG_DIR_NAME: string = 'bugly_tdlog';
const BUGLY_LOG_PREFIX: string = 'slock';
const DIAGNOSTIC_UPLOAD_DIR_NAME: string = 'diagnostics_upload';
const CRASH_DIR_NAME: string = 'crashes';
const MAX_DIAGNOSTIC_EXPORT_CHARS: number = 180000;
const FEEDBACK_FILE_PREFIX: string = 'slock-feedback-';
const FEEDBACK_SUMMARY_NAME: string = 'slock-diagnostics-summary.txt';
const FEEDBACK_ARCHIVE_MIME_TYPE: string = 'application/zip';

export class SlockBuglyCrashListener {
  onCrash(crashType: string, crashName: string, crashMsg: string, crashStack: string): void {
    SlockOhosBuglyDiagnostics.recordCrashCallback('crash', crashType, crashName, crashMsg, crashStack);
  }

  onHiAppEventReceive(crashType: string, crashName: string, crashMsg: string, crashStack: string): void {
    SlockOhosBuglyDiagnostics.recordCrashCallback('hiappevent', crashType, crashName, crashMsg, crashStack);
  }
}

class SlockBuglyLogUploadListener implements LogUploadListener {
  onSuccess(): void {
    SlockOhosDiagnostics.record(TAG, 'bugly_log_upload_success');
  }

  onFailure(): void {
    SlockOhosDiagnostics.record(TAG, 'bugly_log_upload_failure');
  }
}

export interface SlockBuglyDiagnosticsConfig {
  appId: string;
  appKey: string;
  deviceId: string;
  appVersion: string;
  deviceModel: string;
  debugMode: boolean;
}

export class SlockOhosBuglyDiagnostics {
  private static buglyReady: boolean = false;
  private static disabledReason: string = 'not_initialized';
  private static initialized: boolean = false;

  static markBuglyReady(): void {
    SlockOhosBuglyDiagnostics.buglyReady = true;
    SlockOhosBuglyDiagnostics.disabledReason = '';
    SlockOhosDiagnostics.record(TAG, 'bugly_core_ready');
  }

  static disable(reason: string): void {
    SlockOhosBuglyDiagnostics.buglyReady = false;
    SlockOhosBuglyDiagnostics.initialized = false;
    SlockOhosBuglyDiagnostics.disabledReason = reason.length > 0 ? reason : 'disabled';
    SlockOhosDiagnostics.record(TAG, `bugly_disabled reason=${SlockOhosBuglyDiagnostics.disabledReason}`);
  }

  static isBuglyReady(): boolean {
    return SlockOhosBuglyDiagnostics.buglyReady;
  }

  static init(context: common.UIAbilityContext, config: SlockBuglyDiagnosticsConfig): void {
    if (SlockOhosBuglyDiagnostics.initialized) {
      return;
    }
    if (!SlockOhosBuglyDiagnostics.buglyReady) {
      SlockOhosDiagnostics.record(TAG, `bugly_diagnostics_init_skipped reason=${SlockOhosBuglyDiagnostics.disabledReason}`);
      return;
    }

    const appContext = context.getApplicationContext();
    const logDir = SlockOhosBuglyDiagnostics.tdLogDir(context);
    let stage = 'mkdir_tdlog';
    try {
      SlockOhosBuglyDiagnostics.ensureDirectory(logDir);

      stage = 'tdlog_init';
      const logBuilder = new TDLogBuilder();
      logBuilder.logDir = logDir;
      logBuilder.namePrefix = BUGLY_LOG_PREFIX;
      logBuilder.consoleLog = config.debugMode;
      logBuilder.logLevel = LogLevel.LEVEL_ALL;
      logBuilder.mode = TDLogMode.Async;
      logBuilder.maxFileSize = 50 * 1024 * 1024;
      logBuilder.maxAliveTime = 7 * 24 * 60 * 60;
      TDLog.init(logBuilder);

      stage = 'tddiag_init';
      const diagBuilder = new TDDiagBuilder();
      diagBuilder.appId = config.appId;
      diagBuilder.appKey = config.appKey;
      diagBuilder.platform = TDDiagBuilder.PLATFORM_PRO;
      diagBuilder.appVersion = config.appVersion;
      diagBuilder.deviceModel = config.deviceModel;
      diagBuilder.loggerAdapter = TDLog.getTDLogAdapter();
      diagBuilder.logUploadListener = new SlockBuglyLogUploadListener();
      diagBuilder.xgTrafficQuota = 500 * 1024 * 1024;
      TDDiag.init(appContext, diagBuilder);
      TDDiag.setGuid(config.deviceId);

      SlockOhosBuglyDiagnostics.initialized = true;
      SlockOhosDiagnostics.record(TAG, 'bugly_diagnostics_initialized');
      TDLog.i(TAG, 'bugly diagnostics initialized');
    } catch (error) {
      hilog.error(0x0000, TAG, 'Failed to initialize Bugly diagnostics: %{public}s', SlockOhosBuglyDiagnostics.safeString(error));
      SlockOhosDiagnostics.record(TAG, `bugly_diagnostics_init_failed stage=${stage} ${SlockOhosBuglyDiagnostics.safeString(error)}`);
    }
  }

  static record(tag: string, message: string): void {
    if (!SlockOhosBuglyDiagnostics.initialized) {
      return;
    }
    try {
      TDLog.i(tag, message);
    } catch (_error) {
    }
  }

  static recordCrashCallback(source: string, crashType: string, crashName: string, crashMsg: string, crashStack: string): void {
    const summary = `${source} type=${crashType} name=${crashName} message=${crashMsg}`;
    SlockOhosDiagnostics.record(TAG, summary);
    SlockOhosBuglyDiagnostics.record(TAG, `${summary}\n${crashStack}`);
  }

  static reportCaughtError(name: string, message: string, stack: string, extraData: string = ''): Record<string, Object> {
    if (!SlockOhosBuglyDiagnostics.buglyReady) {
      SlockOhosDiagnostics.record(TAG, `bugly_custom_error_skipped ${name} reason=${SlockOhosBuglyDiagnostics.disabledReason}`);
      return SlockOhosBuglyDiagnostics.disabledResult('bugly_custom_error_disabled');
    }
    try {
      Bugly.postCustomError(name, message, stack, false, undefined, extraData);
      SlockOhosDiagnostics.record(TAG, `bugly_custom_error_posted ${name}`);
      return { "status": "ok", "code": "bugly_custom_error_posted" };
    } catch (error) {
      return {
        "status": "error",
        "code": "bugly_custom_error_failed",
        "message": SlockOhosBuglyDiagnostics.safeString(error),
      };
    }
  }

  static storeCrashRelatedError(context: common.UIAbilityContext, name: string, message: string, stack: string, extraData: string): void {
    if (!SlockOhosBuglyDiagnostics.buglyReady) {
      SlockOhosDiagnostics.record(TAG, `bugly_crash_related_error_skipped ${name} reason=${SlockOhosBuglyDiagnostics.disabledReason}`);
      return;
    }
    try {
      Bugly.postCustomError(name, message, stack, true, context.getApplicationContext(), extraData);
      SlockOhosDiagnostics.record(TAG, `bugly_crash_related_error_stored ${name}`);
    } catch (error) {
      SlockOhosDiagnostics.record(TAG, `bugly_crash_related_error_failed ${SlockOhosBuglyDiagnostics.safeString(error)}`);
    }
  }

  static testCrash(kind: string): Record<string, Object> {
    if (!SlockOhosBuglyDiagnostics.buglyReady) {
      return SlockOhosBuglyDiagnostics.disabledResult('bugly_test_crash_disabled');
    }
    try {
      switch (kind) {
        case 'js': {
          SlockOhosDiagnostics.record(TAG, 'bugly_test_js_crash_requested');
          Bugly.testCrash(Bugly.JS_CRASH);
          return { "status": "ok", "code": "bugly_test_js_crash_requested" };
        }
        case 'cpp': {
          SlockOhosDiagnostics.record(TAG, 'bugly_test_cpp_crash_requested');
          Bugly.testCrash(Bugly.CPP_CRASH);
          return { "status": "ok", "code": "bugly_test_cpp_crash_requested" };
        }
        case 'freeze': {
          SlockOhosDiagnostics.record(TAG, 'bugly_test_freeze_requested');
          Bugly.testCrash(Bugly.APP_FREEZE);
          return { "status": "ok", "code": "bugly_test_freeze_requested" };
        }
        default:
          return { "status": "error", "code": "bugly_unknown_test_crash" };
      }
    } catch (error) {
      return {
        "status": "error",
        "code": "bugly_test_crash_failed",
        "message": SlockOhosBuglyDiagnostics.safeString(error),
      };
    }
  }

  static updateScene(action: string, sceneName: string): Record<string, Object> {
    if (!SlockOhosBuglyDiagnostics.buglyReady) {
      return SlockOhosBuglyDiagnostics.disabledResult('bugly_scene_disabled');
    }
    try {
      if (action === 'exit') {
        Bugly.exitScene(sceneName);
      } else {
        Bugly.enterScene(sceneName);
      }
      return { "status": "ok", "code": "bugly_scene_updated" };
    } catch (error) {
      return {
        "status": "error",
        "code": "bugly_scene_failed",
        "message": SlockOhosBuglyDiagnostics.safeString(error),
      };
    }
  }

  static uploadDiagnostics(context: common.UIAbilityContext): Record<string, Object> {
    if (!SlockOhosBuglyDiagnostics.initialized) {
      return SlockOhosBuglyDiagnostics.disabledResult('bugly_diagnostics_not_initialized');
    }

    try {
      TDLog.i(TAG, 'manual diagnostics upload requested');
      TDLog.flushLog();
      const diagnosticFilePath = SlockOhosBuglyDiagnostics.writeDiagnosticSnapshot(context);
      return SlockOhosBuglyDiagnostics.requestBuglyUpload(context, diagnosticFilePath);
    } catch (error) {
      const message = SlockOhosBuglyDiagnostics.safeString(error);
      hilog.error(0x0000, TAG, 'Failed to upload Bugly diagnostics: %{public}s', message);
      SlockOhosDiagnostics.record(TAG, `bugly_log_upload_failed ${message}`);
      return { "status": "error", "code": "bugly_upload_failed", "message": message };
    }
  }

  static async prepareDiagnosticsFeedbackDraft(context: common.UIAbilityContext): Promise<Record<string, Object>> {
    try {
      if (SlockOhosBuglyDiagnostics.initialized) {
        TDLog.i(TAG, 'manual diagnostics feedback export requested');
        TDLog.flushLog();
      }
      const diagnosticFilePath = SlockOhosBuglyDiagnostics.writeDiagnosticSnapshot(context);
      const archivePath = await SlockOhosBuglyDiagnostics.writeFeedbackArchive(context, diagnosticFilePath);
      const archiveBytes = SlockOhosBuglyDiagnostics.readFileBytes(archivePath);
      const bodyBase64 = new util.Base64Helper().encodeToStringSync(archiveBytes);
      const buglyUpload = SlockOhosBuglyDiagnostics.requestBuglyUpload(context, diagnosticFilePath, archivePath);
      const fileName = archivePath.split('/').pop() ?? `${FEEDBACK_FILE_PREFIX}${Date.now()}.zip`;
      SlockOhosDiagnostics.record(TAG, `bugly_feedback_archive_prepared file=${fileName} bytes=${archiveBytes.byteLength}`);
      return {
        "status": "ok",
        "message": SlockOhosBuglyDiagnostics.feedbackMessage(fileName),
        "fileName": fileName,
        "mimeType": FEEDBACK_ARCHIVE_MIME_TYPE,
        "sizeBytes": archiveBytes.byteLength,
        "bodyBase64": bodyBase64,
        "buglyUploadStatus": buglyUpload["status"] ?? "unknown",
        "buglyQueryKey": buglyUpload["queryKey"] ?? "",
      };
    } catch (error) {
      const message = SlockOhosBuglyDiagnostics.safeString(error);
      hilog.error(0x0000, TAG, 'Failed to prepare Bugly feedback archive: %{public}s', message);
      SlockOhosDiagnostics.record(TAG, `bugly_feedback_archive_failed ${message}`);
      return { "status": "error", "code": "bugly_feedback_archive_failed", "message": message };
    }
  }

  static exportDiagnosticsText(context: common.UIAbilityContext): string {
    const bundleInfo = bundleManager.getBundleInfoForSelfSync(bundleManager.BundleFlag.GET_BUNDLE_INFO_DEFAULT);
    const diagnostics = SlockOhosDiagnostics.exportText(1000);
    const clippedDiagnostics =
      diagnostics.length > MAX_DIAGNOSTIC_EXPORT_CHARS
        ? diagnostics.slice(diagnostics.length - MAX_DIAGNOSTIC_EXPORT_CHARS)
        : diagnostics;
    return [
      'Slock OHOS diagnostics',
      `Exported at: ${new Date().toString()}`,
      `Bundle: ${bundleInfo.name}`,
      `Version name: ${bundleInfo.versionName}`,
      `Version code: ${bundleInfo.versionCode}`,
      `Device: ${deviceInfo.manufacture} ${deviceInfo.productModel}`.trim(),
      `Device type: ${deviceInfo.deviceType}`,
      `OS: ${deviceInfo.osFullName} / ${deviceInfo.displayVersion} / API ${deviceInfo.sdkApiVersion}`,
      `Files dir: ${context.filesDir}`,
      '',
      clippedDiagnostics,
    ].join('\n');
  }

  private static writeDiagnosticSnapshot(context: common.UIAbilityContext): string {
    const uploadDir = `${context.filesDir}/${DIAGNOSTIC_UPLOAD_DIR_NAME}`;
    SlockOhosBuglyDiagnostics.ensureDirectory(uploadDir);
    const path = `${uploadDir}/${FEEDBACK_SUMMARY_NAME}`;
    const file = fs.openSync(path, fs.OpenMode.CREATE | fs.OpenMode.TRUNC | fs.OpenMode.WRITE_ONLY);
    try {
      fs.writeSync(file.fd, SlockOhosBuglyDiagnostics.exportDiagnosticsText(context));
    } finally {
      fs.closeSync(file);
    }
    return path;
  }

  private static async writeFeedbackArchive(context: common.UIAbilityContext, diagnosticFilePath: string): Promise<string> {
    const uploadDir = `${context.filesDir}/${DIAGNOSTIC_UPLOAD_DIR_NAME}`;
    SlockOhosBuglyDiagnostics.ensureDirectory(uploadDir);
    SlockOhosBuglyDiagnostics.pruneFeedbackArchives(uploadDir);
    const archivePath = `${uploadDir}/${FEEDBACK_FILE_PREFIX}${Date.now()}.zip`;
    const inputFiles = SlockOhosBuglyDiagnostics.feedbackArchiveFiles(context, diagnosticFilePath);
    if (inputFiles.length === 0) {
      throw new Error('No diagnostics files available for feedback archive');
    }
    await zlib.compressFiles(inputFiles, archivePath, {
      level: zlib.CompressLevel.COMPRESS_LEVEL_DEFAULT_COMPRESSION,
    });
    return archivePath;
  }

  private static requestBuglyUpload(
    context: common.UIAbilityContext,
    diagnosticFilePath: string,
    feedbackArchivePath: string = ''
  ): Record<string, Object> {
    if (!SlockOhosBuglyDiagnostics.initialized) {
      return SlockOhosBuglyDiagnostics.disabledResult('bugly_diagnostics_not_initialized');
    }
    const files = SlockOhosBuglyDiagnostics.uploadPaths(context, diagnosticFilePath, feedbackArchivePath);
    if (files.length === 0) {
      return { "status": "error", "code": "bugly_no_log_files" };
    }

    const bundleInfo = bundleManager.getBundleInfoForSelfSync(bundleManager.BundleFlag.GET_BUNDLE_INFO_DEFAULT);
    const params = new TDDiagUploadParams();
    params.files = files;
    params.tag = 'slock_diagnostics';
    params.summary = `Slock diagnostics ${bundleInfo.versionName}`;
    params.extraInfo = SlockOhosBuglyDiagnostics.uploadExtraInfo(context);
    params.queryKey = `slock-${Date.now()}`;
    TDDiag.uploadLogFiles(params);
    SlockOhosDiagnostics.record(TAG, `bugly_log_upload_requested files=${files.length} key=${params.queryKey}`);
    return { "status": "ok", "code": "bugly_upload_requested", "queryKey": params.queryKey };
  }

  private static uploadPaths(
    context: common.UIAbilityContext,
    diagnosticFilePath: string,
    feedbackArchivePath: string = ''
  ): Array<string> {
    return [
      SlockOhosBuglyDiagnostics.tdLogDir(context),
      diagnosticFilePath,
      feedbackArchivePath,
      `${context.filesDir}/${CRASH_DIR_NAME}`,
    ].filter((path: string) => SlockOhosBuglyDiagnostics.pathExists(path));
  }

  private static feedbackArchiveFiles(context: common.UIAbilityContext, diagnosticFilePath: string): Array<string> {
    return [
      diagnosticFilePath,
      ...SlockOhosBuglyDiagnostics.collectFiles(SlockOhosBuglyDiagnostics.tdLogDir(context)),
      ...SlockOhosBuglyDiagnostics.collectFiles(`${context.filesDir}/${CRASH_DIR_NAME}`),
    ].filter((path: string, index: number, paths: Array<string>) => {
      return SlockOhosBuglyDiagnostics.pathIsFile(path) && paths.indexOf(path) === index;
    });
  }

  private static tdLogDir(context: common.UIAbilityContext): string {
    return `${context.filesDir}/${BUGLY_LOG_DIR_NAME}`;
  }

  private static uploadExtraInfo(context: common.UIAbilityContext): string {
    return JSON.stringify({
      source: 'settings',
      filesDir: context.filesDir,
      device: `${deviceInfo.manufacture} ${deviceInfo.productModel}`.trim(),
      os: `${deviceInfo.osFullName} / ${deviceInfo.displayVersion} / API ${deviceInfo.sdkApiVersion}`,
    });
  }

  private static feedbackMessage(fileName: string): string {
    return [
      '## Raft feedback',
      '',
      '<!-- Describe what happened here. -->',
      '',
      '## Diagnostics',
      '',
      `Bugly TDLog diagnostics are attached as \`${fileName}\`.`,
    ].join('\n');
  }

  private static readFileBytes(path: string): Uint8Array {
    const stat = fs.statSync(path);
    const size = Number(stat.size);
    const file = fs.openSync(path, fs.OpenMode.READ_ONLY);
    try {
      const buffer = new ArrayBuffer(size);
      const bytesRead = fs.readSync(file.fd, buffer);
      return new Uint8Array(buffer.slice(0, bytesRead));
    } finally {
      fs.closeSync(file);
    }
  }

  private static collectFiles(path: string): Array<string> {
    if (!SlockOhosBuglyDiagnostics.pathExists(path)) {
      return [];
    }
    if (SlockOhosBuglyDiagnostics.pathIsFile(path)) {
      return [path];
    }
    if (!SlockOhosBuglyDiagnostics.pathIsDirectory(path)) {
      return [];
    }
    const result: Array<string> = [];
    try {
      fs.listFileSync(path).forEach((name: string) => {
        result.push(...SlockOhosBuglyDiagnostics.collectFiles(`${path}/${name}`));
      });
    } catch (_error) {
    }
    return result;
  }

  private static pruneFeedbackArchives(uploadDir: string): void {
    try {
      const archives: Array<string> = fs.listFileSync(uploadDir)
        .filter((name: string) => name.startsWith(FEEDBACK_FILE_PREFIX) && name.endsWith('.zip'))
        .map((name: string) => `${uploadDir}/${name}`)
        .sort((left: string, right: string) => {
          return fs.statSync(right).mtime - fs.statSync(left).mtime;
        });
      archives.slice(5).forEach((path: string) => {
        try {
          fs.unlinkSync(path);
        } catch (_error) {
        }
      });
    } catch (_error) {
    }
  }

  private static pathExists(path: string): boolean {
    try {
      fs.statSync(path);
      return true;
    } catch (_error) {
      return false;
    }
  }

  private static pathIsFile(path: string): boolean {
    try {
      return fs.statSync(path).isFile();
    } catch (_error) {
      return false;
    }
  }

  private static ensureDirectory(path: string): void {
    try {
      fs.mkdirSync(path, true);
      return;
    } catch (error) {
      if (SlockOhosBuglyDiagnostics.isFileExistsError(error) && SlockOhosBuglyDiagnostics.pathIsDirectory(path)) {
        SlockOhosDiagnostics.record(TAG, `directory_already_exists ${path}`);
        return;
      }
      throw new Error(SlockOhosBuglyDiagnostics.safeString(error));
    }
  }

  private static pathIsDirectory(path: string): boolean {
    try {
      return fs.statSync(path).isDirectory();
    } catch (_error) {
      return false;
    }
  }

  private static isFileExistsError(error: Object): boolean {
    return (error as BusinessError).code === 13900015;
  }

  private static disabledResult(code: string): Record<string, Object> {
    return {
      "status": "disabled",
      "code": code,
      "reason": SlockOhosBuglyDiagnostics.disabledReason.length > 0
        ? SlockOhosBuglyDiagnostics.disabledReason
        : 'not_initialized',
    };
  }

  private static safeString(value: Object): string {
    try {
      return JSON.stringify(value) ?? String(value);
    } catch (_error) {
      return String(value);
    }
  }
}
