import { common } from '@kit.AbilityKit';
import { util } from '@kit.ArkTS';
import fs from '@ohos.file.fs';
import taskpool from '@ohos.taskpool';
import Want from '@ohos.app.ability.Want';

export const SLOCK_OHOS_EXTERNAL_SHARE_STORAGE_KEY = 'slockExternalShareLaunch';
export const SLOCK_OHOS_EXTERNAL_SHARE_INTENT = 'open_share_to_raft';

const SEND_DATA_ACTION = 'ohos.want.action.sendData';
const SHARE_STREAM_KEY = 'ability.params.stream';
const SHARE_CONTENT_KEY = 'ability.params.content';
const SHARE_SUBJECT_KEY = 'ability.params.subject';
const RAFT_SHARE_TOKEN_KEY = 'build.raft.app.externalShareToken';
const MAX_ITEMS = 9;
const MAX_TEXT_LENGTH = 256 * 1024;
const MAX_ITEM_BYTES = 25 * 1024 * 1024;
const MAX_TOTAL_BYTES = 100 * 1024 * 1024;
const ENVELOPE_FILE_NAME = 'envelope.json';
const ENVELOPE_TTL_MILLIS = 24 * 60 * 60 * 1000;

interface SlockOhosExternalShareItem {
  localRef: string;
  displayName: string;
  mimeType: string;
  sizeBytes: number;
}

interface SlockOhosExternalShareEnvelope {
  token: string;
  createdAtEpochMillis: number;
  sourcePlatform: string;
  text: string;
  subject: string;
  items: Array<SlockOhosExternalShareItem>;
}

export interface SlockOhosExternalShareCapture {
  text: string;
  subject: string;
  uris: Array<string>;
  mimeType: string;
  retainedToken: string;
}

export interface SlockOhosExternalShareLaunch {
  intent: string;
  externalShareToken: string;
}

export interface SlockOhosExternalShareIntakeResult {
  admitted: boolean;
  code: string;
  launch?: SlockOhosExternalShareLaunch;
}

export interface SlockOhosExternalShareCaptureResult {
  accepted: boolean;
  code: string;
  capture?: SlockOhosExternalShareCapture;
}

/** Synchronous phase: copy scalar handles, validate shape, and erase the raw Want before return. */
export function captureSlockOhosExternalShareWant(want: Want): SlockOhosExternalShareCaptureResult {
  if (want.action !== SEND_DATA_ACTION) {
    return { accepted: false, code: 'external_share_action_unsupported' };
  }
  const parameters = (want.parameters ?? {}) as Record<string, Object>;
  const textValue = stringValue(parameters[SHARE_CONTENT_KEY]);
  const subjectValue = stringValue(parameters[SHARE_SUBJECT_KEY]);
  const retainedToken = stringValue(parameters[RAFT_SHARE_TOKEN_KEY]);
  const uris = shareUris(want, parameters);
  const mimeType = stringValue(want.type);
  clearRawWant(want, parameters);
  if (textValue.length > MAX_TEXT_LENGTH || subjectValue.length > MAX_TEXT_LENGTH) {
    return { accepted: false, code: 'external_share_text_too_large' };
  }
  if (textValue.trim().length === 0 && subjectValue.trim().length === 0 && uris.length === 0 &&
    retainedToken.length === 0) {
    return { accepted: false, code: 'external_share_empty' };
  }
  if (uris.length > MAX_ITEMS) {
    return { accepted: false, code: 'external_share_too_many_items' };
  }
  if (uris.some((uri: string): boolean => !isSlockOhosGrantedProviderUri(uri))) {
    return { accepted: false, code: 'external_share_uri_unsupported' };
  }
  return {
    accepted: true,
    code: 'external_share_captured',
    capture: {
      text: textValue,
      subject: subjectValue,
      uris: uris,
      mimeType: mimeType,
      retainedToken: retainedToken
    }
  };
}

/** Taskpool phase: all stat/read/write/fsync/prune work stays off UIAbility's main thread. */
@Concurrent
export function persistSlockOhosExternalShare(
  filesDir: string,
  capture: SlockOhosExternalShareCapture,
  now: number
): SlockOhosExternalShareIntakeResult {
  pruneExpiredInboxes(filesDir, now);
  if (isSafeToken(capture.retainedToken) && envelopeIsLive(filesDir, capture.retainedToken, now)) {
    return admitted(capture.retainedToken, 'external_share_redelivery');
  }
  const token = util.generateRandomUUID(true);
  const inbox = `${filesDir}/external-share/inbox/${token}`;
  try {
    fs.mkdirSync(inbox, true);
    const items = copyItems(filesDir, capture.uris, capture.mimeType, inbox);
    const envelope: SlockOhosExternalShareEnvelope = {
      token: token,
      createdAtEpochMillis: now,
      sourcePlatform: 'ohos',
      text: capture.text,
      subject: capture.subject,
      items: items
    };
    publishEnvelope(inbox, envelope);
    return admitted(token, 'external_share_admitted');
  } catch (error) {
    removeTree(inbox);
    return { admitted: false, code: errorCode(error) };
  }
}

/** Serial queue + newest-generation publication fence shared by cold and warm Wants. */
export class SlockOhosExternalShareAdmissionOwner {
  private tail: Promise<void> = Promise.resolve();
  private generationGate: SlockOhosExternalShareGenerationGate = new SlockOhosExternalShareGenerationGate();

  constructor(
    private readonly context: common.UIAbilityContext,
    private readonly publish: (launch: SlockOhosExternalShareLaunch) => void,
    private readonly report: (code: string) => void
  ) {}

  submit(want: Want): boolean {
    const captured = captureSlockOhosExternalShareWant(want);
    if (!captured.accepted || captured.capture === undefined) {
      if (captured.code !== 'external_share_action_unsupported') this.report(captured.code);
      return captured.code !== 'external_share_action_unsupported';
    }
    const generation = this.generationGate.next();
    const capture = captured.capture;
    const retainedWant = want;
    this.tail = this.tail
      .then((): Promise<Object> => {
        const task = new taskpool.Task(
          persistSlockOhosExternalShare,
          this.context.filesDir,
          capture,
          Date.now()
        );
        return taskpool.execute(task, taskpool.Priority.HIGH);
      })
      .then((value: Object): void => {
        const result = value as SlockOhosExternalShareIntakeResult;
        if (!result.admitted || result.launch === undefined) {
          if (this.generationGate.isCurrent(generation)) this.report(result.code);
          return;
        }
        const parameters = (retainedWant.parameters ?? {}) as Record<string, Object>;
        parameters[RAFT_SHARE_TOKEN_KEY] = result.launch.externalShareToken;
        if (this.generationGate.isCurrent(generation)) this.publish(result.launch);
      })
      .catch((_error: Error): void => {
        if (this.generationGate.isCurrent(generation)) this.report('external_share_taskpool_failed');
      });
    return true;
  }
}

export function isSlockOhosGrantedProviderUri(uri: string): boolean {
  const normalized = uri.trim().toLowerCase();
  return normalized.startsWith('file://') || normalized.startsWith('content://');
}

export function slockOhosWriteAll(
  byteLength: number,
  write: (offset: number, length: number) => number
): boolean {
  let offset = 0;
  while (offset < byteLength) {
    const count = write(offset, byteLength - offset);
    if (count <= 0 || count > byteLength - offset) return false;
    offset += count;
  }
  return offset === byteLength;
}

export function slockOhosExternalShareSizeError(size: number, bytesBefore: number): string {
  if (size <= 0) return 'external_share_item_empty';
  if (size > MAX_ITEM_BYTES) return 'external_share_item_too_large';
  if (bytesBefore + size > MAX_TOTAL_BYTES) return 'external_share_total_too_large';
  return '';
}

export function isSlockOhosExternalShareEnvelopeFresh(now: number, modifiedAt: number): boolean {
  const age = now - modifiedAt;
  return age >= 0 && age <= ENVELOPE_TTL_MILLIS;
}

export class SlockOhosExternalShareGenerationGate {
  private generation: number = 0;
  next(): number {
    this.generation += 1;
    return this.generation;
  }
  isCurrent(candidate: number): boolean {
    return candidate === this.generation;
  }
}

function copyItems(
  filesDir: string,
  uris: Array<string>,
  claimedMimeType: string,
  inbox: string
): Array<SlockOhosExternalShareItem> {
  const items: Array<SlockOhosExternalShareItem> = [];
  let totalBytes = 0;
  uris.forEach((uri: string, index: number) => {
    if (!isSlockOhosGrantedProviderUri(uri)) throw new Error('external_share_uri_unsupported');
    const source = fs.openSync(uri, fs.OpenMode.READ_ONLY);
    try {
      if (uri.trim().toLowerCase().startsWith('file://')) {
        const canonical = fs.realPathSync(uri);
        const privateRoot = fs.realPathSync(filesDir);
        if (canonical === privateRoot || canonical.startsWith(`${privateRoot}/`)) {
          throw new Error('external_share_uri_unsupported');
        }
      }
      const size = fs.statSync(source.fd).size;
      const sizeError = slockOhosExternalShareSizeError(size, totalBytes);
      if (sizeError.length > 0) throw new Error(sizeError);
      const displayName = fileName(uri, index);
      const target = `${inbox}/item-${index + 1}-${storageName(displayName)}`;
      const copiedSize = copyBounded(source.fd, target, totalBytes);
      if (copiedSize !== size) throw new Error('external_share_copy_mismatch');
      totalBytes += copiedSize;
      items.push({
        localRef: target,
        displayName: displayName,
        mimeType: mimeType(claimedMimeType),
        sizeBytes: copiedSize
      });
    } finally {
      fs.closeSync(source);
    }
  });
  return items;
}

function copyBounded(sourceFd: number, targetPath: string, bytesBefore: number): number {
  const target = fs.openSync(targetPath, fs.OpenMode.CREATE | fs.OpenMode.TRUNC | fs.OpenMode.WRITE_ONLY);
  let copied = 0;
  try {
    const buffer = new ArrayBuffer(64 * 1024);
    while (true) {
      const read = fs.readSync(sourceFd, buffer);
      if (read <= 0) break;
      copied += read;
      if (copied > MAX_ITEM_BYTES) throw new Error('external_share_item_too_large');
      if (bytesBefore + copied > MAX_TOTAL_BYTES) throw new Error('external_share_total_too_large');
      const complete = slockOhosWriteAll(read, (offset: number, length: number): number =>
        fs.writeSync(target.fd, buffer, { offset: offset, length: length })
      );
      if (!complete) throw new Error('external_share_copy_failed');
    }
    fs.fsyncSync(target.fd);
    return copied;
  } finally {
    fs.closeSync(target);
  }
}

function publishEnvelope(inbox: string, envelope: SlockOhosExternalShareEnvelope): void {
  const target = `${inbox}/${ENVELOPE_FILE_NAME}`;
  const temporary = `${target}.tmp`;
  const bytes = new util.TextEncoder().encodeInto(JSON.stringify(envelope));
  const buffer = bytes.buffer as ArrayBuffer;
  const file = fs.openSync(temporary, fs.OpenMode.CREATE | fs.OpenMode.TRUNC | fs.OpenMode.WRITE_ONLY);
  try {
    const complete = slockOhosWriteAll(bytes.byteLength, (offset: number, length: number): number =>
      fs.writeSync(file.fd, buffer, { offset: offset, length: length })
    );
    if (!complete || fs.statSync(file.fd).size !== bytes.byteLength) {
      throw new Error('external_share_envelope_short_write');
    }
    fs.fsyncSync(file.fd);
  } finally {
    fs.closeSync(file);
  }
  fs.renameSync(temporary, target);
}

function shareUris(want: Want, parameters: Record<string, Object>): Array<string> {
  const result: Array<string> = [];
  const append = (value: string): void => {
    const normalized = value.trim();
    if (normalized.length > 0 && result.indexOf(normalized) < 0) result.push(normalized);
  };
  append(want.uri ?? '');
  const stream = parameters[SHARE_STREAM_KEY];
  if (typeof stream === 'string') {
    append(stream);
  } else if (Array.isArray(stream)) {
    stream.forEach((value: Object) => {
      if (typeof value === 'string') append(value);
    });
  }
  return result;
}

function clearRawWant(want: Want, parameters: Record<string, Object>): void {
  want.uri = '';
  parameters[SHARE_STREAM_KEY] = '';
  parameters[SHARE_CONTENT_KEY] = '';
  parameters[SHARE_SUBJECT_KEY] = '';
}

function admitted(token: string, code: string): SlockOhosExternalShareIntakeResult {
  return {
    admitted: true,
    code: code,
    launch: { intent: SLOCK_OHOS_EXTERNAL_SHARE_INTENT, externalShareToken: token }
  };
}

function stringValue(value: Object | undefined): string {
  return typeof value === 'string' ? value : '';
}

function fileName(uri: string, index: number): string {
  const raw = decodeURIComponent(uri.split('/').pop() ?? '').replace(/[\u0000-\u001f\u007f]/g, '_').trim();
  return raw.length > 0 && raw !== '.' && raw !== '..' ? raw.slice(0, 120) : `shared-item-${index + 1}`;
}

function storageName(displayName: string): string {
  return displayName.replace(/[^A-Za-z0-9._-]/g, '_').slice(0, 120);
}

function mimeType(value: string): string {
  const normalized = value.split(';')[0].trim().toLowerCase();
  return normalized.length > 0 && normalized.length <= 255 ? normalized : 'application/octet-stream';
}

function isSafeToken(value: string): boolean {
  return /^[A-Za-z0-9-]{16,80}$/.test(value);
}

function envelopeIsLive(filesDir: string, token: string, now: number): boolean {
  try {
    const stat = fs.statSync(`${filesDir}/external-share/inbox/${token}/${ENVELOPE_FILE_NAME}`);
    return stat.isFile() && isSlockOhosExternalShareEnvelopeFresh(now, stat.mtime);
  } catch (_error) {
    return false;
  }
}

function pruneExpiredInboxes(filesDir: string, now: number): void {
  const root = `${filesDir}/external-share/inbox`;
  try {
    fs.listFileSync(root).forEach((name: string) => {
      const directory = `${root}/${name}`;
      try {
        const envelope = fs.statSync(`${directory}/${ENVELOPE_FILE_NAME}`);
        if (!envelope.isFile() || !isSlockOhosExternalShareEnvelopeFresh(now, envelope.mtime)) {
          removeTree(directory);
        }
      } catch (_error) {
        removeTree(directory);
      }
    });
  } catch (_error) {
    // Inbox does not exist yet.
  }
}

function errorCode(error: Object): string {
  const message = String(error);
  const known = [
    'external_share_item_empty',
    'external_share_uri_unsupported',
    'external_share_item_too_large',
    'external_share_total_too_large',
    'external_share_copy_mismatch',
    'external_share_envelope_short_write'
  ];
  return known.find((code: string) => message.indexOf(code) >= 0) ?? 'external_share_copy_failed';
}

function removeTree(path: string): void {
  try {
    fs.listFileSync(path).forEach((entry: string) => {
      const child = `${path}/${entry}`;
      try {
        fs.unlinkSync(child);
      } catch (_error) {
        removeTree(child);
      }
    });
    fs.rmdirSync(path);
  } catch (_error) {
    // Best-effort rollback. No token is published when cleanup is incomplete.
  }
}
