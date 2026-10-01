import { common } from '@kit.AbilityKit';
import { fileUri } from '@kit.CoreFileKit';
import util from '@ohos.util';
import fs from '@ohos.file.fs';
import { SlockOhosDiagnostics } from '../kuikly/SlockOhosDiagnostics';

export const SLOCK_OHOS_THREAD_COMPOSER_TEMPORARY_FILE_RETIREMENT_SERVICE =
  'slockOhosThreadComposerTemporaryFileRetirement';

const RETIREMENT_HANDLER_FAILURE = 'temporary_file_retirement_handler_failed';
const RETIREMENT_INVALID_SESSION = 'temporary_file_retirement_session_invalid';

/**
 * FileUri has two forms in this app: a host-less sandbox URI
 * (`file:///data/...`) and the authority-bearing URI returned by
 * `fileUri.getUriFromPath` (`file://<bundle>/data/...`).  The latter is only
 * trusted when its authority is this application; a foreign authority must
 * never become an owned raw pathname merely because it happens to resolve
 * below one of our roots.
 */
export function isSlockOhosThreadComposerTemporaryFileRefAuthorityAllowed(
  ref: string,
  selfBundleAuthority: string
): boolean {
  if (ref.startsWith('/')) return true;
  if (!ref.toLowerCase().startsWith('file://')) return false;
  try {
    const parsed = new fileUri.FileUri(ref);
    const authority = parsed.authority.trim();
    if (authority.length === 0) return true;
    const expected = selfBundleAuthority.trim();
    return expected.length > 0 && authority.toLowerCase() === expected.toLowerCase();
  } catch (_) {
    return false;
  }
}

/**
 * Physical owner for the shared composer temporary-file retirement contract.
 *
 * The common persistence lock calls this service synchronously.  We therefore
 * validate and quarantine the exact raw pathname before returning.  Only the
 * unique sibling tombstone is deleted asynchronously; the original pathname
 * is never queued for a later unlink (which would permit an ABA deletion).
 */
export class SlockOhosThreadComposerTemporaryFileRetirementProvider {
  private readonly context: common.ApplicationContext;
  private readonly selfBundleAuthority: string;

  constructor(context: common.UIAbilityContext) {
    this.selfBundleAuthority = context.abilityInfo.bundleName.trim();
    this.context = context.getApplicationContext();
  }

  retire(sessionId: string, fileRefs: Array<string>): Record<string, Object> {
    const cleanSessionId = sessionId.trim();
    const requested = this.normalizeRefs(fileRefs);
    if (!this.isSafeSessionId(cleanSessionId)) {
      return this.result(
        cleanSessionId,
        requested,
        [],
        [],
        requested,
        [],
        {},
        RETIREMENT_INVALID_SESSION
      );
    }
    if (requested.length === 0) {
      return this.result(cleanSessionId, requested, [], [], [], [], {}, '');
    }

    const roots = this.temporaryRoots();
    const admitted: Array<string> = [];
    const quarantined: Array<string> = [];
    const rejected: Array<string> = [];
    const failed: Array<string> = [];
    const tombstoneByRef: Record<string, Object> = {};
    const tombstones: Array<string> = [];

    requested.forEach((ref: string): void => {
      const source = this.resolveOwnedPath(ref, roots);
      if (source.length === 0) {
        rejected.push(ref);
        return;
      }
      admitted.push(ref);
      const tombstone = this.quarantine(source);
      if (tombstone.length === 0) {
        // Keep the original file untouched when rename fails.  The shared
        // result remains fail-closed and can be retried by the owner.
        failed.push(ref);
        return;
      }
      quarantined.push(ref);
      tombstoneByRef[ref] = tombstone;
      tombstones.push(tombstone);
    });

    tombstones.forEach((tombstone: string): void => {
      // A tombstone is unique and no longer aliases the public fileRef.  It is
      // safe to remove it after the synchronous ownership transition returns.
      setTimeout((): void => {
        try {
          fs.unlinkSync(tombstone);
        } catch (_) {
          // Best-effort physical deletion; the quarantine receipt is already
          // durable and never falls back to deleting the original path.
        }
      }, 0);
    });

    const errorCode = failed.length > 0 ? RETIREMENT_HANDLER_FAILURE : '';
    SlockOhosDiagnostics.record(
      'SlockFilePreviewCleanup',
      `session=${cleanSessionId} requested=${requested.length} admitted=${admitted.length} ` +
        `quarantined=${quarantined.length} rejected=${rejected.length} failed=${failed.length}`
    );
    return this.result(
      cleanSessionId,
      requested,
      admitted,
      quarantined,
      rejected,
      failed,
      tombstoneByRef,
      errorCode
    );
  }

  private temporaryRoots(): Array<string> {
    return [this.context.cacheDir, this.context.tempDir]
      .map((value: string): string => this.trimTrailingSlashes(value))
      .filter((value: string, index: number, values: Array<string>): boolean =>
        value.length > 0 && values.indexOf(value) === index
      );
  }

  private normalizeRefs(fileRefs: Array<string>): Array<string> {
    const refs: Array<string> = [];
    fileRefs.forEach((raw: string): void => {
      if (typeof raw !== 'string') return;
      const value = raw.trim();
      if (value.length > 0 && refs.indexOf(value) < 0) refs.push(value);
    });
    return refs;
  }

  private isSafeSessionId(value: string): boolean {
    return value.length > 0 && value.length <= 256 && !this.hasControlCharacter(value);
  }

  private hasControlCharacter(value: string): boolean {
    for (let index = 0; index < value.length; index += 1) {
      const code = value.charCodeAt(index);
      if (code < 0x20 || code === 0x7f) return true;
    }
    return false;
  }

  private trimTrailingSlashes(value: string): string {
    if (!value.startsWith('/')) return '';
    let result = value;
    while (result.length > 1 && result.endsWith('/')) {
      result = result.slice(0, result.length - 1);
    }
    return result;
  }

  /** Returns raw components and rejects '.', '..', empty interior segments and controls. */
  private rawAbsoluteComponents(value: string): Array<string> | undefined {
    const path = this.trimTrailingSlashes(value);
    if (path.length === 0 || !path.startsWith('/') || this.hasControlCharacter(path) || path.includes('\\')) {
      return undefined;
    }
    const parts = path.split('/');
    if (parts.length === 0 || parts[0].length !== 0) return undefined;
    const components: Array<string> = [];
    for (let index = 1; index < parts.length; index += 1) {
      const component = parts[index];
      if (component.length === 0 || component === '.' || component === '..' ||
        this.hasControlCharacter(component)) {
        return undefined;
      }
      components.push(component);
    }
    return components;
  }

  private resolveFileRef(ref: string): string {
    if (ref.length === 0 || this.hasControlCharacter(ref) || ref.includes('?') || ref.includes('#')) {
      return '';
    }
    if (ref.startsWith('/')) return ref;
    if (!ref.toLowerCase().startsWith('file://')) return '';
    if (!isSlockOhosThreadComposerTemporaryFileRefAuthorityAllowed(ref, this.selfBundleAuthority)) {
      return '';
    }
    try {
      return new fileUri.FileUri(ref).path;
    } catch (_) {
      return '';
    }
  }

  private validDirectoryRoot(root: string): Array<string> | undefined {
    const components = this.rawAbsoluteComponents(root);
    if (components === undefined) return undefined;
    const normalizedRoot = this.trimTrailingSlashes(root);
    try {
      const rootStat = fs.lstatSync(normalizedRoot);
      if (!rootStat.isDirectory() || rootStat.isSymbolicLink()) return undefined;
      let cursor = '/';
      for (let index = 0; index < components.length; index += 1) {
        cursor = cursor === '/' ? `/${components[index]}` : `${cursor}/${components[index]}`;
        const stat = fs.lstatSync(cursor);
        if (!stat.isDirectory() || stat.isSymbolicLink()) return undefined;
      }
      return components;
    } catch (_) {
      return undefined;
    }
  }

  /** Returns the original raw path, never a canonicalized alias. */
  private resolveOwnedPath(ref: string, roots: Array<string>): string {
    const candidatePath = this.resolveFileRef(ref);
    const candidateComponents = this.rawAbsoluteComponents(candidatePath);
    if (candidateComponents === undefined) return '';

    for (let rootIndex = 0; rootIndex < roots.length; rootIndex += 1) {
      const root = roots[rootIndex];
      const rootComponents = this.validDirectoryRoot(root);
      if (rootComponents === undefined || candidateComponents.length <= rootComponents.length) continue;
      let prefixMatches = true;
      for (let index = 0; index < rootComponents.length; index += 1) {
        if (rootComponents[index] !== candidateComponents[index]) {
          prefixMatches = false;
          break;
        }
      }
      if (!prefixMatches) continue;

      let cursor = root;
      let valid = true;
      for (let index = rootComponents.length; index < candidateComponents.length; index += 1) {
        cursor = `${cursor}/${candidateComponents[index]}`;
        try {
          const stat = fs.lstatSync(cursor);
          const isFinal = index === candidateComponents.length - 1;
          if (stat.isSymbolicLink() || (isFinal ? !stat.isFile() : !stat.isDirectory())) {
            valid = false;
            break;
          }
        } catch (_) {
          valid = false;
          break;
        }
      }
      if (valid) return cursor;
    }
    return '';
  }

  private quarantine(source: string): string {
    const separator = source.lastIndexOf('/');
    if (separator <= 0 || separator >= source.length - 1) return '';
    const parent = source.slice(0, separator);
    const leaf = source.slice(separator + 1);
    for (let attempt = 0; attempt < 4; attempt += 1) {
      const tombstone = `${parent}/.${leaf}.retired-${util.generateRandomUUID()}`;
      try {
        fs.lstatSync(tombstone);
        continue;
      } catch (_) {
        // Destination is absent; proceed to the same-directory rename.
      }
      try {
        fs.renameSync(source, tombstone);
        return tombstone;
      } catch (_) {
        // A failed rename must leave source untouched and be reported as failed.
        return '';
      }
    }
    return '';
  }

  private result(
    sessionId: string,
    requested: Array<string>,
    admitted: Array<string>,
    quarantined: Array<string>,
    rejected: Array<string>,
    failed: Array<string>,
    tombstone: Record<string, Object>,
    errorCode: string
  ): Record<string, Object> {
    const payload: Record<string, Object> = {
      'sessionId': sessionId,
      'requested': requested,
      'admitted': admitted,
      'quarantined': quarantined,
      'rejected': rejected,
      'failed': failed,
      'tombstone': tombstone,
    };
    if (errorCode.length > 0) payload['errorCode'] = errorCode;
    return payload;
  }
}
