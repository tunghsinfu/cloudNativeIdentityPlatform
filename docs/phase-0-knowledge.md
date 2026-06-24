# Phase 0 知識重點 — Cloud Native Identity Platform

## 目錄

1. [Spring Boot 基礎](#1-spring-boot-基礎)
2. [Maven 與相依管理](#2-maven-與相依管理)
3. [REST API 設計](#3-rest-api-設計)
4. [Docker 容器化](#4-docker-容器化)
5. [Docker Compose 服務編排](#5-docker-compose-服務編排)
6. [環境變數與機密管理](#6-環境變數與機密管理)
7. [Nginx 反向代理與 API Gateway](#7-nginx-反向代理與-api-gateway)
8. [PostgreSQL 連線](#8-postgresql-連線)
9. [Redis 連線](#9-redis-連線)
10. [JWT 認證](#10-jwt-認證)
11. [Git 版本控制實務](#11-git-版本控制實務)
12. [面試常見問題](#12-面試常見問題)

---

## 1. Spring Boot 基礎

### 1.1 @SpringBootApplication

```java
@SpringBootApplication
@RestController
public class DemoApplication {
    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }
}
```

`@SpringBootApplication` 是三個 annotation 的合成：

| Annotation | 作用 |
|-----------|------|
| `@SpringBootConfiguration` | 標記此類別為 configuration source |
| `@EnableAutoConfiguration` | 根據 classpath 依賴自動配置 Bean |
| `@ComponentScan` | 掃描當前 package 及其子 package 的 `@Component` |

**面試重點**：Spring Boot 如何知道要 auto-configure 哪些東西？

→ `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 檔案列出所有 auto-configuration 類別。Spring Boot 根據條件式註解（`@ConditionalOnClass`、`@ConditionalOnMissingBean` 等）決定是否啟用。

### 1.2 Spring Boot Starter

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-web</artifactId>
</dependency>
```

Starter 是一組預先打包的相依性描述。`spring-boot-starter-web` 包含：

- Spring MVC
- 內嵌 Tomcat
- Jackson（JSON 序列化）
- 驗證（Hibernate Validator）

**面試重點**：Starter 和傳統手動加 dependencies 的差別？

→ Starter 解決了版本相容性問題，由 Spring Boot 的 dependency management（BOM）統一管理版本。

### 1.3 application.properties / application.yml

```properties
server.port=8080
spring.application.name=demo
```

**載入順序**（由高到低優先級）：

1. `file:./config/` — 專案目錄下的 config 目錄
2. `file:./` — 專案根目錄
3. `classpath:config/` — classpath 下的 config 目錄
4. `classpath:` — classpath 根目錄

**外部化設定**：可以透過環境變數、`.env`、command line arguments 覆蓋。

**面試重點**：`${POSTGRES_PASSWORD}` 在 `application.properties` 中是如何被解析的？

→ Spring Boot 的 `PropertySourcesPlaceholderConfigurer` 會解析 `${...}` 佔位符，從 Environment 中查找對應的屬性值。

---

## 2. Maven 與相依管理

### 2.1 Parent POM

```xml
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.3.5</version>
    <relativePath/>
</parent>
```

`spring-boot-starter-parent` 繼承自 `spring-boot-dependencies`，後者定義了所有 Spring Boot 支援的第三方函式庫版本（BOM — Bill of Materials）。

**面試重點**：為什麼要用 Parent POM？

→ 統一版本管理，避免版本衝突。所有 Spring 生態系的函式庫版本都由 BOM 鎖定。

### 2.2 Scope 與選用依賴

```xml
<dependency>
    <groupId>org.postgresql</groupId>
    <artifactId>postgresql</artifactId>
    <scope>runtime</scope>
</dependency>
```

| Scope | 編譯 | 執行 | 說明 |
|-------|------|------|------|
| `compile`（預設） | ✅ | ✅ | 所有階段 |
| `runtime` | ❌ | ✅ | 僅執行期需要（如 JDBC driver） |
| `provided` | ✅ | ❌ | 容器已提供（如 servlet API） |
| `test` | ❌ | ❌ | 僅測試 |

**為什麼 JDBC driver 用 `runtime`？**
→ 編譯期只需要 JDBC 介面（Spring 提供），不需要具體實作。執行期才需要驅動。

---

## 3. REST API 設計

### 3.1 REST 端點模式

```java
@GetMapping("/")
public String hello() {
    return "Hello from Spring Boot!";
}

@GetMapping("/config")
public Map<String, Object> config() {
    return Map.of("key", "value");
}
```

### 3.2 HTTP 狀態碼設計

| 狀況 | HTTP Status |
|------|------------|
| 成功 | 200 OK |
| 新增資源 | 201 Created |
| 請求格式錯誤 | 400 Bad Request |
| 未認證 | 401 Unauthorized |
| 無權限 | 403 Forbidden |
| 資源不存在 | 404 Not Found |
| 請求過多（rate limit） | 429 Too Many Requests |
| 伺服器錯誤 | 500 Internal Server Error |

### 3.3 Map.of 與 Immutable

`Map.of()` 回傳 immutable map，不可修改。面試時可以說明 Java 9 引入的 factory method 與不可變集合的優點。

---

## 4. Docker 容器化

### 4.1 多階段構建（Multi-Stage Build）

```dockerfile
# Stage 1: Build
FROM maven:3.9-eclipse-temurin-21-alpine AS builder
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline
COPY src ./src
RUN mvn clean package -DskipTests

# Stage 2: Run
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=builder /app/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

**為什麼要分兩個 stage？**

| | 單階段 | 多階段 |
|---|--------|--------|
| Image 大小 | ~400MB（含 Maven + JDK） | ~150MB（僅 JRE + jar） |
| Attack surface | 大（包含 build tools） | 小 |
| 快取有效率 | 較差 | 好（dependency layer 可 cache） |

**面試重點**：`RUN mvn dependency:go-offline` 的作用？

→ 在複製 source code 之前先下載所有依賴，這樣依賴層可以被 Docker layer cache。後續只要 pom.xml 不變，就不會重複下載。

### 4.2 Docker Layer Caching

每一條 `RUN`、`COPY`、`ADD` 指令都會產生一個 layer。如果 layer 沒有改變，Docker 會使用 cache。

最佳實踐：
- 把不常變動的指令放前面（`COPY pom.xml`、`RUN mvn dependency:go-offline`）
- 把常變動的放後面（`COPY src ./src`）

### 4.3 Alpine 映像

`-alpine` 基於 Alpine Linux（musl libc + BusyBox），映像大小約 5MB（對比 Ubuntu ~30MB）。

注意事項：
- DNS 解析行為與 glibc 不同
- 部分 Java 函式庫可能需要 glibc（但有 eclipse-temurin 已處理）

---

## 5. Docker Compose 服務編排

### 5.1 docker-compose.yml 結構

```yaml
services:
  app:
    build: ./spring-boot-demo
    ports:
      - "8080:8080"
    environment:
      - POSTGRES_PASSWORD=${POSTGRES_PASSWORD}
    depends_on:
      postgres:
        condition: service_healthy
      redis:
        condition: service_started
    networks:
      - demo-net
    restart: unless-stopped

networks:
  demo-net:
    driver: bridge

volumes:
  pgdata:
```

### 5.2 depends_on 與 Healthcheck

```yaml
depends_on:
  postgres:
    condition: service_healthy
```

單純的 `depends_on` 只保證啟動順序，不保證服務就緒。加上 `condition: service_healthy` 後會等到 healthcheck 通過才啟動相依服務。

```yaml
healthcheck:
  test: ["CMD-SHELL", "pg_isready -U ${POSTGRES_USER} -d ${POSTGRES_DB}"]
  interval: 5s
  timeout: 3s
  retries: 5
```

**面試重點**：為什麼不能只靠 `depends_on`？

→ Docker 只能知道 container 是否已啟動（process exists），無法知道內部服務是否已就緒（port listening, DB accepting connections）。

### 5.3 網路模型

```yaml
networks:
  demo-net:
    driver: bridge
```

- **bridge**（預設）：同一 network 的 container 可以透過 service name 互相訪問
- **host**：共用 host 網路（效能好，但有 port 衝突風險）
- **overlay**：跨主機網路（Swarm mode）

Service name 會自動被 Docker DNS 解析為 container IP，因此 `app` 服務可以透過 `http://postgres:5432` 連線 PostgreSQL。

### 5.4 Volumes 持久化

```yaml
volumes:
  pgdata:
```

- **named volume**：由 Docker 管理，存放在 `/var/lib/docker/volumes/`
- **bind mount**：掛載 host 目錄（`./data:/var/lib/postgresql/data`）

資料庫一定要用 volume，否則 container 重啟後資料遺失。

---

## 6. 環境變數與機密管理

### 6.1 .env 檔案

```bash
APP_ENV=development
POSTGRES_PASSWORD=pg_s3cr3t!
JWT_SECRET=ZGV2LXNlY3JldC...
```

- Docker Compose 自動載入同目錄下的 `.env`
- `.env` 必須加入 `.gitignore`，避免密碼洩漏
- 變數以 `${VAR}` 形式在 `docker-compose.yml` 中引用

### 6.2 變數注入層級

```
.env → docker-compose.yml (${VAR}) → container environment
                                            ↓
                                    application.properties (${VAR})
                                            ↓
                                    Spring @Value("${VAR}")
```

### 6.3 Production vs Development

| 環境 | 機密管理方式 |
|------|------------|
| Development | `.env`（gitignored） |
| Production | Kubernetes Secret / Vault / AWS Secrets Manager |

**面試重點**：如果 `.env` 被 commit 了怎麼辦？

→ 立即 rotate 所有密碼。從 git history 移除（`git filter-branch`），但已 clone 的 repo 無法強制回收，所以密碼仍視為洩漏。

### 6.4 環境區隔

```bash
# .env.development
APP_ENV=development
POSTGRES_PASSWORD=dev_pass

# .env.production
APP_ENV=production
POSTGRES_PASSWORD=prod_pass
```

```bash
docker compose --env-file .env.production up -d
```

---

## 7. Nginx 反向代理與 API Gateway

### 7.1 反向代理設定

```nginx
location /api/ {
    proxy_pass http://app:8080/;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
}
```

**關鍵行為**：
- `proxy_pass` 結尾有 `/` → 去除匹配的 location 前綴（`/api/hello` → `http://app:8080/hello`）
- `proxy_pass` 結尾無 `/` → 保留完整路徑（`/api/hello` → `http://app:8080/api/hello`）

### 7.2 API Gateway Pattern

```
User → Nginx (80)
         ├── /auth/*     → auth-service:8081
         ├── /user/*     → user-service:8082
         ├── /api/*      → app:8080 (demo)
         └── /*          → 靜態頁面
```

**優點**：
- 單一入口點
- 隱藏內部服務架構
- 可以在 gateway 層做 rate limit、TLS termination、logging

### 7.3 Nginx Dockerfile

```dockerfile
FROM nginx:alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY index.html /usr/share/nginx/html/index.html
```

- 覆蓋 `/etc/nginx/conf.d/default.conf` 即可自訂設定
- `nginx:alpine` 映像大小約 23MB

**面試重點**：Nginx 作為 API Gateway 和 Spring Cloud Gateway 的差別？

→ Nginx 是輕量反向代理，適合 routing、SSL termination；Spring Cloud Gateway 可以整合 Spring 生態系做更細粒度的路由邏輯（如根據 JWT claims 路由）。

---

## 8. PostgreSQL 連線

### 8.1 JDBC 設定

```properties
spring.datasource.url=jdbc:postgresql://postgres:5432/${POSTGRES_DB}
spring.datasource.username=${POSTGRES_USER}
spring.datasource.password=${POSTGRES_PASSWORD}
spring.datasource.driver-class-name=org.postgresql.Driver
```

- Driver class 在大多數情況下可以省略（Spring Boot 可以從 URL 推斷）
- `spring.datasource.url` 中的 `postgres` 是 Docker service name

### 8.2 Spring Boot Auto-Configuration

當 classpath 有以下依賴時，Spring Boot 會自動配置 DataSource：
- `spring-boot-starter-jdbc`
- `postgresql` driver

過程：

1. `DataSourceAutoConfiguration` 檢查 classpath 是否有 `DataSource`、`EmbeddedDatabaseType`
2. 讀取 `spring.datasource.*` 屬性
3. 建立 `DataSource` Bean（HikariCP connection pool）
4. 建立 `JdbcTemplate` Bean

### 8.3 連線驗證

```java
@GetMapping("/db-check")
public Map<String, Object> dbCheck() {
    jdbc.queryForObject("SELECT 1", Integer.class);
    return Map.of("postgresql", "OK");
}
```

`SELECT 1` 是標準的資料庫連線測試，不依賴任何 table 存在。

---

## 9. Redis 連線

### 9.1 設定

```properties
spring.data.redis.host=redis
spring.data.redis.port=6379
```

`spring-boot-starter-data-redis` 會自動配置：
- `LettuceConnectionFactory`
- `StringRedisTemplate`（key/value 都是 String）
- `RedisTemplate`（key/value 都是 Object，需 serializer）

### 9.2 StringRedisTemplate 使用

```java
// 寫入
redis.opsForValue().set("key", "value");

// 讀取
String value = redis.opsForValue().get("key");

// 設定 TTL
redis.expire("key", 300, TimeUnit.SECONDS);

// 原子遞增
redis.opsForValue().increment("counter");

// 刪除
redis.delete("key");
```

### 9.3 連線驗證

```java
redis.opsForValue().set("ping", "pong");
String pong = redis.opsForValue().get("ping");
// pong = "pong"
```

---

## 10. JWT 認證

### 10.1 JWT 結構

```
header.payload.signature

header:  {"alg": "HS256", "typ": "JWT"}
payload: {"sub": "alice", "jti": "uuid", "iat": 123, "exp": 456}
signature: HMACSHA256(base64(header) + "." + base64(payload), secret)
```

### 10.2 jjwt 函式庫

```java
// 產生 token
Jwts.builder()
    .subject(username)
    .id(UUID.randomUUID().toString())    // jti
    .issuedAt(new Date())
    .expiration(new Date(System.currentTimeMillis() + expirationMs))
    .signWith(key)
    .compact();

// 驗證 token
Jwts.parser()
    .verifyWith(key)
    .build()
    .parseSignedClaims(token)
    .getPayload()
    .getSubject();
```

### 10.3 JWT claims 設計

| Claim | 全名 | 用途 |
|-------|------|------|
| `sub` | Subject | 使用者識別（通常是 username 或 user id） |
| `jti` | JWT ID | 唯一識別，用於黑名單 |
| `iat` | Issued At | 簽發時間 |
| `exp` | Expiration | 過期時間 |
| `role` | (custom) | 使用者角色 |

**為什麼要存 `jti`？**
→ 因為 JWT 是 stateless，一旦簽發就無法撤銷。要實作登出功能，需要唯一 ID 來標識單一 token，放入黑名單。

### 10.4 Stateless 的真相

> JWT 是 stateless 指的是認證伺服器不需要保存 session，但應用層面仍然需要管理 token lifecycle。

| 需求 | 實作方式 |
|------|---------|
| 登出 | Redis blacklist（`blacklist:{jti}`） |
| Token 過期 | `exp` claim |
| Refresh | Refresh token stored in Redis |

**面試重點**：JWT Stateless 的好處和限制？

好處：
- 不需要 session 儲存
- 微服務間可以直接驗證（shared secret）
- 橫向擴展不需要 session 同步

限制：
- 無法即時撤銷（需 blacklist）
- payload 大小較大
- secret 管理複雜（多服務共用）

---

## 11. Git 版本控制實務

### 11.1 Commit Message 規範

```
<type>: <subject>
```

| Type | 使用時機 |
|------|---------|
| `feat` | 新功能 |
| `fix` | Bug 修正 |
| `docs` | 文件變更 |
| `refactor` | 重構 |
| `test` | 測試 |
| `chore` | 工具/設定變更 |

### 11.2 .gitignore 策略

```
.env              # 環境變數（含機密）
target/           # Maven build output
*.jar             # Build artifacts
.idea/            # IDE 設定
*.log             # 日誌
```

- `.env` 必須在第一次 commit 之前就加入 `.gitignore`
- 如果已經 commit 了機密檔案，除了從 git 移除，還需要 rotate 密碼

### 11.3 逐步驟 Commit

每個子步驟獨立 commit 的好處：
- 易於 review（diff 小）
- 易於 revert（不會牽扯無關變更）
- 提供清晰的專案演進歷史

---

## 12. 面試常見問題

### Spring Boot

**Q: Spring Boot 和 Spring Framework 的差別？**
→ Spring Boot 是建立在 Spring Framework 之上的自動化配置工具。它透過 starter 依賴、auto-configuration、embedded server 等方式大幅減少 boilerplate 設定。

**Q: @SpringBootApplication 內部做了什麼？**
→ 合成 `@SpringBootConfiguration`、`@EnableAutoConfiguration`、`@ComponentScan`。啟動時根據 classpath 依賴條件式自動註冊 Bean。

**Q: 如何覆蓋 Spring Boot 的 auto-configuration？**
→ 定義自訂 `@Bean`（`@ConditionalOnMissingBean` 會跳過）；或使用 `exclude` 屬性；或設定 `spring.autoconfigure.exclude`。

### Docker

**Q: 多階段構建的優缺點？**
→ 優點：減少最終映像大小、減少 attack surface、分離 build/runtime 環境。缺點：CI 需要支援 multi-stage、debug 時需要更多 context。

**Q: Docker layer cache 的原理？**
→ 每一層指令產生一個 diff layer。若上層 cache miss，後續所有層都會 invalidate。因此應將不常變動的指令放前面。

**Q: CMD 和 ENTRYPOINT 的差別？**
→ `ENTRYPOINT` 定義 container 的主要指令，`CMD` 提供預設參數。`docker run` 傳入的參數會覆蓋 `CMD` 但不會覆蓋 `ENTRYPOINT`。

### JWT

**Q: 為什麼登出時需要 Redis blacklist？**
→ JWT 在過期前都是有效的，無法即時撤銷。Blacklist 提供 O(1) 的查詢來阻止已登出的 token。

**Q: Access token 和 Refresh token 的分工？**
→ Access token（短效，15 分鐘）用於 API 認證；Refresh token（長效，7 天）用於取得新的 access token，可在 Redis 中保存以實現 session 管理。

**Q: JWT 和 Session 認證的選擇？**
→ JWT 適合分散式系統、微服務；Session 適合單體應用、需要即時撤銷。也可以混合使用（JWT + Redis session store）。

### Nginx

**Q: 為什麼用 Nginx 而不是直接暴露服務？**
→ 單一入口點、TLS termination、route-based 分流、隱藏內部服務結構、可以在 gateway 層統一做 rate limit。

**Q: proxy_pass 結尾的 `/` 差異？**
→ 有 `/` 會去除 location 前綴，無 `/` 則保留。關鍵細節。

### Redis

**Q: Redis 在認證系統中的角色？**
→ Token blacklist（登出管理）、Rate limiting（防暴力破解）、Cache-aside（User profile 快取）。

**Q: Redis TTL 的用途？**
→ 自動清理過期資料（blacklist 條目、rate limit 計數器、cache entries），不需要手動 GC。

### 架構設計

**Q: 如何設計微服務間的認證？**
→ 共用 JWT secret（簡單但安全性較低）或使用 RSA key pair（auth-service 簽發，user-service 用 public key 驗證）。

**Q: 這個架構的單點故障在哪？**
→ JWT secret 遺失 → 所有 token 失效；Redis 掛掉 → blacklist 失效（可降級為只檢查 exp）；PG 掛掉 → login 失效。

**Q: 如何處理 Redis 掛掉的情況？**
→ Blacklist 檢查降級（只檢查 JWT exp，接受安全妥協）；Cache miss 改查 DB。
