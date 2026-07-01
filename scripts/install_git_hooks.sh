#!/usr/bin/env bash
set -euo pipefail

repo_root="$(git rev-parse --show-toplevel)"
git -C "$repo_root" config core.hooksPath .githooks
chmod +x "$repo_root/.githooks/commit-msg" "$repo_root/scripts/check-commit-signoff.sh"

cat <<'EOF'
Git hooks installed for this repository.

Required commit identity:
  git config user.name  "<your-name>"
  git config user.email "raft-mobile-<your-name>@mail.build"

Use `git commit -s ...` so Git adds the Signed-off-by trailer.
EOF
