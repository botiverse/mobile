# React vs KMP Alignment: Auth Slice (2026-06-26)

Per @artin 02:03 "单独 worktree, 每一点建一个文档, 分析下我们对齐没有"
Owner: @Product-UX-Designer (spec lane)
Worktree: `Slock-worktrees/pux-feature-alignment-2026-06-26`
主仓 main HEAD: `87d41a70`

## Slice Scope: Auth

包含: login / register / forgot password / reset password / email verification / invite accept / server select / device login / human setup / OAuth social login

## React source of truth

`packages/web/src/components/auth/` (9 files):
- LoginPage.tsx (217 lines): email/password + Google/GitHub social + Legal acceptance
- RegisterPage.tsx (139 lines): name/email/password + social + legal
- ForgotPasswordPage.tsx (94 lines): email → reset link
- ResetPasswordPage.tsx (124 lines): token + new password
- EmailVerificationPage.tsx (172 lines): verify code
- InviteAcceptPage.tsx (308 lines): token + register with invite
- ServerSelector.tsx (172 lines): last server / create / join / community
- SocialAuthCallbackPage.tsx (179 lines): OAuth callback

## KMP source

`compose/shared/src/commonMain/kotlin/build/raft/app/AuthComponents.kt` + `AuthPages.kt` + `LoginContract.kt`
- 单文件 vs React 9 files
- 主要实现: login / register / forgot / auth diagnostics / server gate / choose server / create server

## Feature points inventory (06-23 → 06-26)

(24 features, 完整列表参考 `react-kmp-feature-map-2026-06-26.md` Slice 1)

主要类别:
1. **Server 选择** (4 commits): remember selected server per account, pin server recovery sign out, protect server selection from token races, make server chooser scrollable
2. **Logo + Branding** (6 commits): tune raft top logo size, render raft logo from svg asset, package raft logo with ohos assets, enlarge raft top bar logo, reduce raft logo intrinsic size, let raft top logo fill target size
3. **Legal + Casing** (3 commits): add login legal links, add reset password legal links, match reset password legal copy, align auth copy casing
4. **MMKV Session** (3 commits): reuse shared mmkv session store, store shared session in auth mmkv, package shared auth transport
5. **UI polish** (4 commits): default action buttons to large, hide mobile social entry points (per #308 future), add large create account button, render top brand without cached image
6. **Misc** (4 commits): shrink raft logo intrinsic size, wire app shell account actions, wire account recovery entry points, align auth copy casing

## 现状 vs React parity

### ✅ 已对齐 (06-23 → 06-26)
- Login (email + password)
- Register (name + email + password + legal)
- Forgot password
- Choose server (last + community join + create)
- Server remember per account (MMKV)
- Server recovery sign out
- Server selection race protection
- Server chooser scrollable
- Raft logo (SVG asset + ohos bundle + tuned size)
- Legal links
- MMKV session persistence
- Shared auth transport
- Auth copy casing alignment
- Large action button variant

### ⚠️ 接近对齐 (P1)
- Render top brand without cached image (P1, fallback path)
- Wire app shell account actions (entry points)

### ❌ 仍未对齐 (06-26 仍 P0)
1. **Email verification page** — KMP 没接 (`EmailVerificationPage.tsx` React, 缺 KMP)
2. **Reset password (with token)** — KMP 没接 (`ResetPasswordPage.tsx` React, 缺 KMP)
3. **Invite accept page** — KMP 没接 (`InviteAcceptPage.tsx` React, 缺 KMP)
4. **Social auth callback** — KMP 没接 (`SocialAuthCallbackPage.tsx` React, 缺 KMP)
5. **Google/GitHub social login** — 已隐藏 mobile entry points (per #308 future), 但 React UI 已 hide 配合 (#273 done audit auth page copy casing)
6. **Device login** — KMP 没接 (React 路径未明)
7. **Human login setup** — KMP 没接 (React 路径未明)

### ❌ 不在 React parity 范围 (KMP MVP 不需要 / 工程改造)
- Email verification (per #308 future task, 等服务端 ready)
- Reset password (per #308 future, 等服务端 ready)
- Invite accept (per #308 future, 等服务端 ready)
- Social auth callback (per #308 future OAuth handoff)
- Device login (per #308 future)
- Human login setup (per #308 future)

## P0/P1/P2 缺口分类

### P0 (本轮必修, KMP MVP)
- 5 个 route 接通 (login / register / forgot / choose-server / create-server) — 已 done
- Server remember per account — 已 done

### P1 (体验 / 视觉)
- Render top brand without cached image (fallback path)
- Logo size consistency (tuned 多轮)
- Auth copy casing alignment — done

### P2 (业务扩展 / future)
- Email verification (per #308 future task, AID/AAPI lane)
- Reset password (per #308 future, AID/AAPI lane)
- Invite accept (per #308 future, AID/AAPI lane)
- Social auth callback (per #308 future OAuth handoff)
- Device login (per #308 future, owner 拍)
- Human login setup (per #308 future, owner 拍)
- Google/GitHub OAuth handoff (per #308 future, server-supported mobile OAuth)
- Account recovery entry points (per #308 future)

## Owner 拍 (下一步)

- (i) 等 #308 future OAuth handoff server-supported (Cindy/AID lane)
- (ii) owner 显式拍 KMP MVP 是否需要 device login / human setup

我倾向 (i): 等 #308, 不下 fix decision. KMP MVP auth 基础已够 (#219 auth screens 全 done, #273 auth copy casing done).

PUX 不下 fix decision, 不写 code. 不动 source (worktree base = `87d41a70`).

owner: @Product-UX-Designer
date: 2026-06-26