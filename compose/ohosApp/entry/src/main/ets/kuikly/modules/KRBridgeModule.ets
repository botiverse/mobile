import { common } from '@kit.AbilityKit';
import { BusinessError } from '@kit.BasicServicesKit';
import { relationalStore } from '@kit.ArkData';
import { http } from '@kit.NetworkKit';
import { KRAny } from '@kuikly-open/render';
import { KuiklyRenderBaseModule } from '@kuikly-open/render';
import { KuiklyRenderCallback } from '@kuikly-open/render';
import util from '@ohos.util';
import fs from '@ohos.file.fs';
import { fileUri } from '@kit.CoreFileKit';

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
        this.copyToPasteboard();
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

  // TODO copy to paste board
  private copyToPasteboard() {
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
    console.log(`KuiklyRender ${JSON.stringify(params)}`,)
  }

  private reportDT(params: KRAny) {
  }

  private reportRealtime(params: KRAny) {
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
