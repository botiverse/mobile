import { bundleManager, common } from '@kit.AbilityKit';
import { BusinessError } from '@kit.BasicServicesKit';
import deviceInfo from '@ohos.deviceInfo';
import fs from '@ohos.file.fs';
import zlib from '@ohos.zlib';
import { SlockOhosDiagnosticEntry, SlockOhosDiagnostics } from '../kuikly/SlockOhosDiagnostics';
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
const FEEDBACK_FILE_PREFIX: string = 'raft-feedback-';
const FEEDBACK_SUMMARY_NAME: string = 'raft-diagnostics-summary.txt';
const DIAGNOSTIC_LOG_DIR_NAME: string = 'raft_diagnostics_logs';
const DIAGNOSTIC_LOG_FILE_NAME: string = 'raft-diagnostics.jsonl';
const DIAGNOSTIC_SESSION_PREFIX: string = 'launch-';
const DIAGNOSTIC_MAX_SESSIONS: number = 5;
const DIAGNOSTIC_MAX_FILES_PER_SESSION: number = 6;
const DIAGNOSTIC_LOG_MAX_BYTES: number = 1024 * 1024;
const HANDS_FEEDBACK_OMITTED_ENVIRONMENT_LABELS: Array<string> = [
  'Install time',
  'Screen',
  'Density',
  'Font scale',
  'Session',
  'Signed in user',
  'Selected server',
  'Socket',
];

export function filterOhosHandsFeedbackEnvironmentLines(lines: Array<string>): Array<string> {
  return lines.filter((line: string): boolean => {
    const separator = line.indexOf(':');
    const label = (separator >= 0 ? line.slice(0, separator) : line).trim();
    return HANDS_FEEDBACK_OMITTED_ENVIRONMENT_LABELS.indexOf(label) < 0;
  });
}

export function isOhosDiagnosticsLogFileName(name: string): boolean {
  return name.startsWith(DIAGNOSTIC_LOG_FILE_NAME);
}

export function isOhosFeedbackArchiveName(name: string): boolean {
  return name.startsWith(FEEDBACK_FILE_PREFIX) && name.endsWith('.zip');
}

export function isOhosSafeDiagnosticsArchiveComponent(name: string): boolean {
  return name.length > 0 && name !== '.' && name !== '..' &&
    !name.includes('/') && !name.includes('\\') && !name.toLowerCase().includes('slock');
}

export function isOhosDiagnosticsDescendantPath(root: string, path: string): boolean {
  return path === root || path.startsWith(`${root}/`);
}

export enum OhosDiagnosticsSourceNodeKind {
  Missing,
  RegularFile,
  Directory,
  Rejected,
}

export function ohosDiagnosticsSourceNodeKind(
  isSymbolicLink: boolean,
  isFile: boolean,
  isDirectory: boolean
): OhosDiagnosticsSourceNodeKind {
  if (isSymbolicLink) {
    return OhosDiagnosticsSourceNodeKind.Rejected;
  }
  if (isFile) {
    return OhosDiagnosticsSourceNodeKind.RegularFile;
  }
  if (isDirectory) {
    return OhosDiagnosticsSourceNodeKind.Directory;
  }
  return OhosDiagnosticsSourceNodeKind.Rejected;
}

export interface OhosDiagnosticsSourceInspector {
  kind(path: string): OhosDiagnosticsSourceNodeKind;
  childNames(path: string): Array<string>;
}

class FileSystemOhosDiagnosticsSourceInspector implements OhosDiagnosticsSourceInspector {
  kind(path: string): OhosDiagnosticsSourceNodeKind {
    try {
      const stat = fs.lstatSync(path);
      return ohosDiagnosticsSourceNodeKind(stat.isSymbolicLink(), stat.isFile(), stat.isDirectory());
    } catch (_error) {
      return OhosDiagnosticsSourceNodeKind.Missing;
    }
  }

  childNames(path: string): Array<string> {
    try {
      return fs.listFileSync(path);
    } catch (_error) {
      return [];
    }
  }
}

export function collectOhosDiagnosticsSourceFiles(
  root: string,
  path: string,
  inspector: OhosDiagnosticsSourceInspector
): Array<string> {
  if (!isOhosDiagnosticsDescendantPath(root, path)) {
    return [];
  }
  const kind = inspector.kind(path);
  if (kind === OhosDiagnosticsSourceNodeKind.RegularFile) {
    return [path];
  }
  if (kind !== OhosDiagnosticsSourceNodeKind.Directory) {
    return [];
  }
  const result: Array<string> = [];
  inspector.childNames(path).forEach((name: string): void => {
    if (!isOhosSafeDiagnosticsArchiveComponent(name)) {
      return;
    }
    result.push(...collectOhosDiagnosticsSourceFiles(root, `${path}/${name}`, inspector));
  });
  return result;
}

export function ohosUniqueDiagnosticsStagedName(baseName: string, reservedNames: Set<string>): string {
  if (!isOhosSafeDiagnosticsArchiveComponent(baseName)) {
    throw new Error('Unsafe diagnostics archive component');
  }
  const dot = baseName.lastIndexOf('.');
  const stem = dot > 0 ? baseName.slice(0, dot) : baseName;
  const extension = dot > 0 ? baseName.slice(dot) : '';
  let suffix = 1;
  while (true) {
    const candidate = suffix === 1 ? baseName : `${stem}-${suffix}${extension}`;
    if (!reservedNames.has(candidate)) {
      reservedNames.add(candidate);
      return candidate;
    }
    suffix += 1;
  }
}

export function compareOhosDiagnosticsPath(
  leftPath: string,
  rightPath: string,
  leftMtime: number,
  rightMtime: number
): number {
  if (rightMtime !== leftMtime) {
    return rightMtime - leftMtime;
  }
  return leftPath < rightPath ? -1 : leftPath > rightPath ? 1 : 0;
}

export interface OhosDiagnosticsCandidateGroup {
  entryPrefix: string;
  files: Array<string>;
}

export function selectOhosDiagnosticsCandidateGroups(
  groups: Array<OhosDiagnosticsCandidateGroup>,
  maxGroups: number
): Array<OhosDiagnosticsCandidateGroup> {
  return groups.filter((group: OhosDiagnosticsCandidateGroup): boolean => group.files.length > 0).slice(0, maxGroups);
}

export class SlockOhosAppDiagnostics {
  private static currentSessionDir: string = '';

  static initialize(context: common.UIAbilityContext): void {
    if (SlockOhosAppDiagnostics.currentSessionDir.length > 0) {
      return;
    }
    const root = `${context.filesDir}/${DIAGNOSTIC_LOG_DIR_NAME}`;
    SlockOhosAppDiagnostics.ensureDirectory(root);
    const sessionId = `${DIAGNOSTIC_SESSION_PREFIX}${Date.now()}-${Math.floor(Math.random() * 0x100000000).toString(16)}`;
    SlockOhosAppDiagnostics.currentSessionDir = `${root}/${sessionId}`;
    SlockOhosAppDiagnostics.ensureDirectory(SlockOhosAppDiagnostics.currentSessionDir);
    SlockOhosAppDiagnostics.pruneDiagnosticSessions(root);
    SlockOhosDiagnostics.setPersistentRecorder((entry: SlockOhosDiagnosticEntry): void => {
      SlockOhosAppDiagnostics.appendPersistentEntry(entry);
    });
  }

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

  static exportHandsFeedbackEnvironmentText(context: common.UIAbilityContext): string {
    const bundleInfo = bundleManager.getBundleInfoForSelfSync(bundleManager.BundleFlag.GET_BUNDLE_INFO_DEFAULT);
    return filterOhosHandsFeedbackEnvironmentLines(
      SlockOhosAppDiagnostics.environmentLines(bundleInfo)
    ).join('\n');
  }

  static clearLocalFiles(context: common.UIAbilityContext): void {
    SlockOhosDiagnostics.clear();
    [
      `${context.filesDir}/${DIAGNOSTIC_LOG_DIR_NAME}`,
      `${context.filesDir}/${DIAGNOSTIC_UPLOAD_DIR_NAME}`,
    ].forEach((path: string): void => {
      SlockOhosAppDiagnostics.removeDirectoryRecursively(path);
    });
    SlockOhosAppDiagnostics.currentSessionDir = '';
    SlockOhosAppDiagnostics.initialize(context);
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
    const stagedLogFiles = SlockOhosAppDiagnostics.stageRecentLaunchLogs(context, uploadDir);
    const inputFiles = SlockOhosAppDiagnostics.feedbackArchiveFiles(context, diagnosticFilePath, stagedLogFiles);
    if (inputFiles.length === 0) {
      throw new Error('No diagnostics files available for feedback archive');
    }
    try {
      await zlib.compressFiles(inputFiles, archivePath, {
        level: zlib.CompressLevel.COMPRESS_LEVEL_DEFAULT_COMPRESSION,
      });
    } catch (error) {
      // Compression can leave a partial archive behind. Remove it before propagating the
      // generation failure so cancelled/retried uploads do not accumulate unbounded temp files.
      try {
        if (SlockOhosAppDiagnostics.pathExists(archivePath)) fs.unlinkSync(archivePath);
      } catch (_cleanupError) {
      }
      throw error;
    } finally {
      stagedLogFiles.forEach((path: string): void => {
        try {
          fs.unlinkSync(path);
        } catch (_error) {
        }
      });
    }
    return archivePath;
  }

  private static feedbackArchiveFiles(
    context: common.UIAbilityContext,
    diagnosticFilePath: string,
    stagedLogFiles: Array<string>
  ): Array<string> {
    return [
      diagnosticFilePath,
      ...stagedLogFiles,
      ...SlockOhosAppDiagnostics.collectFiles(`${context.filesDir}/${CRASH_DIR_NAME}`),
    ].filter((path: string, index: number, paths: Array<string>) => {
      return SlockOhosAppDiagnostics.pathIsFile(path) && paths.indexOf(path) === index;
    });
  }

  private static appendPersistentEntry(entry: SlockOhosDiagnosticEntry): void {
    if (SlockOhosAppDiagnostics.currentSessionDir.length === 0) {
      return;
    }
    const activePath = `${SlockOhosAppDiagnostics.currentSessionDir}/${DIAGNOSTIC_LOG_FILE_NAME}`;
    try {
      if (SlockOhosAppDiagnostics.pathExists(activePath) && fs.statSync(activePath).size >= DIAGNOSTIC_LOG_MAX_BYTES) {
        SlockOhosAppDiagnostics.rotatePersistentLogs();
      }
      const file = fs.openSync(
        activePath,
        fs.OpenMode.CREATE | fs.OpenMode.APPEND | fs.OpenMode.WRITE_ONLY
      );
      try {
        fs.writeSync(file.fd, `${JSON.stringify(entry)}\n`);
      } finally {
        fs.closeSync(file);
      }
    } catch (_error) {
    }
  }

  private static rotatePersistentLogs(): void {
    const dir = SlockOhosAppDiagnostics.currentSessionDir;
    for (let index = DIAGNOSTIC_MAX_FILES_PER_SESSION - 1; index >= 1; index -= 1) {
      const source = `${dir}/${DIAGNOSTIC_LOG_FILE_NAME}.${index}`;
      if (!SlockOhosAppDiagnostics.pathExists(source)) {
        continue;
      }
      if (index === DIAGNOSTIC_MAX_FILES_PER_SESSION - 1) {
        try {
          fs.unlinkSync(source);
        } catch (_error) {
        }
      } else {
        const target = `${dir}/${DIAGNOSTIC_LOG_FILE_NAME}.${index + 1}`;
        try {
          if (SlockOhosAppDiagnostics.pathExists(target)) {
            fs.unlinkSync(target);
          }
          fs.renameSync(source, target);
        } catch (_error) {
        }
      }
    }
    const active = `${dir}/${DIAGNOSTIC_LOG_FILE_NAME}`;
    const rotated = `${dir}/${DIAGNOSTIC_LOG_FILE_NAME}.1`;
    try {
      if (SlockOhosAppDiagnostics.pathExists(rotated)) {
        fs.unlinkSync(rotated);
      }
      if (SlockOhosAppDiagnostics.pathExists(active)) {
        fs.renameSync(active, rotated);
      }
    } catch (_error) {
    }
  }

  private static diagnosticSessionDirectories(root: string): Array<string> {
    if (!SlockOhosAppDiagnostics.pathIsAdmittedDirectory(root, root)) {
      return [];
    }
    try {
      return fs.listFileSync(root)
        .filter((name: string): boolean =>
          name.startsWith(DIAGNOSTIC_SESSION_PREFIX) && isOhosSafeDiagnosticsArchiveComponent(name)
        )
        .map((name: string): string => `${root}/${name}`)
        .filter((path: string): boolean => SlockOhosAppDiagnostics.pathIsAdmittedDirectory(root, path))
        .sort((left: string, right: string): number => right.localeCompare(left));
    } catch (_error) {
      return [];
    }
  }

  private static pruneDiagnosticSessions(root: string): void {
    SlockOhosAppDiagnostics.diagnosticSessionDirectories(root)
      .slice(DIAGNOSTIC_MAX_SESSIONS)
      .forEach((path: string): void => {
        SlockOhosAppDiagnostics.removeDirectoryRecursively(path);
      });
  }

  private static stageRecentLaunchLogs(context: common.UIAbilityContext, uploadDir: string): Array<string> {
    const staged: Array<string> = [];
    const reservedTargetNames = new Set<string>();
    const candidateGroups: Array<OhosDiagnosticsCandidateGroup> = [];
    const root = `${context.filesDir}/${DIAGNOSTIC_LOG_DIR_NAME}`;
    SlockOhosAppDiagnostics.diagnosticSessionDirectories(root).forEach((sessionDir: string): void => {
      const sessionName = sessionDir.slice(sessionDir.lastIndexOf('/') + 1);
      const files = SlockOhosAppDiagnostics.collectFiles(sessionDir, root)
        .filter((path: string): boolean => isOhosDiagnosticsLogFileName(
          path.slice(path.lastIndexOf('/') + 1)
        ))
        .sort((left: string, right: string): number => compareOhosDiagnosticsPath(
          left,
          right,
          fs.statSync(left).mtime,
          fs.statSync(right).mtime
        ))
        .slice(0, DIAGNOSTIC_MAX_FILES_PER_SESSION);
      candidateGroups.push({
        entryPrefix: `${sessionName}-`,
        files,
      });
    });
    if (SlockOhosAppDiagnostics.pathIsAdmittedDirectory(root, root)) {
      try {
        const flatFiles = fs.listFileSync(root)
          .map((name: string): string => `${root}/${name}`)
          .filter((path: string): boolean => {
            const name = path.slice(path.lastIndexOf('/') + 1);
            return SlockOhosAppDiagnostics.pathIsAdmittedFile(root, path) &&
              isOhosDiagnosticsLogFileName(name) && isOhosSafeDiagnosticsArchiveComponent(name);
          })
          .sort((left: string, right: string): number => compareOhosDiagnosticsPath(
            left,
            right,
            fs.statSync(left).mtime,
            fs.statSync(right).mtime
          ))
          .slice(0, DIAGNOSTIC_MAX_FILES_PER_SESSION);
        candidateGroups.push({
          entryPrefix: 'flat-',
          files: flatFiles,
        });
      } catch (_error) {
      }
    }
    selectOhosDiagnosticsCandidateGroups(candidateGroups, DIAGNOSTIC_MAX_SESSIONS)
      .forEach((group: OhosDiagnosticsCandidateGroup): void => {
        group.files.forEach((sourcePath: string): void => {
          const sourceName = sourcePath.slice(sourcePath.lastIndexOf('/') + 1);
          if (!isOhosSafeDiagnosticsArchiveComponent(sourceName)) {
            return;
          }
          const targetName = ohosUniqueDiagnosticsStagedName(
            `${group.entryPrefix}${sourceName}`,
            reservedTargetNames
          );
          const targetPath = `${uploadDir}/${targetName}`;
          try {
            fs.copyFileSync(sourcePath, targetPath);
            staged.push(targetPath);
          } catch (_error) {
            try {
              if (SlockOhosAppDiagnostics.pathExists(targetPath)) {
                fs.unlinkSync(targetPath);
              }
            } catch (_cleanupError) {
            }
          }
        });
      });
    return staged;
  }

  private static removeDirectoryRecursively(path: string): void {
    if (!SlockOhosAppDiagnostics.pathExists(path)) {
      return;
    }
    if (SlockOhosAppDiagnostics.pathIsFile(path)) {
      try {
        fs.unlinkSync(path);
      } catch (_error) {
      }
      return;
    }
    try {
      fs.listFileSync(path).forEach((name: string): void => {
        SlockOhosAppDiagnostics.removeDirectoryRecursively(`${path}/${name}`);
      });
    } catch (_error) {
    }
    try {
      fs.rmdirSync(path);
    } catch (_error) {
    }
  }

  private static collectFiles(path: string, root: string = path): Array<string> {
    return collectOhosDiagnosticsSourceFiles(root, path, new FileSystemOhosDiagnosticsSourceInspector());
  }

  private static pruneFeedbackArchives(uploadDir: string): void {
    try {
      const archives: Array<string> = fs.listFileSync(uploadDir)
        .filter((name: string) => isOhosFeedbackArchiveName(name))
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
    return SlockOhosAppDiagnostics.sourceNodeKind(path) === OhosDiagnosticsSourceNodeKind.RegularFile;
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
    return SlockOhosAppDiagnostics.sourceNodeKind(path) === OhosDiagnosticsSourceNodeKind.Directory;
  }

  private static sourceNodeKind(path: string): OhosDiagnosticsSourceNodeKind {
    return new FileSystemOhosDiagnosticsSourceInspector().kind(path);
  }

  private static pathIsAdmittedFile(root: string, path: string): boolean {
    return isOhosDiagnosticsDescendantPath(root, path) && SlockOhosAppDiagnostics.pathIsFile(path);
  }

  private static pathIsAdmittedDirectory(root: string, path: string): boolean {
    return isOhosDiagnosticsDescendantPath(root, path) && SlockOhosAppDiagnostics.pathIsDirectory(path);
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
