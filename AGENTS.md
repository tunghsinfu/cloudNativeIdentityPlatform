# AI 協作規範

## 工作範疇

本專案為 Cloud Native Identity Platform，涵蓋：

1. **實作開發** — 撰寫 Java/Spring Boot 程式碼
2. **教學文件** — 每個 Phase 對應一份 `docs/tutorial-phaseN.md`
3. **樣板程式** — 每個 Phase 的關鍵檔案另存至 `samples/phaseN/`
4. **Git 版本控制** — 每完成一個 Phase 就 commit
5. **基礎設施** — Docker Compose、Nginx、k3s YAML

## Phase 開發週期

每個 Phase 的標準流程：

```
1. 讀取 PLAN.md 與 docs/tutorial-phaseN-1.md 了解上下文
2. 實作程式碼（Java / Dockerfile / config）
3. 更新 infra 設定（docker-compose.yml / nginx.conf 等）
4. 撰寫 docs/tutorial-phaseN.md 教學文件
5. 將關鍵檔案複製到 samples/phaseN/ 作為樣板
6. git add + git commit（訊息格式見下方）
```

## Git Commit 規範

- 每完成一個 Phase（或獨立子步驟）就 commit
- 訊息格式：`Phase N — 標題`

```
Phase 7 — User Service

- user-service: Spring Boot, port 8082, shared JWT secret
- UserController: GET/POST /user/v1/profile
- Flyway: V1__create_user_profiles.sql
- docker-compose.yml: add user service
- nginx.conf: add /user/ → user:8082 route
...
```

## 命名規範

### Java

| 項目 | 慣例 | 範例 |
|------|------|------|
| Package | `com.example.{service}` | `com.example.auth`, `com.example.user` |
| Controller | `{Name}Controller` | `AuthController`, `UserController` |
| Repository | `{Name}Repository` | `UserRepository`, `UserProfileRepository` |
| Model | `record` | `User`, `UserProfile` |
| Util | `{Name}Util` | `JwtUtil` |
| Config | `{Name}Config` | `SecurityConfig`, `CorsConfig` |
| Filter | `{Name}Filter` | `JwtAuthenticationFilter` |
| Handler | `{Name}Handler` | `GlobalExceptionHandler` |

### API 端點

- 版本前綴：`/auth/v1/`, `/user/v1/`
- Health check：`/auth/v1/health`, `/user/v1/health`

### 資料庫

- 表名：`snake_case` 複數（`users`, `user_profiles`）
- 欄位：`snake_case`（`display_name`, `avatar_url`）
- Flyway migration：`V{major}__{description}.sql`

### Docker

- 服務名：小寫（`auth`, `user`, `postgres`, `redis`）
- Container name：`{角色}-{服務}`（`auth-service`, `nginx-gateway`）
- Port mapping：`${VAR_NAME:-預設值}` 模式

## 驗證原則

1. **可獨立 build**：`mvn compile` 或 `docker build` 通過
2. **可獨立啟動**：`docker compose up <service>` 正常
3. **可 curl 驗證**：有明確的 request / expected response
4. **可 commit**：每個 Phase 一個 git commit

## 環境注意

- 開發環境無 Java/Maven/Docker，編譯需透過 `docker build` 驗證
- `.env` 已 gitignored，使用 `.env.example` 作為樣板
- 專案根目錄：`/workspace/cloudNativeIdentityPlatform/`
