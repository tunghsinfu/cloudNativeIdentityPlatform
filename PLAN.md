# Cloud Native Identity Platform — 實作計畫

## 專案定位

> AI-augmented Backend / Platform Engineer 養成專案

涵蓋：Spring Security · JWT · Redis · PostgreSQL · Docker · k3s · Prometheus/Grafana/Loki · AI Ops

---

## 總覽

| Phase | 內容 | 狀態 |
|-------|------|------|
| **1** | Project Foundation | ✅ 完成 |
| **2** | 容器編排與 API Gateway | ✅ 完成 |
| **3** | Auth Service 基本 | ✅ 完成 |
| **4** | Auth Service 企業級強化 | ✅ 完成 |
| **5** | Production Hardening | 🔜 目前 |
| **6** | Redis 深度應用 | ⏳ |
| **7** | User Service | ⏳ |
| **8** | k3s 部署 | ⏳ |
| **9** | Observability | ⏳ |
| **10** | 整合測試與 AI 輔助維運 | ⏳ |
| **11** | 文件與作品集 | 持續 |

---

## Phase 1 — Project Foundation ✅

**目標**：建立 Spring Boot Maven 專案，可用 `mvn` 編譯、`docker build` 打包、`make` 操作。

### 1.1 版本控制與目錄結構

| 步驟 | 內容 | 驗證 |
|------|------|------|
| 1.1.1 | `git init`，設定 user | `git log` |
| 1.1.2 | 建立 Maven 標準目錄（src/main/java, src/main/resources） | `ls` |

### 1.2 Maven 專案

| 步驟 | 內容 | 驗證 |
|------|------|------|
| 1.2.1 | `pom.xml` — Spring Boot 3.3.5 parent + spring-boot-starter-web | `mvn compile` |
| 1.2.2 | `DemoApplication.java` — `@SpringBootApplication` + `GET /` → Hello | `mvn spring-boot:run` + `curl` |
| 1.2.3 | `application.properties` — `server.port=8080` | — |

### 1.3 Docker 容器化

| 步驟 | 內容 | 驗證 |
|------|------|------|
| 1.3.1 | Dockerfile — 多階段構建（Maven build → JRE runtime） | `docker build` |
| 1.3.2 | `.dockerignore` — 排除 target, .git | build 速度提升 |

### 1.4 環境變數與 Secrets

| 步驟 | 內容 | 驗證 |
|------|------|------|
| 1.4.1 | `.env` — APP_ENV, APP_VERSION | `docker compose config` |
| 1.4.2 | `.gitignore` — 排除 .env | git status 確認 |
| 1.4.3 | `GET /config` 回傳環境變數 | `curl localhost:8080/config` |

### 1.5 變數預設值與各服務差異

| 步驟 | 內容 | 驗證 |
|------|------|------|
| 1.5.1 | Docker Compose `${VAR:-default}` 語法 | 移除 .env 變數測試 fallback |
| 1.5.2 | Dockerfile ARG → ENV（不同 build args） | 各服務回傳不同 APP_NAME |
| 1.5.3 | 各服務獨立 CACHE_TTL | `curl /config` 確認值不同 |

### 1.6 Developer Experience

| 步驟 | 內容 |
|------|------|
| 1.6.1 | `Makefile` — build, up, down, logs, test |

---

## Phase 2 — 容器編排與 API Gateway ✅

**目標**：所有服務可透過 `docker compose up -d` 一鍵啟動，Nginx 作為統一入口。

### 2.1 Docker Compose 基礎

| 步驟 | 內容 | 驗證 |
|------|------|------|
| 2.1.1 | `docker-compose.yml` — app 單一服務 | `docker compose up -d` |
| 2.1.2 | bridge network（demo-net） | container 間可用 service name 互連 |

### 2.2 Nginx API Gateway

| 步驟 | 內容 | 驗證 |
|------|------|------|
| 2.2.1 | `nginx/nginx.conf` — `/api/` → `app:8080` | `curl localhost/api/` |
| 2.2.2 | `nginx/Dockerfile` — nginx:alpine + 自訂 config | build |
| 2.2.3 | `nginx/index.html` — 前端頁面呼叫後端 | 瀏覽器開啟 |

### 2.3 PostgreSQL

| 步驟 | 內容 | 驗證 |
|------|------|------|
| 2.3.1 | Postgres 16-alpine service + healthcheck | `pg_isready` |
| 2.3.2 | `spring-boot-starter-jdbc` + `postgresql` driver | `mvn compile` |
| 2.3.3 | `spring.datasource.*` 設定 | — |
| 2.3.4 | Spring Boot `.env` → `application.properties` 注入鏈 | 變數正確傳遞 |

### 2.4 Redis

| 步驟 | 內容 | 驗證 |
|------|------|------|
| 2.4.1 | Redis 7-alpine service + healthcheck | `redis-cli ping` |
| 2.4.2 | `spring-boot-starter-data-redis` | `mvn compile` |
| 2.4.3 | `spring.data.redis.*` 設定 | — |

### 2.5 連線驗證

| 步驟 | 內容 | 驗證 |
|------|------|------|
| 2.5.1 | `GET /db-check` — PostgreSQL `SELECT 1` | `{"postgresql": "OK"}` |
| 2.5.2 | `GET /db-check` — Redis SET/GET | `{"redis": "OK"}` |

---

## Phase 3 — Auth Service 基本 ✅

**目標**：提供 register / login / verify / logout API，Nginx 路由到 auth-service。

### 3.1 Auth Service 專案

| 步驟 | 內容 | 驗證 |
|------|------|------|
| 3.1.1 | `auth-service/pom.xml` — web + jdbc + redis + jjwt | `mvn compile` |
| 3.1.2 | `auth-service/Dockerfile` | `docker build` |
| 3.1.3 | `application.properties` — port 8081, PG, Redis, jwt config | — |
| 3.1.4 | `schema.sql` — users table | table 存在 |

### 3.2 JWT 認證 API

| 步驟 | 內容 | 驗證 |
|------|------|------|
| 3.2.1 | `JwtUtil.java` — generate / validate | — |
| 3.2.2 | `POST /auth/register` | curl |
| 3.2.3 | `POST /auth/login` → 回傳 JWT | curl |
| 3.2.4 | `GET /auth/verify` — 解析 Bearer token | curl |
| 3.2.5 | `POST /auth/logout` — Redis blacklist | logout 後 verify 失敗 |

### 3.3 Nginx 路由

| 步驟 | 內容 | 驗證 |
|------|------|------|
| 3.3.1 | nginx.conf 新增 `location /auth/` → `auth:8081` | `curl localhost/auth/health` |
| 3.3.2 | auth-service 加入 docker-compose.yml | `docker compose up -d` |

---

## Phase 4 — Auth Service 企業級強化 🔜

**目標**：將簡易 JWT 改為 Spring Security 架構，補上 BCrypt、Flyway、全域異常處理與 API 版本。

### 4.1 Spring Security

| 步驟 | 內容 | 驗證 |
|------|------|------|
| 4.1.1 | 加入 `spring-boot-starter-security` | `mvn compile` |
| 4.1.2 | `SecurityConfig.java` — permit `/auth/login`, `/auth/register`，其餘需認證 | 無 token → 401 |
| 4.1.3 | `JwtAuthenticationFilter` — 解析 header，寫入 SecurityContext | 有效 token → 200 |
| 4.1.4 | `UserDetailsServiceImpl` — 從 DB 載入 | login 正確檢查 |

### 4.2 完整 JWT（jti + Refresh Token）

| 步驟 | 驗證 |
|------|------|
| 4.2.1 | JWT 加入 `jti`（UUID） | decode 看 payload |
| 4.2.2 | JWT 加入 `role` claim | payload 含 role |
| 4.2.3 | `POST /auth/refresh` | 舊 token 過期後 refresh 成功 |
| 4.2.4 | access token 15 分 / refresh token 7 天 | 實測 |

### 4.3 BCrypt 密碼編碼

| 步驟 | 驗證 |
|------|------|
| 4.3.1 | register 使用 `BCryptPasswordEncoder.encode()` | DB 密碼為 hash |
| 4.3.2 | login 使用 `matches()` | 正確可登入，錯誤拒絕 |

### 4.4 Flyway 資料庫遷移

| 步驟 | 說明 |
|------|------|
| 4.4.1 | 加入 flyway-core，移除 `spring.sql.init.*` |
| 4.4.2 | `V1__create_users.sql` — 初始 schema |
| 4.4.3 | `V2__add_role_column.sql` — 版本演化 |

### 4.5 @ControllerAdvice

| 步驟 | 驗證 |
|------|------|
| 4.5.1 | `GlobalExceptionHandler` + 統一 `ErrorResponse` | 錯誤回傳 `{code, message}` |
| 4.5.2 | 處理 400 / 401 / 403 / 500 | `curl -v` 確認 |

### 4.6 API 版本

```
/auth/v1/register
/auth/v1/login
/auth/v1/verify
/auth/v1/logout
/auth/v1/refresh
```

---

## Phase 5 — Production Hardening

**目標**：從「能跑」進化到「能 production 運行」。這個 Phase 是面試中最能展示經驗差異的部分。

| 步驟 | 內容 | 適用服務 | 驗證 |
|------|------|---------|------|
| 5.1 | Docker 非 root 使用者 | auth, user | `docker run --rm ... id` → appuser |
| 5.2 | Spring Boot Layered JAR | auth, user | build 觀察 layer cache |
| 5.3 | Graceful Shutdown | auth, user | `docker compose stop` 觀察日誌 |
| 5.4 | HikariCP 連線池調校 | auth, user | `/actuator/health` 確認 |
| 5.5 | Docker Resource Limits | auth, user, nginx | `docker stats` |
| 5.6 | CORS 設定 | auth, user | 前端 localhost:3000 可呼叫 |
| 5.7 | Spring Boot Actuator | auth, user | `curl /actuator/health` |

---

## Phase 6 — Redis 深度應用

**目標**：展示 Redis 在認證系統中的真正價值，不只是基本的 SET/GET。

### 6.1 Login Rate Limit

| 步驟 | 驗證 |
|------|------|
| 6.1.1 | 失敗時 INCR `login_fail:{username}`，TTL 600s | 5 次鎖定 |
| 6.1.2 | 達到 5 次回傳 429 | `curl -v` |
| 6.1.3 | 成功時刪除 key | 鎖定解除 |

### 6.2 Cache-Aside Pattern

| 步驟 | 驗證 |
|------|------|
| 6.2.1 | 查詢先讀 Redis `user:{id}`，miss → DB → 回寫 cache | `redis-cli GET` |
| 6.2.2 | 更新時 invalidate | cache 消失 |
| 6.2.3 | TTL 300s | 自動過期 |

---

## Phase 7 — User Service

### 7.1 建立 user-service

| 步驟 | 內容 |
|------|------|
| 7.1.1 | pom.xml + Dockerfile（port 8082，套用 Phase 5 模式） |
| 7.1.2 | Flyway — user_profiles table |
| 7.1.3 | `UserController` — `GET /user/profile`（需 token）|

### 7.2 跨服務 Token 驗證

| 步驟 | 選擇 |
|------|------|
| 7.2.1 | 共用 JWT secret | 或 |
| 7.2.2 | 呼叫 `auth-service /auth/verify` 內部 API | RestTemplate |

### 7.3 Nginx 路由

| 步驟 | 驗證 |
|------|------|
| 7.3.1 | `location /user/` → `user:8082` | `curl localhost/user/profile` |
| 7.3.2 | 完整流程：register → login → call user API | 端到端 |

---

## Phase 8 — k3s 部署

### 8.1 環境

| 步驟 |
|------|
| 8.1.1 | Oracle VM 安裝 k3s |
| 8.1.2 | Namespace `identity` |
| 8.1.3 | Secret（密碼, JWT secret） |
| 8.1.4 | ConfigMap（application.properties, nginx.conf） |

### 8.2 資料層

| 步驟 | 驗證 |
|------|------|
| 8.2.1 | PostgreSQL StatefulSet + Service | `kubectl exec` 連線 |
| 8.2.2 | Redis StatefulSet + Service | `kubectl exec` ping |

### 8.3 應用層

| 步驟 |
|------|
| 8.3.1 | auth-service Deployment + Service |
| 8.3.2 | user-service Deployment + Service |
| 8.3.3 | nginx Deployment + Service |

### 8.4 Ingress

| 步驟 |
|------|
| 8.4.1 | Ingress — `/auth/` → auth-service, `/user/` → user-service |

---

## Phase 9 — Observability

### 9.1 Actuator Metrics

| 步驟 |
|------|
| 9.1.1 | `micrometer-registry-prometheus` |
| 9.1.2 | 自訂 metrics：`login_success_total`, `login_failure_total`, `token_blacklist_count` |
| 9.1.3 | JVM metrics（memory, thread, gc）|

### 9.2 結構化 JSON 日誌

| 步驟 | 說明 |
|------|------|
| 9.2.1 | `logstash-logback-encoder` | pom.xml |
| 9.2.2 | logback-spring.xml — JSON Console Appender | Loki 直接解析 |

### 9.3 Prometheus + Grafana

| 步驟 |
|------|
| 9.3.1 | docker-compose / k8s 加入 Prometheus |
| 9.3.2 | docker-compose / k8s 加入 Grafana（provisioned datasource）|
| 9.3.3 | Dashboard：Login success rate / API latency / Redis hit ratio |

### 9.4 Loki

| 步驟 |
|------|
| 9.4.1 | 加入 Loki + Promtail |
| 9.4.2 | Grafana Logs 面板 `{app="auth-service"}` |

---

## Phase 10 — 整合測試與 AI 輔助維運

### 10.1 Testcontainers

| 步驟 | 說明 |
|------|------|
| 10.1.1 | 加入 testcontainers 依賴 |
| 10.1.2 | `AuthControllerTest` — register → login → verify → logout（真實 PG + Redis）|
| 10.1.3 | `UserControllerTest` — 跨服務流程 |

### 10.2 AI 工作流程

| 工具 | 用途 |
|------|------|
| Cursor | 快速產生 Controller / Service / Repository / Test |
| Claude Code | `kubectl describe pod`、分析 log、修改 yaml、security review |

### 10.3 典型 Prompt 範例

```
分析 auth-service 最近 500 行 log，找出 login latency 增加原因
幫我檢查 Kubernetes deployment 是否有 production risk
幫我對這個 SecurityConfig 做 security review
解釋為什麼這個 JWT filter 會拋 NPE
```

---

## Phase 11 — 文件與作品集

### 11.1 Obsidian 筆記體系

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

### 11.2 GitHub README

- Architecture Diagram
- Why JWT? Why Redis? Why Stateless?
- How to Deploy?（Docker Compose / k3s）
- Failure Scenario（DB 掛了？Redis 掛了？）
- Demo Script（register → login → call API → logout → verify fail）

---

## 時間估時

| Phase | 內容 | 狀態 | 估時 |
|-------|------|------|------|
| 1 | Project Foundation | ✅ 完成 | — |
| 2 | 容器編排與 API Gateway | ✅ 完成 | — |
| 3 | Auth Service 基本 | ✅ 完成 | — |
| 4 | Auth Service 企業級強化 | 🔜 目前 | 1 週 |
| 5 | Production Hardening | ⏳ | 3-4 天 |
| 6 | Redis 深度應用 | ⏳ | 3-4 天 |
| 7 | User Service | ⏳ | 3-4 天 |
| 8 | k3s 部署 | ⏳ | 1 週 |
| 9 | Observability | ⏳ | 1 週 |
| 10 | 整合測試與 AI Ops | ⏳ | 3-4 天 |
| 11 | 文件與作品集 | ⏳ | 持續 |

核心（Phase 4-7）約 **3 週**，加上 k3s + Observability 約 **5 週**。

---

## 每步驟驗證原則

1. **可獨立 build**：`mvn compile` 或 `docker build` 通過
2. **可獨立啟動**：`docker compose up <service>` 正常
3. **可 curl 驗證**：有明確的 request / expected response
4. **可 commit**：每個子步驟一個 git commit
