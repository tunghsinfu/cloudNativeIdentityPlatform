# Cloud Native Identity Platform — 架構規格書

## 1. 系統概述

企業級身份認證與平台基礎服務。提供 JWT 認證、使用者管理、API Gateway、容器化部署、可觀測性與 AI 輔助維運的完整解決方案。

---

## 2. 系統架構

### 2.1 架構圖

```
                         User
                           |
                       [Nginx]  (80)
                     API Gateway
                           |
              +------------+------------+
              |            |            |
         /auth/*      /user/*       /api/*
              |            |            |
        [auth-service] [user-service] [app] (legacy demo)
          :8081          :8082        :8080
              |            |
              +-----+------+
                    |
          +---------+---------+
          |         |         |
      [Redis]   [PostgreSQL]  |
    6379        5432          |
    (blacklist  (users,      |
     rate-limit  profiles)   |
     cache)                  |
                        [Kafka] (optional)
                        user-created events
```

### 2.2 服務職責

| 服務 | 角色 | 技術棧 | 埠號 |
|------|------|--------|------|
| `nginx` | API Gateway / Reverse Proxy | Nginx (Alpine) | 80 |
| `auth-service` | 身份認證（JWT 簽發/驗證） | Spring Boot 3 + Spring Security | 8081 |
| `user-service` | 使用者 CRUD | Spring Boot 3 | 8082 |
| `postgres` | 主資料庫 | PostgreSQL 16 Alpine | 5432 |
| `redis` | 快取 / Blacklist / Rate Limit | Redis 7 Alpine | 6379 |
| `kafka` | 事件驅動（可選） | Kafka + Zookeeper | 9092 |
| `prometheus` | 指標收集 | Prometheus | 9090 |
| `grafana` | 可視化儀表板 | Grafana | 3000 |
| `loki` | 日誌聚合 | Loki | 3100 |

---

## 3. 目錄結構

```
/workspace/
│
├── auth-service/                  # Phase 3-5
│   ├── Dockerfile
│   ├── pom.xml
│   └── src/main/
│       ├── java/com/example/auth/
│       │   ├── AuthApplication.java
│       │   ├── config/
│       │   │   └── SecurityConfig.java
│       │   ├── controller/
│       │   │   └── AuthController.java
│       │   ├── filter/
│       │   │   └── JwtAuthenticationFilter.java
│       │   ├── service/
│       │   │   ├── JwtUtil.java
│       │   │   ├── UserDetailsServiceImpl.java
│       │   │   └── RateLimitService.java
│       │   ├── model/
│       │   │   └── User.java
│       │   ├── repository/
│       │   │   └── UserRepository.java
│       │   └── handler/
│       │       └── GlobalExceptionHandler.java
│       └── resources/
│           ├── application.properties
│           ├── logback-spring.xml
│           └── db/migration/
│               ├── V1__create_users.sql
│               └── V2__add_role_column.sql
│
├── user-service/                  # Phase 7
│   ├── Dockerfile
│   ├── pom.xml
│   └── src/main/
│       ├── java/com/example/user/
│       │   ├── UserApplication.java
│       │   ├── controller/
│       │   │   └── UserController.java
│       │   ├── service/
│       │   │   ├── UserService.java
│       │   │   └── JwtValidator.java
│       │   └── model/
│       │       └── UserProfile.java
│       └── resources/
│           ├── application.properties
│           └── db/migration/
│               └── V1__create_user_profiles.sql
│
├── nginx/                         # Phase 2
│   ├── Dockerfile
│   ├── nginx.conf
│   └── index.html
│
├── k8s/                           # Phase 8
│   ├── namespace.yaml
│   ├── postgres-statefulset.yaml
│   ├── redis-statefulset.yaml
│   ├── auth-deployment.yaml
│   ├── user-deployment.yaml
│   ├── nginx-deployment.yaml
│   ├── ingress.yaml
│   └── secrets.yaml
│
├── monitoring/                    # Phase 9
│   ├── prometheus.yml
│   ├── grafana-dashboard.json
│   ├── loki-config.yml
│   └── promtail-config.yml
│
├── docs/                          # Phase 11
│   ├── phase-0-knowledge.md
│   ├── advanced-topics.md
│   └── interview-questions.md
│
├── .env                           # gitignored
├── .gitignore
├── Makefile
├── docker-compose.yml
├── PLAN.md
└── README.md
```

---

## 4. API 規格

### 4.1 Auth Service (`/auth/v1/`)

| 方法 | 路徑 | 認證 | 說明 |
|------|------|------|------|
| POST | `/auth/v1/register` | ❌ | 註冊使用者 |
| POST | `/auth/v1/login` | ❌ | 登入，回傳 access + refresh token |
| GET | `/auth/v1/verify` | ✅ Bearer | 驗證 access token，回傳使用者資訊 |
| POST | `/auth/v1/logout` | ✅ Bearer | 登出，將 token jti 加入 Redis blacklist |
| POST | `/auth/v1/refresh` | ❌ (需 refresh token) | 用 refresh token 換新 access token |
| GET | `/auth/v1/health` | ❌ | 健康檢查（DB + Redis）|

#### 4.1.1 POST /auth/v1/register

```
Request:
  POST /auth/v1/register
  Content-Type: application/x-www-form-urlencoded
  Body: username=alice&password=Str0ng!Pass&role=USER

Response 201:
  {"status":"ok","message":"user created","username":"alice"}

Response 400:
  {"code":"VALIDATION_ERROR","message":"password must be at least 8 characters"}
```

#### 4.1.2 POST /auth/v1/login

```
Request:
  POST /auth/v1/login
  Body: username=alice&password=Str0ng!Pass

Response 200:
  {
    "status":"ok",
    "access_token":"eyJhbGciOiJIUzI1NiJ9...",
    "refresh_token":"dGhpcyBpcyBh...",
    "expires_in":900
  }

Response 401:
  {"code":"INVALID_CREDENTIALS","message":"invalid username or password"}

Response 429 (rate limit):
  {"code":"TOO_MANY_REQUESTS","message":"account locked for 10 minutes"}
```

#### 4.1.3 GET /auth/v1/verify

```
Request:
  GET /auth/v1/verify
  Authorization: Bearer eyJhbGciOiJIUzI1NiJ9...

Response 200:
  {"status":"ok","username":"alice","role":"USER","jti":"uuid-xxx"}

Response 401:
  {"code":"TOKEN_BLACKLISTED","message":"token has been revoked"}
```

#### 4.1.4 POST /auth/v1/logout

```
Request:
  POST /auth/v1/logout
  Authorization: Bearer eyJhbGciOiJIUzI1NiJ9...

Response 200:
  {"status":"ok","message":"logged out"}
```

#### 4.1.5 POST /auth/v1/refresh

```
Request:
  POST /auth/v1/refresh
  Content-Type: application/json
  Body: {"refresh_token":"dGhpcyBpcyBh..."}

Response 200:
  {
    "access_token":"eyJhbGciOiJIUzI1NiJ9...",
    "refresh_token":"bmV3IHJlZnJl...",
    "expires_in":900
  }
```

### 4.2 User Service (`/user/v1/`)

| 方法 | 路徑 | 認證 | 說明 |
|------|------|------|------|
| GET | `/user/v1/profile` | ✅ Bearer | 取得當前使用者個人資料 |
| PUT | `/user/v1/profile` | ✅ Bearer | 更新個人資料 |
| GET | `/user/v1/health` | ❌ | 健康檢查 |

#### 4.2.1 GET /user/v1/profile

```
Request:
  GET /user/v1/profile
  Authorization: Bearer eyJhbGciOiJIUzI1NiJ9...

Response 200:
  {"username":"alice","email":"alice@example.com","displayName":"Alice","avatarUrl":"https://..."}
```

---

## 5. 資料模型

### 5.1 PostgreSQL — users（auth-service）

```sql
CREATE TABLE users (
    id          SERIAL PRIMARY KEY,
    username    VARCHAR(50) UNIQUE NOT NULL,
    password    VARCHAR(255) NOT NULL,           -- BCrypt hash
    role        VARCHAR(20) NOT NULL DEFAULT 'USER',
    created_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
```

### 5.2 PostgreSQL — user_profiles（user-service）

```sql
CREATE TABLE user_profiles (
    id           SERIAL PRIMARY KEY,
    username     VARCHAR(50) UNIQUE NOT NULL,
    email        VARCHAR(255),
    display_name VARCHAR(100),
    avatar_url   VARCHAR(500),
    created_at   TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at   TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
```

### 5.3 Redis Keys 設計

| Key Pattern | Type | TTL | 用途 |
|------------|------|-----|------|
| `blacklist:{jti}` | string | token 剩餘時間 | Token 黑名單 |
| `login_fail:{username}` | string | 600s | 登入失敗計數 |
| `refresh:{username}:{uuid}` | string | 7 天 | Refresh token |
| `user:{id}` | hash | 300s | User profile cache |

---

## 6. 資料流向

### 6.1 Login

```
User → POST /auth/v1/login
        → SecurityConfig (permitAll)
        → AuthController.login()
            → UserDetailsServiceImpl (DB)
            → BCryptPasswordEncoder.matches()
            → JwtUtil.generateToken() (含 jti, sub, role, iat, exp)
            → Redis SET refresh:{username}:{uuid}
            → return { access_token, refresh_token }
```

### 6.2 Authenticated Request

```
User → GET /user/v1/profile
        → Nginx → /user/ → user-service:8082
        → JwtAuthenticationFilter
            → 解析 Authorization: Bearer xxx
            → JwtUtil.validateAndGetUsername()
            → Redis EXISTS blacklist:{jti}
            → SecurityContextHolder.setAuthentication()
        → UserController.profile()
            → Redis GET user:{username}
                → miss → PostgreSQL → Redis SET(user:{username}, TTL 300)
            → return profile
```

### 6.3 Logout

```
User → POST /auth/v1/logout
        → AuthController.logout()
            → 解析 token，取出 jti
            → Redis SET blacklist:{jti} = "1"
            → Redis EXPIRE blacklist:{jti} = token 剩餘時間
            → Redis DEL refresh:{username}:{uuid}
            → return ok
```

---

## 7. 技術決策記錄

| 決策 | 選擇 | 替代方案 | 理由 |
|------|------|---------|------|
| JWT vs Session | JWT | Session | 微服務間不需共享 session store |
| JWT secret | HMAC-SHA256 (共用 secret) | RSA key pair | 初期簡單，後續可升級 RSA |
| Token blacklist | Redis | DB | O(1) 查詢，自動 TTL 過期 |
| Password hash | BCrypt | Argon2, scrypt | Spring Security 原生支援 |
| DB migration | Flyway | Liquibase, schema.sql | 版本化、不可修改 |
| API Gateway | Nginx | Spring Cloud Gateway | 輕量，不需 JVM |
| Container orchestration | k3s (Docker Compose 開發) | full K8s | 單機部署，資源需求低 |

---

## 8. 非功能性需求

### 8.1 安全性

| 項目 | 實作方式 |
|------|---------|
| 密碼儲存 | BCrypt（cost=10） |
| Token 儲存 | 僅存 blacklist，不存完整 token |
| 容器安全 | 非 root user 執行 |
| 機密管理 | `.env`（開發）/ Kubernetes Secret（生產） |
| Rate Limiting | Redis INCR + TTL |
| CORS | 限定前端來源 |

### 8.2 彈性

| 故障情境 | 行為 | 補救 |
|---------|------|------|
| Redis 掛掉 | Blacklist 降級（只檢查 exp） | Redis 重啟後自動恢復 |
| PostgreSQL 掛掉 | Login / Register 不可用 | 唯讀 cache 仍可服務 |
| Auth Service 掛掉 | 已簽發 token 仍有效直到過期 | K8s 自動重啟 |

### 8.3 可觀測性

| 面向 | 工具 | 指標 |
|------|------|------|
| Metrics | Prometheus + Micrometer | login_success_total, login_failure_total, jvm_memory, http_request_duration |
| Logging | Loki + logstash-logback-encoder | 結構化 JSON（timestamp, level, logger, message, username）|
| Dashboard | Grafana | Login success rate, API latency, Redis hit ratio |
| Health | Spring Boot Actuator | `/actuator/health`, `/actuator/health/liveness`, `/actuator/health/readiness` |

---

## 9. 部署拓撲

### 9.1 開發環境（Docker Compose）

```
單一主機
  ├── docker-compose.yml
  └── .env
```

### 9.2 生產環境（k3s）

```
Oracle VM
  └── k3s
       └── namespace: identity
            ├── auth-service (Deployment: 2 replicas)
            ├── user-service (Deployment: 2 replicas)
            ├── nginx (Deployment: 1 replica)
            ├── postgres (StatefulSet: 1 replica)
            ├── redis (StatefulSet: 1 replica)
            ├── ingress (Traefik)
            ├── prometheus
            └── grafana
```

---

## 10. 開發原則

### 10.1 編碼規範

- Java 21 + Spring Boot 3.3.x
- RESTful API 設計
- 所有端點統一錯誤格式：`{code: string, message: string}`
- Flyway migration 檔案不可修改已 merge 的版本

### 10.2 Commit 規範

```
<type>: <subject>

type: feat / fix / docs / refactor / test / chore
```

### 10.3 驗證原則

每個子步驟必須：

1. `mvn compile` 或 `docker build` 通過
2. `docker compose up <service>` 正常啟動
3. `curl` 有明確的 request → expected response
4. 一個子步驟一個 commit
