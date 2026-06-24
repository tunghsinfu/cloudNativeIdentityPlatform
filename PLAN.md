# Cloud Native Identity Platform — 實作計畫

## 專案定位

> AI-augmented Backend / Platform Engineer 養成專案

不是另一個 CRUD demo，而是一個能展示**企業級身份認證與平台基礎服務**的完整作品。涵蓋：

- Spring Security + JWT
- Redis（Token Blacklist / Rate Limit / Cache）
- PostgreSQL
- Docker / Docker Compose / k3s
- Observability（Prometheus + Grafana + Loki）
- AI 輔助維運（Claude Code / Cursor）

---

## 設計原則

### 見林優先，見樹在後

Phase 劃分以「功能主線」為優先，不讓細節阻礙整體進度：

| 層級 | 作用 | 例子 |
|------|------|------|
| **Phase** | 一條完整的功能主線 | Auth Service、Redis、Deployment |
| **子步驟** | 可獨立 build + 驗證的最小單位 | BCrypt、Flyway、Graceful Shutdown |
| **進階項目** | 面試加分細節（獨立文件） | CDN、gRPC、OAuth2 社交登入 |

進階項目另整理於 `/docs/advanced-topics.md`，不塞入主線避免失焦。

---

## 現狀評估（Phase 0 — 已完成）

| 步驟 | 內容 | 驗證方式 |
|------|------|---------|
| 0.0 | Git repo + 目錄結構 | `git log` |
| 0.1 | `spring-boot-demo/` — 基礎 Spring Boot 3 + Maven | `mvn compile` |
| 0.2 | `nginx/` — API Gateway（反向代理 `/api/`） | `curl localhost/api/` |
| 0.3 | `docker-compose.yml` — app + nginx + postgres + redis | `docker compose up -d` |
| 0.4 | `.env` + `.gitignore` — 環境變數與機密管理 | `docker compose config` |
| 0.5 | `GET /config` — 讀取環境變數 | `curl localhost:8080/config` |
| 0.6 | `GET /db-check` — PostgreSQL + Redis 驗證 | `curl localhost:8080/db-check` |
| 0.7 | `auth-service/` — 基本 JWT（register / login / verify / logout） | `curl localhost/auth/health` |
| 0.8 | Nginx `/auth/` 路由 | `curl localhost/auth/health` |
| 0.9 | Dockerfile 非 root 使用者 | `docker run --rm demo id` → `appuser` |
| 0.10 | Spring Boot Layered JAR | `docker build` 觀察 layer cache |
| 0.11 | Makefile（常用指令捷徑） | `make build` |

---

## Phase 1 — Auth Service 核心強化（2 週）

將 Phase 0 的簡易 JWT 升級為企業級身份認證服務。

### 1.1 Spring Security 導入

| # | 步驟 | 說明 | 驗證 |
|---|------|------|------|
| 1.1.1 | 加入 `spring-boot-starter-security` | pom.xml | `mvn compile` |
| 1.1.2 | `SecurityConfig.java` — permit `/auth/login`,`/auth/register`，其餘需認證 | `SecurityFilterChain` | 無 token → 401 |
| 1.1.3 | `JwtAuthenticationFilter` — 從 header 解析 token，寫入 SecurityContext | `OncePerRequestFilter` | 有效 token → 200 |
| 1.1.4 | `UserDetailsServiceImpl` — 從 DB 載入使用者 | `UserDetailsService` | login 正確檢查密碼 |

### 1.2 完整 JWT（jti + Refresh Token）

| # | 步驟 | 驗證 |
|---|------|------|
| 1.2.1 | JWT 加入 `jti`（UUID） | decode token 看 payload |
| 1.2.2 | JWT 加入 `role` claim | payload 含 role |
| 1.2.3 | `POST /auth/refresh` — refresh token 換新 access token | 舊 token 過期後 refresh 成功 |
| 1.2.4 | access token 15 分鐘，refresh token 7 天 | 實測過期行為 |

### 1.3 BCrypt 密碼編碼

> 當前問題：auth-service 存明碼密碼，這是 production 不可接受的安全缺失。

| # | 步驟 | 驗證 |
|---|------|------|
| 1.3.1 | register 使用 `BCryptPasswordEncoder.encode()` | DB 密碼欄位為 hash 值 |
| 1.3.2 | login 使用 `BCryptPasswordEncoder.matches()` | 正確密碼可登入，錯誤密碼拒絕 |

### 1.4 Flyway 資料庫遷移（取代 schema.sql）

| # | 步驟 | 說明 |
|---|------|------|
| 1.4.1 | 加入 flyway-core 依賴 | pom.xml |
| 1.4.2 | 移除 `spring.sql.init.*`，改為 `V1__create_users.sql` | schema 版本化 |
| 1.4.3 | 建立 `V2__add_role_column.sql` 展示版本演化 | `kubectl exec` 連 DB 確認 |

### 1.5 @ControllerAdvice 全域異常處理

| # | 步驟 | 驗證 |
|---|------|------|
| 1.5.1 | 建立 `GlobalExceptionHandler` + `ErrorResponse` | 所有錯誤回傳 `{code, message}` 一致格式 |
| 1.5.2 | 處理驗證失敗、權限不足、500 等情境 | `curl -v` 確認 HTTP status |

### 1.6 API 版本策略

決定採用 URL path 版本（`/auth/v1/login`），在 Controller 層實作。

```
/auth/v1/register
/auth/v1/login
/auth/v1/verify
/auth/v1/logout
/auth/v1/refresh
```

---

## Phase 1.5 — Production Hardening（1 週）

核心功能完成後，加入企業級運行所需的穩定性與安全性設置。**這個 Phase 是區分「demo」與「production-ready」的關鍵。**

### 1.5.1 Graceful Shutdown

```yaml
# application.properties
server.shutdown=graceful
spring.lifecycle.timeout-per-shutdown-phase=30s

# docker-compose.yml
stop_grace_period: 45s
```

**驗證**：`docker compose stop` 觀察日誌顯示正在處理中的請求完成後才關閉。

### 1.5.2 HikariCP Connection Pool Tuning

```properties
spring.datasource.hikari.maximum-pool-size=10
spring.datasource.hikari.minimum-idle=5
spring.datasource.hikari.connection-timeout=5000
spring.datasource.hikari.idle-timeout=300000
spring.datasource.hikari.max-lifetime=600000
spring.datasource.hikari.leak-detection-threshold=10000
```

**驗證**：`/actuator/health` 顯示 DB 連線狀態，`/actuator/metrics` 查看 pool 用量。

### 1.5.3 Docker Resource Limits

```yaml
services:
  auth:
    deploy:
      resources:
        limits:
          cpus: '0.5'
          memory: 512M
```

**驗證**：`docker stats` 確認上限生效。

### 1.5.4 CORS Configuration

| # | 步驟 | 說明 |
|---|------|------|
| 1.5.4.1 | 建立 `CorsConfig.java` | 允許 localhost:3000（React dev server） |
| 1.5.4.2 | 限定 methods / headers | GET, POST, PUT, DELETE |

### 1.5.5 Actuator（基礎端點）

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
```

```properties
management.endpoints.web.exposure.include=health,info
management.endpoint.health.show-details=always
management.endpoint.health.probes.enabled=true
```

**驗證**：`curl localhost:8081/actuator/health` 回傳 DB + Redis 狀態。

---

## Phase 2 — Redis 深度應用（1 週）

### 2.1 Login Rate Limit（防暴力破解）

| # | 步驟 | 驗證 |
|---|------|------|
| 2.1.1 | 登入失敗 Redis INCR `login_fail:{username}`，TTL 600s | 5 次鎖定 |
| 2.1.2 | 閾值 5 次回傳 429 | `curl -v` 確認 status |
| 2.1.3 | 登入成功刪除 `login_fail:{username}` | 鎖定→等 TTL→可再試 |

### 2.2 Cache-Aside Pattern

| # | 步驟 | 驗證 |
|---|------|------|
| 2.2.1 | 查詢先讀 Redis `user:{id}`，miss 查 DB 回寫 cache | `redis-cli GET user:{id}` |
| 2.2.2 | 更新時 invalidate cache | 更新後 cache 消失 |
| 2.2.3 | cache TTL 300s | 自動過期 |

---

## Phase 3 — User Service（1 週）

### 3.1 建立 user-service

| # | 步驟 | 說明 |
|---|------|------|
| 3.1.1 | pom.xml + Dockerfile | port 8082 |
| 3.1.2 | Flyway `V1__create_user_profiles.sql` | id, username, email, display_name |
| 3.1.3 | `UserController` — `GET /user/profile`（需 token） | 401 未授權 |

### 3.2 跨服務 Token 驗證

| # | 步驟 | 說明 |
|---|------|------|
| 3.2.1 | 共用 JWT secret 或 RSA key pair | application.properties |
| 3.2.2 | user-service `JwtValidator`（只驗證不產生） | curl 成功 |
| 3.2.3 | 或呼叫 `auth-service /auth/verify` 做內部 API 驗證 | RestTemplate |

### 3.3 Nginx 路由

| # | 步驟 | 驗證 |
|---|------|------|
| 3.3.1 | `location /user/` → `user:8082` | `curl localhost/user/profile` |
| 3.3.2 | 完整流程：register → login → call user API | 全部服務 |

---

## Phase 4 — k3s 部署（1 週）

### 4.1 環境建置

| # | 步驟 |
|---|------|
| 4.1.1 | Oracle VM 安裝 k3s |
| 4.1.2 | 建立 Namespace `identity` |
| 4.1.3 | Secret（postgres-password, jwt-secret） |
| 4.1.4 | ConfigMap（application.properties, nginx.conf） |

### 4.2 部署資料層

| # | 步驟 | 驗證 |
|---|------|------|
| 4.2.1 | PostgreSQL StatefulSet + Service | `kubectl exec` 連線 |
| 4.2.2 | Redis StatefulSet + Service | `kubectl exec` ping |

### 4.3 部署應用層

| # | 步驟 |
|---|------|
| 4.3.1 | auth-service Deployment + Service |
| 4.3.2 | user-service Deployment + Service |
| 4.3.3 | nginx Deployment + Service |

### 4.4 Ingress

| # | 步驟 |
|---|------|
| 4.4.1 | Ingress `/auth/` → auth-service, `/user/` → user-service |

---

## Phase 5 — Observability（1 週）

### 5.1 Actuator Metrics（承接 1.5.5）

| # | 步驟 |
|---|------|
| 5.1.1 | 加入 `micrometer-registry-prometheus` |
| 5.1.2 | 自訂 metrics：`login_success_total`, `login_failure_total`, `token_blacklist_count` |
| 5.1.3 | JVM 內建 metrics（memory, thread, gc） |

### 5.2 結構化 JSON 日誌

| # | 步驟 | 說明 |
|---|------|------|
| 5.2.1 | 加入 `logstash-logback-encoder` | pom.xml |
| 5.2.2 | 建立 `logback-spring.xml` — JSON Console Appender | Loki 可直接解析 |

### 5.3 Prometheus + Grafana

| # | 步驟 |
|---|------|
| 5.3.1 | docker-compose / k8s 加入 Prometheus |
| 5.3.2 | docker-compose / k8s 加入 Grafana |
| 5.3.3 | Dashboard：Login success rate / API latency / Redis hit ratio |

### 5.4 Loki + 日誌聚合

| # | 步驟 |
|---|------|
| 5.4.1 | 加入 Loki + Promtail |
| 5.4.2 | Grafana Logs 面板 `{app="auth-service"}` |

---

## Phase 6 — 整合測試（1 週）

### 6.1 Testcontainers

| # | 步驟 | 說明 |
|---|------|------|
| 6.1.1 | 加入 `testcontainers` + `testcontainers-postgresql` 依賴 | pom.xml |
| 6.1.2 | 撰寫 `AuthControllerTest` — register → login → verify → logout | 測試用真實 PG + Redis |
| 6.1.3 | 撰寫 `UserControllerTest` — 跨服務流程 | 整合測試 |

---

## Phase 7 — AI 輔助維運（持續）

| 工具 | 用途 |
|------|------|
| Cursor | 快速產生 Controller / Service / Repository / Test |
| Claude Code | `kubectl describe pod`、分析 log、修改 yaml、security review |

---

## Phase 8 — 文件與作品集（持續）

### 8.1 Obsidian 筆記體系

```
Cloud Native Identity Platform
├── Architecture Decision Record
│   ├── Why JWT not Session
│   ├── Why Blacklist not Revocation List
│   └── Why k3s not full K8s
├── API Design
├── Security Notes
├── Redis Design
├── K8s Problems
└── Interview Questions
```

### 8.2 GitHub README 內容

- Architecture Diagram
- Why JWT? Why Redis? Why Stateless?
- How to Deploy?（Docker Compose / k3s）
- Failure Scenario
- Demo Script

---

## 時間估時

| Phase | 內容 | 估時 | 備註 |
|-------|------|------|------|
| 0 | Foundation（已完成） | — | 基礎建設就緒 |
| 1 | Auth Service Core（Spring Security + JWT + BCrypt + Flyway） | 1 週 | 核心差異化 |
| **1.5** | **Production Hardening** | **3-4 天** | **demo → production-ready** |
| 2 | Redis 深度應用 | 3-4 天 | 面試亮點 |
| 3 | User Service + 跨服務 | 3-4 天 | 微服務互動 |
| 4 | k3s 部署 | 1 週 | DevOps 關鍵 |
| 5 | Observability | 1 週 | 可觀測性 |
| 6 | 整合測試 | 3-4 天 | 品質保證 |
| 7 | AI 輔助維運 | 持續 | 工具整合 |
| 8 | 文件 + 作品集 | 持續 | 面試準備 |

---

## 目錄結構（最終目標）

```
/workspace/
├── auth-service/           # 身份認證服務
├── user-service/           # 使用者管理服務
├── nginx/                  # API Gateway
├── k8s/                    # k3s manifests
├── monitoring/             # Prometheus + Grafana + Loki
├── docs/                   # 知識文件
│   ├── phase-0-knowledge.md
│   └── advanced-topics.md
├── .env                    # 環境變數（gitignored）
├── Makefile                # 常用指令
├── docker-compose.yml      # 開發環境
├── PLAN.md                 # 本計畫
└── README.md               # 作品集入口
```
