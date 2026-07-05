#ifndef SlockQuiverConfig_h
#define SlockQuiverConfig_h

// Quiver reporting endpoint configuration for the iOS host. The client key
// follows the Sentry-DSN model: it identifies the app and ships inside the
// bundle; rotate it from the Quiver admin Settings tab if it leaks.
#define SLOCK_QUIVER_BASE_URL @"https://quiver.oranix.io"
#define SLOCK_QUIVER_APP_SLUG @"raft-ios"
#define SLOCK_QUIVER_CHANNEL @"main"
#define SLOCK_QUIVER_CLIENT_KEY @"qk_2d6ac57a6532bc7ff1d3183b43bda99e2e732a99"

#endif /* SlockQuiverConfig_h */
