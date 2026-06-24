# 微服務 Demo — 操作筆記

此專案展示以 Docker Compose 建置最簡易的微服務架構，包含兩個服務：

| 服務 | 角色 | 技術 |
|------|------|------|
| **app** | 後端 API | Spring Boot 3.3.5 / Java 21 |
| **auth** | 認證服務 | Spring Boot 3.3.5 / JWT |
| **nginx** | API Gateway | Nginx (Alpine) |
| **postgres** | 資料庫 | PostgreSQL 16 Alpine |
| **redis** | 快取 / Token 黑名單 | Redis 7 Alpine |

---

## 專案結構

```
/workspace/
├── spring-boot-demo/       # 後端 API（Spring Boot）
├── auth-service/           # 認證服務（Spring Boot + JWT）
├── nginx/                  # API Gateway（Nginx）
├── .env                    # 環境變數與機密（已加入 .gitignore）
├── docker-compose.yml      # 容器編排
└── README.md               # 本筆記
```

---

## 啟動方式

```bash
# 構建並啟動所有服務
docker compose up -d

# 查看即時日誌
docker compose logs -f

# 測試 API Gateway → Nginx 代理到後端
curl http://localhost/api/

# 測試靜態頁面
curl http://localhost/

# 停止並移除
docker compose down
```

---

## 架構說明

```
瀏覽器 → Nginx (port 80) → /api/*  → app:8080（Spring Boot API）
                          → /auth/* → auth:8081（JWT 認證服務）
                          → /*      靜態頁面
```

- Nginx 監聽 80 埠，提供靜態頁面
- `/api/` 反向代理至 Spring Boot demo（`app:8080`）
- `/auth/` 反向代理至 auth-service（`auth:8081`）

---

## 環境變數與機密資料管理

### 單一 `.env` 檔案

所有環境變數（含密碼）統一放在根目錄的 `.env`，由 Docker Compose 自動載入：

```bash
APP_ENV=development
APP_VERSION=1.0.0
POSTGRES_DB=authdb
POSTGRES_USER=authuser
POSTGRES_PASSWORD=pg_s3cr3t!
JWT_SECRET=ZGV2LXNlY3JldC1rZXktZm9yLWRlbW8tcHVycG9zZXMtb25seSE=
```

### 注入方式

```yaml
# docker-compose.yml
environment:
  - POSTGRES_PASSWORD=${POSTGRES_PASSWORD}  # 從 .env 讀取
```

Spring Boot 的 `application.properties` 再從環境變數讀取：

```properties
spring.datasource.password=${POSTGRES_PASSWORD}
```

### 安全注意

- `.env` 已加入 `.gitignore`，**不會被提交**到版控
- 所有機密集中在一個檔案，易於管理與輪替
- 實務上生產環境建議使用 Docker Swarm Secrets、HashiCorp Vault 或 AWS Secrets Manager

---

## PostgreSQL + Redis 基礎設施

### docker-compose 配置

```yaml
postgres:
  image: postgres:16-alpine
  environment:
    - POSTGRES_DB=${POSTGRES_DB}
    - POSTGRES_USER=${POSTGRES_USER}
    - POSTGRES_PASSWORD_FILE=/run/secrets/postgres_password
  secrets:
    - postgres_password
  healthcheck:
    test: ["CMD-SHELL", "pg_isready -U ${POSTGRES_USER} -d ${POSTGRES_DB}"]
  volumes:
    - pgdata:/var/lib/postgresql/data

redis:
  image: redis:7-alpine
  healthcheck:
    test: ["CMD", "redis-cli", "ping"]
```

- PostgreSQL 密碼透過 Docker Secret 注入（`POSTGRES_PASSWORD_FILE`）
- Redis 無須密碼（開發環境），直接以 `redis-cli ping` 做健康檢查

### Spring Boot 連線設定 (`application.properties`)

```properties
spring.datasource.url=jdbc:postgresql://postgres:5432/${POSTGRES_DB}
spring.datasource.username=${POSTGRES_USER}
spring.datasource.password=${POSTGRES_PASSWORD}
spring.data.redis.host=redis
spring.data.redis.port=6379
```

### 連線驗證端點 `/db-check`

Spring Boot 使用 `JdbcTemplate` 查詢 `SELECT 1` 驗證 PG 連線，並用 `StringRedisTemplate` 執行 `SET/GET` 驗證 Redis：

```bash
curl http://localhost:8080/db-check
# {"postgresql":"OK","redis":"OK (ping=pong)"}
```

---

## Auth Service — JWT 認證

### API 端點

| 方法 | 路徑 | 說明 |
|------|------|------|
| POST | `/auth/register` | 註冊（username + password） |
| POST | `/auth/login` | 登入，回傳 JWT Token |
| GET | `/auth/verify` | 驗證 Token（Bearer header） |
| POST | `/auth/logout` | 登出，Token 加入 Redis 黑名單 |
| GET | `/auth/health` | 健康檢查 |

### 使用範例

```bash
# 註冊
curl -X POST "http://localhost/auth/register?username=alice&password=123456"

# 登入（取得 token）
TOKEN=$(curl -s -X POST "http://localhost/auth/login?username=alice&password=123456" \
  | jq -r '.token')

# 驗證 token
curl -H "Authorization: Bearer $TOKEN" http://localhost/auth/verify

# 登出（token 列入黑名單）
curl -X POST -H "Authorization: Bearer $TOKEN" http://localhost/auth/logout

# 登出後再次驗證（應失敗）
curl -H "Authorization: Bearer $TOKEN" http://localhost/auth/verify
```

### JWT 實作

- 使用 `io.jsonwebtoken` (jjwt) 0.12.6 函式庫
- Token 存於 Redis 黑名單實現登出（`blacklist:<token>`）
- JWT Secret 從 `.env` 的 `JWT_SECRET` 注入

### Nginx 路由

```nginx
location /auth/ {
    proxy_pass http://auth:8081;
}
```

---

## 各服務說明

### spring-boot-demo

- Spring Boot 3.3.5 + spring-boot-starter-web + JDBC + Redis
- 提供 `GET /` 回傳 "Hello from Spring Boot!"
- 提供 `GET /config` 顯示環境變數與 Secrets
- 提供 `GET /db-check` 測試 PostgreSQL 與 Redis 連線
- Dockerfile 使用多階段構建（Maven build → JRE runtime）

### auth-service

- Spring Boot 3.3.5 + spring-boot-starter-web + JDBC + Redis + jjwt
- 提供 JWT 認證 API（註冊 / 登入 / 驗證 / 登出）
- 使用者資料儲存於 PostgreSQL（users table）
- Token 黑名單儲存於 Redis
- Dockerfile 使用多階段構建

### nginx

- 基於 `nginx:alpine` 輕量映像
- 自訂 `nginx.conf` 設定反向代理規則
  - `/` → 靜態頁面
  - `/api/` → Spring Boot demo (`app:8080`)
  - `/auth/` → Auth service (`auth:8081`)
- `index.html` 含前端 JavaScript 呼叫後端 API 並顯示狀態

---

## Git 提交紀錄

```bash
$ git log --oneline
ef5070a fix: 簡化機密管理，移除 secrets/ 與 entrypoint，統一使用 .env
3214c9f docs: README 更新 Secrets 章節
3dddcb1 fix: 移除 docker-compose 明碼密碼，改由 entrypoint 讀取 secret
a661dc4 docs: README 新增 auth-service JWT 認證章節
a1e6896 feat: 建立 auth-service（Spring Boot + JWT）
322e470 docs: README 新增 PostgreSQL + Redis 基礎設施章節
b56d6e5 feat: 新增 /db-check 端點驗證 PG 與 Redis 連線
7d0e2a4 feat: pom.xml 加入 JDBC + PostgreSQL + Redis 依賴
c5ba18b feat: docker-compose 加入 PostgreSQL 16 + Redis 7
2ef3158 docs: README 新增環境變數與 secrets 實作章節
7c0bd00 feat: Spring Boot 新增 /config 端點
2e8e815 feat: app 服務加入 environment 與 secrets
efbfc9b feat: 建立 .env 與 .gitignore
0a83e36 docs: 建立根目錄 README.md
e9228cd feat: 建立根目錄 docker-compose.yml
6a028e1 feat: 建立 Nginx 反向代理服務
5b1e309 feat: 建立 Spring Boot Maven 專案
```
