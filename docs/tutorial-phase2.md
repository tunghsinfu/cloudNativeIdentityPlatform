# Phase 2 教學文件：容器編排與 API Gateway

## 目標

從單一服務擴展為多服務架構：
- Docker Compose 管理多容器
- Nginx 作為 API Gateway（統一入口）
- PostgreSQL 資料庫
- Redis 快取
- 連線驗證端點

---

## 目錄

- [2.1 Docker Compose 基礎](#21-docker-compose-基礎)
- [2.2 Nginx API Gateway](#22-nginx-api-gateway)
- [2.3 PostgreSQL](#23-postgresql)
- [2.4 Redis](#24-redis)
- [2.5 連線驗證](#25-連線驗證)

---

## 2.1 Docker Compose 基礎

> **先備條件**：已完成 Phase 1，`spring-boot-demo/` 目錄中存在 Dockerfile、pom.xml、DemoApplication.java、application.properties。

### 2.1.1 建立 docker-compose.yml

在專案根目錄建立 `docker-compose.yml`：

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
      - "8080:8080"
    environment:
      - APP_ENV=${APP_ENV}
      - APP_VERSION=${APP_VERSION}
      - LOG_LEVEL=${LOG_LEVEL:-INFO}
      - CACHE_TTL=${APP_CACHE_TTL:-300}
    restart: unless-stopped

volumes:
  pgdata:

networks:
  demo-net:
    driver: bridge
EOF
```

**說明**：
- `services` 區塊定義多個 container。目前只有 `app`。
- `build.context` 指向 `spring-boot-demo` 目錄（Phase 1 的 Spring Boot 專案）。
- `build.args` 傳入 build-time 參數（Dockerfile 中的 `ARG APP_NAME`）。
- `container_name` 指定固定的 container 名稱，方便識別。
- `ports: "8080:8080"` 將 container 的 8080 埠對應到 host 的 8080 埠。
- `restart: unless-stopped` 當 Docker daemon 啟動時自動重啟（除非手動 `docker compose stop`）。
- `volumes` 和 `networks` 目前是空的占位符，後續步驟會用到。

### 2.1.2 啟動並驗證

```bash
docker compose up -d        # 背景啟動
curl http://localhost:8080/  # 輸出：Hello from Spring Boot!
docker compose down          # 停止
```

### 2.1.3 bridge network — 以 service name 通訊

在同一個 Docker network 中的 container 可以透過 service name 互相連接，不需要知道 IP 位址。

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
      - "8080:8080"
    environment:
      - APP_ENV=${APP_ENV}
      - APP_VERSION=${APP_VERSION}
      - LOG_LEVEL=${LOG_LEVEL:-INFO}
      - CACHE_TTL=${APP_CACHE_TTL:-300}
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

**為什麼需要 bridge network？**

Docker Compose 預設會建立一個 default network，但顯式宣告 `demo-net` 有兩個好處：

1. **明確性**：所有屬於這個專案的 service 都掛在同一個 network 上
2. **擴展性**：後續加入的 service 只要指定 `networks: - demo-net` 即可互相通訊

**DNS 解析驗證**（需要等到 PostgreSQL 加入後才能實測）：

```bash
# 進入 app container，測試是否能解析 service name
docker exec spring-demo ping postgres
# 輸出：PING postgres (172.x.x.x):  ...  （表示透過 Docker DNS 解析到 IP）

docker exec spring-demo ping redis
# 輸出：PING redis (172.x.x.x):  ...  （同上）
```

在同一個 bridge network 中，Docker 內建 DNS 會自動將 `postgres`、`redis` 等 service name 解析為對應的 container IP。

---

## 2.2 Nginx API Gateway

### 2.2.1 建立 Nginx 設定檔

```bash
mkdir -p nginx

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
}
EOF
```

**設定說明**：

| 指令 | 作用 |
|------|------|
| `listen 80` | Nginx 監聽 80 埠 |
| `location /` | 提供靜態檔案（`/usr/share/nginx/html/`）|
| `location /api/` | 將 `/api/...` 請求代理到後端 |
| `proxy_pass http://app:8080/` | 使用 service name `app` 來連線 |
| `proxy_set_header Host $host` | 保留原始 Host header，讓後端知道原始請求的域名 |
| `proxy_set_header X-Real-IP $remote_addr` | 傳遞使用者真實 IP（否則後端只會看到 Nginx 的 IP） |

**重要**：`proxy_pass` 結尾的 `/` 有特殊意義：

```
請求：GET /api/config
     ↓
location /api/ 匹配，移除 /api/ 前綴
     ↓
proxy_pass http://app:8080/  加上 /
     ↓
後端收到：GET /config
```

如果缺少結尾的 `/`：
```
proxy_pass http://app:8080    （無結尾 /）
     ↓
後端收到：GET /api/config    （前綴沒有被移除）
```

### 2.2.2 建立 Nginx Dockerfile

```bash
cat > nginx/Dockerfile << 'EOF'
FROM nginx:alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY index.html /usr/share/nginx/html/index.html
EXPOSE 80
EOF
```

**為什麼使用 `nginx:alpine`？**

| 映像 | 大小 | 優點 | 缺點 |
|------|------|------|------|
| `nginx:latest` | ~190MB | 完整功能 | 大 |
| `nginx:alpine` | ~40MB | **小、安全** | 無 bash（只有 sh）|
| `nginx:perl` | ~260MB | 支援 Perl 模組 | 肥大 |

Alpine 版本基於 `musl libc` + BusyBox，映像極小。對 Nginx 這種純反向代理，Alpine 是標準選擇。

### 2.2.3 建立前端測試頁面

```bash
cat > nginx/index.html << 'EOF'
<!DOCTYPE html>
<html lang="zh-TW">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Microservices Demo</title>
    <style>
        body { font-family: sans-serif; max-width: 700px; margin: 50px auto; text-align: center; }
        h1 { color: #333; }
        .card { margin-top: 20px; padding: 20px; background: #f0f8ff; border-radius: 8px; text-align: left; }
        .card h3 { margin-top: 0; }
        pre { background: #eee; padding: 10px; border-radius: 4px; overflow-x: auto; }
    </style>
</head>
<body>
    <h1>Microservices Demo</h1>
    <p>Welcome to the microservices demo!</p>
    <div class="card">
        <h3>Backend</h3>
        <p><span id="backend-status">checking...</span></p>
    </div>
    <div class="card">
        <h3>Config (Env & Secrets)</h3>
        <pre id="config-output">loading...</pre>
    </div>
    <script>
        fetch('/api/')
            .then(r => r.text())
            .then(t => document.getElementById('backend-status').textContent = t)
            .catch(() => document.getElementById('backend-status').textContent = 'unreachable');

        fetch('/api/config')
            .then(r => r.json())
            .then(d => document.getElementById('config-output').textContent = JSON.stringify(d, null, 2))
            .catch(() => document.getElementById('config-output').textContent = 'unreachable');
    </script>
</body>
</html>
EOF
```

**前端邏輯**：
- `/api/` — 呼叫後端 Spring Boot 的 `GET /`，顯示 "Hello from Spring Boot!"
- `/api/config` — 呼叫後端 `GET /config`，顯示環境變數設定
- 如果後端不可用，則顯示 "unreachable"

### 2.2.4 更新 docker-compose.yml 加入 Nginx

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

**說明**：
- `build: ./nginx` 使用 `nginx/` 目錄中的 Dockerfile 建構 Nginx 映像
- `depends_on: - app` 確保 app 先啟動（但不等於 app 就緒，只是啟動順序）
- Nginx 的 `proxy_pass http://app:8080` 使用 service name `app` 作為後端位址
- `${SERVER_PORT_APP:-8080}` 讓 host 埠號可以透過 `.env` 自訂，預設為 8080

### 2.2.5 驗證

```bash
docker compose up -d --build

# 1. Nginx 靜態檔案
curl http://localhost/
# 輸出：HTML 內容（index.html）

# 2. 透過 Nginx 代理到後端
curl http://localhost/api/
# 輸出：Hello from Spring Boot!

# 3. 透過 Nginx 取得 config
curl http://localhost/api/config
# 輸出：{ "service": "spring-demo-backend", ... }

docker compose down
```

---

## 2.3 PostgreSQL

### 2.3.1 加入 PostgreSQL Service

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

volumes:
  pgdata:

networks:
  demo-net:
    driver: bridge
EOF
```

**新的 Docker Compose 概念**：

| 概念 | 說明 |
|------|------|
| `healthcheck` | Docker 定期檢查 container 是否就緒。`service_healthy` 讓 `depends_on` 等到就緒才啟動相依服務 |
| `pg_isready` | PostgreSQL 內建工具，確認資料庫可以接受連線 |
| `volumes: pgdata` | 具名 volume，讓資料庫重啟後資料不遺失 |
| `${SERVER_PORT_APP:-8080}` | 從 `.env` 讀取 `SERVER_PORT_APP`，如果未設定則使用 8080 |

**Volume 的三種模式**：

| 模式 | 語法 | 生命週期 | 用途 |
|------|------|---------|------|
| 具名 volume | `pgdata:/var/lib/postgresql/data` | Docker 管理 | 正式環境 |
| 綁定掛載 | `./data:/var/lib/postgresql/data` | 手動管理 | 開發除錯 |
| tmpfs | `type=tmpfs target=/data` | container 生命週期 | 暫存 |

**depends_on 的三種模式**：

```yaml
depends_on:
  - postgres              # 只等啟動（不健康也可）
  postgres:
    condition: service_started    # 同上一行，顯式語法
  postgres:
    condition: service_healthy    # 等健康檢查通過
```

在 Phase 2 中，`app` 使用 `condition: service_healthy`，確保資料庫就緒後才啟動應用程式。

### 2.3.2 更新 .env

```bash
cat > .env << 'EOF'
APP_ENV=development
APP_VERSION=1.0.0
LOG_LEVEL=DEBUG
POSTGRES_DB=authdb
POSTGRES_USER=authuser
POSTGRES_PASSWORD=pg_s3cr3t!
JWT_SECRET=ZGV2LXNlY3JldC1rZXktZm9yLWRlbW8tcHVycG9zZXMtb25seSE=
APP_CACHE_TTL=300
AUTH_CACHE_TTL=60
EOF
```

**注意**：`.env` 必須在 `.gitignore` 中，不會提交到版本控制。這是示範用的密碼，正式環境必須使用強密碼。

### 2.3.3 更新 pom.xml — 加入 JDBC 與 PostgreSQL 依賴

```bash
cat > spring-boot-demo/pom.xml << 'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>3.3.5</version>
        <relativePath/>
    </parent>

    <groupId>com.example</groupId>
    <artifactId>demo</artifactId>
    <version>0.0.1-SNAPSHOT</version>
    <name>demo</name>
    <description>Minimal Spring Boot Demo</description>

    <properties>
        <java.version>21</java.version>
    </properties>

    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-jdbc</artifactId>
        </dependency>
        <dependency>
            <groupId>org.postgresql</groupId>
            <artifactId>postgresql</artifactId>
            <scope>runtime</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
EOF
```

**新依賴**：

| 依賴 | 作用 |
|------|------|
| `spring-boot-starter-jdbc` | 提供 `JdbcTemplate` 與連線池（HikariCP）管理 |
| `postgresql` | PostgreSQL JDBC 驅動（`scope=runtime` 表示編譯不需要，執行時才需要）|

為什麼 `postgresql` 用 `runtime` scope？
- 編譯程式碼時只依賴 JDBC 抽象介面（`javax.sql.DataSource`）
- 只有執行時才需要載入具體驅動（`org.postgresql.Driver`）
- 好處：更換資料庫（如 MySQL）時不需要修改 Java 程式碼，只需更換驅動相依

### 2.3.4 更新 application.properties — 資料庫設定

```bash
cat > spring-boot-demo/src/main/resources/application.properties << 'EOF'
server.port=8080
spring.application.name=${APP_NAME:demo}

# Logging
logging.level.root=${LOG_LEVEL:INFO}

# PostgreSQL
spring.datasource.url=jdbc:postgresql://postgres:5432/${POSTGRES_DB}
spring.datasource.username=${POSTGRES_USER}
spring.datasource.password=${POSTGRES_PASSWORD}
spring.datasource.driver-class-name=org.postgresql.Driver
EOF
```

**資料庫連線 URL 解析**：

```
jdbc:postgresql://postgres:5432/authdb
                   │              │
                   │              └── 資料庫名稱（來自 ${POSTGRES_DB} = authdb）
                   │
                   └── service name，由 Docker DNS 解析為 container IP
```

為什麼不用 `localhost`？
- 在 container 內，`localhost` 是指自己的 container
- PostgreSQL 在另一個 container，必須用 service name `postgres`
- Docker Compose 自動將 service name 註冊到 DNS

### 2.3.5 驗證建置

```bash
mvn compile -f spring-boot-demo/pom.xml
# BUILD SUCCESS
```

---

## 2.4 Redis

### 2.4.1 加入 Redis Service

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

  nginx:
    build: ./nginx
    container_name: nginx-gateway
    ports:
      - "80:80"
    depends_on:
      - app
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

**Redis 健康檢查**：

`["CMD", "redis-cli", "ping"]` 等同於在 container 內執行 `redis-cli ping`：
- Redis 正常 → 回傳 `PONG` → Docker 判定 healthy
- Redis 異常 → 無回傳或錯誤 → Docker 判定 unhealthy

為何 Redis 使用 `service_started` 而非 `service_healthy`？
- Redis 啟動非常快（毫秒級），幾乎不需要等待
- App 端有連線重試機制，Redis 短暫不可用不會造成問題
- 與 PostgreSQL 不同，資料庫連線失敗會直接讓 Spring Boot 啟動失敗

### 2.4.2 更新 pom.xml — 加入 Redis 依賴

```bash
cat > spring-boot-demo/pom.xml << 'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>3.3.5</version>
        <relativePath/>
    </parent>

    <groupId>com.example</groupId>
    <artifactId>demo</artifactId>
    <version>0.0.1-SNAPSHOT</version>
    <name>demo</name>
    <description>Minimal Spring Boot Demo</description>

    <properties>
        <java.version>21</java.version>
    </properties>

    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-jdbc</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-redis</artifactId>
        </dependency>
        <dependency>
            <groupId>org.postgresql</groupId>
            <artifactId>postgresql</artifactId>
            <scope>runtime</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
EOF
```

**`spring-boot-starter-data-redis` 提供了什麼？**

| 元件 | 用途 |
|------|------|
| `RedisTemplate<K, V>` | 通用 Redis 操作（任何資料型態） |
| `StringRedisTemplate` | 專門操作 `String` 型態（最常用） |
| `LettuceConnectionFactory` | 非阻塞式 Redis 連線管理 |
| Platform transaction manager | Redis 交易支援 |

**Lettuce vs Jedis**（Spring Boot 2.x 後預設 Lettuce）：

| | Jedis | Lettuce |
|---|-------|---------|
| 執行模式 | 同步阻塞 | 非同步（Netty）|
| 執行緒安全 | 否（需連線池） | 是（一個連線可多執行緒）|
| 效能 | 一般 | 高（特別是高延遲場景）|
| Spring Boot 預設 | Boot 1.x | Boot 2.x+ |

### 2.4.3 更新 application.properties — Redis 設定

```bash
cat > spring-boot-demo/src/main/resources/application.properties << 'EOF'
server.port=8080
spring.application.name=${APP_NAME:demo}

# Logging
logging.level.root=${LOG_LEVEL:INFO}

# PostgreSQL
spring.datasource.url=jdbc:postgresql://postgres:5432/${POSTGRES_DB}
spring.datasource.username=${POSTGRES_USER}
spring.datasource.password=${POSTGRES_PASSWORD}
spring.datasource.driver-class-name=org.postgresql.Driver

# Redis
spring.data.redis.host=redis
spring.data.redis.port=6379
EOF
```

**Spring Boot 自動設定**：加入 `spring-boot-starter-data-redis` 後，Spring Boot 會自動建立：
- `RedisConnectionFactory`（使用 Lettuce）
- `StringRedisTemplate`
- `RedisTemplate<Object, Object>`

只要設定 `spring.data.redis.host` 和 `spring.data.redis.port` 即可。

---

## 2.5 連線驗證

### 2.5.1 更新 DemoApplication.java — 加入 /db-check 端點

```bash
cat > spring-boot-demo/src/main/java/com/example/demo/DemoApplication.java << 'EOF'
package com.example.demo;

import java.util.Map;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@SpringBootApplication
@RestController
public class DemoApplication {

    private final Environment env;
    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;

    public DemoApplication(Environment env, JdbcTemplate jdbc, StringRedisTemplate redis) {
        this.env = env;
        this.jdbc = jdbc;
        this.redis = redis;
    }

    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }

    @GetMapping("/")
    public String hello() {
        return "Hello from Spring Boot!";
    }

    @GetMapping("/config")
    public Map<String, Object> config() {
        return Map.ofEntries(
            Map.entry("service", env.getProperty("spring.application.name", "unknown")),
            Map.entry("APP_ENV", env.getProperty("APP_ENV", "undefined")),
            Map.entry("APP_VERSION", env.getProperty("APP_VERSION", "undefined")),
            Map.entry("LOG_LEVEL", env.getProperty("LOG_LEVEL", "undefined")),
            Map.entry("CACHE_TTL", env.getProperty("CACHE_TTL", "undefined"))
        );
    }

    @GetMapping("/db-check")
    public Map<String, Object> dbCheck() {
        String pgStatus = "FAIL";
        String redisStatus = "FAIL";
        try {
            jdbc.queryForObject("SELECT 1", Integer.class);
            pgStatus = "OK";
        } catch (Exception e) {
            pgStatus = e.getMessage();
        }
        try {
            redis.opsForValue().set("ping", "pong");
            String pong = redis.opsForValue().get("ping");
            redisStatus = "OK (ping=" + pong + ")";
        } catch (Exception e) {
            redisStatus = e.getMessage();
        }
        return Map.of("postgresql", pgStatus, "redis", redisStatus);
    }
}
EOF
```

**`/db-check` 端點說明**：

```
GET /db-check
├── PostgreSQL：執行 SELECT 1
│   ├── 成功 → "postgresql": "OK"
│   └── 失敗 → "postgresql": "連線錯誤訊息"
│
└── Redis：SET ping → pong，再 GET ping
    ├── 成功 → "redis": "OK (ping=pong)"
    └── 失敗 → "redis": "連線錯誤訊息"
```

**JdbcTemplate 的 operations**：

| 方法 | 用途 | 範例 |
|------|------|------|
| `queryForObject` | 查詢單一結果 | `jdbc.queryForObject("SELECT 1", Integer.class)` |
| `queryForList` | 查詢多行 | `jdbc.queryForList("SELECT * FROM users")` |
| `update` | INSERT / UPDATE / DELETE | `jdbc.update("INSERT INTO ...")` |
| `batchUpdate` | 批次寫入 | `jdbc.batchUpdate(sql, batchArgs)` |

**StringRedisTemplate vs RedisTemplate**：

```java
// StringRedisTemplate — key/value 都是 String
stringRedis.opsForValue().set("key", "value");   // SET key value
stringRedis.opsForValue().get("key");             // GET key → "value"

// RedisTemplate<Object, Object> — 使用 JDK 序列化
redisTemplate.opsForValue().set("key", "value");  // SET \xAC\xED\x00\x05...
```

建議：除非需要序列化物件，否則使用 `StringRedisTemplate` 避免序列化問題。

### 2.5.2 建置並驗證

```bash
# 1. 編譯（確保 Java 程式碼無誤）
mvn compile -f spring-boot-demo/pom.xml

# 2. 建置 Docker 映像（Nginx + Spring Boot）
docker compose build

# 3. 啟動所有服務
docker compose up -d
# 預期：
# - Network demo-net 建立
# - Volume pgdata 建立（資料庫持久化）
# - postgres 啟動
# - redis 啟動
# - app 啟動（等待 postgres 健康檢查通過）
# - nginx 啟動

# 4. 查看啟動狀態
docker compose ps
# NAME             STATUS
# demo-postgres    Up (healthy)
# demo-redis       Up (healthy)
# spring-demo      Up
# nginx-gateway    Up

# 5. 測試 Nginx 靜態檔案
curl http://localhost/
# 輸出：HTML 內容

# 6. 測試 API Gateway 代理
curl http://localhost/api/
# Hello from Spring Boot!

# 7. 測試資料庫連線
curl http://localhost/api/db-check
# {"postgresql": "OK", "redis": "OK (ping=pong)"}

# 8. 如有任一連線失敗，查看日誌
docker compose logs app
```

### 2.5.3 完整的端到端驗證腳本

```bash
#!/bin/bash
set -e

echo "=== Phase 2 驗證 ==="

echo "1. 建置映像..."
docker compose build

echo "2. 啟動所有服務..."
docker compose up -d

echo "3. 等待服務就緒..."
sleep 10

echo "4. 測試首頁..."
curl -s http://localhost/ | head -5

echo "5. 測試 API..."
curl -s http://localhost/api/
echo ""

echo "6. 測試 Config..."
curl -s http://localhost/api/config | python3 -m json.tool

echo "7. 測試 DB 連線..."
curl -s http://localhost/api/db-check | python3 -m json.tool

echo "8. 直接測試 PostgreSQL（不需要進入 container）..."
docker exec demo-postgres psql -U authuser -d authdb -c "SELECT 1;"

echo "9. 直接測試 Redis..."
docker exec demo-redis redis-cli ping

echo "=== 驗證完成 ==="
```

### 2.5.4 常見問題排除

**問題：PostgreSQL 無法啟動**

```bash
docker compose logs postgres
# 查看是否有權限錯誤或 port 衝突

# 如果是 port 5432 已被佔用，修改 host 埠號：
# docker-compose.yml 中 ports: "5433:5432"
```

**問題：App 無法連線到 PostgreSQL**

```bash
docker compose logs app
# 檢查資料庫 URL 中的 service name 是否正確
# 確認 depends_on 使用 condition: service_healthy
```

**問題：Nginx 502 Bad Gateway**

```bash
# 確認 app 已就緒
docker compose exec app curl http://localhost:8080/
# 確認 nginx.conf 中 proxy_pass 的位址正確
```

---

## 最終 docker-compose.yml 架構

```
services:
  app:         Spring Boot (JdbcTemplate + StringRedisTemplate)
  nginx:       API Gateway (反向代理 /api/ → app:8080)
  postgres:    資料庫 (16-alpine, 具名 volume, healthcheck)
  redis:       快取 (7-alpine, healthcheck)

networks:
  demo-net:    bridge (service name DNS 解析)

volumes:
  pgdata:      資料庫持久化
```

---

## Commit 歷史參考

```bash
git log --oneline
# Phase 2 對應的 commits：
# feat: 新增 /db-check 端點驗證 PostgreSQL 與 Redis 連線
# feat: 加入 Redis 7-alpine service + spring-boot-starter-data-redis
# feat: 加入 PostgreSQL 16-alpine + spring-boot-starter-jdbc
# feat: 加入 Nginx API Gateway（proxy_pass /api/ → app:8080）
# feat: 建立 bridge network（demo-net）
# feat: 建立 docker-compose.yml 管理多服務
# ... (Phase 1 commits)
```
