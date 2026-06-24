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

## 現狀評估（已完工）

以下內容已在 `/workspace` 中完成，合併為 Phase 0：

| 步驟 | 內容 | 驗證方式 |
|------|------|---------|
| 0.0 | Git repo 初始化 + 目錄結構 | `git log` |
| 0.1 | `spring-boot-demo/` — 基礎 Spring Boot 3 + Docker | `mvn compile` |
| 0.2 | `nginx/` — API Gateway（反向代理 `/api/`） | `curl localhost/api/` |
| 0.3 | `docker-compose.yml` — 整合 app + nginx + postgres + redis | `docker compose up -d` |
| 0.4 | `.env` + `.gitignore` — 環境變數與機密管理 | `docker compose config` |
| 0.5 | `GET /config` — 讀取環境變數 | `curl localhost:8080/config` |
| 0.6 | `GET /db-check` — PostgreSQL + Redis 連線驗證 | `curl localhost:8080/db-check` |
| 0.7 | `auth-service/` — 基本 JWT 認證（register / login / verify / logout） | `curl localhost/auth/health` |
| 0.8 | Nginx `/auth/` 路由 → auth-service:8081 | `curl localhost/auth/health` |

---

## Phase 1 — 核心強化：企業級 Auth Service（2 週）

### 1.1 導入 Spring Security

將現有 auth-service 的簡易驗證改為 Spring Security 架構：

| # | 步驟 | 說明 | 驗證 |
|---|------|------|------|
| 1.1.1 | 加入 `spring-boot-starter-security` 依賴 | pom.xml | `mvn compile` |
| 1.1.2 | 建立 `SecurityConfig.java` — 設定 permitAll for `/auth/login`, `/auth/register`；其餘需認證 | `@Bean SecurityFilterChain` | 未帶 token 請求 `/auth/verify` → 401 |
| 1.1.3 | 建立 `JwtAuthenticationFilter.java` — 從 `Authorization` header 解析 token，設定 `SecurityContextHolder` | `OncePerRequestFilter` | 帶有效 token → 200 |
| 1.1.4 | 建立 `UserDetailsServiceImpl.java` — 從 DB 載入使用者 | `UserDetailsService` | login 正確檢查密碼 |

**可驗證**：`curl -X POST .../auth/login` → 取得 token；不帶 token 訪問受保護端點 → 401

### 1.2 完整 JWT 規範（含 jti + Refresh Token）

| # | 步驟 | 說明 | 驗證 |
|---|------|------|------|
| 1.2.1 | JWT claims 加入 `jti`（JWT ID, UUID） | `JwtUtil.java` | decode token 看 payload |
| 1.2.2 | JWT 加入 `role` claim | `JwtUtil.java` | payload 含 role |
| 1.2.3 | 實作 Refresh Token — `POST /auth/refresh`，用 refresh token 換新 access token | Refresh token 存 Redis | 舊 token 過期後 refresh 成功 |
| 1.2.4 | access token 有效期 15 分鐘，refresh token 有效期 7 天 | `application.properties` | 實測過期行為 |

**可驗證**：取得 token → 等 15 分（或設短秒數測試）→ `/auth/refresh` 取得新 token

### 1.3 升級 Logout — Redis Token Blacklist（JWT Stateless 管理）

| # | 步驟 | 說明 | 驗證 |
|---|------|------|------|
| 1.3.1 | Logout 接收 token，解析 `jti` | `AuthController.java` | — |
| 1.3.2 | 寫入 Redis `blacklist:{jti}`，值為 `"1"`，TTL = token 剩餘時間 | `StringRedisTemplate` | `redis-cli TTL blacklist:{jti}` |
| 1.3.3 | JwtAuthenticationFilter 檢查 token 是否在黑名單中 | 過濾器流程 | logout 後再次 verify → 401 |

**可驗證**：login → logout → 用同一 token 訪問 → 401

---

## Phase 2 — Redis 深度應用（1 週）

### 2.1 Login Rate Limit（防暴力破解）

| # | 步驟 | 說明 | 驗證 |
|---|------|------|------|
| 2.1.1 | 登入失敗時 Redis INCR `login_fail:{username}`，TTL 600s | `AuthController` | 5 次錯誤後鎖定 |
| 2.1.2 | 達到閾值（5 次）回傳 429 Too Many Requests | 自訂 exception | `curl -v` 看 status |
| 2.1.3 | 登入成功時刪除 `login_fail:{username}` | 解除鎖定 | 鎖定→等 TTL→可再試 |

**可驗證**：連續送 6 次錯誤密碼 → 第 6 次回 429

### 2.2 Cache-Aside Pattern（User Profile Cache）

| # | 步驟 | 說明 | 驗證 |
|---|------|------|------|
| 2.2.1 | 查詢使用者時先讀 Redis `user:{id}`，miss 則查 DB 並回寫 cache | `CacheAsideService` | `redis-cli GET user:{id}` 有資料 |
| 2.2.2 | 更新使用者時 invalidate cache（DEL `user:{id}`） | `@CacheEvict` 或手動 | 更新後 cache 消失 |
| 2.2.3 | cache TTL 設定（預設 300s） | `application.properties` | TTL 自動過期 |

**可驗證**：第一次查 → 慢（DB）；第二次查 → 快（Redis）；redis-cli 確認 key 存在

---

## Phase 3 — User Service + 跨服務架構（1 週）

### 3.1 建立 user-service

| # | 步驟 | 說明 | 驗證 |
|---|------|------|------|
| 3.1.1 | 建立 `user-service/pom.xml` + Dockerfile | port 8082 | `mvn compile` |
| 3.1.2 | 建立 `schema.sql` — `user_profiles` table（id, username, email, display_name, avatar_url, created_at） | 自動初始化 | 連 PG 查 table |
| 3.1.3 | 建立 `UserController` — `GET /user/profile`（需 token） | 從 auth-service 驗證 token | 401 未授權 |

### 3.2 跨服務 Token 驗證

| # | 步驟 | 說明 | 驗證 |
|---|------|------|------|
| 3.2.1 | user-service 啟動時取得 auth-service 的 JWT secret（共用 secret 或 RSA public key） | `application.properties` | — |
| 3.2.2 | user-service 建立 `JwtValidator`（共用 JWT 驗證邏輯，不含產生） | 只 validate 不 generate | `curl .../user/profile` 成功 |
| 3.2.3 | 或改為 user-service 呼叫 `auth-service /auth/verify` 驗證 token（內部 API） | `RestTemplate` / `WebClient` | 雙重驗證 |

### 3.3 Nginx 路由 + 驗證

| # | 步驟 | 說明 | 驗證 |
|---|------|------|------|
| 3.3.1 | `nginx.conf` 新增 `location /user/` → `user:8082` | nginx | `curl localhost/user/profile` |
| 3.3.2 | 完整端到端流程：register → login → call user API | 全部服務 | — |

---

## Phase 4 — 容器化部署（k3s）（1 週）

將現有 Docker Compose 應用搬遷到 k3s：

### 4.1 k3s 環境建置

| # | 步驟 | 說明 |
|---|------|------|
| 4.1.1 | Oracle VM 安裝 k3s | `curl -sfL https://get.k3s.io \| sh -` |
| 4.1.2 | 建立 Namespace `identity` | `kubectl create ns identity` |
| 4.1.3 | 建立 Secret（postgres-password, jwt-secret） | `kubectl create secret generic` |
| 4.1.4 | 建立 ConfigMap（application.properties, nginx.conf） | `kubectl create configmap` |

### 4.2 部署資料層

| # | 步驟 | 驗證 |
|---|------|------|
| 4.2.1 | 部署 PostgreSQL StatefulSet + Service | `kubectl exec` 連線 |
| 4.2.2 | 部署 Redis StatefulSet + Service | `kubectl exec` ping |

### 4.3 部署應用層

| # | 步驟 | 驗證 |
|---|------|------|
| 4.3.1 | 部署 auth-service Deployment + Service | `kubectl port-forward` 測試 |
| 4.3.2 | 部署 user-service Deployment + Service | 同上 |
| 4.3.3 | 部署 nginx Deployment + Service | 同上 |

### 4.4 Ingress

| # | 步驟 | 驗證 |
|---|------|------|
| 4.4.1 | 建立 Ingress（/auth/ → auth-service, /user/ → user-service） | `curl` 從外部 |

---

## Phase 5 — Observability（1 週）

### 5.1 Metrics 收集

| # | 步驟 | 說明 |
|---|------|------|
| 5.1.1 | 加入 `micrometer-registry-prometheus` 依賴 | pom.xml |
| 5.1.2 | 自訂 Metrics：`login_success_total`, `login_failure_total`, `token_blacklist_count`, `cache_hit_ratio` | `MeterRegistry` |
| 5.1.3 | JVM 內建 metrics（memory, thread, gc） | Actuator |

### 5.2 Grafana + Prometheus

| # | 步驟 | 說明 |
|---|------|------|
| 5.2.1 | docker-compose / k8s 加入 Prometheus | `prometheus.yml` |
| 5.2.2 | docker-compose / k8s 加入 Grafana | provisioned datasource |
| 5.2.3 | 建立 Dashboard：Login success rate / API latency / Redis hit ratio | grafana.com 匯入 |

### 5.3 Loki + 日誌聚合

| # | 步驟 | 說明 |
|---|------|------|
| 5.3.1 | 加入 Loki + Promtail / Alloy | docker-compose |
| 5.3.2 | Grafana 建立 Logs 面板 | `{app="auth-service"}` |

---

## Phase 6 — AI 輔助維運（持續進行）

### 6.1 設定 AI 工作流程

| 工具 | 用途 |
|------|------|
| Cursor | 快速產生 Controller / Service / Repository / Test |
| Claude Code | `kubectl describe pod` 找 crash、分析 log、修改 yaml、security review |

### 6.2 典型 AI Prompt 範例

```
分析 auth-service 最近 500 行 log，找出 login latency 增加原因
幫我檢查 Kubernetes deployment 是否有 production risk
幫我對這個 SecurityConfig 做 security review
解釋為什麼這個 JWT filter 會拋 NPE
```

---

## Phase 7 — 文件與作品集（持續進行）

### 7.1 Obsidian 筆記體系

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

### 7.2 GitHub README 內容

- Architecture Diagram
- Why JWT? Why Redis? Why Stateless?
- How to Deploy? (Docker Compose / k3s)
- Failure Scenario（DB 掛了會怎樣？Redis 掛了會怎樣？）
- Demo Script（從 register → login → call API → logout → verify fail）

---

## 時間估時

| Phase | 內容 | 估時 | 備註 |
|-------|------|------|------|
| 0 | 現狀（已完成） | — | 基礎建設就緒 |
| 1 | Spring Security + JWT 強化 | 1 週 | 核心差異化 |
| 2 | Redis 深度應用 | 3-4 天 | 面試亮點 |
| 3 | User Service + 跨服務 | 3-4 天 | 微服務互動 |
| 4 | k3s 部署 | 1 週 | DevOps 關鍵 |
| 5 | Observability | 1 週 | 可觀測性 |
| 6 | AI 輔助維運 | 持續 | 工具整合 |
| 7 | 文件 + 作品集 | 持續 | 面試準備 |

總計約 **4-5 週**可完成核心（Phase 1-4），之後逐步疊加 Observability 與 AI。

---

## 每步驟驗證原則

每個子步驟必須符合：

1. **可獨立 build**：`mvn compile` 或 `docker build` 通過
2. **可獨立啟動**：`docker compose up <service>` 正常
3. **可 curl 驗證**：有明確的 request / expected response
4. **可 commit**：每個子步驟一個 git commit

---

## 目錄結構（最終目標）

```
/workspace/
├── spring-boot-demo/       # 可保留作為測試用，或移除
├── auth-service/           # 主專案：身份認證服務
├── user-service/           # 使用者管理服務
├── nginx/                  # API Gateway
├── k8s/                    # k3s manifests
│   ├── namespace.yaml
│   ├── auth-deployment.yaml
│   ├── user-deployment.yaml
│   ├── postgres-statefulset.yaml
│   ├── redis-statefulset.yaml
│   ├── ingress.yaml
│   └── secrets.yaml
├── monitoring/             # Prometheus + Grafana + Loki 設定
│   ├── prometheus.yml
│   ├── grafana-dashboard.json
│   └── loki-config.yml
├── .env                    # 環境變數（gitignored）
├── docker-compose.yml      # 開發環境編排
└── README.md               # 作品集入口
```
