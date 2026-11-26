---
type: feat
---

Support `computer_host_identity_v0` same-physical-machine Computer grouping (`sameMachineGroup`): parse and preserve `sameMachineGroup` across `ComputersApi`, `SharedComputersStore` durable/sync snapshots, and Computer detail models, and show the localized "Same machine as …" peer Computers hint in the Computer detail header when peer Computers share the same physical host (leaving the UI unchanged when alone or when the field is absent).
