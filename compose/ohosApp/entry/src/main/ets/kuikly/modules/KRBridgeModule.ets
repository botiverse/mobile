import { bundleManager, common } from '@kit.AbilityKit';
import { BusinessError } from '@kit.BasicServicesKit';
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
import { HandsFeedbackClient } from '@botiverse/hands';
import slockOhosMotionBridge from '../SlockOhosMotionBridge';
import slockOhosRealtimeBridge from '../SlockOhosRealtimeBridge';
import { SlockOhosClipboard } from '../SlockOhosClipboard';
import { SlockOhosDiagnostics } from '../SlockOhosDiagnostics';
import { slockOhosAttachmentDownloadSink } from '../SlockOhosAttachmentDownloadSink';
import { SlockOhosApiEnvironment, SlockOhosApiEnvironmentStore } from '../SlockOhosApiEnvironmentStore';
import { SLOCK_OHOS_BUILD_TIME, SLOCK_OHOS_GIT_COMMIT } from '../generated/SlockOhosBuildMetadata';

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
  static readonly SAVE_IMAGE_TO_PHOTOS = "saveImageToPhotos";
  static readonly CANCEL_ATTACHMENT_EXPORT = "cancelAttachmentExport";
  static readonly ATTACHMENT_DOWNLOAD_CONTROL = "attachmentDownloadControl";
  static readonly ATTACHMENT_DOWNLOAD_OWNER_CHANGED = "attachmentDownloadOwnerChanged";
  static readonly LOG = "log";
  static readonly PERFORMANCE_MARKER = "performanceMarker";
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
  static readonly ATTACHMENT_CACHE_MAX_FILES = 80;
  static readonly ATTACHMENT_CACHE_MAX_AGE_MS = 24 * 60 * 60 * 1000;
  static readonly QUIVER_FEEDBACK_MAX_ATTACHMENTS = 3;
  static readonly QUIVER_FEEDBACK_MAX_ATTACHMENT_BYTES = 10 * 1024 * 1024;
  private activePhotoExportDownloadId: string = "";
  private activePhotoExportOperationId: string = "";
  private activePhotoExportGeneration: number = 0;
  private nextPhotoExportGeneration: number = 0;

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
      default:
        break;
    }
    return null;
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
    const payload = params as Record<string, string>;
    const url = payload['url'];
    if (url === undefined || url.length === 0) {
      return;
    }
    const context = getContext(this) as common.UIAbilityContext;
    context.startAbility({
      action: 'ohos.want.action.viewData',
      uri: url,
      type: payload['mimeType']
    }).catch((error: BusinessError) => {
      console.error(`KuiklyRender openUrl failed ${error.code}: ${error.message}`);
    });
  }

  private async openAttachment(params: KRAny, callback: KuiklyRenderCallback) {
    const records = this.parseRecord(params);
    const url = records["url"] as string | undefined;
    const attachmentId = (records["attachmentId"] as string | undefined) ?? "attachment";
    const fileName = (records["fileName"] as string | undefined) ?? "attachment";
    const mimeType = records["mimeType"] as string | undefined;
    const trackProgress = records["trackProgress"] === true || records["trackProgress"] === "true";
    const downloadId = (records["downloadId"] as string | undefined) ?? `${attachmentId}:${fileName}`;
    if (url === undefined || url.length === 0) {
      callback({ "status": "failed", "message": "Unable to download attachment" });
      return;
    }
    const context = getContext(this) as common.UIAbilityContext;
    try {
      const cacheDir = `${context.cacheDir}/slock-attachments`;
      fs.mkdirSync(cacheDir, true);
      this.cleanupAttachmentCache(cacheDir);
      const path = `${cacheDir}/${this.safeAttachmentCacheFileName(attachmentId, fileName, mimeType)}`;
      const cached = this.isUsableAttachmentCacheFile(path);
      if (!cached) {
        const downloadResult = await slockOhosAttachmentDownloadSink.download(
          url,
          path,
          downloadId,
          path,
          trackProgress ? callback : undefined
        );
        if (downloadResult["status"] !== "complete") {
          callback(downloadResult);
          return;
        }
      }
      const uri = fileUri.getUriFromPath(path);
      try {
        await context.startAbility({
          action: "ohos.want.action.viewData",
          uri: uri,
          type: mimeType
        });
        SlockOhosDiagnostics.record("SlockAttachmentOpen", `attachmentId=${attachmentId.substring(0, 8)} status=opened cached=${cached}`);
        callback({ "status": "opened" });
      } catch (error) {
        const businessError = error as BusinessError;
        SlockOhosDiagnostics.record(
          "SlockAttachmentOpen",
          `attachmentId=${attachmentId.substring(0, 8)} status=no_app code=${businessError.code ?? "unknown"}`
        );
        callback({ "status": "no_app", "message": "No app can open this file" });
      }
    } catch (error) {
      const businessError = error as BusinessError;
      SlockOhosDiagnostics.record(
        "SlockAttachmentOpen",
        `attachmentId=${attachmentId.substring(0, 8)} status=failed code=${businessError.code ?? "unknown"}`
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
    const trackProgress = records["trackProgress"] === true || records["trackProgress"] === "true";
    const downloadId = (records["downloadId"] as string | undefined) ?? `${attachmentId}:${requestedFileName}`;
    if (url === undefined || url.length === 0) {
      callback({ "status": "failed", "message": "Unable to download attachment" });
      return;
    }
    const context = getContext(this) as common.UIAbilityContext;
    try {
      const cacheDir = `${context.cacheDir}/slock-attachments`;
      fs.mkdirSync(cacheDir, true);
      this.cleanupAttachmentCache(cacheDir);
      const requestedBaseName = requestedFileName.split("/").pop()?.split("\\").pop() ?? "attachment";
      const fileName = this.safeAttachmentPathSegment(requestedBaseName).slice(0, 120) || "attachment";
      const path = `${cacheDir}/${this.safeAttachmentCacheFileName(attachmentId, requestedFileName, mimeType)}`;
      const cached = this.isUsableAttachmentCacheFile(path);
      if (!cached) {
        const downloadResult = await slockOhosAttachmentDownloadSink.download(
          url,
          path,
          downloadId,
          path,
          trackProgress ? callback : undefined
        );
        if (downloadResult["status"] !== "complete") {
          callback(downloadResult);
          return;
        }
      }
      const options = new picker.DocumentSaveOptions();
      options.newFileNames = [fileName];
      const targetUris = await new picker.DocumentViewPicker(context).save(options);
      const targetUri = targetUris.length > 0 ? targetUris[0] : undefined;
      if (targetUri === undefined || targetUri.length === 0) {
        callback({ "status": "cancelled" });
        return;
      }
      try {
        fs.copyFileSync(path, targetUri);
      } catch (error) {
        const businessError = error as BusinessError;
        SlockOhosDiagnostics.record(
          "SlockAttachmentSave",
          `attachmentId=${attachmentId.substring(0, 8)} status=storage_failed code=${businessError.code ?? "unknown"}`
        );
        callback({ "status": "storage_failed", "message": "Unable to save attachment" });
        return;
      }
      SlockOhosDiagnostics.record(
        "SlockAttachmentSave",
        `attachmentId=${attachmentId.substring(0, 8)} status=saved cached=${cached}`
      );
      callback({
        "status": "saved",
        "message": "Saved to Files",
        "uri": targetUri,
        "fileName": fileName,
        "cached": true,
        "sizeBytes": fs.statSync(path).size
      });
    } catch (error) {
      const businessError = error as BusinessError;
      const message = businessError.message ?? `${error}`;
      if (message.toLowerCase().includes("cancel")) {
        callback({ "status": "cancelled" });
        return;
      }
      SlockOhosDiagnostics.record(
        "SlockAttachmentSave",
        `attachmentId=${attachmentId.substring(0, 8)} status=failed code=${businessError.code ?? "unknown"}`
      );
      callback({ "status": "storage_failed", "message": "Unable to save attachment" });
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
    if (claimedMimeType.length > 0 && claimedMimeType !== "application/octet-stream" &&
      claimedMimeType !== "binary/octet-stream" && !claimedMimeType.startsWith("image/")) {
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
      fs.mkdirSync(cacheDir, true);
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
    this.activePhotoExportDownloadId = "";
    this.activePhotoExportOperationId = "";
    this.activePhotoExportGeneration = 0;
    slockOhosAttachmentDownloadSink.control(downloadId, attemptToken, "cancel");
  }

  private isCurrentPhotoExport(downloadId: string, operationId: string, exportGeneration: number): boolean {
    return this.activePhotoExportDownloadId === downloadId &&
      this.activePhotoExportOperationId === operationId &&
      this.activePhotoExportGeneration === exportGeneration;
  }

  private imageMetadataMatches(fileName: string, mimeType: string, actualExtension: string): boolean {
    const extension = fileName.split("?")[0].split("#")[0].split(".").pop()?.toLowerCase() ?? "";
    const normalizedExtension = extension === "jpeg" ? "jpg" : extension;
    const extensionMimeType = this.imageMimeTypeForExtension(normalizedExtension);
    const normalizedClaimedMimeType = this.normalizedImageMimeType(mimeType);
    const actualMimeType = this.normalizedImageMimeType(this.imageMimeTypeForExtension(actualExtension));
    const hasSpecificClaim = mimeType.length > 0 && mimeType !== "application/octet-stream" &&
      mimeType !== "binary/octet-stream";
    if (actualMimeType.length === 0) return false;
    if (hasSpecificClaim && normalizedClaimedMimeType !== actualMimeType) return false;
    return extensionMimeType.length === 0 || this.normalizedImageMimeType(extensionMimeType) === actualMimeType;
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
      case "image/jpeg": return "image/jpeg";
      case "image/heic":
      case "image/heif": return "image/heif";
      default: return mimeType;
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
        case "setRecompositionProfilerEnabled": {
          const rawEnabled = records["enabled"];
          const enabled = rawEnabled === true || rawEnabled === "true";
          SlockOhosDiagnostics.record("Settings", `recomposition_profiler_enabled=${enabled}`);
          callback({ "status": "ok", "enabled": enabled });
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
      SlockOhosDiagnostics.record("Settings", `quiver_feedback_submitted ticket=${ticketId.slice(0, 8)}`);
      return { "status": "ok", "ticketId": ticketId };
    } catch (error) {
      const message = String(error);
      SlockOhosDiagnostics.record("Settings", `quiver_feedback_submit_failed ${message}`);
      return { "status": "error", "code": "quiver_feedback_failed", "message": message };
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
        .slice(0, KRBridgeModule.QUIVER_FEEDBACK_MAX_ATTACHMENTS);
      const attachmentPaths = userAttachmentPaths.slice(0, KRBridgeModule.QUIVER_FEEDBACK_MAX_ATTACHMENTS);
      if (attachmentPaths.length < KRBridgeModule.QUIVER_FEEDBACK_MAX_ATTACHMENTS) {
        const archivePath = await SlockOhosAppDiagnostics.prepareFeedbackArchivePath(context);
        if (this.feedbackAttachmentFits(archivePath)) {
          attachmentPaths.push(archivePath);
        } else {
          SlockOhosDiagnostics.record("Settings", `quiver_feedback_diagnostics_attachment_skipped path=${archivePath}`);
        }
      }
      const ticketId = await HandsFeedbackClient.submit(
        context,
        message,
        'bug',
        attachmentPaths.slice(0, KRBridgeModule.QUIVER_FEEDBACK_MAX_ATTACHMENTS),
        [],
      );
      const shortId = ticketId.slice(0, 8);
      const environmentText = SlockOhosAppDiagnostics.exportEnvironmentText(context);
      const attachmentNames = this.feedbackAttachmentNames(attachmentPaths);
      const attachmentSection = attachmentNames.length > 0
        ? `\nattachments:\n${attachmentNames.join('\n')}`
        : '';
      const shareText = `Hands feedback ${shortId}\nTicket: ${ticketId}${attachmentSection}\n\nEnvironment:\n${environmentText}\n\nFeedback:\n${message}`;
      SlockOhosClipboard.copyText(shareText);
      SlockOhosDiagnostics.record("Settings", `quiver_feedback_submitted ticket=${shortId}`);
      return {
        "status": "ok",
        "ticketId": ticketId,
        "shareText": shareText,
        "attachment_names": attachmentNames
      };
    } catch (error) {
      const message = String(error);
      SlockOhosDiagnostics.record("Settings", `quiver_feedback_submit_failed ${message}`);
      return { "status": "error", "code": "settings_feedback_submit_failed", "message": message };
    }
  }

  private feedbackAttachmentPaths(value: Object | undefined): Array<string> {
    if (!Array.isArray(value)) {
      return [];
    }
    const paths: Array<string> = [];
    (value as Array<Record<string, Object>>).forEach((file: Record<string, Object>) => {
      const fileRef = (file["fileRef"] as string | undefined) ?? "";
      if (fileRef.length > 0) {
        if (this.feedbackAttachmentFits(fileRef)) {
          paths.push(fileRef);
        }
      }
    });
    return paths.slice(0, KRBridgeModule.QUIVER_FEEDBACK_MAX_ATTACHMENTS);
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
      .slice(0, KRBridgeModule.QUIVER_FEEDBACK_MAX_ATTACHMENTS);
  }

  private feedbackAttachmentFits(path: string): boolean {
    try {
      const stat = fs.statSync(path);
      return stat.isFile() && stat.size > 0 && stat.size <= KRBridgeModule.QUIVER_FEEDBACK_MAX_ATTACHMENT_BYTES;
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
      SlockOhosDiagnostics.record(
        "ThreadAttachmentUpload",
        `start imageOnly=${imageOnly} channelIdPresent=${channelId.length > 0}`
      );

      if (channelId.length === 0) {
        callback(this.threadAttachmentError(400, "missing_channel_id", "Missing channel id"));
        return;
      }

      const uris = await this.pickThreadAttachmentUris(imageOnly).catch((error: BusinessError) => {
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

      const files = this.readPickedThreadAttachments(uris, imageOnly);
      const byteCount = files.reduce((sum: number, file: OhosPickedThreadAttachment) => sum + file.sizeBytes, 0);
      SlockOhosDiagnostics.record("ThreadAttachmentUpload", `picked_file_refs count=${files.length} bytes=${byteCount}`);
      if (files.length === 0) {
        callback(this.threadAttachmentError(400, "attachment_read_failed", "No readable attachments"));
        return;
      }

      callback(this.threadPickedAttachmentFiles(files));
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
        pickerUnavailable ? "attachment_picker_unavailable" : "attachment_upload_failed",
        message
      ));
    }
  }

  private async pickThreadAttachmentUris(imageOnly: boolean): Promise<Array<string>> {
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

  private readPickedThreadAttachments(uris: Array<string>, imageOnly: boolean): Array<OhosPickedThreadAttachment> {
    const files: Array<OhosPickedThreadAttachment> = [];
    uris.forEach((uri: string, index: number) => {
      const file = fs.openSync(uri, fs.OpenMode.READ_ONLY);
      try {
        const stat = fs.statSync(file.fd);
        files.push({
          fileName: this.fileNameFromUri(uri, index, imageOnly),
          mimeType: this.mimeTypeFromName(uri, imageOnly),
          previewUri: uri,
          fileRef: uri,
          sizeBytes: stat.size
        });
      } finally {
        fs.closeSync(file);
      }
    });
    return files;
  }

  private threadPickedAttachmentFiles(files: Array<OhosPickedThreadAttachment>): Record<string, Object> {
    return {
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
          "source": "platform_picker"
        };
      })
    };
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
