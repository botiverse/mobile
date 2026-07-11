import common from '@ohos.app.ability.common';
import fs from '@ohos.file.fs';
import { SlockOhosDiagnostics } from '../kuikly/SlockOhosDiagnostics';

export const SLOCK_OHOS_CURL_RUNTIME_SERVICE = 'slockOhosCurlRuntimeConfig';

// task #252: app-owned CA bundle for the NetworkKMM libcurl transport.
// The PEM ships in resources/resfile, which the installer unpacks into the
// app sandbox as a real read-only file under Context.resourceDir — so the
// K/N side (POSIX fopen) can read it directly, the layout is identical in
// debug and release HAPs, and bundle upgrades are atomic with the package.
// The bundle content is pinned to NetworkCurlCaBundleManifest (SHA-256
// verified by VBTransportCurl.configure on every install attempt and by the
// verifyOhosCurlCaBundle gradle gate at build time).
const SLOCK_CURL_CA_BUNDLE_FILE = 'cacert-2026-05-14.pem';
const SLOCK_CURL_RUNTIME_LOG_TAG = 'SlockCurlRuntime';

export class SlockOhosCurlRuntimeConfig {
  private context: common.Context;

  constructor(context: common.Context) {
    this.context = context;
  }

  // mode is a diagnostics hint from the shared retry path ('retry' after a
  // failed configure, 'initial' otherwise); the path is package-managed and
  // read-only, so there is nothing to re-stage — we re-verify and report.
  caBundlePath(mode: string): string {
    const path = `${this.context.resourceDir}/${SLOCK_CURL_CA_BUNDLE_FILE}`;
    try {
      const stat = fs.statSync(path);
      if (stat.size <= 0) {
        SlockOhosDiagnostics.record(
          SLOCK_CURL_RUNTIME_LOG_TAG,
          `curl_ca_bundle_empty mode=${mode} path=${path}`,
          Date.now(),
          'error'
        );
        return '';
      }
      return path;
    } catch (error) {
      SlockOhosDiagnostics.record(
        SLOCK_CURL_RUNTIME_LOG_TAG,
        `curl_ca_bundle_missing mode=${mode} path=${path} cause=${String(error)}`,
        Date.now(),
        'error'
      );
      return '';
    }
  }
}
