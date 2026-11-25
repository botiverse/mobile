---
platform:
  - android
  - ios
category: feature
---

- Wide navigation rail now syncs per-server rail places (`GET`/`PATCH /servers/:id/sidebar-order` with `places`, `placesVersion`, and `placeableApps`, plus live `rail_places:updated` and `permission-updates:changed` updates, matching web #16682 / #16684) while falling back to the account tab order on older servers.
- Chat and Activity stay fixed first on modern servers, installed App places render with their App icon and open in Raft's in-app container after a 15-second freshness check (with an unavailable prompt if the App is no longer on the server), and an Edit navigation bar dialog lets users reorder, remove, and add built-in places or installed Apps with optimistic concurrency conflict handling.
