# Phase 3 教學文件：Basic Auth Service

## 目標

建立獨立的 JWT 認證微服務：
- auth-service（Spring Boot, port 8081）
- JWT 簽發與驗證（jjwt 函式庫）
- Redis token blacklist（登出後 token 失效）
- PostgreSQL 使用者儲存
- Nginx 路由 `/auth/` → auth-service

---

## 目錄

- [3.1 Auth Service 專案](#31-auth-service-專案)
- [3.2 JWT 認證 API](#32-jwt-認證-api)
- [3.3 Nginx 路由](#33-nginx-路由)
- [3.4 端到端驗證](#34-端到端驗證)

---

## 3.1 Auth Service 專案

> **先備條件**：已完成 Phase 2，docker-compose.yml 中有 app / nginx / postgres / redis 四個 service。

### 3.1.1 建立目錄與 pom.xml

```bash
mkdir -p auth-service/src/main/java/com/example/auth
mkdir -p auth-service/src/main/resources
```

`pom.xml` 已預先準備在 `samples/phase3/pom.xml`，直接複製：

```bash
cp samples/phase3/pom.xml auth-service/pom.xml
```

**jjwt 三個模組的分工**：

| 模組 | scope | 作用 |
|------|-------|------|
| `jjwt-api` | compile | 公開 API：`Jwts.builder()`, `Jwts.parser()` 等 |
| `jjwt-impl` | runtime | 實作 class：編碼/解碼/簽章邏輯 |
| `jjwt-jackson` | runtime | JSON 序列化：將 JWT payload 轉為 Java Map |

為什麼分成三個模組？為了讓 Android 等不支援某些函式庫的平台可以只匯入需要的部分。

### 3.1.2 建立 application.properties

```bash
cat > auth-service/src/main/resources/application.properties << 'EOF'
server.port=8081
spring.application.name=${APP_NAME:auth-service}

logging.level.root=${LOG_LEVEL:INFO}

spring.datasource.url=jdbc:postgresql://postgres:5432/${POSTGRES_DB}
spring.datasource.username=${POSTGRES_USER}
spring.datasource.password=${POSTGRES_PASSWORD}
spring.datasource.driver-class-name=org.postgresql.Driver

spring.data.redis.host=redis
spring.data.redis.port=6379

spring.sql.init.mode=always
spring.sql.init.schema-locations=classpath:schema.sql

jwt.secret=${JWT_SECRET:default-dev-secret-key-change-in-production!}
jwt.expiration-ms=3600000
EOF
```

**重要設定解釋**：

| 設定 | 說明 |
|------|------|
| `server.port=8081` | auth-service 與 app 不同埠，可同時運行 |
| `spring.sql.init.mode=always` | 每次啟動執行 schema.sql（開發用，Phase 4 會改用 Flyway）|
| `jwt.secret=${JWT_SECRET:...}` | JWT 簽章密鑰，來自 `.env`，有開發用預設值 |
| `jwt.expiration-ms=3600000` | Token 有效期 1 小時（以毫秒為單位）|

為什麼 auth-service 使用 8081 而不是 8080？
- 8080 已被 demo app 使用
- 微服務各自獨立埠號，透過 Nginx 統一對外服務
- 後續 user-service 使用 8082，以此類推

### 3.1.3 建立 schema.sql

```bash
cat > auth-service/src/main/resources/schema.sql << 'EOF'
CREATE TABLE IF NOT EXISTS users (
    id SERIAL PRIMARY KEY,
    username VARCHAR(50) UNIQUE NOT NULL,
    password VARCHAR(255) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
EOF
```

**注意**：這是 Phase 3 的簡化版，密碼直接儲存明碼，也沒有 `role` 欄位。Phase 4 會改用 BCrypt + Flyway 並加入 role。

### 3.1.4 建立 AuthApplication.java

```bash
cat > auth-service/src/main/java/com/example/auth/AuthApplication.java << 'EOF'
package com.example.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

@SpringBootApplication
public class AuthApplication {

    private static final Logger log = LoggerFactory.getLogger(AuthApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(AuthApplication.class, args);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        log.info("Auth service started on port {}",
            System.getenv().getOrDefault("SERVER_PORT_AUTH", "8081"));
    }
}
EOF
```

`ApplicationReadyEvent` 是 Spring Boot 在應用程式完全就緒後觸發的事件。`@EventListener` 讓此方法在事件發生時自動執行，適合放置啟動確認日誌。

### 3.1.5 建立 JwtUtil.java

完整原始碼在 `samples/phase3/JwtUtil.java`，直接複製：

```bash
cp samples/phase3/JwtUtil.java \
   auth-service/src/main/java/com/example/auth/JwtUtil.java
```

**JwtUtil 角色**：在 `auth-service` 中負責所有 JWT 相關操作——產生 token 與驗證 token。

**JWT token 的結構**：

```
header.payload.signature
  │       │        │
  │       │        └── HMAC-SHA256(header + "." + payload, secret)
  │       │
  │       └── Base64URL({ "sub": "alice", "iat": 1700000000, "exp": 1700003600 })
  │
  └── Base64URL({ "alg": "HS256", "typ": "JWT" })
```

**jjwt API 逐步拆解**：

```java
// 產生 token
Jwts.builder()                          // 建立 JWT（Json Web Token）builder
    .subject(username)                  // sub: 使用者識別
    .issuedAt(new Date())               // iat: 簽發時間
    .expiration(...)                    // exp: 過期時間
    .signWith(key)                      // 使用 HMAC-SHA256 簽章
    .compact();                         // 輸出為 compact URL-safe string

// 驗證 token
Jwts.parser()                            // 建立 parser
    .verifyWith(key)                     // 設定驗證密鑰
    .build()                             // 建立 thread-safe 的 parser
    .parseSignedClaims(token)            // 解析並驗證簽章
    .getPayload()                        // 取得 payload (Claims)
    .getSubject();                       // 取得 sub 欄位
```

**Secret key 處理**：

```java
// 從設定檔讀取 base64 編碼的 secret
this.key = Keys.hmacShaKeyFor(
    Decoders.BASE64.decode(
        java.util.Base64.getEncoder().encodeToString(secret.getBytes())
    )
);
```

這行程式做了三件事：
1. `secret.getBytes()` — 將字串轉為 byte array
2. `Base64.getEncoder().encodeToString(...)` — 編碼為 Base64 字串（jjwt 需要）
3. `Decoders.BASE64.decode(...)` — jjwt 解碼回 byte array
4. `Keys.hmacShaKeyFor(...)` — 建立 HMAC-SHA SecretKey

對 HMAC-SHA256，密鑰長度至少需要 256 bits（32 bytes）。

### 3.1.6 建立 Dockerfile

```bash
cat > auth-service/Dockerfile << 'EOF'
# Stage 1: Build
FROM maven:3.9-eclipse-temurin-21-alpine AS builder
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline
COPY src ./src
RUN mvn clean package -DskipTests

# Stage 2: Run
FROM eclipse-temurin:21-jre-alpine

ARG APP_NAME=unknown
ENV APP_NAME=${APP_NAME}

WORKDIR /app
COPY --from=builder /app/target/*.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
EOF
```

**差異**：修改 `EXPOSE 8081`（與 auth-service 內部埠號一致），以及通過 `APP_NAME` ARG 傳入服務名稱 `auth-service-jwt`。

### 3.1.7 建置驗證

```bash
mvn compile -f auth-service/pom.xml

docker build -t auth-service ./auth-service/
# 輸出：Successfully tagged auth-service:latest
```

---

## 3.2 JWT 認證 API

### 3.2.1 建立 AuthController.java

完整原始碼在 `samples/phase3/AuthController.java`，直接複製：

```bash
cp samples/phase3/AuthController.java \
   auth-service/src/main/java/com/example/auth/AuthController.java
```

**API 說明**：

| 端點 | 方法 | 參數 | 說明 |
|------|------|------|------|
| `/auth/register` | POST | username, password | 新增使用者（明碼儲存，僅供開發）|
| `/auth/login` | POST | username, password | 驗證後回傳 JWT |
| `/auth/verify` | GET | Authorization: Bearer | 驗證 token 是否有效 |
| `/auth/logout` | POST | Authorization: Bearer | 將 token 加入 Redis blacklist |
| `/auth/health` | GET | 無 | 健康檢查（DB + Redis）|

**各 API 實作細節**：

**POST /auth/register**

```
流程：
User → POST /auth/register (username, password)
        → jdbc.update("INSERT INTO users ...")
            → 成功：回傳 status ok
            → 失敗（如 username 重複）：回傳 error + DB 錯誤訊息
```

使用 `Map.of()` 來回傳 JSON response，Spring Boot 會自動序列化為 `{"status":"ok","message":"user created"}`。

**POST /auth/login**

```
流程：
User → POST /auth/login (username, password)
        → JDBC query：SELECT password FROM users WHERE username = ?
        → 比較密碼（目前是明碼比較，Phase 4 使用 BCrypt）
            → 不符合：回傳 invalid credentials
            → 符合：jwt.generateToken(username) → 回傳 token
```

**GET /auth/verify**

```
流程：
User → GET /auth/verify (Authorization: Bearer xxx)
        → 解析 header，取出 token（移除 "Bearer " 前綴）
        → 檢查 Redis blacklist
            → 存在：token 已被撤銷
        → jwt.validateAndGetUsername(token)
            → 成功：回傳 username
            → 失敗（expired / 竄改）：回傳 invalid token
```

**POST /auth/logout**

```
流程：
User → POST /auth/logout (Authorization: Bearer xxx)
        → 取出 token
        → Redis SET blacklist:<token> = "1"
        → 回傳 logged out
```

黑名單的 key 格式為 `blacklist:${token}`，後續 verify 時檢查此 key 是否存在。

**為什麼用整個 token 當 key？**

這是 Phase 3 的簡化做法（O(n) 空間）。Phase 4 引入 jti（JWT ID）後，改為 `blacklist:${jti}`，key 長度固定（UUID），空間效率更好。

### 3.2.2 單元測試（獨立啟動 auth-service 測試）

建置 auth-service 映像：

```bash
docker build -t auth-service ./auth-service/
```

---

## 3.3 Nginx 路由

### 3.3.1 更新 nginx.conf

```bash
cat > nginx/nginx.conf << 'EOF'
server {
    listen 80;
    server_name localhost;

    location / {
        root /usr/share/nginx/html;
        index index.html;
    }

    location /api/ {
        proxy_pass http://app:8080/;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
    }

    location /auth/ {
        proxy_pass http://auth:8081;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
    }
}
EOF
```

**Nginx 路由比對規則**：

```
請求：POST /auth/login
       │
       ├── location /        → 匹配（但 /auth/ 更精確）
       └── location /auth/   → 匹配（最長前綴優先）
                                → proxy_pass http://auth:8081/auth/login
```

Nginx 在比對 `location` 時，使用最長前綴匹配原則。`/auth/` 比 `/` 更長，所以 `/auth/login` 會路由到 auth-service。

### 3.3.2 更新 docker-compose.yml — 加入 auth service

```bash
cat > docker-compose.yml << 'EOF'
services:
  app:
    build:
      context: ./spring-boot-demo
      args:
        APP_NAME: spring-demo-backend
    container_name: spring-demo
    ports:
      - "${SERVER_PORT_APP:-8080}:8080"
    environment:
      - APP_ENV=${APP_ENV}
      - APP_VERSION=${APP_VERSION}
      - LOG_LEVEL=${LOG_LEVEL:-INFO}
      - CACHE_TTL=${APP_CACHE_TTL:-300}
      - POSTGRES_DB=${POSTGRES_DB}
      - POSTGRES_USER=${POSTGRES_USER}
      - POSTGRES_PASSWORD=${POSTGRES_PASSWORD}
    depends_on:
      postgres:
        condition: service_healthy
      redis:
        condition: service_started
    restart: unless-stopped
    networks:
      - demo-net

  auth:
    build:
      context: ./auth-service
      args:
        APP_NAME: auth-service-jwt
    container_name: auth-service
    ports:
      - "${SERVER_PORT_AUTH:-8081}:8081"
    environment:
      - LOG_LEVEL=${LOG_LEVEL:-INFO}
      - CACHE_TTL=${AUTH_CACHE_TTL:-60}
      - POSTGRES_DB=${POSTGRES_DB}
      - POSTGRES_USER=${POSTGRES_USER}
      - POSTGRES_PASSWORD=${POSTGRES_PASSWORD}
      - JWT_SECRET=${JWT_SECRET}
    depends_on:
      postgres:
        condition: service_healthy
      redis:
        condition: service_started
    restart: unless-stopped
    networks:
      - demo-net

  nginx:
    build: ./nginx
    container_name: nginx-gateway
    ports:
      - "80:80"
    depends_on:
      - app
      - auth
    restart: unless-stopped
    networks:
      - demo-net

  postgres:
    image: postgres:16-alpine
    container_name: demo-postgres
    environment:
      - POSTGRES_DB=${POSTGRES_DB}
      - POSTGRES_USER=${POSTGRES_USER}
      - POSTGRES_PASSWORD=${POSTGRES_PASSWORD}
    ports:
      - "5432:5432"
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U ${POSTGRES_USER} -d ${POSTGRES_DB}"]
      interval: 5s
      timeout: 3s
      retries: 5
    volumes:
      - pgdata:/var/lib/postgresql/data
    restart: unless-stopped
    networks:
      - demo-net

  redis:
    image: redis:7-alpine
    container_name: demo-redis
    ports:
      - "6379:6379"
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 5s
      timeout: 3s
      retries: 5
    restart: unless-stopped
    networks:
      - demo-net

volumes:
  pgdata:

networks:
  demo-net:
    driver: bridge
EOF
```

**新增的 auth service 重點**：

```yaml
auth:
  build:
    context: ./auth-service
    args:
      APP_NAME: auth-service-jwt    # Dockerfile ARG → ENV
  container_name: auth-service
  ports:
    - "${SERVER_PORT_AUTH:-8081}:8081"  # host 埠可自訂，預設 8081
  environment:
    - CACHE_TTL=${AUTH_CACHE_TTL:-60}  # auth 使用較短的 TTL（60s）
    - JWT_SECRET=${JWT_SECRET}         # 來自 .env 的 JWT 密鑰
```

**${AUTH_CACHE_TTL:-60} 與 ${APP_CACHE_TTL:-300} 的差異**：

這是 Phase 1.5 引入的「各服務差異化設定」：
- demo app：快取 TTL 300 秒（一般資料）
- auth service：快取 TTL 60 秒（認證相關，需要即時更新）

不同服務使用不同的預設值，但都可以在 `.env` 中統一覆蓋。

**為什麼 auth service 需要 `POSTGRES_*` 環境變數？**

auth-service 直接連線到 PostgreSQL（而非透過 app 服務），所以需要資料庫連線資訊。所有服務都從 `.env` 讀取同一組資料庫憑證。

### 3.3.3 更新前端頁面啟用 Auth 操作

Phase 2 建立的前端頁面（`nginx/index.html`）已經包含 Register / Login / Logout / Verify 表單。加入 auth-service 後，這些功能正式啟用。

如果尚未套用最新的互動式頁面，複製一份到 Nginx container：

```bash
docker cp nginx/index.html nginx-gateway:/usr/share/nginx/html/index.html
```

或是重新 build Nginx：

```bash
docker compose build nginx
docker compose up -d nginx
```

**Phase 3 完成後，瀏覽器操作流程**：

```
1. 打開 http://localhost/
2. 填寫 Username + Password → 點 Register
3. 填寫相同帳密 → 點 Login → token 自動顯示
4. 點 Verify Token → 顯示 username 與 token 檢查結果
5. 點 Logout → token 被 Redis blacklist
6. 再次點 Verify Token → 回傳 "token revoked"
```

## 3.4 端到端驗證

### 3.4.1 完整測試腳本

除了透過瀏覽器操作（http://localhost/），也可以用 curl 驗證：
echo ">>> /auth/verify"
curl -s http://localhost/auth/verify \
  -H "Authorization: Bearer $TOKEN" | python3 -m json.tool
# 預期：{"status":"ok","username":"alice"}

# 10. 使用無效 token
echo ">>> /auth/verify (invalid token)"
curl -s http://localhost/auth/verify \
  -H "Authorization: Bearer invalid-token" | python3 -m json.tool
# 預期：{"status":"error","message":"invalid token"}

# 11. 登出
echo ">>> /auth/logout"
curl -s -X POST http://localhost/auth/logout \
  -H "Authorization: Bearer $TOKEN" | python3 -m json.tool
# 預期：{"status":"ok","message":"logged out"}

# 12. 登出後驗證（應失敗）
echo ">>> /auth/verify after logout"
curl -s http://localhost/auth/verify \
  -H "Authorization: Bearer $TOKEN" | python3 -m json.tool
# 預期：{"status":"error","message":"token revoked"}

# 13. 錯誤密碼登入
echo ">>> /auth/login (wrong password)"
curl -s -X POST http://localhost/auth/login \
  -d "username=alice&password=WrongPass" | python3 -m json.tool
# 預期：{"status":"error","message":"invalid credentials"}

echo ""
echo "=== Phase 3 驗證完成 ==="
```

### 3.4.2 Redis blacklist 驗證（手動）

```bash
# 登入取得 token
TOKEN=$(curl -s -X POST http://localhost/auth/login \
  -d "username=alice&password=Str0ng!Pass" | python3 -c "import sys,json; print(json.load(sys.stdin)['token'])")

# 登出前：Redis 無此 key
docker exec demo-redis redis-cli EXISTS "blacklist:$TOKEN"
# 輸出：(integer) 0

# 登出
curl -s -X POST http://localhost/auth/logout \
  -H "Authorization: Bearer $TOKEN"

# 登出後：Redis 有此 key
docker exec demo-redis redis-cli EXISTS "blacklist:$TOKEN"
# 輸出：(integer) 1

docker exec demo-redis redis-cli GET "blacklist:$TOKEN"
# 輸出："1"
```

### 3.4.3 常見問題

**問題：docker compose build 時 auth-service 建置失敗**

```bash
# 確認 Maven 編譯通過
mvn compile -f auth-service/pom.xml

# 查看詳細錯誤
docker compose build auth --no-cache
```

**問題：Nginx 502 連不到 auth**

```bash
# 確認 auth container 有啟動
docker compose ps

# 直接測試 auth container
docker compose exec auth curl http://localhost:8081/auth/health

# 確認 nginx.conf 的 proxy_pass 位址
# 必須是 service name "auth" 而非 "localhost"
```

**問題：註冊時出現 duplicate key**

```bash
# 清除資料庫並重建
docker compose down -v   # 刪除 volume（包含 pgdata）
docker compose up -d
```

**問題：JWT 驗證失敗**

```bash
# 確認 .env 中的 JWT_SECRET 有設定
# 確認 auth-service 和任何驗證 token 的服務使用相同的 secret
```

### 3.4.4 Phase 3 已知限制

Phase 3 是刻意簡化的實作，有以下已知限制（將在 Phase 4 解決）：

| 限制 | Phase 3 | Phase 4 |
|------|---------|---------|
| 密碼儲存 | 明碼 | BCrypt hash |
| Schema 管理 | `schema.sql`（自動執行） | Flyway（版本化）|
| JWT 內容 | 僅 username | 加入 jti（唯一 ID）、role |
| Token 撤銷 | 黑名單 key 為完整 token | 黑名單 key 為 jti |
| 安全性 | 無 Spring Security | Spring Security filter chain |
| 錯誤處理 | 回傳原始 Exception | @ControllerAdvice 統一格式 |
| API 版本 | 無前綴 | `/auth/v1/` |
| 前端操作 | 僅 curl | **瀏覽器互動頁面**（Register / Login / Verify / Logout）|

**瀏覽器操作路徑摘要**：

```
http://localhost/
       │
       ├── 架構狀態列 → 顯示所有服務連線狀態
       ├── Register   → POST /auth/register  → PostgreSQL INSERT
       ├── Login      → POST /auth/login     → JWT token 產生
       ├── Verify     → GET  /auth/verify    → 簽章 + 黑名單檢查
       └── Logout     → POST /auth/logout    → Redis blacklist 寫入
```

---

### 附錄 A：如何查看 Log

認證流程發生問題時，log 是最直接的除錯工具。

**查看所有服務 log**：

```bash
docker compose logs -f --timestamps
```

**只看某個服務**：

```bash
docker compose logs -f --timestamps auth
docker compose logs -f --timestamps app
docker compose logs -f --timestamps nginx
```

**過濾關鍵字**：

```bash
docker compose logs auth | grep -i error
docker compose logs auth | grep -i "login\|register\|token"
```

**`--timestamps` 的作用**：為每行 log 加上時間戳，便於比對多個服務間的事件順序。

| 情境 | 指令 | 看什麼 |
|------|------|--------|
| 服務無法啟動 | `docker compose logs auth` | 最後幾行的 Exception stack trace |
| 註冊失敗 | `docker compose logs auth \| grep register` | SQL 錯誤訊息 |
| Token 驗證問題 | `docker compose logs auth \| grep verify` | JWT 解析結果 |
| 前端 502 | `docker compose logs nginx` | upstream 連線被拒 |
| 資料庫連線問題 | `docker compose logs postgres` | 認證或連線錯誤 |

---

## Commit 歷史參考

```bash
git log --oneline
# Phase 3 對應的 commits：
# feat: 更新 nginx.conf — /auth/ → auth:8081
# feat: docker-compose.yml 加入 auth service
# feat: 建立 AuthController — register/login/verify/logout + Redis blacklist
# feat: 建立 JwtUtil — jjwt generate + validate
# feat: 建立 auth-service Maven 專案（Spring Boot + jjwt 0.12.6）
# ... (Phase 2, Phase 1 commits)
```
