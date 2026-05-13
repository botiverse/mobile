# Android Auth API Contract

Task: #68

Source: downloaded web bundle `Server_files/index-BaD09UKb.js` checked on 2026-05-13.

## Endpoints

| Method | Path | Body | Android landing point |
| --- | --- | --- | --- |
| `POST` | `/api/auth/login` | `{ "email": string, "password": string }` | `KtorAuthRepository.login()` |
| `POST` | `/api/auth/register` | `{ "email": string, "password": string, "name": string }` | `KtorAuthRepository.register()` |
| `POST` | `/api/auth/forgot-password` | `{ "email": string }` | `KtorAuthRepository.forgotPassword()` |
| `POST` | `/api/auth/refresh` | `{ "refreshToken": string }` | `KtorAuthRepository.refresh()` / `AuthTokenRefresher` |
| `GET` | `/api/auth/me` | none | `KtorAuthRepository.currentUser()` |
| `POST` | `/api/auth/logout` | `{ "refreshToken": string }` | `KtorAuthRepository.logout()` |

## Response Semantics

- Login/register return `{ user, accessToken, refreshToken }`.
- Android saves login/register tokens into `AuthSessionStore`, then fetches `/api/servers` to populate server selection.
- Forgot-password success only needs a successful status. Android does not mutate local auth session.
- Error bodies may include `{ "error": string }`; Android maps that to `AuthResult.Failure.message`.

## Regression Coverage

- `AuthRepositoryTest.registerStoresSessionAndSendsWebCompatibleBody`
- `AuthRepositoryTest.forgotPasswordPostsEmailWithoutChangingSession`
- `AuthRepositoryTest.forgotPasswordValidatesEmailBeforeCallingApi`
