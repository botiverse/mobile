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

export enum SlockOhosAttachmentOpenDiagnosticTerminal {
  REQUESTED = 'requested',
  LAUNCH_SUCCEEDED = 'launch_succeeded',
  LAUNCH_FAILED = 'launch_failed',
  PREPARE_FAILED = 'prepare_failed',
}

export enum SlockOhosAttachmentOpenPreflightStage {
  REQUESTED = 'requested',
  REQUEST_REJECTED = 'request_rejected',
  CACHE_CHECKED = 'cache_checked',
  PUBLIC_DOWNLOAD_MISSING = 'public_download_missing',
  DOWNLOAD_STARTED = 'download_started',
  DOWNLOAD_TERMINAL = 'download_terminal',
  WANT_HANDOFF = 'want_handoff',
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

function slockOhosAttachmentOpenDiagnosticAction(action: string | undefined): string {
  if (action === SLOCK_OHOS_ACTION_SELECT) {
    return 'select';
  }
  if (action === SLOCK_OHOS_ACTION_VIEW_DATA) {
    return 'view_data';
  }
  return action === undefined || action.length === 0 ? 'none' : 'other';
}

function slockOhosAttachmentOpenDiagnosticMimeType(mimeType: string | undefined): string {
  const normalized = mimeType?.trim().toLowerCase() ?? '';
  switch (normalized) {
    case 'application/zip':
    case 'general.zip-archive':
    case 'application/octet-stream':
    case 'application/pdf':
    case 'text/plain':
    case 'image/jpeg':
    case 'image/png':
    case 'image/gif':
    case 'video/mp4':
    case 'audio/mpeg':
      return normalized;
    default:
      return normalized.length === 0 ? 'none' : 'other';
  }
}

function slockOhosAttachmentOpenDiagnosticDownloadStatus(status: string | undefined): string {
  switch (status?.trim().toLowerCase()) {
    case 'complete':
    case 'failed':
    case 'cancelled':
    case 'missing':
      return status.trim().toLowerCase();
    default:
      return status === undefined || status.length === 0 ? 'none' : 'other';
  }
}

/**
 * Covers the request/download/cache stages that happen before a chooser Want exists. Values are
 * fixed booleans or allowlisted categories; URL, path, filename, owner key, identifier, and token
 * material are never accepted by this API.
 */
export function slockOhosAttachmentOpenPreflightDiagnostic(
  stage: SlockOhosAttachmentOpenPreflightStage,
  mimeType: string | undefined,
  urlPresent: boolean,
  requireCommittedDownload: boolean,
  ownerPrivate: boolean,
  privateCommitted: boolean | undefined = undefined,
  cacheUsable: boolean | undefined = undefined,
  downloadStatus: string | undefined = undefined
): string {
  return [
    `stage=${stage}`,
    `mime=${slockOhosAttachmentOpenDiagnosticMimeType(mimeType)}`,
    `urlPresent=${urlPresent}`,
    `committedRequired=${requireCommittedDownload}`,
    `ownerPrivate=${ownerPrivate}`,
    `privateCommitted=${privateCommitted === undefined ? 'unknown' : privateCommitted}`,
    `cacheUsable=${cacheUsable === undefined ? 'unknown' : cacheUsable}`,
    `downloadStatus=${slockOhosAttachmentOpenDiagnosticDownloadStatus(downloadStatus)}`,
  ].join(' ');
}

/** Executable owner for the pre-Want trace. The host injects its persistent diagnostic sink. */
export class SlockOhosAttachmentOpenPreflightTrace {
  private readonly mimeType: string | undefined;
  private readonly urlPresent: boolean;
  private readonly requireCommittedDownload: boolean;
  private readonly ownerPrivate: boolean;
  private readonly sink: (message: string) => void;

  constructor(
    mimeType: string | undefined,
    urlPresent: boolean,
    requireCommittedDownload: boolean,
    ownerPrivate: boolean,
    sink: (message: string) => void
  ) {
    this.mimeType = mimeType;
    this.urlPresent = urlPresent;
    this.requireCommittedDownload = requireCommittedDownload;
    this.ownerPrivate = ownerPrivate;
    this.sink = sink;
  }

  requested(): void {
    this.emit(SlockOhosAttachmentOpenPreflightStage.REQUESTED);
  }

  requestRejected(): void {
    this.emit(SlockOhosAttachmentOpenPreflightStage.REQUEST_REJECTED, undefined, undefined, 'failed');
  }

  publicDownloadMissing(privateCommitted: boolean): void {
    this.emit(SlockOhosAttachmentOpenPreflightStage.PUBLIC_DOWNLOAD_MISSING, privateCommitted, false, 'missing');
  }

  cacheChecked(privateCommitted: boolean, cacheUsable: boolean): void {
    this.emit(SlockOhosAttachmentOpenPreflightStage.CACHE_CHECKED, privateCommitted, cacheUsable);
  }

  downloadStarted(privateCommitted: boolean): void {
    this.emit(SlockOhosAttachmentOpenPreflightStage.DOWNLOAD_STARTED, privateCommitted, false);
  }

  downloadTerminal(privateCommitted: boolean, status: string | undefined): void {
    this.emit(SlockOhosAttachmentOpenPreflightStage.DOWNLOAD_TERMINAL, privateCommitted, false, status);
  }

  wantHandoff(privateCommitted: boolean, downloaded: boolean): void {
    this.emit(
      SlockOhosAttachmentOpenPreflightStage.WANT_HANDOFF,
      privateCommitted,
      true,
      downloaded ? 'complete' : undefined
    );
  }

  private emit(
    stage: SlockOhosAttachmentOpenPreflightStage,
    privateCommitted: boolean | undefined = undefined,
    cacheUsable: boolean | undefined = undefined,
    downloadStatus: string | undefined = undefined
  ): void {
    this.sink(
      slockOhosAttachmentOpenPreflightDiagnostic(
        stage,
        this.mimeType,
        this.urlPresent,
        this.requireCommittedDownload,
        this.ownerPrivate,
        privateCommitted,
        cacheUsable,
        downloadStatus
      )
    );
  }
}

function slockOhosAttachmentOpenDiagnosticUriScheme(uri: string | undefined): string {
  const separator = uri?.indexOf(':') ?? -1;
  const normalized = separator > 0 ? uri?.substring(0, separator).trim().toLowerCase() ?? '' : '';
  switch (normalized) {
    case 'file':
    case 'content':
    case 'datashare':
    case 'http':
    case 'https':
      return normalized;
    default:
      return normalized.length === 0 ? 'none' : 'other';
  }
}

function slockOhosAttachmentOpenDiagnosticHasReadFlag(want: Want | undefined): boolean {
  return ((want?.flags ?? 0) & wantConstant.Flags.FLAG_AUTH_READ_URI_PERMISSION) !== 0;
}

function slockOhosAttachmentOpenDiagnosticHasStream(want: Want | undefined): boolean {
  const stream = want?.parameters?.[wantConstant.Params.PARAMS_STREAM] as Array<string> | undefined;
  return stream !== undefined && stream.length > 0;
}

/**
 * Emits only fixed labels and allowlisted values. It intentionally never serializes a Want,
 * URI path, picker filename, stream value, attachment identifier, content, or token.
 */
export function slockOhosAttachmentOpenDiagnostic(
  wants: SlockOhosAttachmentOpenWants | undefined,
  terminal: SlockOhosAttachmentOpenDiagnosticTerminal,
  code: number | undefined = undefined
): string {
  const outer = wants?.chooser;
  const child = outer?.parameters?.[SLOCK_OHOS_SELECT_INTENT_PARAMETER] as Want | undefined;
  const sanitizedCode = code !== undefined && Number.isFinite(code) ? `${Math.floor(code)}` : 'none';
  return [
    `outer=${slockOhosAttachmentOpenDiagnosticAction(outer?.action)}`,
    `child=${slockOhosAttachmentOpenDiagnosticAction(child?.action)}`,
    `mime=${slockOhosAttachmentOpenDiagnosticMimeType(outer?.type)}`,
    `uriScheme=${slockOhosAttachmentOpenDiagnosticUriScheme(outer?.uri)}`,
    `outerRead=${slockOhosAttachmentOpenDiagnosticHasReadFlag(outer)}`,
    `childRead=${slockOhosAttachmentOpenDiagnosticHasReadFlag(child)}`,
    `outerStream=${slockOhosAttachmentOpenDiagnosticHasStream(outer)}`,
    `childStream=${slockOhosAttachmentOpenDiagnosticHasStream(child)}`,
    `terminal=${terminal}`,
    `code=${sanitizedCode}`,
  ].join(' ');
}
