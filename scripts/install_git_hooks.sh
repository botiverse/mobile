#!/usr/bin/env bash
set -euo pipefail

repo_root="$(git rev-parse --show-toplevel)"
git -C "$repo_root" config core.hooksPath .githooks
chmod +x "$repo_root/.githooks/commit-msg" "$repo_root/scripts/check-commit-signoff.sh"

cat <<'EOF'
Git hooks installed for this repository.

Required commit identity in a shared checkout:
  git \
    -c user.name="<your-name>" \
    -c user.email="raft-mobile-<your-name>@mail.build" \
    commit -s -m "<message>"

Do not rely on repo-local user.name/user.email when multiple agents share the
same checkout. In an isolated private clone or worktree, local Git identity is
also fine.
EOF
