/**
 * Quiver server coordinates for the OHOS app.
 *
 * raft-ohos is a separate Quiver app from raft-android so crashes, feedback,
 * and (later) release channels stay per-platform.
 */
export const QUIVER_BASE_URL: string = 'https://quiver.oranix.io';
export const QUIVER_APP_SLUG: string = 'raft-ohos';
export const QUIVER_CHANNEL: string = 'main';

/**
 * Per-app client key (Sentry-DSN model): required by the feedback endpoint,
 * identifies the app rather than a user. Rotate from the Quiver admin
 * Settings tab if it leaks.
 */
export const QUIVER_CLIENT_KEY: string = 'qk_425c2f5b28aae3d46037634665b99bc72605bb6e';
