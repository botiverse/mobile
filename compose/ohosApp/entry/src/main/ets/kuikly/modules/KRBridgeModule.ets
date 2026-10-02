import { bundleManager, common } from '@kit.AbilityKit';
import { BusinessError } from '@kit.BasicServicesKit';
import { camera, cameraPicker } from '@kit.CameraKit';
import { picker, fileUri } from '@kit.CoreFileKit';
import { photoAccessHelper } from '@kit.MediaLibraryKit';
import { image } from '@kit.ImageKit';
import { notificationManager } from '@kit.NotificationKit';
import { promptAction } from '@kit.ArkUI';
import { KRAny } from '@kuikly-open/render';
import { KuiklyRenderBaseModule } from '@kuikly-open/render';
import { KuiklyRenderCallback } from '@kuikly-open/render';
import router from '@ohos.router';
import util from '@ohos.util';
import fs from '@ohos.file.fs';
import deviceInfo from '@ohos.deviceInfo';
import { SlockOhosAppDiagnostics } from '../../entryability/SlockOhosAppDiagnostics';
import {
  isSlockOhosThreadComposerTemporaryFileRefAuthorityAllowed,
  SlockOhosThreadComposerTemporaryFileRetirementProvider,
} from '../../entryability/SlockOhosThreadComposerTemporaryFileRetirement';
import { HandsFeedbackClient } from '@botiverse/hands';
import slockOhosMotionBridge from '../SlockOhosMotionBridge';
import slockOhosRealtimeBridge from '../SlockOhosRealtimeBridge';
import { SlockOhosClipboard } from '../SlockOhosClipboard';
import { SlockOhosDiagnostics } from '../SlockOhosDiagnostics';
import {
  cacheSlockOhosPickedAttachment,
  ensureSlockOhosAttachmentCacheDirectory,
  normalizeSlockOhosImagePickerFileName,
} from '../SlockOhosPickedAttachmentCache';
import { slockOhosHandsFeedbackAttachmentPaths } from '../SlockOhosHandsFeedbackAttachments';
import {
  discardSlockOhosFeedbackDraftAttachments,
  promoteSlockOhosFeedbackDraftAttachments,
  SlockOhosFeedbackDraftAttachmentSource,
} from '../SlockOhosHandsFeedbackDraftAttachments';
import { slockOhosAttachmentDownloadSink } from '../SlockOhosAttachmentDownloadSink';
import { SlockOhosApiEnvironment, SlockOhosApiEnvironmentStore } from '../SlockOhosApiEnvironmentStore';
import {
  buildSlockOhosAttachmentOpenWants,
  slockOhosAttachmentOpenDiagnostic,
  slockOhosAttachmentOpenFailureKind,
  slockOhosAttachmentOpenPreflightDecision,
  SlockOhosAttachmentOpenDiagnosticTerminal,
  SlockOhosAttachmentOpenFailureKind,
  SlockOhosAttachmentOpenPreflightDecision,
  SlockOhosAttachmentOpenPreflightTrace,
  SlockOhosAttachmentOpenWants,
} from '../SlockOhosAttachmentOpenWant';
import {
  cleanupSlockOhosPrivateAttachmentOpenAliases,
  cleanupSlockOhosPrivateAttachmentOrphanParts,
  copySlockOhosPrivateAttachmentToProviderUri,
  ensureSlockOhosPrivateAttachmentDirectory,
  isSlockOhosPrivateAttachmentOwnerKey,
  materializeSlockOhosPrivateAttachmentOpenAlias,
  slockOhosPrivateAttachmentOpenAliasDirectoryName,
  slockOhosPrivateAttachmentOpenAliasFileName,
  slockOhosPrivateAttachmentFileName,
} from '../SlockOhosPrivateAttachmentStore';
import { SLOCK_OHOS_BUILD_TIME, SLOCK_OHOS_GIT_COMMIT } from '../generated/SlockOhosBuildMetadata';
import { prepareSlockOhosAppLogin } from '../SlockOhosAppLogin';
import { prepareSlockOhosMobileOAuth } from '../SlockOhosMobileOAuth';

interface OhosPickedThreadAttachment {
  fileName: string;
  mimeType: string;
  previewUri: string;
  fileRef: string;
  sizeBytes: number;
}

interface OhosPickedThreadAttachmentPayload {
  id: string;
  fileName: string;
  mimeType: string;
  sizeBytes: number;
  previewUri: string;
  fileRef: string;
  source: string;
}

interface OhosPickedThreadAttachmentReadResult {
  files: Array<OhosPickedThreadAttachment>;
  errors: Array<Record<string, Object>>;
}

interface SlockOhosCachedPickedAttachmentLike {
  fileName: string;
  mimeType: string;
  previewUri: string;
  fileRef: string;
  sizeBytes: number;
}

const SLOCK_THREAD_ATTACHMENT_IMAGE_MAX_EDGE = 2048;
const SLOCK_THREAD_ATTACHMENT_IMAGE_JPEG_QUALITY = 82;
const SLOCK_THREAD_ATTACHMENT_IMAGE_COMPRESSION_ERROR = 'attachment_image_compression_failed';

class SlockThreadAttachmentImageCompressionError extends Error {
  constructor(message: string) {
    super(message);
    this.name = SLOCK_THREAD_ATTACHMENT_IMAGE_COMPRESSION_ERROR;
  }
}

class SlockThreadImageCompressionCancelledError extends Error {
  constructor() {
    super('cancelled');
    this.name = 'thread_image_compression_cancelled';
  }
}

const SLOCK_THREAD_IMAGE_COMPRESSION_CACHE_DIR_NAME = 'slock-picked-thread-attachments';
const SLOCK_THREAD_IMAGE_COMPRESSION_SOURCE = 'composer_image_compressor';
const SLOCK_THREAD_IMAGE_COMPRESSION_MAX_REQUEST_ID_LENGTH = 128;
const SLOCK_THREAD_IMAGE_COMPRESSION_MAX_FILE_NAME_LENGTH = 256;

enum SlockThreadImageCompressionOperationState {
  ACTIVE = 0,
  PENDING = 1,
  DELIVERED = 2,
  CANCELLED = 3,
}

class SlockThreadImageCompressionOperation {
  readonly requestId: string;
  readonly callback: KuiklyRenderCallback;
  readonly outputPath: string;
  readonly partPath: string;
  state: SlockThreadImageCompressionOperationState = SlockThreadImageCompressionOperationState.ACTIVE;

  constructor(requestId: string, callback: KuiklyRenderCallback, outputPath: string) {
    this.requestId = requestId;
    this.callback = callback;
    this.outputPath = outputPath;
    this.partPath = `${outputPath}.part`;
  }

  isCancelled(): boolean {
    return this.state === SlockThreadImageCompressionOperationState.CANCELLED;
  }

  markPending(): boolean {
    if (this.state !== SlockThreadImageCompressionOperationState.ACTIVE) return false;
    this.state = SlockThreadImageCompressionOperationState.PENDING;
    return true;
  }

  markDelivered(): boolean {
    if (this.state !== SlockThreadImageCompressionOperationState.PENDING) return false;
    this.state = SlockThreadImageCompressionOperationState.DELIVERED;
    return true;
  }

  cancel(force: boolean = false): boolean {
    if (this.state === SlockThreadImageCompressionOperationState.CANCELLED ||
      (this.state === SlockThreadImageCompressionOperationState.DELIVERED && !force)) {
      return false;
    }
    this.state = SlockThreadImageCompressionOperationState.CANCELLED;
    return true;
  }

  async removeOutputs(): Promise<void> {
    try { await fs.unlink(this.partPath); } catch (_) {
    }
    try { await fs.unlink(this.outputPath); } catch (_) {
    }
  }
}

interface OhosDiagnosticEntryPayload {
  timestampLabel: string;
  tag: string;
  message: string;
}

interface OhosStorageRowPayload {
  target: string;
  title: string;
  detail: string;
  sizeLabel: string;
  clearEnabled: boolean;
}

interface OhosAttachmentCacheEntry {
  path: string;
  mtime: number;
}

export class KRBridgeModule extends KuiklyRenderBaseModule {
  static readonly MODULE_NAME = "HRBridgeModule";
  static readonly SHOW_ALERT = "showAlert";
  static readonly CLOSE_PAGE = "closePage";
  static readonly OPEN_PAGE = "openPage";
  static readonly COPY_TO_PASTE_BOARD = "copyToPasteboard";
  static readonly TOAST = "toast";
  static readonly OPEN_URL = "openUrl";
  static readonly OPEN_ATTACHMENT = "openAttachment";
  static readonly SAVE_ATTACHMENT = "saveAttachment";
  static readonly ATTACHMENT_CACHE_STATUS = "attachmentCacheStatus";
  static readonly DELETE_ATTACHMENT_CACHE = "deleteAttachmentCache";
  static readonly RETIRE_FILE_PREVIEW_TEMPORARY_FILES = "retireFilePreviewTemporaryFiles";
  static readonly EXPORT_DOWNLOADED_ATTACHMENT = "exportDownloadedAttachment";
  static readonly SAVE_IMAGE_TO_PHOTOS = "saveImageToPhotos";
  static readonly CANCEL_ATTACHMENT_EXPORT = "cancelAttachmentExport";
  static readonly ATTACHMENT_DOWNLOAD_CONTROL = "attachmentDownloadControl";
  static readonly ATTACHMENT_DOWNLOAD_OWNER_CHANGED = "attachmentDownloadOwnerChanged";
  static readonly LOG = "log";
  static readonly PERFORMANCE_MARKER = "performanceMarker";
  static readonly PROBE_THREAD_VIEWPORT_PIXELS = "probeThreadViewportPixels";
  static readonly CONVERSATION_FORWARD_GESTURE = "conversationForwardGesture";
  static readonly PERFORMANCE_SCENE = "performanceScene";
  static readonly KUIKLY_PERFORMANCE_SNAPSHOT = "kuiklyPerformanceSnapshot";
  static readonly REPORT_DT = "reportDT";
  static readonly REPORT_REAL_TIME = "reportRealTime";
  static readonly REPORT_REAL_TIME_LEGACY = "reportRealtime";
  static readonly LOCAL_SERVE_TIME = "localServeTime";
  static readonly CURRENT_TIME_STAMP = "currentTimestamp";
  static readonly DATE_FORMATTER = "dateFormatter";
  static readonly READ_ASSET_FILE = "readAssetFile";
  static readonly MOTION_CAPABILITY = "motionCapability";
  static readonly SETTINGS_CAPABILITY = "settingsCapability";
  static readonly THREAD_ATTACHMENT_UPLOAD = "threadAttachmentUpload";
  static readonly COMPRESS_THREAD_IMAGE = "compressThreadImage";
  static readonly CANCEL_COMPRESS_THREAD_IMAGE = "cancelCompressThreadImage";
  static readonly APP_BADGE = "appBadge";
  static readonly ATTACHMENT_CACHE_MAX_FILES = 80;
  static readonly ATTACHMENT_CACHE_MAX_AGE_MS = 24 * 60 * 60 * 1000;
  static readonly HANDS_FEEDBACK_MAX_ATTACHMENTS = 3;
  static readonly HANDS_FEEDBACK_MAX_ATTACHMENT_BYTES = 10 * 1024 * 1024;
  private activePhotoExportDownloadId: string = "";
  private activePhotoExportOperationId: string = "";
  private activePhotoExportGeneration: number = 0;
  private nextPhotoExportGeneration: number = 0;
  private activePrivateDownloadId: string = "";
  private privateExportActive: boolean = false;
  private threadImageCompressionOperations: Map<string, SlockThreadImageCompressionOperation> = new Map();
  private threadImageCompressionDestroyed: boolean = false;

  syncMode(): boolean {
    return false;
  }

  call(method: string, params: KRAny, callback: KuiklyRenderCallback): KRAny {
    switch (method) {
      case KRBridgeModule.SHOW_ALERT: {
        this.showAlert(params, callback);
        break;
      }
      case KRBridgeModule.CLOSE_PAGE: {
        this.closePage();
        break;
      }
      case KRBridgeModule.OPEN_PAGE: {
        this.openPage();
        break;
      }
      case KRBridgeModule.COPY_TO_PASTE_BOARD: {
        this.copyToPasteboard(params);
        break;
      }
      case KRBridgeModule.TOAST: {
        this.toast(params);
        break;
      }
      case KRBridgeModule.OPEN_URL: {
        this.openUrl(params);
        break;
      }
      case KRBridgeModule.OPEN_ATTACHMENT: {
        this.openAttachment(params, callback);
        break;
      }
      case KRBridgeModule.SAVE_ATTACHMENT: {
        this.saveAttachment(params, callback);
        break;
      }
      case KRBridgeModule.ATTACHMENT_CACHE_STATUS: {
        this.attachmentCacheStatus(params, callback);
        break;
      }
      case KRBridgeModule.DELETE_ATTACHMENT_CACHE: {
        this.deleteAttachmentCache(params, callback);
        break;
      }
      case KRBridgeModule.RETIRE_FILE_PREVIEW_TEMPORARY_FILES: {
        return this.retireFilePreviewTemporaryFiles(params);
      }
      case KRBridgeModule.EXPORT_DOWNLOADED_ATTACHMENT: {
        this.exportDownloadedAttachment(params, callback);
        break;
      }
      case KRBridgeModule.SAVE_IMAGE_TO_PHOTOS: {
        this.saveImageToPhotos(params, callback);
        break;
      }
      case KRBridgeModule.CANCEL_ATTACHMENT_EXPORT: {
        this.cancelAttachmentExport(params);
        break;
      }
      case KRBridgeModule.ATTACHMENT_DOWNLOAD_CONTROL: {
        this.attachmentDownloadControl(params, callback);
        break;
      }
      case KRBridgeModule.ATTACHMENT_DOWNLOAD_OWNER_CHANGED: {
        this.attachmentDownloadOwnerChanged();
        break;
      }
      case KRBridgeModule.LOG: {
        this.log(params);
        break;
      }
      case KRBridgeModule.PERFORMANCE_MARKER: {
        this.performanceMarker(params);
        break;
      }
      case KRBridgeModule.PROBE_THREAD_VIEWPORT_PIXELS: {
        const records = this.parseRecord(params);
        callback({
          "status": "unsupported",
          "generation": records["generation"] ?? 0
        });
        break;
      }
      case KRBridgeModule.CONVERSATION_FORWARD_GESTURE: {
        // iOS-only native gesture admission. Shared history remains inert on OHOS.
        break;
      }
      case KRBridgeModule.PERFORMANCE_SCENE: {
        this.performanceScene(params);
        break;
      }
      case KRBridgeModule.KUIKLY_PERFORMANCE_SNAPSHOT: {
        this.kuiklyPerformanceSnapshot(params);
        break;
      }
      case KRBridgeModule.REPORT_DT: {
        this.reportDT(params);
        break;
      }
      case KRBridgeModule.REPORT_REAL_TIME:
      case KRBridgeModule.REPORT_REAL_TIME_LEGACY: {
        this.reportRealtime(params);
        break;
      }
      case KRBridgeModule.LOCAL_SERVE_TIME: {
        this.localServeTime(params, callback);
        break;
      }
      case KRBridgeModule.CURRENT_TIME_STAMP: {
        return this.currentTimestamp();
      }
      case KRBridgeModule.DATE_FORMATTER: {
        return this.dateFormatter(params);
      }
      case KRBridgeModule.READ_ASSET_FILE: {
        this.readAssetFile(params, callback);
        break;
      }
      case KRBridgeModule.MOTION_CAPABILITY: {
        this.motionCapability(params, callback);
        break;
      }
      case KRBridgeModule.SETTINGS_CAPABILITY: {
        this.settingsCapability(params, callback);
        break;
      }
      case KRBridgeModule.THREAD_ATTACHMENT_UPLOAD: {
        this.threadAttachmentUpload(params, callback);
        break;
      }
      case KRBridgeModule.COMPRESS_THREAD_IMAGE: {
        this.compressThreadImage(params, callback).catch((): void => {
          // The async method owns its terminal response.  An unexpected setup/release
          // rejection is deliberately swallowed here so the dispatcher cannot race a failure
          // callback with the worker's already-delivered success/failure response.
        });
        break;
      }
      case KRBridgeModule.CANCEL_COMPRESS_THREAD_IMAGE: {
        this.cancelCompressThreadImage(params);
        break;
      }
      case KRBridgeModule.APP_BADGE: {
        this.appBadge(params);
        break;
      }
      default: {
        SlockOhosDiagnostics.record(
          "HRBridgeModule",
          `unsupported bridge method dropped: ${method}`,
          Date.now(),
          "warn"
        );
        if (callback !== undefined && callback !== null) {
          callback({
            "code": -1,
            "message": "Method not implemented"
          });
        }
        break;
      }
    }
    return null;
  }

  onDestroy(): void {
    this.threadImageCompressionDestroyed = true;
    this.threadImageCompressionOperations.forEach((operation: SlockThreadImageCompressionOperation): void => {
      // Destruction is stronger than an ordinary caller cancellation: even a callback that has
      // just crossed the renderer boundary must not leave this module-owned cache object behind.
      operation.cancel(true);
      operation.removeOutputs().catch((): void => {
      });
    });
    this.threadImageCompressionOperations.clear();
    super.onDestroy();
  }

  private showAlert(params: KRAny, callback: KuiklyRenderCallback) {
  }

  private closePage() {
    closeOhosPage(getContext(this) as common.UIAbilityContext);
  }

  // TODO open new page
  private openPage() {
  }

  private copyToPasteboard(params: KRAny) {
    const payload = this.parseRecord(params);
    const contentValue = payload['content'];
    if (typeof contentValue !== 'string' || contentValue.length === 0) {
      return;
    }
    try {
      SlockOhosClipboard.copyText(contentValue);
      SlockOhosDiagnostics.record('SlockClipboard', 'copy_to_pasteboard');
    } catch (error) {
      console.error(`KuiklyRender copyToPasteboard failed ${JSON.stringify(error)}`);
    }
  }

  private toast(params: KRAny) {
    const payload = this.parseRecord(params);
    const content = payload["content"] as string | undefined;
    if (content === undefined || content.length === 0) {
      return;
    }
    promptAction.showToast({ message: content, duration: 2000 });
  }

  private openUrl(params: KRAny) {
    if (params == null) {
      return;
    }
    let payload: Record<string, Object>;
    try {
      payload = this.parseRecord(params);
    } catch (_) {
      SlockOhosDiagnostics.record("HRBridgeModule", "openUrl invalid JSON payload", Date.now(), "warn");
      return;
    }
    if (payload == null) {
      return;
    }
    const url = payload['url'];
    if (typeof url !== 'string' || url.length === 0) {
      return;
    }
    const mimeType = payload['mimeType'];
    const context = getContext(this) as common.UIAbilityContext;
    context.startAbility({
      action: 'ohos.want.action.viewData',
      entities: ['entity.system.browsable'],
      uri: url,
      type: typeof mimeType === 'string' ? mimeType : undefined
    }).catch((error: BusinessError) => {
      console.error(`KuiklyRender openUrl failed ${error.code}: ${error.message}`);
      SlockOhosDiagnostics.record(
        "HRBridgeModule",
        `openUrl startAbility failed code=${error.code} message=${error.message} url_len=${url.length} -> in-app browser fallback`,
        Date.now(),
        "warn"
      );
      // Fall back to the Kuikly in-app browser page so the user still sees the link
      // instead of a silent no-op. Mirrors the Android #2700 fallback to
      // compose_in_app_browser. RouterAdapter.openPage pushes
      // params={ pageName, pageData } where pageData itself carries `pageName`
      // plus the page args — here `url` (SLOCK_IN_APP_BROWSER_URL_KEY,
      // SlockLinkNavigation.kt:136).
      // Fallback only applies to http/https — mailto/tel/etc have no in-app
      // browser representation and stay a silent failure.
      if (!url.startsWith('http://') && !url.startsWith('https://')) {
        return;
      }
      const inAppPageData: Record<string, Object> = {
        'pageName': 'compose_in_app_browser',
        'url': url
      };
      try {
        router.pushUrl({
          url: 'pages/Index',
          params: {
            pageName: 'compose_in_app_browser',
            pageData: inAppPageData
          }
        });
      } catch (routerError) {
        SlockOhosDiagnostics.record(
          "HRBridgeModule",
          `openUrl in-app browser push failed: ${JSON.stringify(routerError)}`,
          Date.now(),
          "error"
        );
      }
    });
  }

  private async openAttachment(params: KRAny, callback: KuiklyRenderCallback) {
    const records = this.parseRecord(params);
    const url = records["url"] as string | undefined;
    const attachmentId = (records["attachmentId"] as string | undefined) ?? "attachment";
    const fileName = (records["fileName"] as string | undefined) ?? "attachment";
    const mimeType = records["mimeType"] as string | undefined;
    const ownerKey = (records["ownerKey"] as string | undefined) ?? "";
    const requireCommittedDownload = records["requirePublicDownload"] === true || records["requirePublicDownload"] === "true";
    const trackProgress = records["trackProgress"] === true || records["trackProgress"] === "true";
    const downloadId = (records["downloadId"] as string | undefined) ?? `${attachmentId}:${fileName}`;
    const ownerPrivate = isSlockOhosPrivateAttachmentOwnerKey(ownerKey);
    const preflightTrace = new SlockOhosAttachmentOpenPreflightTrace(
      mimeType,
      url !== undefined && url.length > 0,
      requireCommittedDownload,
      ownerPrivate,
      (message: string): void => SlockOhosDiagnostics.record("SlockAttachmentOpenPreflight", message)
    );
    preflightTrace.requested();
    const context = getContext(this) as common.UIAbilityContext;
    try {
      const privateDirectory = ensureSlockOhosPrivateAttachmentDirectory(context.filesDir);
      const privatePath = ownerPrivate
        ? this.privateAttachmentPath(privateDirectory, ownerKey, attachmentId, fileName, mimeType)
        : "";
      const privateCommitted = privatePath.length > 0 && this.isUsablePersistentAttachmentFile(privatePath);
      const preflightDecision = slockOhosAttachmentOpenPreflightDecision(
        requireCommittedDownload,
        privateCommitted,
        url !== undefined && url.length > 0
      );
      if (preflightDecision === SlockOhosAttachmentOpenPreflightDecision.PUBLIC_DOWNLOAD_MISSING) {
        preflightTrace.publicDownloadMissing(privateCommitted);
        callback({ "status": "missing", "message": "Downloaded attachment is unavailable" });
        return;
      }
      if (preflightDecision === SlockOhosAttachmentOpenPreflightDecision.URL_MISSING) {
        preflightTrace.requestRejected();
        callback({ "status": "failed", "message": "Unable to download attachment" });
        return;
      }
      const cacheDir = `${context.cacheDir}/slock-attachments`;
      ensureSlockOhosAttachmentCacheDirectory(cacheDir);
      this.cleanupAttachmentCache(cacheDir);
      const path = privateCommitted
        ? privatePath
        : `${cacheDir}/${this.safeAttachmentCacheFileName(attachmentId, fileName, mimeType)}`;
      const cached = privateCommitted || this.isUsableAttachmentCacheFile(path);
      preflightTrace.cacheChecked(privateCommitted, cached);
      if (!cached) {
        preflightTrace.downloadStarted(privateCommitted);
        const downloadResult = await slockOhosAttachmentDownloadSink.download(
          url ?? "",
          path,
          downloadId,
          path,
          trackProgress ? callback : undefined
        );
        preflightTrace.downloadTerminal(privateCommitted, downloadResult["status"] as string | undefined);
        if (downloadResult["status"] !== "complete") {
          callback(downloadResult);
          return;
        }
      }
      let openPath = path;
      let retainedOpenDirectory = '';
      if (privateCommitted) {
        const openDirectory = `${cacheDir}/${slockOhosPrivateAttachmentOpenAliasDirectoryName(ownerKey, attachmentId)}`;
        retainedOpenDirectory = openDirectory;
        ensureSlockOhosAttachmentCacheDirectory(openDirectory);
        openPath = await materializeSlockOhosPrivateAttachmentOpenAlias(
          path,
          `${openDirectory}/${slockOhosPrivateAttachmentOpenAliasFileName(
            fileName,
            this.extensionForMimeType(mimeType)
          )}`
        );
      }
      cleanupSlockOhosPrivateAttachmentOpenAliases(
        cacheDir,
        KRBridgeModule.ATTACHMENT_CACHE_MAX_AGE_MS,
        KRBridgeModule.ATTACHMENT_CACHE_MAX_FILES,
        Date.now(),
        retainedOpenDirectory
      );
      const uri = fileUri.getUriFromPath(openPath);
      let openWants: SlockOhosAttachmentOpenWants | undefined = undefined;
      try {
        preflightTrace.wantHandoff(privateCommitted, !cached);
        openWants = buildSlockOhosAttachmentOpenWants(uri, mimeType);
        SlockOhosDiagnostics.record(
          "SlockAttachmentOpen",
          slockOhosAttachmentOpenDiagnostic(openWants, SlockOhosAttachmentOpenDiagnosticTerminal.REQUESTED)
        );
        await context.startAbility(openWants.view);
        SlockOhosDiagnostics.record(
          "SlockAttachmentOpen",
          slockOhosAttachmentOpenDiagnostic(openWants, SlockOhosAttachmentOpenDiagnosticTerminal.LAUNCH_SUCCEEDED)
        );
        callback({ "status": "opened", "cached": privateCommitted, "storageKind": privateCommitted ? "app_private" : "cache" });
      } catch (error) {
        const businessError = error as BusinessError;
        const failureKind = slockOhosAttachmentOpenFailureKind(businessError.code);
        SlockOhosDiagnostics.record(
          "SlockAttachmentOpen",
          slockOhosAttachmentOpenDiagnostic(
            openWants,
            openWants === undefined
              ? SlockOhosAttachmentOpenDiagnosticTerminal.PREPARE_FAILED
              : SlockOhosAttachmentOpenDiagnosticTerminal.LAUNCH_FAILED,
            businessError.code
          )
        );
        if (failureKind === SlockOhosAttachmentOpenFailureKind.NO_APP) {
          callback({ "status": "no_app", "message": "No app can open this file" });
        } else {
          callback({ "status": "failed", "message": "Unable to open attachment" });
        }
      }
    } catch (error) {
      const businessError = error as BusinessError;
      SlockOhosDiagnostics.record(
        "SlockAttachmentOpen",
        slockOhosAttachmentOpenDiagnostic(
          undefined,
          SlockOhosAttachmentOpenDiagnosticTerminal.PREPARE_FAILED,
          businessError.code
        )
      );
      callback({ "status": "failed", "message": "Unable to download attachment" });
    }
  }

  private async saveAttachment(params: KRAny, callback: KuiklyRenderCallback) {
    const records = this.parseRecord(params);
    const url = records["url"] as string | undefined;
    const attachmentId = (records["attachmentId"] as string | undefined) ?? "attachment";
    const requestedFileName = (records["fileName"] as string | undefined) ?? "attachment";
    const mimeType = records["mimeType"] as string | undefined;
    const ownerKey = (records["ownerKey"] as string | undefined) ?? "";
    const trackProgress = records["trackProgress"] === true || records["trackProgress"] === "true";
    const downloadId = (records["downloadId"] as string | undefined) ?? `${attachmentId}:${requestedFileName}`;
    if (url === undefined || url.length === 0) {
      callback({ "status": "failed", "message": "Unable to download attachment" });
      return;
    }
    if (!isSlockOhosPrivateAttachmentOwnerKey(ownerKey)) {
      callback({ "status": "failed", "message": "Authentication required" });
      return;
    }
    if (this.activePrivateDownloadId.length > 0 || this.privateExportActive) {
      callback({ "status": "failed", "message": "Another attachment operation is active" });
      return;
    }
    this.activePrivateDownloadId = downloadId;
    const context = getContext(this) as common.UIAbilityContext;
    try {
      const directory = ensureSlockOhosPrivateAttachmentDirectory(context.filesDir);
      const path = this.privateAttachmentPath(directory, ownerKey, attachmentId, requestedFileName, mimeType);
      const cached = this.isUsablePersistentAttachmentFile(path);
      if (!cached) {
        cleanupSlockOhosPrivateAttachmentOrphanParts(path);
        const downloadResult = await slockOhosAttachmentDownloadSink.download(
          url,
          path,
          downloadId,
          path,
          trackProgress ? callback : undefined
        );
        if (downloadResult["status"] !== "complete") {
          callback(this.slockAttachmentSaveFailurePayload(downloadResult));
          return;
        }
      }
      const stat = fs.statSync(path);
      SlockOhosDiagnostics.record(
        "SlockAttachmentDownload",
        `attachmentId=${attachmentId.substring(0, 8)} status=saved_private cached=${cached}`
      );
      callback({
        "status": "saved",
        "message": "Downloaded",
        "uri": fileUri.getUriFromPath(path),
        "storageKind": "app_private",
        "cached": true,
        "sizeBytes": stat.size,
        "recordVersion": Math.max(1, Math.floor(stat.mtime))
      });
    } catch (error) {
      const businessError = error as BusinessError;
      SlockOhosDiagnostics.record(
        "SlockAttachmentDownload",
        `attachmentId=${attachmentId.substring(0, 8)} status=failed code=${businessError.code ?? "unknown"}`
      );
      callback({ "status": "storage_failed", "message": "Unable to download attachment" });
    } finally {
      if (this.activePrivateDownloadId === downloadId) this.activePrivateDownloadId = "";
    }
  }

  private slockAttachmentSaveFailurePayload(downloadResult: Record<string, Object>): Record<string, Object> {
    const status = downloadResult["status"] as string | undefined;
    if (status === "cancelled") {
      return downloadResult;
    }
    const errorKind = (downloadResult["errorKind"] as string | undefined) ?? "";
    const storageFailure = errorKind === "storage_write" ||
      errorKind === "storage_prepare" ||
      errorKind === "storage_publish";
    return {
      "status": storageFailure ? "storage_failed" : "download_failed",
      "message": (downloadResult["message"] as string | undefined) ?? "Unable to download attachment",
      "errorKind": errorKind
    };
  }

  private attachmentCacheStatus(params: KRAny, callback: KuiklyRenderCallback) {
    const records = this.parseRecord(params);
    const attachmentId = (records["attachmentId"] as string | undefined) ?? "attachment";
    const requestedFileName = (records["fileName"] as string | undefined) ?? "attachment";
    const mimeType = records["mimeType"] as string | undefined;
    const ownerKey = (records["ownerKey"] as string | undefined) ?? "";
    if (!isSlockOhosPrivateAttachmentOwnerKey(ownerKey)) {
      callback({ "status": "failed", "cached": false, "message": "Authentication required" });
      return;
    }
    try {
      const context = getContext(this) as common.UIAbilityContext;
      const directory = ensureSlockOhosPrivateAttachmentDirectory(context.filesDir);
      const path = this.privateAttachmentPath(directory, ownerKey, attachmentId, requestedFileName, mimeType);
      if (!this.isUsablePersistentAttachmentFile(path)) {
        callback({ "status": "ok", "cached": false, "active": this.activePrivateDownloadId.length > 0 });
        return;
      }
      const stat = fs.statSync(path);
      callback({
        "status": "ok",
        "cached": true,
        "active": false,
        "attemptToken": "",
        "recordVersion": Math.max(1, Math.floor(stat.mtime)),
        "sizeBytes": stat.size,
        "uri": fileUri.getUriFromPath(path),
        "storageKind": "app_private"
      });
    } catch (_) {
      callback({ "status": "storage_failed", "cached": false, "message": "Unable to inspect downloaded attachment" });
    }
  }

  private deleteAttachmentCache(params: KRAny, callback: KuiklyRenderCallback) {
    const records = this.parseRecord(params);
    const attachmentId = (records["attachmentId"] as string | undefined) ?? "attachment";
    const requestedFileName = (records["fileName"] as string | undefined) ?? "attachment";
    const mimeType = records["mimeType"] as string | undefined;
    const ownerKey = (records["ownerKey"] as string | undefined) ?? "";
    const expectedRecordVersion = Number(records["recordVersion"] ?? 0);
    if (!isSlockOhosPrivateAttachmentOwnerKey(ownerKey)) {
      callback({ "status": "failed", "message": "Authentication required" });
      return;
    }
    if (this.activePrivateDownloadId.length > 0 || this.privateExportActive) {
      callback({ "status": "failed", "message": "Another attachment operation is active" });
      return;
    }
    try {
      const context = getContext(this) as common.UIAbilityContext;
      const directory = ensureSlockOhosPrivateAttachmentDirectory(context.filesDir);
      const path = this.privateAttachmentPath(directory, ownerKey, attachmentId, requestedFileName, mimeType);
      let stat: fs.Stat | null = null;
      try {
        stat = fs.statSync(path);
      } catch (_) {
      }
      if (stat === null || !stat.isFile()) {
        callback({ "status": "ok", "cached": false, "deleted": false });
        return;
      }
      const recordVersion = Math.max(1, Math.floor(stat.mtime));
      if (Number.isFinite(expectedRecordVersion) && expectedRecordVersion > 0 &&
        Math.floor(expectedRecordVersion) !== recordVersion) {
        callback({ "status": "stale", "message": "Downloaded attachment changed" });
        return;
      }
      fs.unlinkSync(path);
      try {
        fs.statSync(path);
        callback({ "status": "failed", "message": "Unable to delete downloaded attachment" });
        return;
      } catch (_) {
      }
      callback({ "status": "ok", "cached": false, "deleted": true });
    } catch (_) {
      callback({ "status": "failed", "message": "Unable to delete downloaded attachment" });
    }
  }

  /**
   * Compatibility entry point for older renderer callers.  New shared callers use the common
   * registry directly; this path deliberately reuses the same physical provider so a legacy
   * call cannot regress to cache-only unlink or an asynchronous delete of the original ref.
   */
  private retireFilePreviewTemporaryFiles(params: KRAny): Record<string, Object> {
    const records = this.parseRecord(params);
    const rawRefs = records["fileRefs"];
    const refs: Array<string> = [];
    if (Array.isArray(rawRefs)) {
      (rawRefs as Array<Object>).forEach((value: Object): void => {
        if (typeof value === 'string' && value.trim().length > 0) refs.push(value.trim());
      });
    }
    const sessionId = ((records["sessionId"] as string | undefined) ?? '').trim();
    const context = getContext(this) as common.UIAbilityContext;
    const result = new SlockOhosThreadComposerTemporaryFileRetirementProvider(context)
      .retire(sessionId, refs);
    SlockOhosDiagnostics.record(
      "SlockFilePreviewCleanup",
      `session=${result['sessionId']} requested=${(result['requested'] as Array<string>).length} ` +
        `admitted=${(result['admitted'] as Array<string>).length} ` +
        `quarantined=${(result['quarantined'] as Array<string>).length} ` +
        `rejected=${(result['rejected'] as Array<string>).length} ` +
        `failed=${(result['failed'] as Array<string>).length}`
    );
    return result;
  }

  private isOhosCacheOwnedRegularFile(cacheRoot: string, targetPath: string): boolean {
    if (cacheRoot.length === 0 || targetPath.length === 0 ||
      !targetPath.startsWith(`${cacheRoot}/`)) return false;
    try {
      const rootStat = fs.lstatSync(cacheRoot);
      if (!rootStat.isDirectory() || rootStat.isSymbolicLink()) return false;
      const relativeSegments = targetPath.substring(cacheRoot.length + 1).split('/');
      let candidate = cacheRoot;
      for (let index = 0; index < relativeSegments.length; index += 1) {
        const segment = relativeSegments[index];
        if (segment.length === 0) return false;
        candidate = `${candidate}/${segment}`;
        const stat = fs.lstatSync(candidate);
        if (stat.isSymbolicLink()) return false;
        const isLast = index === relativeSegments.length - 1;
        if (isLast ? !stat.isFile() : !stat.isDirectory()) return false;
      }
      return relativeSegments.length > 0;
    } catch (_) {
      return false;
    }
  }

  private async exportDownloadedAttachment(params: KRAny, callback: KuiklyRenderCallback) {
    const records = this.parseRecord(params);
    const attachmentId = (records["attachmentId"] as string | undefined) ?? "attachment";
    const requestedFileName = (records["fileName"] as string | undefined) ?? "attachment";
    const mimeType = records["mimeType"] as string | undefined;
    const ownerKey = (records["ownerKey"] as string | undefined) ?? "";
    if (!isSlockOhosPrivateAttachmentOwnerKey(ownerKey)) {
      callback({ "status": "failed", "message": "Authentication required" });
      return;
    }
    if (this.activePrivateDownloadId.length > 0 || this.privateExportActive) {
      callback({ "status": "failed", "message": "Another attachment operation is active" });
      return;
    }
    this.privateExportActive = true;
    const context = getContext(this) as common.UIAbilityContext;
    try {
      const directory = ensureSlockOhosPrivateAttachmentDirectory(context.filesDir);
      const path = this.privateAttachmentPath(directory, ownerKey, attachmentId, requestedFileName, mimeType);
      if (!this.isUsablePersistentAttachmentFile(path)) {
        callback({ "status": "missing", "message": "Downloaded attachment is unavailable" });
        return;
      }
      const requestedBaseName = requestedFileName.split("/").pop()?.split("\\").pop() ?? "attachment";
      const fileName = this.safeAttachmentPathSegment(requestedBaseName).slice(0, 120) || "attachment";
      const options = new picker.DocumentSaveOptions();
      options.newFileNames = [fileName];
      const targetUris = await new picker.DocumentViewPicker(context).save(options);
      const targetUri = targetUris.length > 0 ? targetUris[0] : undefined;
      if (targetUri === undefined || targetUri.length === 0) {
        callback({ "status": "cancelled" });
        return;
      }
      const sizeBytes = copySlockOhosPrivateAttachmentToProviderUri(path, targetUri);
      SlockOhosDiagnostics.record(
        "SlockAttachmentExport",
        `attachmentId=${attachmentId.substring(0, 8)} status=exported size=${sizeBytes}`
      );
      callback({ "status": "exported", "message": "Saved to Files", "uri": targetUri, "sizeBytes": sizeBytes });
    } catch (error) {
      const businessError = error as BusinessError;
      const message = businessError.message ?? `${error}`;
      if (message.toLowerCase().includes("cancel")) {
        callback({ "status": "cancelled" });
        return;
      }
      SlockOhosDiagnostics.record(
        "SlockAttachmentExport",
        `attachmentId=${attachmentId.substring(0, 8)} status=failed code=${businessError.code ?? "unknown"}`
      );
      callback({ "status": "storage_failed", "message": "Unable to save to Files" });
    } finally {
      this.privateExportActive = false;
    }
  }

  private async saveImageToPhotos(params: KRAny, callback: KuiklyRenderCallback) {
    const records = this.parseRecord(params);
    const url = records["url"] as string | undefined;
    const attachmentId = (records["attachmentId"] as string | undefined) ?? "attachment";
    const requestedFileName = (records["fileName"] as string | undefined) ?? "image";
    const claimedMimeType = ((records["mimeType"] as string | undefined) ?? "").split(";")[0].trim().toLowerCase();
    const ownerKey = (records["ownerKey"] as string | undefined) ?? "";
    const trackProgress = records["trackProgress"] === true || records["trackProgress"] === "true";
    const downloadId = (records["downloadId"] as string | undefined) ?? `${attachmentId}:${requestedFileName}`;
    const operationId = (records["operationId"] as string | undefined) ?? "";
    if (url === undefined || url.length === 0) {
      callback({ "status": "failed", "message": "Unable to download attachment" });
      return;
    }
    if (ownerKey.length !== 64) {
      callback({ "status": "failed", "message": "Authentication required" });
      return;
    }
    if (operationId.length === 0) {
      callback({ "status": "failed", "message": "Missing attachment export authority" });
      return;
    }
    if (claimedMimeType.length > 0 && this.normalizedImageMimeType(claimedMimeType).length === 0) {
      callback({ "status": "unsupported_image", "message": "This attachment is not a supported image", "retryable": false });
      return;
    }
    if (this.activePhotoExportDownloadId.length > 0) {
      callback({ "status": "failed", "message": "Another photo save is already active" });
      return;
    }
    this.nextPhotoExportGeneration += 1;
    const exportGeneration = this.nextPhotoExportGeneration;
    this.activePhotoExportDownloadId = downloadId;
    this.activePhotoExportOperationId = operationId;
    this.activePhotoExportGeneration = exportGeneration;
    const context = getContext(this) as common.UIAbilityContext;
    try {
      const cacheDir = `${context.cacheDir}/slock-attachments`;
      ensureSlockOhosAttachmentCacheDirectory(cacheDir);
      this.cleanupAttachmentCache(cacheDir);
      const path = `${cacheDir}/${ownerKey}-${this.safeAttachmentCacheFileName(attachmentId, requestedFileName, claimedMimeType)}`;
      const cached = this.isUsableAttachmentCacheFile(path);
      if (!cached) {
        const progressCallback: KuiklyRenderCallback | undefined = trackProgress ?
          (payload: KRAny): void => {
            if (this.isCurrentPhotoExport(downloadId, operationId, exportGeneration)) callback(payload);
          } : undefined;
        const downloadResult = await slockOhosAttachmentDownloadSink.download(
          url,
          path,
          downloadId,
          path,
          progressCallback
        );
        if (downloadResult["status"] !== "complete") {
          if (this.isCurrentPhotoExport(downloadId, operationId, exportGeneration)) callback(downloadResult);
          return;
        }
      }
      if (!this.isCurrentPhotoExport(downloadId, operationId, exportGeneration)) return;
      let width = 0;
      let height = 0;
      const extension = this.detectedImageExtension(path);
      if (extension.length === 0 || !this.imageMetadataMatches(requestedFileName, claimedMimeType, extension)) {
        callback({ "status": "unsupported_image", "message": "This attachment is not a supported image", "retryable": false });
        return;
      }
      const sourceFile = fs.openSync(path, fs.OpenMode.READ_ONLY);
      let imageSource: image.ImageSource | undefined = undefined;
      try {
        imageSource = image.createImageSource(sourceFile.fd);
        if (imageSource === undefined) {
          callback({ "status": "unsupported_image", "message": "This attachment is not a supported image", "retryable": false });
          return;
        }
        const imageInfo = await imageSource.getImageInfo();
        width = imageInfo.size.width;
        height = imageInfo.size.height;
      } finally {
        try {
          if (imageSource !== undefined) await imageSource.release();
        } finally {
          fs.closeSync(sourceFile);
        }
      }
      if (width <= 0 || height <= 0 || !this.isCurrentPhotoExport(downloadId, operationId, exportGeneration)) {
        if (this.isCurrentPhotoExport(downloadId, operationId, exportGeneration)) {
          callback({ "status": "unsupported_image", "message": "This attachment is not a supported image", "retryable": false });
        }
        return;
      }
      const title = requestedFileName.split("/").pop()?.split("\\").pop()?.replace(/\.[^.]+$/, "") ?? "image";
      const configs: Array<photoAccessHelper.PhotoCreationConfig> = [{
        title: title,
        fileNameExtension: extension,
        photoType: photoAccessHelper.PhotoType.IMAGE,
        subtype: photoAccessHelper.PhotoSubtype.DEFAULT
      }];
      const helper = photoAccessHelper.getPhotoAccessHelper(context);
      const targetUris = await helper.showAssetsCreationDialog([fileUri.getUriFromPath(path)], configs);
      const targetUri = targetUris.length > 0 ? targetUris[0] : undefined;
      if (!this.isCurrentPhotoExport(downloadId, operationId, exportGeneration)) {
        if (targetUri !== undefined && targetUri.length > 0) {
          try {
            fs.unlinkSync(targetUri);
          } catch (ignoredError) {
            SlockOhosAppDiagnostics.record("gallery", `Unable to remove cancelled photo asset: ${ignoredError}`);
          }
        }
        return;
      }
      if (targetUri === undefined || targetUri.length === 0) {
        callback({ "status": "cancelled" });
        return;
      }
      fs.copyFileSync(path, targetUri);
      callback({
        "status": "saved",
        "message": "Saved to Photos",
        "uri": targetUri,
        "fileName": requestedFileName,
        "mimeType": this.imageMimeTypeForExtension(extension),
        "pixelWidth": width,
        "pixelHeight": height,
        "cached": cached,
        "sizeBytes": fs.statSync(path).size
      });
    } catch (error) {
      if (!this.isCurrentPhotoExport(downloadId, operationId, exportGeneration)) return;
      const businessError = error as BusinessError;
      const message = businessError.message ?? `${error}`;
      if (message.toLowerCase().includes("cancel")) {
        callback({ "status": "cancelled" });
      } else if (businessError.code === 201) {
        callback({ "status": "permission_denied", "message": "Allow Photos access, then try again", "retryable": true });
      } else {
        callback({ "status": "photos_failed", "message": "Unable to save image to Photos", "retryable": true });
      }
    } finally {
      if (this.isCurrentPhotoExport(downloadId, operationId, exportGeneration)) {
        this.activePhotoExportDownloadId = "";
        this.activePhotoExportOperationId = "";
        this.activePhotoExportGeneration = 0;
      }
    }
  }

  private cancelAttachmentExport(params: KRAny): void {
    const records = this.parseRecord(params);
    const downloadId = records["downloadId"] as string | undefined;
    const operationId = records["operationId"] as string | undefined;
    const attemptToken = (records["attemptToken"] as string | undefined) ?? "";
    if (downloadId === undefined || downloadId.length === 0 ||
      operationId === undefined || operationId.length === 0 ||
      this.activePhotoExportDownloadId !== downloadId ||
      this.activePhotoExportOperationId !== operationId) return;
    slockOhosAttachmentDownloadSink.controlExisting(downloadId, attemptToken, "cancel");
    if (this.activePhotoExportDownloadId === downloadId &&
      this.activePhotoExportOperationId === operationId) {
      this.activePhotoExportDownloadId = "";
      this.activePhotoExportOperationId = "";
      this.activePhotoExportGeneration = 0;
    }
  }

  private isCurrentPhotoExport(downloadId: string, operationId: string, exportGeneration: number): boolean {
    return this.activePhotoExportDownloadId === downloadId &&
      this.activePhotoExportOperationId === operationId &&
      this.activePhotoExportGeneration === exportGeneration;
  }

  private imageMetadataMatches(fileName: string, mimeType: string, actualExtension: string): boolean {
    const cleanName = fileName.split("?")[0].split("#")[0].split("/").pop()?.split("\\").pop() ?? "";
    const dotIndex = cleanName.lastIndexOf(".");
    const extension = dotIndex > 0 && dotIndex < cleanName.length - 1 ?
      cleanName.substring(dotIndex + 1).toLowerCase() : "";
    const normalizedExtension = extension === "jpeg" ? "jpg" : extension;
    const extensionMimeType = this.imageMimeTypeForExtension(normalizedExtension);
    const normalizedClaimedMimeType = this.normalizedImageMimeType(mimeType);
    const actualMimeType = this.normalizedImageMimeType(this.imageMimeTypeForExtension(actualExtension));
    if (actualMimeType.length === 0) return false;
    if (mimeType.length > 0 && normalizedClaimedMimeType !== actualMimeType) return false;
    if (extension.length === 0) return true;
    return extensionMimeType.length > 0 && this.normalizedImageMimeType(extensionMimeType) === actualMimeType;
  }

  private detectedImageExtension(path: string): string {
    const file = fs.openSync(path, fs.OpenMode.READ_ONLY);
    const header = new ArrayBuffer(32);
    let readBytes = 0;
    try {
      readBytes = fs.readSync(file.fd, header, { length: 32 });
    } finally {
      fs.closeSync(file);
    }
    const bytes = new Uint8Array(header.slice(0, Math.max(0, readBytes)));
    if (bytes.length >= 3 && bytes[0] === 0xFF && bytes[1] === 0xD8 && bytes[2] === 0xFF) return "jpg";
    if (bytes.length >= 8 && bytes[0] === 0x89 && bytes[1] === 0x50 && bytes[2] === 0x4E && bytes[3] === 0x47 &&
      bytes[4] === 0x0D && bytes[5] === 0x0A && bytes[6] === 0x1A && bytes[7] === 0x0A) return "png";
    if (bytes.length >= 6 && bytes[0] === 0x47 && bytes[1] === 0x49 && bytes[2] === 0x46 &&
      bytes[3] === 0x38 && (bytes[4] === 0x37 || bytes[4] === 0x39) && bytes[5] === 0x61) return "gif";
    if (bytes.length >= 12 && bytes[0] === 0x52 && bytes[1] === 0x49 && bytes[2] === 0x46 && bytes[3] === 0x46 &&
      bytes[8] === 0x57 && bytes[9] === 0x45 && bytes[10] === 0x42 && bytes[11] === 0x50) return "webp";
    if (bytes.length >= 2 && bytes[0] === 0x42 && bytes[1] === 0x4D) return "bmp";
    if (bytes.length >= 12 && bytes[4] === 0x66 && bytes[5] === 0x74 && bytes[6] === 0x79 && bytes[7] === 0x70) {
      const brand = String.fromCharCode(bytes[8], bytes[9], bytes[10], bytes[11]);
      if (["heic", "heix", "hevc", "hevx", "heim", "heis"].includes(brand)) return "heic";
      if (["mif1", "msf1"].includes(brand)) return "heif";
    }
    return "";
  }

  private imageMimeTypeForExtension(extension: string): string {
    switch (extension) {
      case "jpg": return "image/jpeg";
      case "png": return "image/png";
      case "webp": return "image/webp";
      case "gif": return "image/gif";
      case "heic": return "image/heic";
      case "heif": return "image/heif";
      case "bmp": return "image/bmp";
      default: return "";
    }
  }

  private normalizedImageMimeType(mimeType: string): string {
    switch (mimeType) {
      case "image/jpg":
      case "image/jpeg":
      case "image/pjpeg": return "image/jpeg";
      case "image/png":
      case "image/x-png": return "image/png";
      case "image/webp": return "image/webp";
      case "image/gif": return "image/gif";
      case "image/heic":
      case "image/heif": return "image/heif";
      case "image/bmp":
      case "image/x-ms-bmp": return "image/bmp";
      default: return "";
    }
  }

  private attachmentDownloadControl(params: KRAny, callback: KuiklyRenderCallback): void {
    const records = this.parseRecord(params);
    const downloadId = records["downloadId"] as string | undefined;
    const attemptToken = (records["attemptToken"] as string | undefined) ?? "";
    const action = records["action"] as string | undefined;
    if (downloadId === undefined || downloadId.length === 0 || action === undefined || action.length === 0) {
      callback({ "status": "failed", "message": "Missing download control field" });
      return;
    }
    const status = slockOhosAttachmentDownloadSink.control(downloadId, attemptToken, action);
    callback({ "status": status });
  }

  private attachmentDownloadOwnerChanged(): void {
    // OHOS keeps transfer authority in the common coordinator; shared leases fence UI commits.
  }

  private log(params: KRAny) {
    if (params == null) {
      return
    }
    const payload = this.parseRecord(params);
    const contentValue = payload['content'];
    const content = typeof contentValue === 'string' ? contentValue : JSON.stringify(params);
    const level = this.parseBridgeLogLevel(payload['level']);
    const tagValue = payload['tag'];
    const tag = typeof tagValue === 'string' && tagValue.length > 0 ? tagValue : 'KuiklyRender';
    SlockOhosDiagnostics.record(tag, content, Date.now(), level);
    if (this.shouldMirrorBridgeLogLevel(level)) {
      this.mirrorBridgeLogToConsole(level, content);
    }
  }

  private parseBridgeLogLevel(value: Object | undefined): string {
    if (typeof value !== 'string') {
      return 'info';
    }
    const normalized = value.trim().toLowerCase();
    if (normalized === 'debug' || normalized === 'info' || normalized === 'warn' || normalized === 'error') {
      return normalized;
    }
    if (normalized === 'fatal') {
      return 'error';
    }
    return 'info';
  }

  private shouldMirrorBridgeLogLevel(level: string): boolean {
    return level !== 'debug';
  }

  private mirrorBridgeLogToConsole(level: string, content: string) {
    const message = `KuiklyRender ${content}`;
    if (level === 'warn') {
      console.warn(message);
      return;
    }
    if (level === 'error') {
      console.error(message);
      return;
    }
    console.log(message);
  }

  private reportDT(params: KRAny) {
  }

  private appBadge(params: KRAny) {
    const records = this.parseRecord(params);
    const countValue = records["count"];
    const count = typeof countValue === 'number' ? Math.max(0, Math.floor(countValue)) : 0;
    const source = typeof records["source"] === 'string' ? records["source"] as string : "unknown";
    const isStale = records["isStale"] === true;
    // notificationManager.setBadgeNumber never prompts the user; notification authorization
    // only gates notification posting, not the launcher badge surface itself.
    notificationManager.setBadgeNumber(count).then((): void => {
      SlockOhosDiagnostics.record(
        "HRBridgeModule",
        `appBadge count=${count} source=${source} stale=${isStale}`,
        Date.now(),
        "info"
      );
    }).catch((err: BusinessError): void => {
      SlockOhosDiagnostics.record(
        "HRBridgeModule",
        `appBadge setBadgeNumber failed code=${err.code} message=${err.message}`,
        Date.now(),
        "warn"
      );
    });
  }

  private performanceScene(params: KRAny) {
    if (params == null) {
      return;
    }
    const payload = this.parseRecord(params);
    const actionValue = payload['action'];
    const sceneValue = payload['sceneName'];
    const action = typeof actionValue === 'string' ? actionValue : 'enter';
    const sceneName = typeof sceneValue === 'string' ? sceneValue.trim() : '';
    if (sceneName.length === 0) {
      return;
    }
    const result = SlockOhosAppDiagnostics.updateScene(action, sceneName);
    if (result["status"] === "ok") {
      SlockOhosDiagnostics.record('SlockPerfScene', `${action} scene=${sceneName} result=ok`);
    } else {
      SlockOhosDiagnostics.record(
        'SlockPerfScene',
        `${action} scene=${sceneName} result=${result["status"]} code=${result["code"]}`
      );
    }
  }

  private performanceMarker(params: KRAny) {
    if (params == null) {
      return;
    }
    const payload = this.parseRecord(params);
    const timestampMs = Date.now();
    const sceneId = this.markerString(payload['sceneId'], 'unknown');
    const routeName = this.markerString(payload['routeName'], 'unknown');
    const phase = this.markerString(payload['phase'], 'unknown');
    const event = this.markerString(payload['event'], 'instant');
    const threadHash = this.markerString(payload['threadHash'], 'unknown');
    const durationValue = payload['durationMs'];
    const durationPart =
      typeof durationValue === 'number' && durationValue >= 0
        ? ` durationMs=${durationValue}`
        : '';
    const metadataPart = this.markerMetadata(payload['metadata']);
    const summary =
      `SLOCK_PERF_MARKER sceneId=${sceneId} route=${routeName} phase=${phase} event=${event} ` +
      `tsMs=${timestampMs} threadHash=${threadHash}${durationPart}${metadataPart}`;
    SlockOhosDiagnostics.record('SlockPerfMarker', summary, timestampMs);
    console.info(`[SlockPerfMarker] ${summary}`);
  }

  private kuiklyPerformanceSnapshot(params: KRAny) {
    if (params == null) {
      return;
    }
    const payload = this.parseRecord(params);
    const summaryValue = payload['summary'];
    const summary = typeof summaryValue === 'string' ? summaryValue : '';
    if (summary.length === 0) {
      return;
    }
    SlockOhosDiagnostics.record('SlockKuiklyPerfSnapshot', summary);
    SlockOhosAppDiagnostics.record('SlockKuiklyPerfSnapshot', summary);
  }

  private markerString(value: Object, fallback: string): string {
    if (typeof value !== 'string') {
      return fallback;
    }
    const sanitized = SlockOhosDiagnostics.sanitize(value).trim();
    if (sanitized.length === 0) {
      return fallback;
    }
    return sanitized.replace(/\s+/g, '_').slice(0, 80);
  }

  private markerMetadata(value: Object): string {
    if (value == null || typeof value !== 'object' || Array.isArray(value)) {
      return '';
    }
    const record = value as Record<string, Object>;
    const pairs: Array<string> = [];
    Object.keys(record).sort().forEach((key: string): void => {
      if (pairs.length >= 8) {
        return;
      }
      const rawValue = record[key];
      if (rawValue == null) {
        return;
      }
      const cleanKey = SlockOhosDiagnostics.sanitize(key).replace(/\s+/g, '_').slice(0, 40);
      const cleanValue = SlockOhosDiagnostics.sanitize(String(rawValue)).replace(/\s+/g, '_').slice(0, 80);
      if (cleanKey.length > 0 && cleanValue.length > 0) {
        pairs.push(`${cleanKey}=${cleanValue}`);
      }
    });
    return pairs.length > 0 ? ` meta=${pairs.join(',')}` : '';
  }

  private reportRealtime(params: KRAny) {
    if (params == null) {
      return;
    }
    const command = this.parseRecord(params);
    slockOhosRealtimeBridge.handleCommand(command);
  }

  private motionCapability(params: KRAny, callback: KuiklyRenderCallback) {
    if (params == null) {
      callback({ "status": "error", "code": "missing_motion_params" });
      return;
    }
    callback(slockOhosMotionBridge.handleCommand(this.parseRecord(params)));
  }

  private localServeTime(params: KRAny, callback: KuiklyRenderCallback) {
    callback({ "time": Date.now() / 1000.0 });
  }

  private currentTimestamp(): string {
    return Date.now().toString()
  }

  // TODO format date
  private dateFormatter(params: KRAny): string {
    return "";
  }

  private async settingsCapability(params: KRAny, callback: KuiklyRenderCallback) {
    try {
      const records = this.parseRecord(params);
      const action = records["action"] as string;
      switch (action) {
        case "appLoginPrepare":
        case "mobileOAuthLink": {
          try {
            const provider = (records["providerId"] as string | undefined) ?? '';
            const preparation = action === 'appLoginPrepare' ? prepareSlockOhosAppLogin() : prepareSlockOhosMobileOAuth(provider, 'link');
            callback({
              "status": "prepared",
              "generation": preparation.generation,
              "codeVerifier": preparation.codeVerifier,
              "codeChallenge": preparation.codeChallenge,
              "returnUri": preparation.returnUri,
            });
          } catch (_error) {
            callback({ "status": "error", "code": "settings_mobile_oauth_pkce_failed" });
          }
          return;
        }
        case "appLoginOpen":
        case "mobileOAuthOpen": {
          const authorizationUrl = ((records["authorizationUrl"] as string | undefined) ?? '').trim();
          if (!authorizationUrl.startsWith('https://')) {
            callback({ "status": "error", "code": "settings_mobile_oauth_url_invalid" });
            return;
          }
          try {
            const context = getContext(this) as common.UIAbilityContext;
            await context.startAbility({
              action: 'ohos.want.action.viewData',
              uri: authorizationUrl,
              entities: ['entity.system.browsable'],
            });
            callback({ "status": "started" });
          } catch (_error) {
            callback({ "status": "error", "code": "settings_mobile_oauth_browser_failed" });
          }
          return;
        }
        case "loadStorage": {
          callback(this.loadStorageCapability());
          return;
        }
        case "clearStorage": {
          callback(this.clearStorageCapability(records));
          return;
        }
        case "loadAbout": {
          callback(await this.loadAboutCapability());
          return;
        }
        case "loadDeveloper": {
          callback(this.loadDeveloperCapability());
          return;
        }
        case "setApiEnvironment": {
          callback(this.setApiEnvironmentCapability(records));
          return;
        }
        case "apiEnvironmentChanged": {
          callback(this.onApiEnvironmentChangedCapability(records));
          return;
        }
        case "loadDiagnostics": {
          callback(this.loadDiagnosticsCapability());
          return;
        }
        case "copyDiagnostics": {
          callback(this.copyDiagnosticsCapability());
          return;
        }
        case "shareDiagnostics": {
          callback(this.copyDiagnosticsCapability());
          return;
        }
        case "clearDiagnostics": {
          SlockOhosAppDiagnostics.clearLocalFiles(getContext(this) as common.UIAbilityContext);
          SlockOhosDiagnostics.record("Settings", "diagnostics_cleared");
          callback({ "status": "ok", "code": "diagnostics_cleared" });
          return;
        }
        case "prepareDiagnosticsFeedback": {
          // Raft-channel feedback flow retired (parity with Android):
          // a non-draft response skips the channel picker and the shared
          // settings UI falls through to sendFeedback -> Hands.
          callback({ "status": "unsupported" });
          return;
        }
        case "sendFeedback": {
          callback(await this.sendFeedbackToHandsCapability());
          return;
        }
        case "submitFeedback": {
          callback(await this.submitFeedbackToHandsCapability(records));
          return;
        }
        case "promoteFeedbackDraftAttachments": {
          callback(this.promoteFeedbackDraftAttachmentsCapability(records));
          return;
        }
        case "discardFeedbackDraftAttachments": {
          callback(this.discardFeedbackDraftAttachmentsCapability(records));
          return;
        }
        case "requestNotificationPermission": {
          callback(await this.requestNotificationPermissionCapability());
          return;
        }
        case "openNotificationSettings": {
          callback(await this.openNotificationSettingsCapability());
          return;
        }
        case "sendTestNotification": {
          callback(await this.sendTestNotificationCapability());
          return;
        }
        case "postUnreadNotification": {
          callback(await this.postUnreadNotificationCapability(records));
          return;
        }
        case "cancelTestNotification":
        case "cancelNotification": {
          callback(await this.cancelNotificationCapability(records));
          return;
        }
        default: {
          callback({
            "status": "error",
            "code": "settings_capability_unknown",
            "message": `Unsupported settings capability action: ${action}`
          });
          return;
        }
      }
    } catch (error) {
      callback(this.notificationCapabilityError("settings_capability_failed", error));
    }
  }

  private loadStorageCapability(feedback: string = ""): Record<string, Object> {
    const rows: Array<OhosStorageRowPayload> = [
      {
        target: "database",
        title: "Local database",
        detail: "OHOS storage usage is not exposed through the host capability yet.",
        sizeLabel: "--",
        clearEnabled: false
      },
      {
        target: "imageCache",
        title: "Image cache",
        detail: "Image cache clearing is not available on OHOS yet.",
        sizeLabel: "--",
        clearEnabled: false
      },
      {
        target: "temporaryAndLogs",
        title: "Temporary files and logs",
        detail: "Use Diagnostics clear logs for the local diagnostics buffer.",
        sizeLabel: "--",
        clearEnabled: false
      }
    ];
    return {
      "status": "ok",
      "totalSizeLabel": "Unavailable",
      "feedback": feedback,
      "rows": rows
    };
  }

  private clearStorageCapability(records: Record<string, Object>): Record<string, Object> {
    const target = records["target"] as string;
    SlockOhosDiagnostics.record("Settings", `storage_clear_unsupported target=${target}`);
    return {
      "status": "error",
      "code": "settings_storage_clear_unsupported",
      "message": "Storage clearing is not available on OHOS yet."
    };
  }

  private async loadAboutCapability(): Promise<Record<string, Object>> {
    const context = getContext(this) as common.UIAbilityContext;
    const bundleInfo = bundleManager.getBundleInfoForSelfSync(bundleManager.BundleFlag.GET_BUNDLE_INFO_DEFAULT);
    const notificationsEnabled = await notificationManager.isNotificationEnabled().catch((_error: BusinessError) => false);
    return {
      "status": "ok",
      "appName": "Raft",
      "versionLabel": bundleInfo.versionName,
      "versionCodeLabel": `${bundleInfo.versionCode}`,
      "buildLabel": `${bundleInfo.versionCode}`,
      "sourceLabel": SLOCK_OHOS_GIT_COMMIT.length > 0 ? SLOCK_OHOS_GIT_COMMIT : "unknown",
      "packageName": bundleInfo.name,
      "socketStatusLabel": "Shared shell",
      "notificationStatusLabel": notificationsEnabled ? "Enabled" : "Disabled",
      "deviceLabel": `${deviceInfo.manufacture} ${deviceInfo.productModel}`.trim(),
      "modelLabel": deviceInfo.productModel,
      "platformLabel": `${deviceInfo.osFullName} / ${deviceInfo.displayVersion} / API ${deviceInfo.sdkApiVersion}`,
      "sdkLabel": `${deviceInfo.sdkApiVersion}`,
      "installTimeLabel": "--",
      "screenLabel": "--",
      "densityLabel": "--",
      "fontScaleLabel": "--",
      "baseUrl": SlockOhosApiEnvironmentStore.current(this).apiBaseUrl,
      "filesDir": context.filesDir,
      "buildTime": SLOCK_OHOS_BUILD_TIME.length > 0 ? SLOCK_OHOS_BUILD_TIME : "unknown",
      "gitCommit": SLOCK_OHOS_GIT_COMMIT.length > 0 ? SLOCK_OHOS_GIT_COMMIT : "unknown"
    };
  }

  private loadDeveloperCapability(feedback: string = ""): Record<string, Object> {
    const current = SlockOhosApiEnvironmentStore.current(this);
    const options = SlockOhosApiEnvironmentStore.PREDEFINED.map((environment) => {
      return this.apiEnvironmentPayload(environment);
    });
    return {
      "status": "ok",
      "environmentKey": current.key,
      "environmentLabel": current.label,
      "apiBaseUrl": current.apiBaseUrl,
      "socketBaseUrl": current.socketBaseUrl,
      "webUrl": current.webUrl,
      "options": options,
      "feedback": feedback
    };
  }

  private setApiEnvironmentCapability(records: Record<string, Object>): Record<string, Object> {
    const environmentKey = (records["environmentKey"] as string) ?? "";
    const before = SlockOhosApiEnvironmentStore.current(this);
    const after =
      environmentKey === SlockOhosApiEnvironmentStore.CUSTOM_KEY
        ? SlockOhosApiEnvironmentStore.save({
            key: environmentKey,
            label: (records["environmentLabel"] as string) ?? "Custom",
            apiBaseUrl: (records["apiBaseUrl"] as string) ?? "",
            socketBaseUrl: (records["socketBaseUrl"] as string) ?? "",
            webUrl: (records["webUrl"] as string) ?? ""
          }, this)
        : SlockOhosApiEnvironmentStore.select(environmentKey, this);
    if (after === null) {
      return {
        "status": "error",
        "code": "settings_developer_environment_unknown"
      };
    }
    const changed =
      before.key !== after.key ||
        before.apiBaseUrl !== after.apiBaseUrl ||
        before.socketBaseUrl !== after.socketBaseUrl ||
        before.webUrl !== after.webUrl;
    if (changed) {
      SlockOhosApiEnvironmentStore.clearAuthSession(this);
      slockOhosRealtimeBridge.disconnectForSessionEnd("api_environment_changed");
      SlockOhosDiagnostics.record("SlockDeveloper", `api_environment_changed from=${before.key} to=${after.key}`);
    }
    return this.loadDeveloperCapability(
      changed
        ? `API server switched to ${after.label}. Sign in again to use this environment.`
        : `${after.label} is already selected.`
    );
  }

  private onApiEnvironmentChangedCapability(records: Record<string, Object>): Record<string, Object> {
    slockOhosRealtimeBridge.disconnectForSessionEnd("api_environment_changed");
    SlockOhosDiagnostics.record(
      "SlockDeveloper",
      `api_environment_changed from=${(records["beforeEnvironmentKey"] as string) ?? ""} to=${(records["afterEnvironmentKey"] as string) ?? ""}`
    );
    return { "status": "ok" };
  }

  private loadDiagnosticsCapability(): Record<string, Object> {
    const entries: Array<OhosDiagnosticEntryPayload> = SlockOhosDiagnostics.recent(500).map((entry): OhosDiagnosticEntryPayload => {
      return {
        "timestampLabel": new Date(entry.timestampMillis).toString(),
        "tag": entry.tag,
        "message": entry.message
      };
    });
    return { "status": "ok", "entries": entries };
  }

  private copyDiagnosticsCapability(): Record<string, Object> {
    const context = getContext(this) as common.UIAbilityContext;
    SlockOhosClipboard.copyText(SlockOhosAppDiagnostics.exportDiagnosticsText(context));
    SlockOhosDiagnostics.record("Settings", "diagnostics_copied");
    return { "status": "ok", "code": "diagnostics_copied" };
  }

  /**
   * Submit in-app feedback to the Hands ticket system (kind=bug) with the
   * diagnostics archive attached — same behavior as the Android host.
   */
  private async sendFeedbackToHandsCapability(): Promise<Record<string, Object>> {
    try {
      const context = getContext(this) as common.UIAbilityContext;
      const archivePath = await SlockOhosAppDiagnostics.prepareFeedbackArchivePath(context);
      const ticketId = await HandsFeedbackClient.submit(
        context,
        'In-app feedback (diagnostics attached).',
        'bug',
        [archivePath],
        [],
      );
      SlockOhosDiagnostics.record("Settings", `hands_feedback_submitted ticket=${ticketId.slice(0, 8)}`);
      return { "status": "ok", "ticketId": ticketId };
    } catch (error) {
      const message = String(error);
      SlockOhosDiagnostics.record("Settings", `hands_feedback_submit_failed ${message}`);
      return { "status": "error", "code": "hands_feedback_failed", "message": message };
    }
  }

  private async submitFeedbackToHandsCapability(records: Record<string, Object>): Promise<Record<string, Object>> {
    try {
      const message = ((records["message"] as string | undefined) ?? "").trim();
      if (message.length === 0) {
        return { "status": "error", "code": "settings_feedback_message_required", "message": "Feedback message is required" };
      }
      const context = getContext(this) as common.UIAbilityContext;
      const userAttachmentPaths = this.feedbackAttachmentPaths(records["files"])
        .slice(0, KRBridgeModule.HANDS_FEEDBACK_MAX_ATTACHMENTS);
      const attachmentPaths = userAttachmentPaths.slice(0, KRBridgeModule.HANDS_FEEDBACK_MAX_ATTACHMENTS);
      if (attachmentPaths.length < KRBridgeModule.HANDS_FEEDBACK_MAX_ATTACHMENTS) {
        const archivePath = await SlockOhosAppDiagnostics.prepareFeedbackArchivePath(context);
        if (this.feedbackAttachmentFits(archivePath)) {
          attachmentPaths.push(archivePath);
        } else {
          SlockOhosDiagnostics.record("Settings", `hands_feedback_diagnostics_attachment_skipped path=${archivePath}`);
        }
      }
      const ticketId = await HandsFeedbackClient.submit(
        context,
        message,
        'bug',
        attachmentPaths.slice(0, KRBridgeModule.HANDS_FEEDBACK_MAX_ATTACHMENTS),
        [],
      );
      const shortId = ticketId.slice(0, 8);
      const environmentText = SlockOhosAppDiagnostics.exportHandsFeedbackEnvironmentText(context);
      const attachmentNames = this.feedbackAttachmentNames(attachmentPaths);
      const attachmentSection = attachmentNames.length > 0
        ? `\nattachments:\n${attachmentNames.join('\n')}`
        : '';
      const shareText = `Hands feedback ${shortId}\nTicket: ${ticketId}${attachmentSection}\n\nEnvironment:\n${environmentText}\n\nFeedback:\n${message}`;
      SlockOhosClipboard.copyText(shareText);
      SlockOhosDiagnostics.record("Settings", `hands_feedback_submitted ticket=${shortId}`);
      return {
        "status": "ok",
        "ticketId": ticketId,
        "shareText": shareText,
        "attachment_names": attachmentNames
      };
    } catch (error) {
      const message = String(error);
      SlockOhosDiagnostics.record("Settings", `hands_feedback_submit_failed ${message}`);
      return { "status": "error", "code": "settings_feedback_submit_failed", "message": message };
    }
  }

  private promoteFeedbackDraftAttachmentsCapability(records: Record<string, Object>): Record<string, Object> {
    try {
      const rawFiles = records["files"];
      if (!Array.isArray(rawFiles)) {
        throw new Error('Missing feedback draft attachment files');
      }
      const sources: Array<SlockOhosFeedbackDraftAttachmentSource> = [];
      (rawFiles as Array<Record<string, Object>>).forEach((file: Record<string, Object>): void => {
        sources.push({
          id: (file["id"] as string | undefined) ?? '',
          fileName: (file["fileName"] as string | undefined) ?? 'attachment',
          mimeType: (file["mimeType"] as string | undefined) ?? 'application/octet-stream',
          fileRef: (file["fileRef"] as string | undefined) ?? '',
        });
      });
      const context = getContext(this) as common.UIAbilityContext;
      const promoted = promoteSlockOhosFeedbackDraftAttachments(
        `${context.filesDir}/settings-hands-feedback-drafts`,
        (records["accountId"] as string | undefined) ?? '',
        (records["groupId"] as string | undefined) ?? '',
        sources,
        Number(records["maxBytes"] ?? 0),
      );
      return { "status": "ok", "files": promoted };
    } catch (error) {
      const message = String(error);
      return {
        "status": "error",
        "code": message.includes('too_large')
          ? "settings_feedback_attachment_too_large"
          : "settings_feedback_attachment_promotion_failed",
        "message": message,
      };
    }
  }

  private discardFeedbackDraftAttachmentsCapability(records: Record<string, Object>): Record<string, Object> {
    const rawRefs = records["fileRefs"];
    const refs: Array<string> = [];
    if (Array.isArray(rawRefs)) {
      (rawRefs as Array<Object>).forEach((value: Object): void => {
        if (typeof value === 'string' && value.length > 0) {
          refs.push(value);
        }
      });
    }
    const context = getContext(this) as common.UIAbilityContext;
    discardSlockOhosFeedbackDraftAttachments(
      `${context.filesDir}/settings-hands-feedback-drafts`,
      (records["accountId"] as string | undefined) ?? '',
      refs,
    );
    return { "status": "ok" };
  }

  private feedbackAttachmentPaths(value: Object | undefined): Array<string> {
    return slockOhosHandsFeedbackAttachmentPaths(
      value,
      KRBridgeModule.HANDS_FEEDBACK_MAX_ATTACHMENTS,
      KRBridgeModule.HANDS_FEEDBACK_MAX_ATTACHMENT_BYTES,
    );
  }

  private feedbackAttachmentNames(paths: Array<string>): Array<string> {
    return paths
      .map((attachmentPath: string): string => {
        const normalizedPath = attachmentPath.replace(/\\/g, '/');
        const segments = normalizedPath.split('/');
        const fileName = segments.length > 0 ? segments[segments.length - 1] : '';
        return fileName.replace(/[\r\n]+/g, ' ').trim();
      })
      .filter((fileName: string): boolean => fileName.length > 0)
      .slice(0, KRBridgeModule.HANDS_FEEDBACK_MAX_ATTACHMENTS);
  }

  private feedbackAttachmentFits(path: string): boolean {
    try {
      const stat = fs.statSync(path);
      return stat.isFile() && stat.size > 0 && stat.size <= KRBridgeModule.HANDS_FEEDBACK_MAX_ATTACHMENT_BYTES;
    } catch (_error) {
      return false;
    }
  }

  private async requestNotificationPermissionCapability(): Promise<Record<string, Object>> {
    try {
      if (await notificationManager.isNotificationEnabled()) {
        await this.ensureNotificationSlot();
        return { "status": "ok", "code": "permission_granted" };
      }

      const context = getContext(this) as common.UIAbilityContext;
      await notificationManager.requestEnableNotification(context);
      const enabled = await notificationManager.isNotificationEnabled();
      if (enabled) {
        await this.ensureNotificationSlot();
        return { "status": "ok", "code": "permission_granted" };
      }
      return { "status": "error", "code": "permission_denied", "message": "Notification permission is disabled" };
    } catch (error) {
      return this.notificationCapabilityError("permission_denied", error);
    }
  }

  private async openNotificationSettingsCapability(): Promise<Record<string, Object>> {
    try {
      const context = getContext(this) as common.UIAbilityContext;
      await notificationManager.openNotificationSettings(context);
      return { "status": "ok", "code": "settings_opened" };
    } catch (error) {
      return this.notificationCapabilityError("settings_open_failed", error);
    }
  }

  private async sendTestNotificationCapability(): Promise<Record<string, Object>> {
    try {
      if (!(await notificationManager.isNotificationEnabled())) {
        return { "status": "error", "code": "permission_denied", "message": "Notification permission is disabled" };
      }
      await this.ensureNotificationSlot();

      const notificationId = Math.max(1, Math.floor(Date.now() % 2147483647));
      const request: notificationManager.NotificationRequest = {
        id: notificationId,
        notificationSlotType: notificationManager.SlotType.SOCIAL_COMMUNICATION,
        tapDismissed: true,
        autoDeletedTime: Date.now() + 60000,
        content: {
          notificationContentType: notificationManager.ContentType.NOTIFICATION_CONTENT_BASIC_TEXT,
          normal: {
            title: "Raft",
            text: "System notifications are enabled for this device."
          }
        }
      };
      await notificationManager.publish(request);
      return { "status": "ok", "code": "posted", "notificationId": notificationId };
    } catch (error) {
      return this.notificationCapabilityError("post_failed", error);
    }
  }

  private async postUnreadNotificationCapability(records: Record<string, Object>): Promise<Record<string, Object>> {
    try {
      if (!(await notificationManager.isNotificationEnabled())) {
        return { "status": "error", "code": "permission_denied", "message": "Notification permission is disabled" };
      }
      await this.ensureNotificationSlot();

      const key = this.stringRecordValue(records, "notificationKey", `${records["serverId"] ?? ""}:${records["threadId"] ?? ""}:${records["messageId"] ?? ""}`);
      const notificationId = this.stableNotificationId(key);
      const title = this.stringRecordValue(records, "title", "Raft");
      const text = this.stringRecordValue(records, "body", "You have a new unread message.");
      const category = this.stringRecordValue(records, "category", "general_unread");
      const request: notificationManager.NotificationRequest = {
        id: notificationId,
        notificationSlotType: notificationManager.SlotType.SOCIAL_COMMUNICATION,
        tapDismissed: true,
        autoDeletedTime: Date.now() + 300000,
        content: {
          notificationContentType: notificationManager.ContentType.NOTIFICATION_CONTENT_BASIC_TEXT,
          normal: {
            title,
            text
          }
        }
      };
      await notificationManager.publish(request);
      return {
        "status": "ok",
        "code": "posted",
        "notificationId": notificationId,
        "channelId": "ohos-social-communication",
        "category": category
      };
    } catch (error) {
      return this.notificationCapabilityError("post_failed", error);
    }
  }

  private async cancelNotificationCapability(records: Record<string, Object>): Promise<Record<string, Object>> {
    const notificationId = Number(records["notificationId"] ?? records["id"] ?? 0);
    if (notificationId <= 0) {
      return { "status": "error", "code": "missing_notification_id" };
    }
    try {
      await notificationManager.cancel(notificationId);
      return { "status": "ok", "code": "cancelled", "notificationId": notificationId };
    } catch (error) {
      return this.notificationCapabilityError("cancel_failed", error);
    }
  }

  private async ensureNotificationSlot(): Promise<void> {
    await notificationManager.addSlot(notificationManager.SlotType.SOCIAL_COMMUNICATION);
  }

  private notificationCapabilityError(code: string, error: Object): Record<string, Object> {
    const businessError = error as BusinessError;
    return {
      "status": "error",
      "code": code,
      "platformCode": businessError.code ?? -1,
      "message": businessError.message ?? `${error}`
    };
  }

  private stringRecordValue(records: Record<string, Object>, key: string, fallback: string): string {
    const value = records[key];
    if (typeof value === "string" && value.trim().length > 0) {
      return value;
    }
    return fallback;
  }

  private stableNotificationId(key: string): number {
    let hash = 0;
    for (let index = 0; index < key.length; index++) {
      hash = ((hash << 5) - hash + key.charCodeAt(index)) | 0;
    }
    const positive = Math.abs(hash);
    return Math.max(1, positive % 2147483647);
  }

  private async threadAttachmentUpload(params: KRAny, callback: KuiklyRenderCallback) {
    try {
      const records = this.parseRecord(params);
      const channelId = (records["channelId"] as string | undefined) ?? "";
      const imageOnlyValue = records["imageOnly"];
      const imageOnly = imageOnlyValue === true || imageOnlyValue === "true";
      const captureValue = records["capture"];
      const capture = captureValue === true || captureValue === "true";
      const compressImagesValue = records["compressImages"];
      const compressImages = compressImagesValue === true || compressImagesValue === "true";
      SlockOhosDiagnostics.record(
        "ThreadAttachmentUpload",
        `start imageOnly=${imageOnly} capture=${capture} compressImages=${compressImages} ` +
          `channelIdPresent=${channelId.length > 0}`
      );

      if (channelId.length === 0) {
        callback(this.threadAttachmentError(400, "missing_channel_id", "Missing channel id"));
        return;
      }

      const uris = await this.pickThreadAttachmentUris(imageOnly, capture).catch((error: BusinessError) => {
        SlockOhosDiagnostics.record(
          "ThreadAttachmentUpload",
          `picker_failed code=${error.code ?? -1} message=${error.message ?? ""}`
        );
        throw new Error(error.message ?? "Attachment picker unavailable");
      });
      SlockOhosDiagnostics.record("ThreadAttachmentUpload", `picker_result count=${uris.length}`);
      if (uris.length === 0) {
        callback({ "code": 204, "bodyText": "" });
        return;
      }

      const readResult = await this.readPickedThreadAttachments(uris, imageOnly, compressImages);
      const files = readResult.files;
      const errors = readResult.errors;
      const byteCount = files.reduce((sum: number, file: OhosPickedThreadAttachment) => sum + file.sizeBytes, 0);
      SlockOhosDiagnostics.record(
        "ThreadAttachmentUpload",
        `picked_file_refs count=${files.length} errors=${errors.length} bytes=${byteCount}`
      );
      if (files.length === 0) {
        const compressionFailed = errors.some(
          (entry: Record<string, Object>) => entry["errorCode"] === SLOCK_THREAD_ATTACHMENT_IMAGE_COMPRESSION_ERROR
        );
        callback(this.threadAttachmentError(
          400,
          compressionFailed ? SLOCK_THREAD_ATTACHMENT_IMAGE_COMPRESSION_ERROR : "attachment_read_failed",
          compressionFailed ? "Unable to compress image attachment" : "No readable attachments"
        ));
        return;
      }

      callback(this.threadPickedAttachmentFiles(files, capture, errors));
    } catch (error) {
      const businessError = error as BusinessError;
      const message = businessError.message ?? `${error}`;
      const pickerUnavailable = message === "Attachment picker unavailable";
      SlockOhosDiagnostics.record(
        "ThreadAttachmentUpload",
        `failed code=${businessError.code ?? -1} picker=${pickerUnavailable} message=${message}`
      );
      callback(this.threadAttachmentError(
        0,
        pickerUnavailable
          ? "attachment_picker_unavailable"
          : error instanceof SlockThreadAttachmentImageCompressionError
            ? SLOCK_THREAD_ATTACHMENT_IMAGE_COMPRESSION_ERROR
            : "attachment_upload_failed",
        message
      ));
    }
  }

  private async compressThreadImage(params: KRAny, callback: KuiklyRenderCallback): Promise<void> {
    // Reserve the operation before the first suspension.  The shared bridge installs its
    // cancellation hook immediately after invoking native; if the coroutine is cancelled in
    // that tiny window, a pre-suspension reservation lets cancelCompressThreadImage see and fence
    // this request instead of allowing a stale worker/output to escape.
    let operation: SlockThreadImageCompressionOperation | undefined = undefined;
    let preflightTerminal = false;
    const failPreflight = (): void => {
      if (preflightTerminal || this.threadImageCompressionDestroyed) return;
      preflightTerminal = true;
      try {
        callback(this.threadImageCompressionFailure());
      } catch (_) {
        // Renderer teardown/callback removal must not turn a terminal native response into an
        // uncaught exception on the bridge dispatch turn.
      }
    };
    try {
      const records = this.parseRecord(params);
      const requestId = this.threadImageCompressionString(records["requestId"]);
      const fileRef = this.threadImageCompressionString(records["fileRef"]);
      const fileName = this.threadImageCompressionString(records["fileName"]);
      const mimeType = this.threadImageCompressionString(records["mimeType"])
        .split(';')[0].trim().toLowerCase();
      if (this.threadImageCompressionDestroyed || !this.isValidThreadImageCompressionRequestId(requestId) ||
        !this.isValidThreadImageCompressionFileName(fileName) || !mimeType.startsWith('image/') ||
        !this.isValidThreadImageCompressionFileRef(fileRef)) {
        failPreflight();
        return;
      }
      if (this.threadImageCompressionOperations.has(requestId)) {
        failPreflight();
        return;
      }
      const context = getContext(this) as common.UIAbilityContext;
      const outputDirectory = `${context.cacheDir}/${SLOCK_THREAD_IMAGE_COMPRESSION_CACHE_DIR_NAME}`;
      // UUID-backed names avoid collisions across remounted renderers and make every output an
      // operation-owned cache object.  Encoding first targets the sibling .part; a same-directory
      // rename publishes the final .jpg atomically.
      const outputPath = `${outputDirectory}/composer-image-${util.generateRandomUUID()}.jpg`;
      operation = new SlockThreadImageCompressionOperation(requestId, callback, outputPath);
      this.threadImageCompressionOperations.set(requestId, operation);
      const ownedOperation = operation;
      // Yield only after admission.  No filesystem/image work occurs before this point; the
      // worker below owns all blocking/decoder operations.
      await Promise.resolve();
      if (this.threadImageCompressionDestroyed || ownedOperation.isCancelled() ||
        this.threadImageCompressionOperations.get(requestId) !== ownedOperation) {
        ownedOperation.removeOutputs().catch((): void => {
        });
        if (this.threadImageCompressionOperations.get(requestId) === ownedOperation) {
          this.threadImageCompressionOperations.delete(requestId);
        }
        return;
      }
      this.runThreadImageCompression(ownedOperation, fileRef, fileName, mimeType).catch((): void => {
        // runThreadImageCompression owns normal terminal responses.  If an unexpected setup or
        // release rejection escapes it, route one guarded failure through the same operation gate
        // instead of racing a second callback from the dispatcher.
        if (this.threadImageCompressionOperations.get(requestId) === ownedOperation) {
          this.deliverThreadImageCompression(ownedOperation, this.threadImageCompressionFailure());
          this.threadImageCompressionOperations.delete(requestId);
        }
        if (ownedOperation.state !== SlockThreadImageCompressionOperationState.DELIVERED ||
          this.threadImageCompressionDestroyed) {
          ownedOperation.removeOutputs().catch((): void => {
          });
        }
      });
    } catch (_) {
      if (operation === undefined) {
        failPreflight();
      } else if (this.threadImageCompressionOperations.get(operation.requestId) === operation) {
        this.deliverThreadImageCompression(operation, this.threadImageCompressionFailure());
        this.threadImageCompressionOperations.delete(operation.requestId);
        operation.removeOutputs().catch((): void => {
        });
      }
    }
  }

  private cancelCompressThreadImage(params: KRAny): void {
    let records: Record<string, Object>;
    try {
      records = this.parseRecord(params);
    } catch (_) {
      return;
    }
    const requestId = this.threadImageCompressionString(records["requestId"]);
    if (requestId.length === 0) return;
    const operation = this.threadImageCompressionOperations.get(requestId);
    if (operation === undefined) return;
    // Ordinary caller cancellation may stop work that is still ACTIVE/PENDING.  Once the
    // callback has been delivered, the consumer owns the generated JPEG; do not revoke that
    // terminal operation or delete its output here.  Module destruction is the only force-cancel
    // path (see onDestroy), where no consumer can adopt the result.  The JS event loop serializes
    // this transition with delivery.
    if (operation.cancel()) {
      operation.removeOutputs().catch((): void => {
      });
      if (this.threadImageCompressionOperations.get(requestId) === operation) {
        this.threadImageCompressionOperations.delete(requestId);
      }
    }
  }

  private async runThreadImageCompression(
    operation: SlockThreadImageCompressionOperation,
    fileRef: string,
    fileName: string,
    mimeType: string
  ): Promise<void> {
    let deliveredSuccess = false;
    let sourceFile: fs.File | undefined = undefined;
    try {
      this.checkThreadImageCompressionCancelled(operation);
      const context = getContext(this) as common.UIAbilityContext;
      const sourcePath = await this.resolveThreadImageCompressionSourcePath(
        fileRef,
        [context.cacheDir, context.tempDir],
        (): void => this.checkThreadImageCompressionCancelled(operation)
      );
      if (sourcePath.length === 0 || !(await this.isThreadImageCompressionInput(
        fileName,
        mimeType,
        sourcePath,
        (): void => this.checkThreadImageCompressionCancelled(operation)
      ))) {
        throw new SlockThreadAttachmentImageCompressionError('Unable to decode image attachment');
      }
      this.checkThreadImageCompressionCancelled(operation);
      sourceFile = await fs.open(sourcePath, fs.OpenMode.READ_ONLY);
      this.checkThreadImageCompressionCancelled(operation);
      const sourceStat = await fs.stat(sourceFile.fd);
      this.checkThreadImageCompressionCancelled(operation);
      if (!sourceStat.isFile() || sourceStat.size <= 0) {
        throw new SlockThreadAttachmentImageCompressionError('Unable to decode image attachment');
      }
      const cached = await this.cacheCompressedPickedThreadImage(
        sourceFile.fd,
        operation.outputPath,
        fileName,
        (): boolean => operation.isCancelled()
      );
      this.checkThreadImageCompressionCancelled(operation);
      const file: Record<string, Object> = {
        "fileRef": cached.fileRef,
        "fileName": this.threadImageCompressionOutputName(fileName),
        "mimeType": "image/jpeg",
        "previewUri": cached.previewUri,
        "sizeBytes": cached.sizeBytes,
        "source": SLOCK_THREAD_IMAGE_COMPRESSION_SOURCE
      };
      const payload: Record<string, Object> = {
        "code": 200,
        "bodyText": JSON.stringify({ "files": [file] })
      };
      deliveredSuccess = this.deliverThreadImageCompression(operation, payload);
    } catch (error) {
      if (!(error instanceof SlockThreadImageCompressionCancelledError)) {
        this.deliverThreadImageCompression(operation, this.threadImageCompressionFailure());
      }
    } finally {
      if (sourceFile !== undefined) {
        try { await fs.close(sourceFile); } catch (_) {
        }
      }
      if (!deliveredSuccess || operation.isCancelled()) {
        await operation.removeOutputs();
      }
      if (this.threadImageCompressionOperations.get(operation.requestId) === operation) {
        this.threadImageCompressionOperations.delete(operation.requestId);
      }
    }
  }

  private deliverThreadImageCompression(
    operation: SlockThreadImageCompressionOperation,
    payload: Record<string, Object>
  ): boolean {
    if (this.threadImageCompressionDestroyed ||
      this.threadImageCompressionOperations.get(operation.requestId) !== operation ||
      operation.state !== SlockThreadImageCompressionOperationState.ACTIVE) {
      return false;
    }
    if (!operation.markPending() || !operation.markDelivered()) return false;
    if (this.threadImageCompressionDestroyed) {
      operation.cancel();
      operation.removeOutputs().catch((): void => {
      });
      return false;
    }
    try {
      operation.callback(payload);
    } catch (_) {
      // Callback removal/page teardown is a renderer concern; the operation remains terminal.
      operation.removeOutputs().catch((): void => {
      });
    }
    return true;
  }

  private checkThreadImageCompressionCancelled(operation: SlockThreadImageCompressionOperation): void {
    if (this.threadImageCompressionDestroyed || operation.isCancelled() ||
      this.threadImageCompressionOperations.get(operation.requestId) !== operation) {
      throw new SlockThreadImageCompressionCancelledError();
    }
  }

  private threadImageCompressionFailure(): Record<string, Object> {
    return {
      "code": 400,
      "errorCode": SLOCK_THREAD_ATTACHMENT_IMAGE_COMPRESSION_ERROR,
      "errorBodyText": "Unable to compress image attachment"
    };
  }

  private threadImageCompressionString(value: Object | undefined): string {
    return typeof value === 'string' ? value.trim() : '';
  }

  private hasThreadImageCompressionControlCharacter(value: string): boolean {
    for (let index = 0; index < value.length; index += 1) {
      const code = value.charCodeAt(index);
      if (code < 0x20 || code === 0x7f) return true;
    }
    return false;
  }

  private isValidThreadImageCompressionRequestId(value: string): boolean {
    return value.length > 0 && value.length <= SLOCK_THREAD_IMAGE_COMPRESSION_MAX_REQUEST_ID_LENGTH &&
      !this.hasThreadImageCompressionControlCharacter(value);
  }

  private isValidThreadImageCompressionFileName(value: string): boolean {
    return value.length > 0 && value.length <= SLOCK_THREAD_IMAGE_COMPRESSION_MAX_FILE_NAME_LENGTH &&
      !value.includes('/') && !value.includes('\\') && !value.includes('..') &&
      !this.hasThreadImageCompressionControlCharacter(value);
  }

  private isValidThreadImageCompressionFileRef(value: string): boolean {
    if (value.length === 0 || this.hasThreadImageCompressionControlCharacter(value) ||
      value.includes('?') || value.includes('#')) return false;
    return value.startsWith('/') || value.toLowerCase().startsWith('file://');
  }

  private async resolveThreadImageCompressionSourcePath(
    fileRef: string,
    roots: Array<string>,
    checkCancelled: () => void = (): void => {
    }
  ): Promise<string> {
    let sourcePath = '';
    const context = getContext(this) as common.UIAbilityContext;
    if (!isSlockOhosThreadComposerTemporaryFileRefAuthorityAllowed(
      fileRef,
      context.abilityInfo.bundleName,
    )) {
      return '';
    }
    try {
      sourcePath = fileRef.startsWith('/') ? fileRef : new fileUri.FileUri(fileRef).path;
    } catch (_) {
      return '';
    }
    const normalizedSource = this.normalizeOhosTemporaryPath(sourcePath);
    if (normalizedSource.length === 0) return '';
    for (let index = 0; index < roots.length; index += 1) {
      const root = this.normalizeOhosTemporaryPath(roots[index]);
      if (await this.isOhosCacheOwnedRegularFileAsync(root, normalizedSource, checkCancelled)) {
        checkCancelled();
        try {
          const sourceStat = await fs.stat(normalizedSource);
          checkCancelled();
          if (sourceStat.isFile() && sourceStat.size > 0) return normalizedSource;
        } catch (error) {
          if (error instanceof SlockThreadImageCompressionCancelledError) throw error;
          return '';
        }
      }
    }
    return '';
  }

  private async isOhosCacheOwnedRegularFileAsync(
    cacheRoot: string,
    targetPath: string,
    checkCancelled: () => void = (): void => {
    }
  ): Promise<boolean> {
    if (cacheRoot.length === 0 || targetPath.length === 0 ||
      !targetPath.startsWith(`${cacheRoot}/`)) return false;
    try {
      const rootStat = await fs.lstat(cacheRoot);
      checkCancelled();
      if (!rootStat.isDirectory() || rootStat.isSymbolicLink()) return false;
      const relativeSegments = targetPath.substring(cacheRoot.length + 1).split('/');
      let candidate = cacheRoot;
      for (let index = 0; index < relativeSegments.length; index += 1) {
        const segment = relativeSegments[index];
        if (segment.length === 0) return false;
        candidate = `${candidate}/${segment}`;
        const stat = await fs.lstat(candidate);
        checkCancelled();
        const isLast = index === relativeSegments.length - 1;
        if (stat.isSymbolicLink() || (isLast ? !stat.isFile() : !stat.isDirectory())) return false;
      }
      return relativeSegments.length > 0;
    } catch (error) {
      if (error instanceof SlockThreadImageCompressionCancelledError) throw error;
      return false;
    }
  }

  private async isThreadImageCompressionInput(
    fileName: string,
    mimeType: string,
    sourcePath: string,
    checkCancelled: () => void = (): void => {
    }
  ): Promise<boolean> {
    const normalizedMimeType = mimeType.split(';')[0].trim().toLowerCase();
    if (!normalizedMimeType.startsWith('image/')) return false;
    const extension = fileName.split('.').pop()?.toLowerCase() ?? '';
    if (['3gp', 'avi', 'flv', 'm4v', 'mkv', 'mov', 'mp4', 'mpeg', 'mpg', 'webm', 'wmv'].includes(extension)) {
      return false;
    }
    // The display name can disagree with the provider's real path.  Reject a known movie
    // extension from the admitted source path as well, before ImageKit opens the bytes.
    const normalizedSourcePath = sourcePath.toLowerCase().split('?')[0].split('#')[0];
    if (/\.(3gp|avi|flv|m4v|mkv|mov|mp4|mpeg|mpg|webm|wmv)$/.test(normalizedSourcePath)) {
      return false;
    }
    try {
      const sourceStat = await fs.stat(sourcePath);
      checkCancelled();
      return sourceStat.isFile() && sourceStat.size > 0;
    } catch (error) {
      if (error instanceof SlockThreadImageCompressionCancelledError) throw error;
      return false;
    }
  }

  private threadImageCompressionOutputName(fileName: string): string {
    const leaf = fileName.split('/').pop()?.split('\\').pop() ?? 'image';
    const stem = leaf.replace(/\.[^.]*$/, '') || 'image';
    let safeStem = '';
    for (let index = 0; index < stem.length; index += 1) {
      const character = stem.charAt(index);
      const allowed = (character >= 'a' && character <= 'z') ||
        (character >= 'A' && character <= 'Z') ||
        (character >= '0' && character <= '9') ||
        character === '.' || character === '-' || character === '_' || character === ' ';
      safeStem += allowed ? character : '_';
    }
    safeStem = safeStem.trim().slice(0, SLOCK_THREAD_IMAGE_COMPRESSION_MAX_FILE_NAME_LENGTH - 4) || 'image';
    return `${safeStem}.jpg`;
  }

  private async pickThreadAttachmentUris(imageOnly: boolean, capture: boolean): Promise<Array<string>> {
    if (capture) {
      const profile: cameraPicker.PickerProfile = {
        cameraPosition: camera.CameraPosition.CAMERA_POSITION_BACK
      };
      const result = await cameraPicker.pick(
        getContext(this) as common.UIAbilityContext,
        [cameraPicker.PickerMediaType.PHOTO],
        profile
      );
      return result.resultCode === 0 && result.resultUri.length > 0 ? [result.resultUri] : [];
    }
    if (imageOnly) {
      const options = new photoAccessHelper.PhotoSelectOptions();
      options.MIMEType = photoAccessHelper.PhotoViewMIMETypes.IMAGE_TYPE;
      options.maxSelectNumber = 9;
      const result = await new photoAccessHelper.PhotoViewPicker().select(options);
      return result.photoUris ?? [];
    }

    const options = new picker.DocumentSelectOptions();
    options.maxSelectNumber = 9;
    return await new picker.DocumentViewPicker(getContext(this) as common.UIAbilityContext).select(options);
  }

  private async readPickedThreadAttachments(
    uris: Array<string>,
    imageOnly: boolean,
    compressImages: boolean
  ): Promise<OhosPickedThreadAttachmentReadResult> {
    const files: Array<OhosPickedThreadAttachment> = [];
    const errors: Array<Record<string, Object>> = [];
    const context = getContext(this) as common.UIAbilityContext;
    const cacheDir = `${context.cacheDir}/slock-attachments`;
    if (compressImages) {
      await this.ensureThreadImageCompressionCacheDirectory(cacheDir);
    } else {
      ensureSlockOhosAttachmentCacheDirectory(cacheDir);
    }
    this.cleanupAttachmentCache(cacheDir);
    const pickedAt = Date.now();
    for (let index = 0; index < uris.length; index++) {
      const uri = uris[index];
      try {
        const file = await fs.open(uri, fs.OpenMode.READ_ONLY);
        try {
          const stat = await fs.stat(file.fd);
          const sourceFileName = this.fileNameFromUri(uri, index, imageOnly);
          const sourceMimeType = this.mimeTypeFromName(uri, imageOnly);
          const fileName = normalizeSlockOhosImagePickerFileName(
            sourceFileName,
            sourceMimeType,
            imageOnly,
          );
          const shouldCompress = compressImages && this.isOhosImageAttachment(uri, sourceMimeType, imageOnly);
          const cached = shouldCompress
            ? await this.cacheCompressedPickedThreadImage(
                file.fd,
                `${cacheDir}/${this.safeAttachmentCacheFileName(
                  `picked-${pickedAt}-${index}`,
                  this.compressedImageFileName(fileName),
                  'image/jpeg'
                )}`,
                fileName
              )
            : cacheSlockOhosPickedAttachment(
                file.fd,
                `${cacheDir}/${this.safeAttachmentCacheFileName(
                  `picked-${pickedAt}-${index}`,
                  fileName,
                  sourceMimeType
                )}`,
                fileName,
                sourceMimeType,
                stat.size
              );
          files.push({
            fileName: shouldCompress ? this.compressedImageFileName(fileName) : cached.fileName,
            mimeType: shouldCompress ? 'image/jpeg' : cached.mimeType,
            previewUri: cached.previewUri,
            fileRef: cached.fileRef,
            sizeBytes: cached.sizeBytes
          });
        } finally {
          try { await fs.close(file); } catch (_) {
          }
        }
      } catch (error) {
        const compressionFailed = error instanceof SlockThreadAttachmentImageCompressionError;
        errors.push({
          "index": index,
          "errorCode": compressionFailed ? SLOCK_THREAD_ATTACHMENT_IMAGE_COMPRESSION_ERROR : "attachment_read_failed",
          "message": (error as Error).message ?? `${error}`
        });
      }
    }
    return { files: files, errors: errors };
  }

  private threadPickedAttachmentFiles(
    files: Array<OhosPickedThreadAttachment>,
    capture: boolean,
    errors: Array<Record<string, Object>> = []
  ): Record<string, Object> {
    const compressionFailed = errors.some(
      (entry: Record<string, Object>) => entry["errorCode"] === SLOCK_THREAD_ATTACHMENT_IMAGE_COMPRESSION_ERROR
    );
    const result: Record<string, Object> = {
      "code": 200,
      "bodyText": "",
      "files": files.map((file: OhosPickedThreadAttachment, index: number): OhosPickedThreadAttachmentPayload => {
        return {
          "id": `picked-${Date.now()}-${index}`,
          "fileName": file.fileName,
          "mimeType": file.mimeType,
          "sizeBytes": file.sizeBytes,
          "previewUri": file.previewUri,
          "fileRef": file.fileRef,
          "source": capture ? "platform_camera" : "platform_picker"
        };
      })
    };
    if (errors.length > 0) {
      result["code"] = 206;
      result["errors"] = errors;
      result["skippedCount"] = errors.length;
      result["errorCode"] = compressionFailed
        ? SLOCK_THREAD_ATTACHMENT_IMAGE_COMPRESSION_ERROR
        : "attachment_read_partial";
      result["errorBodyText"] = `${errors.length} attachment(s) could not be read`;
    }
    return result;
  }

  private compressedImageFileName(fileName: string): string {
    const leaf = fileName.split('/').pop()?.split('\\').pop() ?? 'image';
    const stem = leaf.replace(/\.[^.]*$/, '') || 'image';
    return `${stem}.jpg`;
  }

  private isOhosImageAttachment(uri: string, mimeType: string, imageOnly: boolean): boolean {
    const normalizedMimeType = mimeType.split(';')[0].trim().toLowerCase();
    if (normalizedMimeType.startsWith('video/')) return false;
    const lowerUri = uri.toLowerCase().split('?')[0];
    // Providers occasionally report application/octet-stream for a picked movie.  Reject the
    // extension before accepting a generic image-only MIME so a video cannot enter ImageKit.
    if (/\.(3gp|avi|flv|m4v|mkv|mov|mp4|mpeg|mpg|webm|wmv)$/.test(lowerUri)) return false;
    if (normalizedMimeType.startsWith('image/')) return true;
    if (/\.(avif|bmp|gif|heic|heif|jpe?g|png|webp|tiff?)$/.test(lowerUri)) return true;
    return imageOnly && normalizedMimeType === 'application/octet-stream';
  }

  private async cacheCompressedPickedThreadImage(
    sourceFd: number,
    targetPath: string,
    sourceFileName: string,
    isCancelled: () => boolean = (): boolean => false
  ): Promise<SlockOhosCachedPickedAttachmentLike> {
    let imageSource: image.ImageSource | undefined = undefined;
    let pixelMap: image.PixelMap | undefined = undefined;
    let packer: image.ImagePacker | undefined = undefined;
    let outputFile: fs.File | undefined = undefined;
    let complete = false;
    const partPath = `${targetPath}.part`;
    const checkCancelled = (): void => {
      if (isCancelled()) {
        throw new SlockThreadImageCompressionCancelledError();
      }
    };
    try {
      checkCancelled();
      imageSource = image.createImageSource(sourceFd);
      if (imageSource === undefined) {
        throw new SlockThreadAttachmentImageCompressionError('Unable to decode image attachment');
      }
      const imageInfo = await imageSource.getImageInfo();
      checkCancelled();
      const sourceWidth = imageInfo.size.width;
      const sourceHeight = imageInfo.size.height;
      if (sourceWidth <= 0 || sourceHeight <= 0) {
        throw new SlockThreadAttachmentImageCompressionError('Unable to decode image attachment');
      }
      const scale = Math.min(
        1,
        SLOCK_THREAD_ATTACHMENT_IMAGE_MAX_EDGE / Math.max(sourceWidth, sourceHeight)
      );
      const desiredSize: image.Size = {
        width: Math.max(1, Math.round(sourceWidth * scale)),
        height: Math.max(1, Math.round(sourceHeight * scale))
      };
      pixelMap = await imageSource.createPixelMap({ desiredSize: desiredSize });
      checkCancelled();
      if (pixelMap === undefined) {
        throw new SlockThreadAttachmentImageCompressionError('Unable to decode image attachment');
      }
      await this.applyOhosImageOrientation(imageSource, pixelMap, checkCancelled);
      checkCancelled();
      await this.ensureThreadImageCompressionCacheDirectory(
        targetPath.substring(0, targetPath.lastIndexOf('/'))
      );
      // Never open the final path for encoding.  A sibling part file remains invisible to
      // consumers until the same-directory rename below commits a complete JPEG.
      try { await fs.unlink(partPath); } catch (_) {
      }
      checkCancelled();
      outputFile = await fs.open(
        partPath,
        fs.OpenMode.CREATE | fs.OpenMode.TRUNC | fs.OpenMode.WRITE_ONLY
      );
      checkCancelled();
      packer = image.createImagePacker();
      await packer.packToFile(
        pixelMap,
        outputFile.fd,
        {
          format: 'image/jpeg',
          quality: SLOCK_THREAD_ATTACHMENT_IMAGE_JPEG_QUALITY
        }
      );
      checkCancelled();
      await fs.close(outputFile);
      outputFile = undefined;
      const partStat = await fs.stat(partPath);
      checkCancelled();
      if (!partStat.isFile() || partStat.size <= 0) {
        throw new SlockThreadAttachmentImageCompressionError('Unable to encode image attachment');
      }
      checkCancelled();
      // Both paths are in the app cache directory, so same-directory rename is an atomic
      // publication boundary on OHOS file systems.  No reader can observe a partially encoded
      // final file.
      await fs.rename(partPath, targetPath);
      checkCancelled();
      const outputStat = await fs.stat(targetPath);
      checkCancelled();
      if (!outputStat.isFile() || outputStat.size <= 0) {
        throw new SlockThreadAttachmentImageCompressionError('Unable to encode image attachment');
      }
      const cachedFileRef = fileUri.getUriFromPath(targetPath);
      const cachedPreviewUri = `file://${targetPath}`;
      if (!cachedFileRef.startsWith('file://')) {
        throw new SlockThreadAttachmentImageCompressionError('Attachment cache URI is unavailable');
      }
      complete = true;
      return {
        fileName: this.compressedImageFileName(sourceFileName),
        mimeType: 'image/jpeg',
        previewUri: cachedPreviewUri,
        fileRef: cachedFileRef,
        sizeBytes: outputStat.size
      };
    } catch (error) {
      if (error instanceof SlockThreadImageCompressionCancelledError) throw error;
      if (error instanceof SlockThreadAttachmentImageCompressionError) throw error;
      throw new SlockThreadAttachmentImageCompressionError(
        (error as Error).message ?? 'Unable to compress image attachment'
      );
    } finally {
      if (outputFile !== undefined) {
        try { await fs.close(outputFile); } catch (_) {
        }
      }
      if (packer !== undefined) {
        try { await packer.release(); } catch (_) {
        }
      }
      if (pixelMap !== undefined) {
        try { await pixelMap.release(); } catch (_) {
        }
      }
      if (imageSource !== undefined) {
        try { await imageSource.release(); } catch (_) {
        }
      }
      if (!complete || isCancelled()) {
        try { await fs.unlink(partPath); } catch (_) {
        }
        try { await fs.unlink(targetPath); } catch (_) {
        }
      }
    }
  }

  private async ensureThreadImageCompressionCacheDirectory(path: string): Promise<void> {
    if (path.length === 0 || !path.startsWith('/')) {
      throw new SlockThreadAttachmentImageCompressionError('Attachment cache unavailable');
    }
    try {
      const existing = await fs.lstat(path);
      if (existing.isDirectory() && !existing.isSymbolicLink()) return;
      throw new SlockThreadAttachmentImageCompressionError('Attachment cache unavailable');
    } catch (error) {
      // A missing directory is the normal path.  Recursive async mkdir is safe to race with
      // another request; after an EEXIST-style rejection, re-stat and accept only a real,
      // non-symlink directory.
      try {
        await fs.mkdir(path, true);
        return;
      } catch (mkdirError) {
        try {
          const after = await fs.lstat(path);
          if (after.isDirectory() && !after.isSymbolicLink()) return;
        } catch (_) {
        }
        if (error instanceof SlockThreadAttachmentImageCompressionError) throw error;
        // ArkTS does not allow throwing an arbitrary catch value.  Preserve the
        // fail-closed compression contract with a typed domain error instead of
        // leaking a platform-specific mkdir rejection across the bridge.
        throw new SlockThreadAttachmentImageCompressionError('Attachment cache unavailable');
      }
    }
  }

  private async applyOhosImageOrientation(
    imageSource: image.ImageSource,
    pixelMap: image.PixelMap,
    checkCancelled: () => void = (): void => {
    }
  ): Promise<void> {
    let orientation = '';
    try {
      orientation = (await imageSource.getImageProperty(image.PropertyKey.ORIENTATION)).toString();
    } catch (_) {
      return;
    }
    checkCancelled();
    switch (orientation) {
      case '2':
      case 'Top-right':
        await pixelMap.flip(true, false);
        checkCancelled();
        break;
      case '3':
      case 'Bottom-right':
        await pixelMap.rotate(180);
        checkCancelled();
        break;
      case '4':
      case 'Bottom-left':
        await pixelMap.flip(false, true);
        checkCancelled();
        break;
      case '5':
      case 'Left-top':
        await pixelMap.flip(true, false);
        checkCancelled();
        await pixelMap.rotate(270);
        checkCancelled();
        break;
      case '6':
      case 'Right-top':
        await pixelMap.rotate(90);
        checkCancelled();
        break;
      case '7':
      case 'Right-bottom':
        await pixelMap.flip(true, false);
        checkCancelled();
        await pixelMap.rotate(90);
        checkCancelled();
        break;
      case '8':
      case 'Left-bottom':
        await pixelMap.rotate(270);
        checkCancelled();
        break;
      default:
        break;
    }
  }

  private fileNameFromUri(uri: string, index: number, imageOnly: boolean): string {
    const raw = uri.split("/").pop() ?? "";
    const withoutQuery = raw.split("?")[0];
    const fallback = imageOnly ? `image-${index + 1}.jpg` : `attachment-${index + 1}`;
    if (withoutQuery.length === 0) {
      return fallback;
    }
    try {
      return decodeURIComponent(withoutQuery);
    } catch (_error) {
      return withoutQuery;
    }
  }

  private mimeTypeFromName(uri: string, imageOnly: boolean): string {
    const lower = uri.toLowerCase();
    if (lower.endsWith(".png")) return "image/png";
    if (lower.endsWith(".gif")) return "image/gif";
    if (lower.endsWith(".webp")) return "image/webp";
    if (lower.endsWith(".jpg") || lower.endsWith(".jpeg") || imageOnly) return "image/jpeg";
    if (lower.endsWith(".mp4")) return "video/mp4";
    if (lower.endsWith(".mov")) return "video/quicktime";
    if (lower.endsWith(".m4v")) return "video/x-m4v";
    if (lower.endsWith(".webm")) return "video/webm";
    if (lower.endsWith(".pdf")) return "application/pdf";
    if (lower.endsWith(".txt")) return "text/plain";
    return "application/octet-stream";
  }

  private threadAttachmentError(code: number, errorCode: string, message: string): Record<string, Object> {
    return {
      "code": code,
      "bodyText": "",
      "errorCode": errorCode,
      "errorBodyText": message
    };
  }

  private safeAttachmentCacheFileName(attachmentId: string, fileName: string, mimeType?: string): string {
    const safeId = this.safeAttachmentPathSegment(attachmentId).slice(0, 36) || "attachment";
    const rawName = fileName.split("/").pop()?.split("\\").pop() ?? "attachment";
    const extension = rawName.indexOf(".") < 0 ? this.extensionForMimeType(mimeType) : "";
    const safeName = this.safeAttachmentPathSegment(`${rawName}${extension}`).slice(0, 120) || "attachment";
    return `${safeId}-${safeName}`;
  }

  private privateAttachmentPath(
    directory: string,
    ownerKey: string,
    attachmentId: string,
    fileName: string,
    mimeType?: string
  ): string {
    return `${directory}/${slockOhosPrivateAttachmentFileName(
      ownerKey,
      attachmentId,
      fileName,
      this.extensionForMimeType(mimeType)
    )}`;
  }

  private extensionForMimeType(mimeType?: string): string {
    switch ((mimeType ?? "").toLowerCase()) {
      case "application/pdf":
        return ".pdf";
      case "text/plain":
        return ".txt";
      case "image/png":
        return ".png";
      case "image/jpeg":
      case "image/jpg":
        return ".jpg";
      case "image/gif":
        return ".gif";
      case "image/webp":
        return ".webp";
      case "application/json":
        return ".json";
      case "application/zip":
        return ".zip";
      case "application/vnd.android.package-archive":
        return ".apk";
      default:
        return "";
    }
  }

  private isUsableAttachmentCacheFile(path: string): boolean {
    try {
      const stat = fs.statSync(path);
      const ageMs = Date.now() - (stat.mtime * 1000);
      return stat.isFile() && stat.size > 0 && ageMs >= 0 && ageMs <= KRBridgeModule.ATTACHMENT_CACHE_MAX_AGE_MS;
    } catch (_) {
      return false;
    }
  }

  private isUsablePersistentAttachmentFile(path: string): boolean {
    try {
      const stat = fs.statSync(path);
      return stat.isFile() && stat.size > 0;
    } catch (_) {
      return false;
    }
  }

  private cleanupAttachmentCache(cacheDir: string) {
    try {
      const entries: Array<OhosAttachmentCacheEntry> = [];
      fs.listFileSync(cacheDir).forEach((name: string) => {
        try {
          const path = `${cacheDir}/${name}`;
          const stat = fs.statSync(path);
          if (stat.isFile() && !name.includes('.part')) {
            entries.push({ path: path, mtime: stat.mtime });
          }
        } catch (_) {
        }
      });
      entries.sort((left: OhosAttachmentCacheEntry, right: OhosAttachmentCacheEntry) => right.mtime - left.mtime);
      entries.slice(KRBridgeModule.ATTACHMENT_CACHE_MAX_FILES).forEach((entry: OhosAttachmentCacheEntry) => {
        this.safeUnlink(entry.path);
      });
    } catch (_) {
    }
  }

  private safeUnlink(path: string) {
    try {
      fs.unlinkSync(path);
    } catch (_) {
    }
  }

  private normalizeOhosTemporaryPath(value: string): string {
    const normalizedSeparators = value.replace(/\\/g, '/');
    if (!normalizedSeparators.startsWith('/')) return '';
    const segments: Array<string> = [];
    const rawSegments = normalizedSeparators.split('/');
    for (let index = 0; index < rawSegments.length; index += 1) {
      const segment = rawSegments[index];
      if (segment.length === 0 || segment === '.') continue;
      if (segment === '..') return '';
      segments.push(segment);
    }
    return `/${segments.join('/')}`.replace(/\/$/, '');
  }

  private safeAttachmentPathSegment(value: string): string {
    let result = "";
    for (let index = 0; index < value.length; index += 1) {
      const character = value.charAt(index);
      if (
        (character >= "a" && character <= "z") ||
        (character >= "A" && character <= "Z") ||
        (character >= "0" && character <= "9") ||
        character === "." ||
        character === "-" ||
        character === "_" ||
        character === " "
      ) {
        result += character;
      } else {
        result += "_";
      }
    }
    return result.trim();
  }

  private httpResultToArrayBuffer(result: string | ArrayBuffer | Object): ArrayBuffer {
    if (result instanceof ArrayBuffer) {
      return result;
    }
    const text = typeof result === "string" ? result : JSON.stringify(result);
    const encoder = new util.TextEncoder();
    return encoder.encodeInto(text).buffer as ArrayBuffer;
  }

  private httpResultToString(result: string | ArrayBuffer | Object): string {
    if (typeof result === 'string') {
      return result;
    }
    if (result instanceof ArrayBuffer) {
      const textDecoder = new util.TextDecoder('utf-8');
      return textDecoder.decodeWithStream(new Uint8Array(result));
    }
    return JSON.stringify(result);
  }

  private safeUrlForDiagnostics(rawUrl: string): string {
    if (rawUrl.length === 0) {
      return "";
    }
    const queryIndex = rawUrl.indexOf("?");
    const withoutQuery = queryIndex >= 0 ? rawUrl.slice(0, queryIndex) : rawUrl;
    return withoutQuery.slice(0, 160);
  }

  private apiEnvironmentPayload(environment: SlockOhosApiEnvironment): Record<string, Object> {
    return {
      "key": environment.key,
      "label": environment.label,
      "apiBaseUrl": environment.apiBaseUrl,
      "socketBaseUrl": environment.socketBaseUrl,
      "webUrl": environment.webUrl
    };
  }

  private parseRecord(params: KRAny): Record<string, Object> {
    if (typeof params === 'string') {
      return JSON.parse(params) as Record<string, Object>;
    }
    return params as Record<string, Object>;
  }

  private readAssetFile(params: KRAny, callback: KuiklyRenderCallback) {
    // let startTimeMills = Date.now();
    let records = JSON.parse(params as string) as Record<string, string>;
    let assetPath = records["assetPath"];

    let context = getContext(this) as common.UIAbilityContext;
    /*
    context.resourceManager.getRawFileContent(assetPath, (error: BusinessError, value: Uint8Array) => {
      let textDecoder: util.TextDecoder = util.TextDecoder.create();
      let res: string = textDecoder.decodeWithStream(value);
      callback({
        "result": JSON.parse(res)
      });
    });
    */
    let resDir = context.resourceDir
    let path = resDir + '/' + assetPath
    let assetURI = fileUri.getUriFromPath(path)
    const file = fs.openSync(assetURI, fs.OpenMode.READ_ONLY);
    const stat = fs.statSync(file.fd);
    const arrayBuffer = new ArrayBuffer(stat.size);
    fs.read(file.fd, arrayBuffer, (err: BusinessError, readLen: number) => {
      if (err) {
        console.error("read failed with error message: " + err.message + ", error code: " + err.code);
      } else {
        let buf = new Uint8Array(arrayBuffer, 0, readLen);
        const textDecoder = new util.TextDecoder('utf-8');
        const res = textDecoder.decodeWithStream(new Uint8Array(buf));
        callback({
          'result': JSON.parse(res)
        });
      }
      fs.closeSync(file);
    });
  }
}

function closeOhosPage(context: common.UIAbilityContext): void {
  const routerLength = Number.parseInt(router.getLength(), 10);
  if (!Number.isNaN(routerLength) && routerLength > 1) {
    router.back();
    return;
  }
  context.terminateSelf().catch((error: BusinessError) => {
    console.error(`KuiklyRender terminateSelf failed ${error.code}: ${error.message}`);
  });
}
