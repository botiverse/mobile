import { common } from '@kit.AbilityKit';
import { BusinessError } from '@kit.BasicServicesKit';
import { relationalStore } from '@kit.ArkData';
import { picker, fileUri } from '@kit.CoreFileKit';
import { photoAccessHelper } from '@kit.MediaLibraryKit';
import { http } from '@kit.NetworkKit';
import { notificationManager } from '@kit.NotificationKit';
import { KRAny } from '@kuikly-open/render';
import { KuiklyRenderBaseModule } from '@kuikly-open/render';
import { KuiklyRenderCallback } from '@kuikly-open/render';
import util from '@ohos.util';
import fs from '@ohos.file.fs';
import slockOhosMotionBridge from '../SlockOhosMotionBridge';
import slockOhosRealtimeBridge from '../SlockOhosRealtimeBridge';
import { SlockOhosClipboard } from '../SlockOhosClipboard';
import { SlockOhosDiagnostics } from '../SlockOhosDiagnostics';

interface OhosPickedThreadAttachment {
  fileName: string;
  mimeType: string;
  bytes: Uint8Array;
}

export class KRBridgeModule extends KuiklyRenderBaseModule {
  static readonly MODULE_NAME = "HRBridgeModule";
  static readonly SHOW_ALERT = "showAlert";
  static readonly CLOSE_PAGE = "closePage";
  static readonly OPEN_PAGE = "openPage";
  static readonly COPY_TO_PASTE_BOARD = "copyToPasteboard";
  static readonly TOAST = "toast";
  static readonly OPEN_URL = "openUrl";
  static readonly LOG = "log";
  static readonly REPORT_DT = "reportDT";
  static readonly REPORT_REAL_TIME = "reportRealtime";
  static readonly LOCAL_SERVE_TIME = "localServeTime";
  static readonly CURRENT_TIME_STAMP = "currentTimestamp";
  static readonly DATE_FORMATTER = "dateFormatter";
  static readonly READ_ASSET_FILE = "readAssetFile";
  static readonly AUTH_REQUEST = "authRequest";
  static readonly RELATIONAL_STORE = "relationalStore";
  static readonly MOTION_CAPABILITY = "motionCapability";
  static readonly SETTINGS_CAPABILITY = "settingsCapability";
  static readonly THREAD_ATTACHMENT_UPLOAD = "threadAttachmentUpload";

  private rdbStores: Map<string, relationalStore.RdbStore> = new Map();

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
        this.toast();
        break;
      }
      case KRBridgeModule.OPEN_URL: {
        this.openUrl(params);
        break;
      }
      case KRBridgeModule.LOG: {
        this.log(params);
        break;
      }
      case KRBridgeModule.REPORT_DT: {
        this.reportDT(params);
        break;
      }
      case KRBridgeModule.REPORT_REAL_TIME: {
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
      case KRBridgeModule.AUTH_REQUEST: {
        this.authRequest(params, callback);
        break;
      }
      case KRBridgeModule.RELATIONAL_STORE: {
        this.relationalStore(params, callback);
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

  // TODO close page
  private closePage() {
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

  // TODO open toast
  private toast() {
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

  private log(params: KRAny) {
    if (params == null) {
      return
    }
    const payload = this.parseRecord(params);
    const contentValue = payload['content'];
    const content = typeof contentValue === 'string' ? contentValue : JSON.stringify(params);
    SlockOhosDiagnostics.record('KuiklyRender', content);
    console.log(`KuiklyRender ${JSON.stringify(params)}`,)
  }

  private reportDT(params: KRAny) {
  }

  private reportRealtime(params: KRAny) {
    if (params == null) {
      return;
    }
    slockOhosRealtimeBridge.handleCommand(this.parseRecord(params));
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
    console.log(`========== ${params}`)
    return "";
  }

  private async relationalStore(params: KRAny, callback: KuiklyRenderCallback) {
    try {
      const records = this.parseRecord(params);
      const action = records["action"] as string;
      const sql = records["sql"] as string;
      const bindArgs = (records["bindArgs"] as relationalStore.ValueType[]) ?? [];
      const store = await this.rdbStore(
        (records["databaseName"] as string) ?? "slock_shared.db",
        Number(records["version"] ?? 1)
      );

      if (action === "query") {
        const resultSet = await store.querySql(sql, bindArgs);
        try {
          callback(this.resultSetToResponse(resultSet));
        } finally {
          resultSet.close();
        }
        return;
      }

      await store.executeSql(sql, bindArgs);
      callback({ "status": "ok" });
    } catch (error) {
      const businessError = error as BusinessError;
      callback({
        "status": "error",
        "code": businessError.code ?? -1,
        "message": businessError.message ?? `${error}`
      });
    }
  }

  private async authRequest(params: KRAny, callback: KuiklyRenderCallback) {
    const request = http.createHttp();
    try {
      const records = this.parseRecord(params);
      const url = records["url"] as string;
      const methodName = ((records["method"] as string) ?? "GET").toUpperCase();
      const bodyText = records["bodyText"] as string | undefined;
      const authorization = records["authorization"] as string | undefined;
      const serverId = records["serverId"] as string | undefined;
      const header: Record<string, string> = {
        "Accept": "application/json",
        "Content-Type": "application/json"
      };
      if (authorization !== undefined && authorization.length > 0) {
        header["Authorization"] = authorization;
      }
      if (serverId !== undefined && serverId.length > 0) {
        header["X-Server-Id"] = serverId;
      }

      const response = await request.request(url, {
        method: methodName === "POST" ? http.RequestMethod.POST : http.RequestMethod.GET,
        header: header,
        extraData: bodyText,
        connectTimeout: 30000,
        readTimeout: 30000
      });
      const responseCode = Number(response.responseCode ?? 0);
      const body = this.httpResultToString(response.result);
      callback({
        "code": responseCode,
        "bodyText": responseCode >= 200 && responseCode < 300 ? body : "",
        "errorBodyText": responseCode >= 200 && responseCode < 300 ? "" : body
      });
    } catch (error) {
      const businessError = error as BusinessError;
      callback({
        "code": 0,
        "bodyText": "",
        "errorBodyText": businessError.message ?? `${error}`,
        "message": businessError.message ?? `${error}`
      });
    } finally {
      request.destroy();
    }
  }

  private async settingsCapability(params: KRAny, callback: KuiklyRenderCallback) {
    try {
      const records = this.parseRecord(params);
      const action = records["action"] as string;
      switch (action) {
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
            title: "Slock",
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

      const session = await this.readStoredAuthSession();
      const accessToken = ((session?.["accessToken"] as string | undefined) ?? "").trim();
      const serverId = this.currentServerId(session);
      if (accessToken.length === 0 || serverId.length === 0) {
        SlockOhosDiagnostics.record(
          "ThreadAttachmentUpload",
          `auth_missing hasToken=${accessToken.length > 0} hasServer=${serverId.length > 0}`
        );
        callback(this.threadAttachmentError(401, "auth_required", "Auth required"));
        return;
      }

      const files = this.readPickedThreadAttachments(uris, imageOnly);
      const byteCount = files.reduce((sum: number, file: OhosPickedThreadAttachment) => sum + file.bytes.byteLength, 0);
      SlockOhosDiagnostics.record("ThreadAttachmentUpload", `read_files count=${files.length} bytes=${byteCount}`);
      if (files.length === 0) {
        callback(this.threadAttachmentError(400, "attachment_read_failed", "No readable attachments"));
        return;
      }

      callback(await this.uploadThreadAttachments(channelId, accessToken, serverId, files));
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

  private async readStoredAuthSession(): Promise<Record<string, Object> | null> {
    const store = await this.rdbStore("slock_shared.db", 1);
    const resultSet = await store.querySql("SELECT value FROM slock_kv WHERE key = ?", ["auth_session"]);
    try {
      if (!resultSet.goToNextRow()) {
        return null;
      }
      const encoded = resultSet.getString(0);
      if (encoded.length === 0) {
        return null;
      }
      return JSON.parse(encoded) as Record<string, Object>;
    } finally {
      resultSet.close();
    }
  }

  private currentServerId(session: Record<string, Object> | null): string {
    if (session == null) {
      return "";
    }
    const selectedServerId = ((session["selectedServerId"] as string | undefined) ?? "").trim();
    if (selectedServerId.length > 0) {
      return selectedServerId;
    }
    const servers = (session["servers"] as Array<Record<string, Object>> | undefined) ?? [];
    if (servers.length === 0) {
      return "";
    }
    return ((servers[0]["id"] as string | undefined) ?? "").trim();
  }

  private readPickedThreadAttachments(uris: Array<string>, imageOnly: boolean): Array<OhosPickedThreadAttachment> {
    const files: Array<OhosPickedThreadAttachment> = [];
    uris.forEach((uri: string, index: number) => {
      const file = fs.openSync(uri, fs.OpenMode.READ_ONLY);
      try {
        const stat = fs.statSync(file.fd);
        const buffer = new ArrayBuffer(stat.size);
        const readLength = fs.readSync(file.fd, buffer);
        files.push({
          fileName: this.fileNameFromUri(uri, index, imageOnly),
          mimeType: this.mimeTypeFromName(uri, imageOnly),
          bytes: new Uint8Array(buffer, 0, readLength)
        });
      } finally {
        fs.closeSync(file);
      }
    });
    return files;
  }

  private async uploadThreadAttachments(
    channelId: string,
    accessToken: string,
    serverId: string,
    files: Array<OhosPickedThreadAttachment>
  ): Promise<Record<string, Object>> {
    const boundary = `SlockBoundary${Date.now()}`;
    const body = this.multipartThreadAttachmentBody(boundary, channelId, files);
    const request = http.createHttp();
    try {
      SlockOhosDiagnostics.record(
        "ThreadAttachmentUpload",
        `upload_start files=${files.length} bytes=${body.byteLength}`
      );
      const response = await request.request("https://api.slock.ai/api/attachments/upload", {
        method: http.RequestMethod.POST,
        header: {
          "Accept": "application/json",
          "Authorization": `Bearer ${accessToken}`,
          "X-Server-Id": serverId,
          "Content-Type": `multipart/form-data; boundary=${boundary}`
        },
        extraData: body,
        connectTimeout: 30000,
        readTimeout: 60000
      });
      const responseCode = Number(response.responseCode ?? 0);
      const responseBody = this.httpResultToString(response.result);
      SlockOhosDiagnostics.record(
        "ThreadAttachmentUpload",
        `upload_result code=${responseCode} success=${responseCode >= 200 && responseCode < 300} bodyLength=${responseBody.length}`
      );
      return {
        "code": responseCode,
        "bodyText": responseCode >= 200 && responseCode < 300 ? responseBody : "",
        "errorCode": responseCode >= 200 && responseCode < 300 ? "" : "attachment_upload_failed",
        "errorBodyText": responseCode >= 200 && responseCode < 300 ? "" : responseBody
      };
    } finally {
      request.destroy();
    }
  }

  private multipartThreadAttachmentBody(
    boundary: string,
    channelId: string,
    files: Array<OhosPickedThreadAttachment>
  ): ArrayBuffer {
    const chunks: Array<Uint8Array> = [];
    chunks.push(this.utf8(`--${boundary}\r\n`));
    chunks.push(this.utf8(`Content-Disposition: form-data; name="channelId"\r\n\r\n`));
    chunks.push(this.utf8(`${channelId}\r\n`));
    files.forEach((file: OhosPickedThreadAttachment) => {
      chunks.push(this.utf8(`--${boundary}\r\n`));
      chunks.push(this.utf8(
        `Content-Disposition: form-data; name="files"; filename="${this.escapeMultipartValue(file.fileName)}"\r\n`
      ));
      chunks.push(this.utf8(`Content-Type: ${file.mimeType}\r\n\r\n`));
      chunks.push(file.bytes);
      chunks.push(this.utf8(`\r\n`));
    });
    chunks.push(this.utf8(`--${boundary}--\r\n`));
    return this.concatBytes(chunks).buffer;
  }

  private utf8(value: string): Uint8Array {
    return new util.TextEncoder('utf-8').encodeInto(value);
  }

  private concatBytes(chunks: Array<Uint8Array>): Uint8Array {
    const total = chunks.reduce((sum: number, chunk: Uint8Array) => sum + chunk.length, 0);
    const bytes = new Uint8Array(total);
    let offset = 0;
    chunks.forEach((chunk: Uint8Array) => {
      bytes.set(chunk, offset);
      offset += chunk.length;
    });
    return bytes;
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

  private escapeMultipartValue(value: string): string {
    return value.replace(/\\/g, "\\\\").replace(/"/g, "\\\"");
  }

  private threadAttachmentError(code: number, errorCode: string, message: string): Record<string, Object> {
    return {
      "code": code,
      "bodyText": "",
      "errorCode": errorCode,
      "errorBodyText": message
    };
  }

  private async rdbStore(databaseName: string, version: number): Promise<relationalStore.RdbStore> {
    const cached = this.rdbStores.get(databaseName);
    if (cached !== undefined) {
      return cached;
    }

    const context = getContext(this) as common.UIAbilityContext;
    const store = await relationalStore.getRdbStore(context, {
      name: databaseName,
      securityLevel: relationalStore.SecurityLevel.S1
    });
    if (store.version < version) {
      store.version = version;
    }
    this.rdbStores.set(databaseName, store);
    return store;
  }

  private resultSetToResponse(resultSet: relationalStore.ResultSet): Record<string, Object> {
    const columns = resultSet.columnNames;
    const rows: Array<Array<Object | null>> = [];
    while (resultSet.goToNextRow()) {
      const row: Array<Object | null> = [];
      for (let index = 0; index < columns.length; index++) {
        row.push(this.valueAt(resultSet, index));
      }
      rows.push(row);
    }
    return {
      "status": "ok",
      "columns": columns,
      "rows": rows
    };
  }

  private valueAt(resultSet: relationalStore.ResultSet, index: number): Object | null {
    if (resultSet.isColumnNull(index)) {
      return null;
    }
    return resultSet.getString(index);
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
