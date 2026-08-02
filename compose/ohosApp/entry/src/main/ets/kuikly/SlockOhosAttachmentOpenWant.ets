import { Want, wantConstant } from '@kit.AbilityKit';

export const SLOCK_OHOS_ACTION_VIEW_DATA: string = 'ohos.want.action.viewData';
export const SLOCK_OHOS_ACTION_SELECT: string = 'ohos.want.action.select';
export const SLOCK_OHOS_SELECT_INTENT_PARAMETER: string = 'ability.want.params.INTENT';
export const SLOCK_OHOS_PICKER_TYPE_PARAMETER: string = 'ability.picker.type';
export const SLOCK_OHOS_PICKER_FILE_NAMES_PARAMETER: string = 'ability.picker.fileNames';
export const SLOCK_OHOS_PICKER_FILE_SIZES_PARAMETER: string = 'ability.picker.fileSizes';
export const SLOCK_OHOS_DEFAULT_ATTACHMENT_MIME_TYPE: string = 'application/octet-stream';

export enum SlockOhosAttachmentOpenFailureKind {
  NO_APP = 'no_app',
  FAILED = 'failed',
}

export interface SlockOhosAttachmentOpenWants {
  readonly view: Want;
  readonly chooser: Want;
}

export function buildSlockOhosAttachmentOpenWants(
  uri: string,
  requestedMimeType: string | undefined,
  fileName: string = 'attachment',
  sizeBytes: number = 0
): SlockOhosAttachmentOpenWants {
  const normalizedUri = uri.trim();
  if (normalizedUri.length === 0) {
    throw new Error('Attachment URI is required');
  }
  const normalizedMimeType = requestedMimeType?.trim() ?? '';
  const mimeType = normalizedMimeType.length > 0
    ? normalizedMimeType
    : SLOCK_OHOS_DEFAULT_ATTACHMENT_MIME_TYPE;
  const streamUris: Array<string> = [normalizedUri];
  const viewParameters: Record<string, Object> = {};
  viewParameters[wantConstant.Params.PARAMS_STREAM] = streamUris;
  const view: Want = {
    action: SLOCK_OHOS_ACTION_VIEW_DATA,
    uri: normalizedUri,
    type: mimeType,
    flags: wantConstant.Flags.FLAG_AUTH_READ_URI_PERMISSION,
    parameters: viewParameters,
  };
  const chooserParameters: Record<string, Object> = {};
  chooserParameters[SLOCK_OHOS_SELECT_INTENT_PARAMETER] = view;
  chooserParameters[wantConstant.Params.PARAMS_STREAM] = streamUris;
  chooserParameters[SLOCK_OHOS_PICKER_TYPE_PARAMETER] = mimeType;
  chooserParameters[SLOCK_OHOS_PICKER_FILE_NAMES_PARAMETER] = [fileName];
  chooserParameters[SLOCK_OHOS_PICKER_FILE_SIZES_PARAMETER] = [Math.max(0, Math.floor(sizeBytes))];
  const chooser: Want = {
    action: SLOCK_OHOS_ACTION_SELECT,
    uri: normalizedUri,
    type: mimeType,
    flags: wantConstant.Flags.FLAG_AUTH_READ_URI_PERMISSION,
    parameters: chooserParameters,
  };
  return { view, chooser };
}

export function slockOhosAttachmentOpenFailureKind(code: number | undefined): SlockOhosAttachmentOpenFailureKind {
  return code === 16000001 || code === 16000019
    ? SlockOhosAttachmentOpenFailureKind.NO_APP
    : SlockOhosAttachmentOpenFailureKind.FAILED;
}
