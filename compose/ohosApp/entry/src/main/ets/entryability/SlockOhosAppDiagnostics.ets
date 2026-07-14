import { bundleManager, common } from '@kit.AbilityKit';
import { BusinessError } from '@kit.BasicServicesKit';
import deviceInfo from '@ohos.deviceInfo';
import fs from '@ohos.file.fs';
import zlib from '@ohos.zlib';
import { SlockOhosDiagnostics } from '../kuikly/SlockOhosDiagnostics';
import { SLOCK_OHOS_GIT_COMMIT } from '../kuikly/generated/SlockOhosBuildMetadata';

/**
 * App diagnostics for HarmonyOS: local log breadcrumbs + a feedback archive
 * builder used by the Hands feedback flow.
 *
 * External crash reporting moved — crash capture is handled by the Hands SDK
 * (HandsCrashReporter). This keeps the public methods the app relies on
 * (record / reportCaughtError / updateScene / exportDiagnosticsText /
 * prepareFeedbackArchivePath), all backed by the local diagnostics logger.
 */
const TAG: string = 'SlockOhosDiagnostics';
const DIAGNOSTIC_UPLOAD_DIR_NAME: string = 'diagnostics_upload';
const CRASH_DIR_NAME: string = 'crashes';
const MAX_DIAGNOSTIC_EXPORT_CHARS: number = 180000;
const FEEDBACK_FILE_PREFIX: string = 'slock-feedback-';
const FEEDBACK_SUMMARY_NAME: string = 'slock-diagnostics-summary.txt';

export class SlockOhosAppDiagnostics {
  static record(tag: string, message: string): void {
    SlockOhosDiagnostics.record(tag, message);
  }

  static reportCaughtError(name: string, message: string, stack: string, extraData: string = ''): Record<string, Object> {
    SlockOhosDiagnostics.recordCrashCaptured();
    SlockOhosDiagnostics.record(TAG, `caught_error ${name}: ${message}\n${stack}${extraData.length > 0 ? `\n${extraData}` : ''}`);
    return { "status": "ok", "code": "recorded" };
  }

  static updateScene(action: string, sceneName: string): Record<string, Object> {
    SlockOhosDiagnostics.record(TAG, `scene ${action}=${sceneName}`);
    return { "status": "ok", "code": "scene_recorded" };
  }

  /** Build the diagnostics feedback archive and return its path (for Hands upload). */
  static async prepareFeedbackArchivePath(context: common.UIAbilityContext): Promise<string> {
    const summaryText = SlockOhosAppDiagnostics.exportDiagnosticsText(context);
    const diagnosticFilePath = SlockOhosAppDiagnostics.writeDiagnosticSnapshot(context, summaryText);
    return SlockOhosAppDiagnostics.writeFeedbackArchive(context, diagnosticFilePath);
  }

  static exportDiagnosticsText(context: common.UIAbilityContext): string {
    const bundleInfo = bundleManager.getBundleInfoForSelfSync(bundleManager.BundleFlag.GET_BUNDLE_INFO_DEFAULT);
    SlockOhosAppDiagnostics.recordDiagnosticsEnvironment(context, bundleInfo);
    const diagnostics = SlockOhosDiagnostics.exportText(1000);
    const clippedDiagnostics =
      diagnostics.length > MAX_DIAGNOSTIC_EXPORT_CHARS
        ? diagnostics.slice(diagnostics.length - MAX_DIAGNOSTIC_EXPORT_CHARS)
        : diagnostics;
    return [
      SlockOhosAppDiagnostics.diagnosticsHeaderText(bundleInfo),
      '',
      clippedDiagnostics,
    ].join('\n');
  }

  static exportEnvironmentText(context: common.UIAbilityContext): string {
    const bundleInfo = bundleManager.getBundleInfoForSelfSync(bundleManager.BundleFlag.GET_BUNDLE_INFO_DEFAULT);
    return SlockOhosAppDiagnostics.environmentLines(bundleInfo).join('\n');
  }

  private static diagnosticsHeaderText(
    bundleInfo: bundleManager.BundleInfo = bundleManager.getBundleInfoForSelfSync(bundleManager.BundleFlag.GET_BUNDLE_INFO_DEFAULT)
  ): string {
    return [
      'Raft diagnostics',
      '',
      'Environment',
      ...SlockOhosAppDiagnostics.environmentLines(bundleInfo),
      '',
      'OHOS diagnostics',
    ].join('\n');
  }

  private static environmentLines(
    bundleInfo: bundleManager.BundleInfo
  ): Array<string> {
    return [
      'App: Raft',
      `Version: ${bundleInfo.versionName}`,
      `Version code: ${bundleInfo.versionCode}`,
      `Build: ${bundleInfo.versionCode}`,
      `Source: ${SlockOhosAppDiagnostics.valueOrDash(SLOCK_OHOS_GIT_COMMIT)}`,
      `Package: ${bundleInfo.name}`,
      `Platform: ${deviceInfo.osFullName} / ${deviceInfo.displayVersion} / API ${deviceInfo.sdkApiVersion}`,
      `SDK: ${deviceInfo.sdkApiVersion}`,
      `Device: ${deviceInfo.manufacture} ${deviceInfo.productModel}`.trim(),
      `Model: ${SlockOhosAppDiagnostics.valueOrDash(deviceInfo.productModel)}`,
      'Install time: --',
      'Screen: --',
      'Density: --',
      'Font scale: --',
      'Session: --',
      'Signed in user: --',
      'Selected server: --',
      'Socket: Shared shell',
    ];
  }

  private static valueOrDash(value: string | undefined): string {
    return value !== undefined && value.length > 0 ? value : '--';
  }

  private static recordDiagnosticsEnvironment(
    context: common.UIAbilityContext,
    bundleInfo: bundleManager.BundleInfo
  ): void {
    SlockOhosDiagnostics.record(
      TAG,
      `environment bundle=${bundleInfo.name} version=${bundleInfo.versionName} code=${bundleInfo.versionCode} ` +
        `device=${`${deviceInfo.manufacture} ${deviceInfo.productModel}`.trim()} ` +
        `model=${deviceInfo.productModel} sdk=${deviceInfo.sdkApiVersion} installTime=-- screen=-- density=-- fontScale=-- ` +
        `deviceType=${deviceInfo.deviceType} ` +
        `os=${deviceInfo.osFullName}/${deviceInfo.displayVersion}/api${deviceInfo.sdkApiVersion} ` +
        `filesDir=${context.filesDir}`
    );
  }

  private static writeDiagnosticSnapshot(context: common.UIAbilityContext, text: string = ''): string {
    const uploadDir = `${context.filesDir}/${DIAGNOSTIC_UPLOAD_DIR_NAME}`;
    SlockOhosAppDiagnostics.ensureDirectory(uploadDir);
    const path = `${uploadDir}/${FEEDBACK_SUMMARY_NAME}`;
    const file = fs.openSync(path, fs.OpenMode.CREATE | fs.OpenMode.TRUNC | fs.OpenMode.WRITE_ONLY);
    try {
      fs.writeSync(file.fd, text.length > 0 ? text : SlockOhosAppDiagnostics.exportDiagnosticsText(context));
    } finally {
      fs.closeSync(file);
    }
    return path;
  }

  private static async writeFeedbackArchive(context: common.UIAbilityContext, diagnosticFilePath: string): Promise<string> {
    const uploadDir = `${context.filesDir}/${DIAGNOSTIC_UPLOAD_DIR_NAME}`;
    SlockOhosAppDiagnostics.ensureDirectory(uploadDir);
    SlockOhosAppDiagnostics.pruneFeedbackArchives(uploadDir);
    const archivePath = `${uploadDir}/${FEEDBACK_FILE_PREFIX}${Date.now()}.zip`;
    const inputFiles = SlockOhosAppDiagnostics.feedbackArchiveFiles(context, diagnosticFilePath);
    if (inputFiles.length === 0) {
      throw new Error('No diagnostics files available for feedback archive');
    }
    await zlib.compressFiles(inputFiles, archivePath, {
      level: zlib.CompressLevel.COMPRESS_LEVEL_DEFAULT_COMPRESSION,
    });
    return archivePath;
  }

  private static feedbackArchiveFiles(context: common.UIAbilityContext, diagnosticFilePath: string): Array<string> {
    return [
      diagnosticFilePath,
      ...SlockOhosAppDiagnostics.collectFiles(`${context.filesDir}/${CRASH_DIR_NAME}`),
    ].filter((path: string, index: number, paths: Array<string>) => {
      return SlockOhosAppDiagnostics.pathIsFile(path) && paths.indexOf(path) === index;
    });
  }

  private static collectFiles(path: string): Array<string> {
    if (!SlockOhosAppDiagnostics.pathExists(path)) {
      return [];
    }
    if (SlockOhosAppDiagnostics.pathIsFile(path)) {
      return [path];
    }
    if (!SlockOhosAppDiagnostics.pathIsDirectory(path)) {
      return [];
    }
    const result: Array<string> = [];
    try {
      fs.listFileSync(path).forEach((name: string) => {
        result.push(...SlockOhosAppDiagnostics.collectFiles(`${path}/${name}`));
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
      if (SlockOhosAppDiagnostics.isFileExistsError(error) && SlockOhosAppDiagnostics.pathIsDirectory(path)) {
        return;
      }
      throw new Error(SlockOhosAppDiagnostics.safeString(error));
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

  private static safeString(value: Object): string {
    try {
      return JSON.stringify(value) ?? String(value);
    } catch (_error) {
      return String(value);
    }
  }
}
