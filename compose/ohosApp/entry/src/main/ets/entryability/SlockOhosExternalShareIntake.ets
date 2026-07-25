import { common } from '@kit.AbilityKit';
import { util } from '@kit.ArkTS';
import fs from '@ohos.file.fs';
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
const REDELIVERY_WINDOW_MILLIS = 30 * 1000;
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

interface SlockOhosExternalShareRedeliveryReceipt {
  token: string;
  admittedAtEpochMillis: number;
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

/** One owner for both cold onCreate and warm onNewWant share admission. */
export class SlockOhosExternalShareIntake {
  private static admittedWantFingerprints: Map<string, SlockOhosExternalShareRedeliveryReceipt> =
    new Map<string, SlockOhosExternalShareRedeliveryReceipt>();

  static admit(context: common.UIAbilityContext, want: Want): SlockOhosExternalShareIntakeResult {
    if (want.action !== SEND_DATA_ACTION) {
      return { admitted: false, code: 'external_share_action_unsupported' };
    }
    const parameters = (want.parameters ?? {}) as Record<string, Object>;
    const text = SlockOhosExternalShareIntake.stringValue(parameters[SHARE_CONTENT_KEY]).slice(0, MAX_TEXT_LENGTH);
    const subject = SlockOhosExternalShareIntake.stringValue(parameters[SHARE_SUBJECT_KEY]).slice(0, MAX_TEXT_LENGTH);
    const uris = SlockOhosExternalShareIntake.shareUris(want, parameters);
    const retainedToken = SlockOhosExternalShareIntake.stringValue(parameters[RAFT_SHARE_TOKEN_KEY]);
    const fingerprint = SlockOhosExternalShareIntake.fingerprint(text, subject, uris);
    const now = Date.now();
    SlockOhosExternalShareIntake.pruneExpiredInboxes(context, now);
    SlockOhosExternalShareIntake.pruneRedeliveryReceipts(now);
    const existingReceipt = SlockOhosExternalShareIntake.admittedWantFingerprints.get(fingerprint);
    SlockOhosExternalShareIntake.clearRawWant(want, parameters);
    if (SlockOhosExternalShareIntake.isSafeToken(retainedToken) &&
      SlockOhosExternalShareIntake.envelopeExists(context, retainedToken)) {
      return {
        admitted: true,
        code: 'external_share_redelivery',
        launch: { intent: SLOCK_OHOS_EXTERNAL_SHARE_INTENT, externalShareToken: retainedToken }
      };
    }
    if (existingReceipt !== undefined && now - existingReceipt.admittedAtEpochMillis <= REDELIVERY_WINDOW_MILLIS) {
      return {
        admitted: true,
        code: 'external_share_redelivery',
        launch: { intent: SLOCK_OHOS_EXTERNAL_SHARE_INTENT, externalShareToken: existingReceipt.token }
      };
    }
    if (text.trim().length === 0 && subject.trim().length === 0 && uris.length === 0) {
      return { admitted: false, code: 'external_share_empty' };
    }
    if (uris.length > MAX_ITEMS) {
      return { admitted: false, code: 'external_share_too_many_items' };
    }

    const token = util.generateRandomUUID(true);
    const inbox = `${context.filesDir}/external-share/inbox/${token}`;
    try {
      fs.mkdirSync(inbox, true);
      const items = SlockOhosExternalShareIntake.copyItems(context, uris, want.type ?? '', inbox);
      const envelope: SlockOhosExternalShareEnvelope = {
        token: token,
        createdAtEpochMillis: now,
        sourcePlatform: 'ohos',
        text: text,
        subject: subject,
        items: items
      };
      SlockOhosExternalShareIntake.publishEnvelope(inbox, envelope);
      SlockOhosExternalShareIntake.admittedWantFingerprints.set(fingerprint, {
        token: token,
        admittedAtEpochMillis: now
      });
      parameters[RAFT_SHARE_TOKEN_KEY] = token;
      return {
        admitted: true,
        code: 'external_share_admitted',
        launch: { intent: SLOCK_OHOS_EXTERNAL_SHARE_INTENT, externalShareToken: token }
      };
    } catch (error) {
      SlockOhosExternalShareIntake.removeTree(inbox);
      return { admitted: false, code: SlockOhosExternalShareIntake.errorCode(error) };
    }
  }

  private static copyItems(
    context: common.UIAbilityContext,
    uris: Array<string>,
    claimedMimeType: string,
    inbox: string
  ): Array<SlockOhosExternalShareItem> {
    const items: Array<SlockOhosExternalShareItem> = [];
    let totalBytes = 0;
    uris.forEach((uri: string, index: number) => {
      if (uri.indexOf(context.filesDir) >= 0) throw new Error('external_share_uri_unsupported');
      const source = fs.openSync(uri, fs.OpenMode.READ_ONLY);
      try {
        const size = fs.statSync(source.fd).size;
        if (size <= 0) throw new Error('external_share_item_empty');
        if (size > MAX_ITEM_BYTES) throw new Error('external_share_item_too_large');
        if (totalBytes + size > MAX_TOTAL_BYTES) throw new Error('external_share_total_too_large');
        const displayName = SlockOhosExternalShareIntake.fileName(uri, index);
        const target = `${inbox}/item-${index + 1}-${SlockOhosExternalShareIntake.storageName(displayName)}`;
        const copiedSize = SlockOhosExternalShareIntake.copyBounded(source.fd, target, totalBytes);
        if (copiedSize !== size) throw new Error('external_share_copy_mismatch');
        totalBytes += copiedSize;
        items.push({
          localRef: target,
          displayName: displayName,
          mimeType: SlockOhosExternalShareIntake.mimeType(claimedMimeType),
          sizeBytes: copiedSize
        });
      } finally {
        fs.closeSync(source);
      }
    });
    return items;
  }

  private static copyBounded(sourceFd: number, targetPath: string, bytesBefore: number): number {
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
        let written = 0;
        while (written < read) {
          const count = fs.writeSync(target.fd, buffer, { offset: written, length: read - written });
          if (count <= 0) throw new Error('external_share_copy_failed');
          written += count;
        }
      }
      fs.fsyncSync(target.fd);
      return copied;
    } finally {
      fs.closeSync(target);
    }
  }

  private static publishEnvelope(inbox: string, envelope: SlockOhosExternalShareEnvelope): void {
    const target = `${inbox}/${ENVELOPE_FILE_NAME}`;
    const temporary = `${target}.tmp`;
    const file = fs.openSync(temporary, fs.OpenMode.CREATE | fs.OpenMode.TRUNC | fs.OpenMode.WRITE_ONLY);
    try {
      fs.writeSync(file.fd, JSON.stringify(envelope));
      fs.fsyncSync(file.fd);
    } finally {
      fs.closeSync(file);
    }
    fs.renameSync(temporary, target);
  }

  private static shareUris(want: Want, parameters: Record<string, Object>): Array<string> {
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

  private static clearRawWant(want: Want, parameters: Record<string, Object>): void {
    want.uri = '';
    parameters[SHARE_STREAM_KEY] = '';
    parameters[SHARE_CONTENT_KEY] = '';
    parameters[SHARE_SUBJECT_KEY] = '';
  }

  private static stringValue(value: Object | undefined): string {
    return typeof value === 'string' ? value : '';
  }

  private static fileName(uri: string, index: number): string {
    const raw = decodeURIComponent(uri.split('/').pop() ?? '').replace(/[\u0000-\u001f\u007f]/g, '_').trim();
    return raw.length > 0 && raw !== '.' && raw !== '..' ? raw.slice(0, 120) : `shared-item-${index + 1}`;
  }

  private static storageName(displayName: string): string {
    return displayName.replace(/[^A-Za-z0-9._-]/g, '_').slice(0, 120);
  }

  private static mimeType(value: string): string {
    const normalized = value.split(';')[0].trim().toLowerCase();
    return normalized.length > 0 && normalized.length <= 255 ? normalized : 'application/octet-stream';
  }

  private static isSafeToken(value: string): boolean {
    return /^[A-Za-z0-9-]{16,80}$/.test(value);
  }

  private static envelopeExists(context: common.UIAbilityContext, token: string): boolean {
    try {
      const stat = fs.statSync(`${context.filesDir}/external-share/inbox/${token}/${ENVELOPE_FILE_NAME}`);
      return stat.isFile() && Date.now() - stat.mtime >= 0 && Date.now() - stat.mtime <= ENVELOPE_TTL_MILLIS;
    } catch (_error) {
      return false;
    }
  }

  private static fingerprint(text: string, subject: string, uris: Array<string>): string {
    const input = `${text}\u0000${subject}\u0000${uris.join('\u0000')}`;
    let hash = 2166136261;
    for (let index = 0; index < input.length; index += 1) {
      hash ^= input.charCodeAt(index);
      hash = Math.imul(hash, 16777619);
    }
    return `${hash >>> 0}:${input.length}`;
  }

  private static pruneRedeliveryReceipts(now: number): void {
    SlockOhosExternalShareIntake.admittedWantFingerprints.forEach(
      (receipt: SlockOhosExternalShareRedeliveryReceipt, fingerprint: string) => {
        if (now - receipt.admittedAtEpochMillis > REDELIVERY_WINDOW_MILLIS) {
          SlockOhosExternalShareIntake.admittedWantFingerprints.delete(fingerprint);
        }
      }
    );
  }

  private static pruneExpiredInboxes(context: common.UIAbilityContext, now: number): void {
    const root = `${context.filesDir}/external-share/inbox`;
    try {
      fs.listFileSync(root).forEach((name: string) => {
        const directory = `${root}/${name}`;
        try {
          const envelope = fs.statSync(`${directory}/${ENVELOPE_FILE_NAME}`);
          if (!envelope.isFile() || now - envelope.mtime < 0 || now - envelope.mtime > ENVELOPE_TTL_MILLIS) {
            SlockOhosExternalShareIntake.removeTree(directory);
          }
        } catch (_error) {
          SlockOhosExternalShareIntake.removeTree(directory);
        }
      });
    } catch (_error) {
      // Inbox does not exist yet.
    }
  }

  private static errorCode(error: Object): string {
    const message = String(error);
    const known = [
      'external_share_item_empty',
      'external_share_uri_unsupported',
      'external_share_item_too_large',
      'external_share_total_too_large',
      'external_share_copy_mismatch'
    ];
    const match = known.find((code: string) => message.indexOf(code) >= 0);
    return match ?? 'external_share_copy_failed';
  }

  private static removeTree(path: string): void {
    try {
      const entries = fs.listFileSync(path);
      entries.forEach((entry: string) => {
        const child = `${path}/${entry}`;
        try {
          fs.unlinkSync(child);
        } catch (_error) {
          SlockOhosExternalShareIntake.removeTree(child);
        }
      });
      fs.rmdirSync(path);
    } catch (_error) {
      // Best-effort rollback. No token is published when cleanup is incomplete.
    }
  }
}
