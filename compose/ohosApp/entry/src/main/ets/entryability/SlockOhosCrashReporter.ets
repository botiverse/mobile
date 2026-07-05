import { bundleManager, common, errorManager } from '@kit.AbilityKit';
import deviceInfo from '@ohos.deviceInfo';
import fs from '@ohos.file.fs';
import hilog from '@ohos.hilog';
import { SlockOhosClipboard } from '../kuikly/SlockOhosClipboard';
import { SlockOhosDiagnostics } from '../kuikly/SlockOhosDiagnostics';
import { SlockOhosBuglyDiagnostics } from './SlockOhosBuglyDiagnostics';
import { QuiverCrashUploader } from '@oranix/quiver';

const TAG: string = 'SlockOhosCrashReporter';
const CRASH_LOG_MAX_CHARS: number = 180000;
const RECENT_DIAGNOSTICS_MAX_CHARS: number = 80000;

export class SlockOhosCrashReporter {
  private static installed: boolean = false;

  static install(context: common.UIAbilityContext): void {
    if (SlockOhosCrashReporter.installed) {
      return;
    }
    SlockOhosCrashReporter.installed = true;
    const observer: errorManager.ErrorObserver = {
      onUnhandledException: (errMsg: string): void => {
        SlockOhosCrashReporter.captureCrash(context, 'unhandledException', errMsg);
      },
      onException: (errObject: Error): void => {
        SlockOhosCrashReporter.captureCrash(
          context,
          'exception',
          SlockOhosCrashReporter.errorToText(errObject),
        );
      },
    };
    errorManager.on('error', observer);
    SlockOhosDiagnostics.record(TAG, 'crash_reporter_installed');
    // Store-then-send: upload any crashes captured in previous sessions to
    // Quiver, off the launch critical path.
    setTimeout(() => {
      QuiverCrashUploader.uploadPending(context).catch((error: Error) => {
        hilog.warn(0x0000, TAG, 'quiver crash upload pass failed: %{public}s', String(error));
      });
    }, 3000);
  }

  private static captureCrash(
    context: common.UIAbilityContext,
    reason: string,
    errorText: string,
  ): void {
    const crashLog = SlockOhosCrashReporter.buildCrashLog(context, reason, errorText).slice(0, CRASH_LOG_MAX_CHARS);
    try {
      const crashPath = SlockOhosCrashReporter.writeCrashLog(context, crashLog);
      QuiverCrashUploader.enforceRetention(context);
      const firstLine = errorText.split('\n')[0] ?? '';
      const exceptionClass = firstLine.split(':')[0]?.trim() ?? 'OhosCrash';
      const exceptionMessage = firstLine.slice(exceptionClass.length + 1).trim();
      const frameLine = errorText.split('\n').find((line: string): boolean => line.trim().startsWith('at ')) ?? '';
      QuiverCrashUploader.writeMeta(crashPath, {
        exception_class: exceptionClass.length > 0 ? exceptionClass : 'OhosCrash',
        exception_message: exceptionMessage,
        top_frame: frameLine.trim().replace(/^at\s+/, ''),
        reason: reason,
        crash_at: Date.now(),
      });
    } catch (error) {
      hilog.error(0x0000, TAG, 'Failed to write crash log: %{public}s', SlockOhosCrashReporter.safeString(error));
    }
    SlockOhosBuglyDiagnostics.storeCrashRelatedError(
      context,
      `SlockOhos${reason}`,
      SlockOhosDiagnostics.sanitize(errorText).slice(0, 2000),
      SlockOhosDiagnostics.sanitize(errorText),
      crashLog,
    );
    try {
      SlockOhosClipboard.copyText(crashLog);
      hilog.error(0x0000, TAG, '%{public}s', 'Crash log copied to pasteboard');
    } catch (error) {
      hilog.error(0x0000, TAG, 'Failed to copy crash log: %{public}s', SlockOhosCrashReporter.safeString(error));
    }
  }

  private static buildCrashLog(
    context: common.UIAbilityContext,
    reason: string,
    errorText: string,
  ): string {
    const bundleInfo = bundleManager.getBundleInfoForSelfSync(bundleManager.BundleFlag.GET_BUNDLE_INFO_DEFAULT);
    const diagnostics = SlockOhosDiagnostics.exportText(500);
    const clippedDiagnostics =
      diagnostics.length > RECENT_DIAGNOSTICS_MAX_CHARS
        ? diagnostics.slice(diagnostics.length - RECENT_DIAGNOSTICS_MAX_CHARS)
        : diagnostics;
    const lines: string[] = [
      'Raft OHOS crash log',
      `Crash at: ${new Date().toString()}`,
      `Bundle: ${bundleInfo.name}`,
      `Version name: ${bundleInfo.versionName}`,
      `Version code: ${bundleInfo.versionCode}`,
      `Device: ${deviceInfo.manufacture} ${deviceInfo.productModel}`.trim(),
      `Device type: ${deviceInfo.deviceType}`,
      `OS: ${deviceInfo.osFullName} / ${deviceInfo.displayVersion} / API ${deviceInfo.sdkApiVersion}`,
      `Files dir: ${context.filesDir}`,
      `Reason: ${reason}`,
      '',
      'Error:',
      SlockOhosDiagnostics.sanitize(errorText),
    ];
    if (clippedDiagnostics.length > 0) {
      lines.push('', 'Recent diagnostics:', clippedDiagnostics);
    }
    return lines.join('\n');
  }

  private static writeCrashLog(context: common.UIAbilityContext, crashLog: string): string {
    const crashDir = `${context.filesDir}/crashes`;
    fs.mkdirSync(crashDir, true);
    const timestamp = new Date().toISOString().replace(/[:.]/g, '-');
    const crashPath = `${crashDir}/crash-${timestamp}.txt`;
    const file = fs.openSync(crashPath, fs.OpenMode.CREATE | fs.OpenMode.TRUNC | fs.OpenMode.WRITE_ONLY);
    try {
      fs.writeSync(file.fd, crashLog);
    } finally {
      fs.closeSync(file);
    }
    hilog.error(0x0000, TAG, 'Crash log written to: %{public}s', crashPath);
    return crashPath;
  }

  private static errorToText(error: Error): string {
    const stack = error.stack ?? '';
    const message = error.message ?? '';
    return `${error.name}: ${message}\n${stack}`;
  }

  private static safeString(value: Object): string {
    try {
      return JSON.stringify(value) ?? String(value);
    } catch (error) {
      return String(value);
    }
  }
}
