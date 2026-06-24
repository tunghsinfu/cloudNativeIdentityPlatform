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
├── secrets/                # Docker Secrets（已加入 .gitignore）
├── .env                    # 環境變數（已加入 .gitignore）
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

## 環境變數與 Secrets 實作

### .env 檔案

專案根目錄的 `.env` 檔案由 Docker Compose 自動載入：

```bash
APP_ENV=development
APP_VERSION=1.0.0
```

這些變數透過 `docker-compose.yml` 的 `environment:` 區段傳入容器：

```yaml
environment:
  - APP_ENV=${APP_ENV}
  - APP_VERSION=${APP_VERSION}
```

### Docker Secrets

敏感資料（如資料庫密碼）以 Docker Secret 方式管理，**不寫入 docker-compose.yml 明碼**：

| 檔案 | 容器內路徑 | 用途 |
|------|-----------|------|
| `./secrets/db_password.txt` | `/run/secrets/db_password` | Demo 用密碼 |
| `./secrets/postgres_password.txt` | `/run/secrets/postgres_password` | PostgreSQL 密碼 |

密碼透過 entrypoint 腳本自動讀取並設為環境變數：

```bash
# entrypoint.sh
if [ -f /run/secrets/postgres_password ]; then
    export POSTGRES_PASSWORD=$(cat /run/secrets/postgres_password)
fi
exec java -jar app.jar
```

Spring Boot 另可透過直接讀取檔案取得密碼：

```java
Path secretPath = Path.of("/run/secrets/db_password");
String password = Files.readString(secretPath).trim();
```

Spring Boot 提供 `/config` 端點驗證兩者的值：

```bash
# 檢視環境變數與 secrets
curl http://localhost:8080/config
curl http://localhost/api/config   # 經 Nginx
```

輸出範例：

```json
{
  "APP_ENV" : "development",
  "APP_VERSION" : "1.0.0",
  "DB_PASSWORD" : "s3cr3t!Passw0rd",
  "secret_source" : "/run/secrets/db_password"
}
```

### 安全注意

- `.env` 與 `secrets/` 已加入 `.gitignore`，避免誤提交
- 實務上應使用 Docker Swarm Secrets、HashiCorp Vault 或 AWS Secrets Manager

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
3dddcb1 fix: 移除 docker-compose 明碼密碼，改由 entrypoint 腳本從 Docker Secret 讀取
a1e6896 feat: 建立 auth-service（Spring Boot + JWT），含註冊/登入/驗證/登出 API
322e470 docs: README 新增 PostgreSQL + Redis 基礎設施章節
b56d6e5 feat: 新增 /db-check 端點，驗證 PostgreSQL 與 Redis 連線狀態
7d0e2a4 feat: pom.xml 加入 JDBC + PostgreSQL + Redis 依賴；設定資料源與 Redis 連線參數
c5ba18b feat: docker-compose 加入 PostgreSQL 16 + Redis 7 基礎設施服務
2ef3158 docs: README 新增環境變數與 secrets 實作章節
7c0bd00 feat: Spring Boot 新增 /config 端點，讀取環境變數與 Docker secrets
2e8e815 feat: app 服務加入 environment 與 secrets
efbfc9b feat: 建立 .env 環境變數檔、secrets 目錄與 .gitignore
0a83e36 docs: 建立根目錄 README.md，記錄微服務 Demo 架構與操作方式
e9228cd feat: 建立根目錄 docker-compose.yml
6a028e1 feat: 建立 Nginx 反向代理服務
5b1e309 feat: 建立 Spring Boot Maven 專案（spring-boot-demo）
```
