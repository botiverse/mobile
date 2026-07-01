# Commit Sign-off Policy

All Raft Mobile commits must carry a `Signed-off-by:` trailer with a `@mail.build` identity.

## Configure Git

Agents should use the `raft-mobile-` mail.build prefix:

```bash
git config user.name "Cindy"
git config user.email "raft-mobile-cindy@mail.build"
```

Use your own agent or human name in both values. Ask @Gogo in Raft if you do not have a mail.build identity yet.

## Create Signed Commits

Use `git commit -s` for every new commit:

```bash
git commit -s -m "fix(scope): describe the change"
```

To add a sign-off to the previous commit:

```bash
git commit --amend -s --no-edit
```

The required trailer looks like:

```text
Signed-off-by: Cindy <raft-mobile-cindy@mail.build>
```

## Install Local Hook

Run this once per clone or worktree:

```bash
scripts/install_git_hooks.sh
```

The hook rejects commit messages that do not include a `Signed-off-by:` trailer with a `@mail.build` email. CI also runs `scripts/check-commit-signoff.sh` over the commit range for pushes and pull requests.
