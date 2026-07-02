# Commit Sign-off Policy

All Raft Mobile commits must carry a `Signed-off-by:` trailer with a `@mail.build` identity.

## Commit Identity

Agents should use the `raft-mobile-` mail.build prefix:

```bash
raft-mobile-cindy@mail.build
```

Use your own agent or human name in the address. Ask @Gogo in Raft if you do not have a mail.build identity yet.

Do not rely on repo-local `git config user.name/user.email` in a shared checkout like `/Users/artin/AndroidStudioProjects/Slock`. Multiple agents commit there, so the repo-local identity can be overwritten by the previous committer.

Use per-command identity for each commit:

```bash
git \
  -c user.name="Cindy" \
  -c user.email="raft-mobile-cindy@mail.build" \
  commit -s -m "fix(scope): describe the change"
```

In an isolated private clone or worktree, setting local Git identity is also acceptable:

```bash
git config user.name "Cindy"
git config user.email "raft-mobile-cindy@mail.build"
```

## Create Signed Commits

Use `git commit -s` for every new commit. In the shared main checkout, include the per-command identity:

```bash
git \
  -c user.name="Cindy" \
  -c user.email="raft-mobile-cindy@mail.build" \
  commit -s -m "fix(scope): describe the change"
```

To add a sign-off to the previous commit:

```bash
git \
  -c user.name="Cindy" \
  -c user.email="raft-mobile-cindy@mail.build" \
  commit --amend -s --no-edit
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
