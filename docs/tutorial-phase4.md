# Phase 4 — Auth 服務企業級升級

在這一章中，我們將把 Phase 3 的 Auth Service 從「可用」升級到「企業級」，加入：

| 功能 | 說明 |
|------|------|
| **Spring Security** | 標準化認證授權框架，filter chain 取代手動 header 解析 |
| **BCrypt** | 密碼單向雜湊，不再儲存明碼 |
| **jti + Refresh Token** | 完整的 JWT 規範，支援 Access / Refresh 雙 Token |
| **Flyway** | 資料庫 Schema 版本控制，取代 `schema.sql` |
| **@ControllerAdvice** | 統一錯誤處理，回傳 JSON 格式錯誤 |
| **API 版本化** | `/auth/v1/` 路徑前綴 |

## 4.1 Spring Security

### 4.1.1 加入 Spring Security 與 Flyway 依賴

修改 `auth-service/pom.xml`，在 `<dependencies>` 中加入三個新的 dependency：

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-security</artifactId>
</dependency>
<dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-core</artifactId>
</dependency>
<dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-database-postgresql</artifactId>
    <scope>runtime</scope>
</dependency>
```

- `spring-boot-starter-security`：Spring Security 的核心 starter
- `flyway-core` + `flyway-database-postgresql`：Flyway 資料庫遷移

完整檔案可直接複製：

```bash
cp samples/phase4/pom.xml auth-service/pom.xml
```

### 4.1.2 SecurityConfig

建立 `auth-service/src/main/java/com/example/auth/config/SecurityConfig.java`：

```bash
cp samples/phase4/SecurityConfig.java auth-service/src/main/java/com/example/auth/config/SecurityConfig.java
```

關鍵邏輯：

1. **停用 CSRF** — 因為我們使用 stateless JWT，不需要 CSRF 保護
2. **Stateless Session** — 不建立 HTTP Session，每次請求都獨立驗證
3. **公開路徑** — `/auth/v1/register`、`/auth/v1/login`、`/auth/v1/refresh`、`/auth/v1/health` 不需認證
4. **JwtAuthenticationFilter** — 在 `UsernamePasswordAuthenticationFilter` 之前插入，從 `Authorization: Bearer` header 解析 JWT
5. **AuthenticationEntryPoint** — 當未認證的請求抵達需要認證的 endpoint 時，回傳 401 JSON

### 4.1.3 JwtAuthenticationFilter

建立 `auth-service/src/main/java/com/example/auth/filter/JwtAuthenticationFilter.java`：

```bash
cp samples/phase4/JwtAuthenticationFilter.java auth-service/src/main/java/com/example/auth/filter/JwtAuthenticationFilter.java
```

這個 Filter 的運作流程：

```
Request → [JwtAuthenticationFilter] → [Spring Security] → [Controller]
                │
                ├─ Header 有 Bearer token? ──→ 解析 JWT ──→ 驗證簽章與逾期 ──→ OK → 設定 SecurityContext
                │                                     │                       │
                │                                     └─ 失敗 → 不設定         └─ 黑名單有 jti? → 不設定
                │
                └─ 沒有 token → 直接放行（讓 Security 決定是否拒絕）
```

值得注意的是：

- **黑名單檢查使用 jti**（Phase 3 是用整串 token），更有效率的 Redis key
- **將 jti 存到 `auth.setDetails(jti)`**，讓 controller 可以取用
- 即使黑名單命中，filter 也會繼續執行 `chain.doFilter`，不直接回傳錯誤。回傳 401 的工作交給 Spring Security 的 `AuthenticationEntryPoint`

### 4.1.4 UserDetailsServiceImpl

建立 `auth-service/src/main/java/com/example/auth/service/UserDetailsServiceImpl.java`：

```bash
cp samples/phase4/UserDetailsServiceImpl.java auth-service/src/main/java/com/example/auth/service/UserDetailsServiceImpl.java
```

此類別實作 `UserDetailsService` 介面：

- `loadUserByUsername()` → 從 PostgreSQL 查詢使用者
- 回傳 Spring Security 標準的 `UserDetails` 物件
- 包含 `ROLE_USER`（或 `ROLE_ADMIN`）權限

## 4.2 BCrypt 密碼編碼

### 4.2.1 PasswordEncoder Bean

在 `SecurityConfig.java` 中已經定義了 `PasswordEncoder` Bean：

```java
@Bean
public PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
}
```

### 4.2.2 UserRepository

建立 `auth-service/src/main/java/com/example/auth/repository/UserRepository.java`：

```bash
cp samples/phase4/UserRepository.java auth-service/src/main/java/com/example/auth/repository/UserRepository.java
```

```bash
cp samples/phase4/User.java auth-service/src/main/java/com/example/auth/model/User.java
```

`UserRepository` 使用 `JdbcTemplate`（與 Phase 3 相同）封裝兩個查詢：

- `findByUsername()` — `SELECT id, username, password, role FROM users WHERE username = ?`
- `save()` — `INSERT INTO users (username, password, role) VALUES (?, ?, ?)`

### 4.2.3 註冊流程（BCrypt）

在 `AuthController` 中，註冊時密碼經過 BCrypt 編碼：

```java
String hash = passwordEncoder.encode(password);
userRepository.save(username, hash, role);
```

登入時使用 `matches()` 比對：

```java
if (!passwordEncoder.matches(password, user.password())) {
    throw new BadCredentialsException("invalid username or password");
}
```

## 4.3 完整 JWT（jti + Refresh Token）

### 4.3.1 JwtUtil 升級

```bash
cp samples/phase4/JwtUtil.java auth-service/src/main/java/com/example/auth/JwtUtil.java
```

| 項目 | Phase 3 | Phase 4 |
|------|---------|---------|
| Claims | `sub` | `jti`, `sub`, `role`, `iat`, `exp` |
| 過期時間 | 1 小時 | 15 分鐘（可配置） |
| jti | 無 | UUID，用於黑名單與追蹤 |
| role | 無 | 在 JWT 中攜帶角色 |

### 4.3.2 `POST /auth/v1/refresh`

當 Access Token 過期時，Client 可以用 Refresh Token 換新的 Token Pair：

```
Request:  POST /auth/v1/refresh  body: refresh_token=alice:uuid
Response: { access_token, refresh_token, expires_in }
```

Refresh Token 格式為 `{username}:{uuid}`：

1. 拆解 `refresh_token` 為 username 與 uuid
2. 查詢 Redis `refresh:{username}:{uuid}`
3. 若存在，刪除舊記錄（單次使用）
4. 產生新的 Access Token 與 Refresh Token
5. 儲存新 Refresh Token 到 Redis，TTL 7 天

### 4.3.3 登出改為 jti-based

在 Phase 3，黑名單 key 為 `blacklist:{完整token}`。Phase 4 改為 `blacklist:{jti}`：

```java
// 從 SecurityContext 取出 jti
String jti = (String) auth.getDetails();
redis.opsForValue().set("blacklist:" + jti, "1", Duration.ofMillis(jwtUtil.getAccessTokenExpMs()));
```

好處：
- 更短的 Redis key
- 不需要儲存完整 token
- TTL 設定為 Access Token 的剩餘壽命

## 4.4 Flyway 資料庫遷移

### 4.4.1 為什麼要用 Flyway？

Phase 3 使用 `schema.sql` + `spring.sql.init.mode=always`，每次啟動都會重新執行。這在開發階段還行，但在正式環境會：
- 無法追蹤 schema 的版本變更
- 無法執行增量修改（如新增欄位）
- 有資料遺失風險

Flyway 解決了這些問題：每次 migration 只執行一次，並記錄在 `flyway_schema_history` 表中。

### 4.4.2 移除 schema.sql

```bash
rm auth-service/src/main/resources/schema.sql
```

### 4.4.3 建立 V1 Migration

建立 `auth-service/src/main/resources/db/migration/V1__create_users.sql`：

```bash
cp samples/phase4/V1__create_users.sql auth-service/src/main/resources/db/migration/V1__create_users.sql
```

```sql
CREATE TABLE IF NOT EXISTS users (
    id BIGSERIAL PRIMARY KEY,
    username VARCHAR(100) NOT NULL UNIQUE,
    password VARCHAR(255) NOT NULL,
    role VARCHAR(20) NOT NULL DEFAULT 'USER',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
```

注意這裡使用了 `CREATE TABLE IF NOT EXISTS`，讓同一個 migration 可以安全地重複執行。這在開發環境中特別有用（例如容器重建時）。

### 4.4.4 建立 V2 Migration

建立 `auth-service/src/main/resources/db/migration/V2__add_role_column.sql`：

```bash
cp samples/phase4/V2__add_role_column.sql auth-service/src/main/resources/db/migration/V2__add_role_column.sql
```

```sql
ALTER TABLE users ADD COLUMN IF NOT EXISTS role VARCHAR(20) NOT NULL DEFAULT 'USER';
```

這個 migration 展示了 Flyway 的增量能力：當 schema 需要變更時，建立新的 migration 檔案，Flyway 自動計算並執行尚未執行的 migration。

### 4.4.5 修改 application.properties

```bash
cp samples/phase4/application.properties auth-service/src/main/resources/application.properties
```

主要變更：

| 舊設定 | 新設定 |
|--------|--------|
| `spring.sql.init.mode=always` | `spring.flyway.enabled=true` |
| `spring.sql.init.schema-locations=...` | `spring.flyway.baseline-on-migrate=true` |
| `jwt.expiration-ms=3600000` | `jwt.access-token-expiration-ms=900000` |
| — | `jwt.refresh-token-expiration-ms=604800000` |

`spring.flyway.baseline-on-migrate=true` 表示當資料庫已有資料表時（例如從 Phase 3 升級），Flyway 會自動建立 baseline 而不會報錯。

## 4.5 @ControllerAdvice 統一錯誤處理

### 4.5.1 ErrorResponse

```bash
cp samples/phase4/ErrorResponse.java auth-service/src/main/java/com/example/auth/handler/ErrorResponse.java
```

一個簡單的 `record`，統一錯誤回傳格式：

```json
{"code": "BAD_REQUEST", "message": "username already exists"}
```

### 4.5.2 GlobalExceptionHandler

```bash
cp samples/phase4/GlobalExceptionHandler.java auth-service/src/main/java/com/example/auth/handler/GlobalExceptionHandler.java
```

處理的例外類型與對應 HTTP 狀態碼：

| 例外 | HTTP 狀態 | Code |
|------|-----------|------|
| `IllegalArgumentException` | 400 | `BAD_REQUEST` |
| `MissingServletRequestParameterException` | 400 | `MISSING_PARAM` |
| `BadCredentialsException` | 401 | `INVALID_CREDENTIALS` |
| `UsernameNotFoundException` | 401 | `INVALID_CREDENTIALS` |
| 其他 Exception | 500 | `INTERNAL_ERROR` |

## 4.6 API 版本化

所有端點從 `/auth/` 改為 `/auth/v1/`：

| 功能 | 端點 |
|------|------|
| 註冊 | `POST /auth/v1/register` |
| 登入 | `POST /auth/v1/login` |
| 驗證 | `GET  /auth/v1/verify` |
| 登出 | `POST /auth/v1/logout` |
| 刷新 Token | `POST /auth/v1/refresh` |
| 健康檢查 | `GET /auth/v1/health` |

### Nginx 設定

Nginx 的設定不需要修改 — `location /auth/` 已經會把 `/auth/v1/...` 的請求轉發到 auth-service：

```nginx
location /auth/ {
    proxy_pass http://auth:8081;
}
```

## 4.7 驗證測試

### 4.7.1 重啟服務

```bash
docker compose down
docker compose up -d
```

### 4.7.2 健康檢查

```bash
curl http://localhost:28080/auth/v1/health
# {"status":"ok","service":"auth-service"}
```

### 4.7.3 註冊

```bash
curl -s -X POST http://localhost:28080/auth/v1/register \
  -d "username=alice&password=Str0ng!Pass" | jq .
# {"status":"ok","message":"user registered"}
```

### 4.7.4 嘗試重複註冊

```bash
curl -s -X POST http://localhost:28080/auth/v1/register \
  -d "username=alice&password=Str0ng!Pass" | jq .
# {"code":"BAD_REQUEST","message":"username already exists"}
```

### 4.7.5 登入

```bash
curl -s -X POST http://localhost:28080/auth/v1/login \
  -d "username=alice&password=Str0ng!Pass" | jq .
# {
#   "status": "ok",
#   "access_token": "eyJ...",
#   "refresh_token": "alice:uuid",
#   "expires_in": 900
# }
```

### 4.7.6 驗證 Token

```bash
TOKEN="<access_token from login>"
curl -s http://localhost:28080/auth/v1/verify \
  -H "Authorization: Bearer $TOKEN" | jq .
# {"status":"ok","username":"alice","role":"USER","jti":"<uuid>"}
```

### 4.7.7 無 Token 請求

```bash
curl -s http://localhost:28080/auth/v1/verify | jq .
# {"code":"UNAUTHORIZED","message":"missing or invalid token"}
```

### 4.7.8 刷新 Token

```bash
REFRESH_TOKEN="<refresh_token from login>"
curl -s -X POST http://localhost:28080/auth/v1/refresh \
  -d "refresh_token=$REFRESH_TOKEN" | jq .
# {
#   "status": "ok",
#   "access_token": "eyJ...",
#   "refresh_token": "alice:new-uuid",
#   "expires_in": 900
# }
```

### 4.7.9 重複使用 Refresh Token

```bash
# 第二次使用同一個 refresh_token 應該失敗
curl -s -X POST http://localhost:28080/auth/v1/refresh \
  -d "refresh_token=$REFRESH_TOKEN" | jq .
# {"code":"INVALID_CREDENTIALS","message":"invalid or expired refresh token"}
```

### 4.7.10 登出

```bash
curl -s -X POST http://localhost:28080/auth/v1/logout \
  -H "Authorization: Bearer $TOKEN" | jq .
# {"status":"ok","message":"logged out"}
```

### 4.7.11 登出後再次驗證

```bash
curl -s http://localhost:28080/auth/v1/verify \
  -H "Authorization: Bearer $TOKEN" | jq .
# {"code":"UNAUTHORIZED","message":"missing or invalid token"}
```

## 4.8 架構總覽

```
Browser                            auth-service (8081)
  │                                    │
  ├─ POST /auth/v1/register ──────────→ AuthController
  │   (username, password)              ├─ BCrypt.encode(password)
  │                                    ├─ UserRepository.save()
  │                                    └─ PostgreSQL INSERT
  │
  ├─ POST /auth/v1/login ─────────────→ AuthController
  │   (username, password)              ├─ UserRepository.findByUsername()
  │                                    ├─ BCrypt.matches(password, hash)
  │                                    ├─ JwtUtil.generateAccessToken()
  │                                    │   └─ jti + sub + role + iat + exp
  │                                    ├─ Redis SET refresh:{user}:{uuid}
  │                                    └─ Return tokens
  │
  ├─ GET  /auth/v1/verify ────────────→ JwtAuthenticationFilter
  │   Authorization: Bearer <jwt>        ├─ JwtUtil.validateAndGetClaims()
  │                                    ├─ Redis HASKEY blacklist:{jti}
  │                                    ├─ Set SecurityContext
  │                                    └─ AuthController.verify()
  │
  ├─ POST /auth/v1/logout ────────────→ JwtAuthenticationFilter
  │   Authorization: Bearer <jwt>        ├─ (same verify flow)
  │                                    └─ AuthController.logout()
  │                                        └─ Redis SET blacklist:{jti}
  │
  └─ POST /auth/v1/refresh ───────────→ AuthController
      (refresh_token)                    ├─ Redis GETANDDELETE refresh:{user}:{uuid}
                                        ├─ JwtUtil.generateAccessToken()
                                        └─ Redis SET refresh:{user}:{new-uuid}
```

## 附錄 A：Phase 4 變更摘要

與 Phase 3 相比，Phase 4 共新增/修改了以下檔案：

| 檔案 | 狀態 | 說明 |
|------|------|------|
| `pom.xml` | 修改 | +spring-boot-starter-security, +flyway-core |
| `application.properties` | 修改 | Flyway 設定、jwt 雙 expiration |
| `schema.sql` | 刪除 | 由 Flyway migrations 取代 |
| `AuthController.java` | 重寫 | BCrypt, jti, refresh, /v1/ |
| `JwtUtil.java` | 重寫 | jti, role, 15min access |
| `config/SecurityConfig.java` | 新增 | filter chain, BCrypt bean |
| `filter/JwtAuthenticationFilter.java` | 新增 | Bearer 解析, 黑名單檢查 |
| `service/UserDetailsServiceImpl.java` | 新增 | UserDetailsService 實作 |
| `model/User.java` | 新增 | 資料模型 record |
| `repository/UserRepository.java` | 新增 | JDBC DAO |
| `handler/ErrorResponse.java` | 新增 | 統一錯誤格式 |
| `handler/GlobalExceptionHandler.java` | 新增 | 統一例外處理 |
| `db/migration/V1__create_users.sql` | 新增 | Flyway 初始 migration |
| `db/migration/V2__add_role_column.sql` | 新增 | Flyway 增量 migration |

## 附錄 B：常見問題

### B.1 Flyway 報錯 "Found non-empty schema without schema history table"

如果 `baseline-on-migrate=true` 沒有解決，可以手動 baseline：

```bash
# 在容器中執行
docker compose exec -T auth mvn flyway:baseline -Dflyway.baselineVersion=0
```

或者清空資料庫重新開始：

```bash
docker compose down -v   # 會刪除 volume，包含 PostgreSQL 資料
docker compose up -d
```

### B.2 舊的 Nginx 快取

如果清除瀏覽器快取後仍看到舊頁面，可以用 `curl` 確認 API 端點：

```bash
curl http://localhost:28080/auth/v1/health  # 應該回傳新版 JSON
curl http://localhost:28080/auth/health     # 應該回傳 401 (由 Spring Security 保護)
```

### B.3 Login 回傳 403

Phase 4 使用 `/auth/v1/login`，不是 `/auth/login`。確認前端與 curl 都使用新端點。

## 附錄 C：Phase 4 概念圖

```
┌─────────────────────────────────────────────────────────┐
│                    Spring Security                       │
│  ┌──────────┐  ┌──────────────────┐  ┌──────────────┐  │
│  │ Security  │  │ JwtAuthentication│  │  Endpoint    │  │
│  │ Config    │  │ Filter           │  │  Security    │  │
│  │          │  │                  │  │              │  │
│  │ permit:  │  │ 1. Parse Bearer │  │ /register:   │  │
│  │ /register│  │ 2. Validate JWT │  │   permitAll  │  │
│  │ /login   │  │ 3. Check Redis  │  │ /login:      │  │
│  │ /refresh │  │ 4. Set Auth in  │  │   permitAll  │  │
│  │ /health  │  │    SecurityCtx  │  │ /verify:     │  │
│  │          │  │                  │  │   authenticated│
│  │ stateless│  │ 5. chain.doFilter│  │ /logout:     │  │
│  │ no CSRF  │  │                  │  │   authenticated│
│  └──────────┘  └──────────────────┘  └──────────────┘  │
└─────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────┐
│                    JWT Token Structure                   │
│                                                         │
│  Header:  { "alg": "HS256" }                            │
│  Payload: {                                             │
│    "jti":  "550e8400-e29b-41d4-a716-446655440000",     │
│    "sub":  "alice",                                      │
│    "role": "USER",                                       │
│    "iat":  1700000000,                                   │
│    "exp":  1700000900   ← 15 minutes                     │
│  }                                                       │
│  Sign:    HMAC-SHA256( base64(header) || "." ||          │
│                       base64(payload), secret )           │
└─────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────┐
│                    Flyway Migration                      │
│                                                         │
│  db/migration/                                          │
│  ├── V1__create_users.sql     ← 已執行                   │
│  └── V2__add_role_column.sql  ← 已執行                   │
│                                                         │
│  PostgreSQL: flyway_schema_history                       │
│  ┌──────┬─────────────────────────┬──────────┐          │
│  │ vers │ description             │ success  │          │
│  ├──────┼─────────────────────────┼──────────┤          │
│  │ 1    │ create users           │ true     │          │
│  │ 2    │ add role column        │ true     │          │
│  └──────┴─────────────────────────┴──────────┘          │
└─────────────────────────────────────────────────────────┘
```

---

**Previous**: Phase 3 — Auth 服務 from Scratch
**Next**: Phase 5 — 正式環境強化 (Production Hardening)
