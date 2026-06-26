# Phase 5 — Production Hardening

從「能跑」進化到「能 Production 執行」。這個 Phase 涵蓋 7 個面向：

| 步驟 | 內容 | 主要檔案 |
|------|------|---------|
| 5.1 | Docker 非 root 使用者 | `Dockerfile` |
| 5.2 | Spring Boot Layered JAR | `Dockerfile` |
| 5.3 | Graceful Shutdown | `application.properties` |
| 5.4 | HikariCP 連線池調校 | `application.properties` |
| 5.5 | Docker Resource Limits | `docker-compose.yml` |
| 5.6 | CORS 設定 | `CorsConfig.java` |
| 5.7 | Spring Boot Actuator | `pom.xml` + `application.properties` |

## 5.1 Docker 非 root 使用者

Container 內以 root 執行是常見的安全風險。如果攻擊者透過應用程式漏洞取得 shell，等同擁有 host 的 root 權限。

### 建立 appuser

在 Dockerfile 的 runtime stage 加入：

```dockerfile
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
...
USER appuser
```

- `addgroup -S`：建立 system group（gid < 1000）
- `adduser -S`：建立 system user，沒有密碼、沒有 home directory
- `USER appuser`：後續的 `ENTRYPOINT` 和 `CMD` 都以 appuser 執行

### 為什麼不需要 `chown`？

Spring Boot 的 embedded Tomcat 在 `java.io.tmpdir`（通常是 `/tmp`）寫入 session 和 upload 檔案。`/tmp` 預設權限是 `1777`（drwxrwxrwt），任何使用者都可寫入，所以 appuser 不需要額外 `chown`。

驗證：

```bash
docker compose exec auth id
# uid=100(appuser) gid=101(appgroup) groups=101(appgroup)
```

## 5.2 Spring Boot Layered JAR

Layered JAR 將應用程式拆成四層，利用 Docker layer cache 加速建構：

| 層 | 內容 | 變更頻率 |
|----|------|---------|
| `dependencies/` | 第三方函式庫（Spring, Jackson, JJWT…） | 幾乎不變 |
| `spring-boot-loader/` | Spring Boot 類別載入器 | 幾乎不變 |
| `snapshot-dependencies/` | SNAPSHOT 版本依賴 | 偶爾 |
| `application/` | 你自己寫的程式碼和資源檔 | 每次 |

### 原理

```bash
# 查看 JAR 中的層結構
java -Djarmode=layertools -jar app.jar list

# 解壓成目錄
java -Djarmode=layertools -jar app.jar extract
# 產生：dependencies/  spring-boot-loader/  snapshot-dependencies/  application/
```

### Dockerfile 應用

完整 Dockerfile：

```dockerfile
FROM maven:3.9-eclipse-temurin-21-alpine AS builder
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline
COPY src ./src
RUN mvn clean package -DskipTests

FROM eclipse-temurin:21-jre-alpine

ARG APP_NAME=unknown
ENV APP_NAME=${APP_NAME}

RUN addgroup -S appgroup && adduser -S appuser -G appgroup

WORKDIR /app
COPY --from=builder /app/target/*.jar app.jar

EXPOSE 8081
USER appuser
ENTRYPOINT ["java", "-jar", "app.jar"]
```

目前的實作保留 `java -jar` 的方式執行，以保證最大相容性。Layered JAR 在 Spring Boot 3.3+ 中 loader class 的 package 有變動，要整合進 Docker 層快取需要對應版本調整，屬於可追加的進階優化（詳見 Spring Boot 官方文件 [Container Images](https://docs.spring.io/spring-boot/reference/packaging/container-images.html)）。

## 5.3 Graceful Shutdown

當 `docker compose stop` 或 `SIGTERM` 送達時，Spring Boot 預設的行為是立即中斷所有請求並關閉。這會導致正在處理的請求失敗。

```properties
server.shutdown=graceful
spring.lifecycle.timeout-per-shutdown-phase=30s
```

設定後的行為：

```
docker compose stop auth
# 1. Docker 送出 SIGTERM 給 auth container
# 2. Spring Boot 停止接受新請求（回傳 503）
# 3. 等待正在處理的請求完成（最多 30 秒）
# 4. 關閉 DataSource、Redis 連線
# 5. container 停止
```

驗證：

```bash
# 終端 1：持續發送請求
while true; do curl -s http://localhost:28080/auth/v1/health; sleep 0.5; done

# 終端 2：重啟 auth
docker compose restart auth
# 觀察終端 1 — 不會有連線中斷的錯誤
```

## 5.4 HikariCP 連線池調校

Spring Boot 2.0+ 預設使用 HikariCP，不需額外 dependency。調整參數：

```properties
spring.datasource.hikari.maximum-pool-size=10
spring.datasource.hikari.minimum-idle=2
spring.datasource.hikari.connection-timeout=5000
spring.datasource.hikari.idle-timeout=300000
spring.datasource.hikari.max-lifetime=600000
```

| 參數 | 預設值 | 調整值 | 說明 |
|------|--------|--------|------|
| `maximum-pool-size` | 10 | 10 | 最多同時 10 個連線（auth-service 夠用） |
| `minimum-idle` | 10 | 2 | 閒置時保留 2 個連線，節省資源 |
| `connection-timeout` | 30000 | 5000 | 等不到連線 5 秒就噴錯，不卡死 |
| `idle-timeout` | 600000 | 300000 | 閒置連線 5 分鐘後釋放 |
| `max-lifetime` | 1800000 | 600000 | 連線最長活 10 分鐘，避免 PG 回收 |

## 5.5 Docker Resource Limits

Container 沒有限制時，可以吃光 host 的所有資源。在 `docker-compose.yml` 加入：

```yaml
services:
  auth:
    deploy:
      resources:
        limits:
          cpus: '0.5'
          memory: 256M
```

Compose 範例值：

| 服務 | CPU | 記憶體 | 說明 |
|------|-----|--------|------|
| app | 0.5 | 256M | demo 應用，負載低 |
| auth | 0.5 | 256M | JWT 驗證服務 |
| nginx | 0.25 | 128M | 靜態檔案 + 反向代理 |
| postgres | — | — | 不限制（資料庫需要穩定資源） |

> **注意**：`docker compose` 的 `deploy.resources` 需要 `compose` 指令而不是 `docker-compose`（v1）。如果使用 swarm 模式才會真正強制限制，但在 `docker compose up -d` 下僅作為最佳嘗試（best-effort）。

## 5.6 CORS 設定

當前端應用（例如 React dev server on `localhost:3000`）從不同 origin 呼叫 API 時，瀏覽器會先發送 OPTIONS preflight 請求。伺服器必須回應正確的 CORS header。

建立 `CorsConfig.java`：

```bash
cp samples/phase5/CorsConfig.java auth-service/src/main/java/com/example/auth/config/CorsConfig.java
```

```java
@Configuration
public class CorsConfig {
    @Bean
    public WebMvcConfigurer corsConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/auth/v1/**")
                        .allowedOrigins("*")
                        .allowedMethods("GET", "POST", "OPTIONS")
                        .allowedHeaders("*");
            }
        };
    }
}
```

- `allowedOrigins("*")`：允許所有 origin（開發環境。正式環境應指定特定 domain）
- `allowedMethods("GET", "POST", "OPTIONS")`：只允許這些 HTTP method
- 路徑限制在 `/auth/v1/**`，不影響其他服務

## 5.7 Spring Boot Actuator

加入依賴：

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
```

設定：

```properties
management.endpoints.web.exposure.include=health,info
management.endpoint.health.show-details=never
management.info.env.enabled=true
```

Actuator 會自動加上：
- `GET /actuator/health` — 服務存活檢查（回傳 `{"status":"UP"}`）
- `GET /actuator/info` — 程式資訊

> **安全注意**：Phase 4 的 SecurityConfig 使用 `.anyRequest().authenticated()`，所以 `/actuator/*` 同樣需要認證。我們在 `SecurityConfig.java` 的 `permitAll()` 清單中加入了 `/actuator/health` 與 `/actuator/info`，讓健康檢查不需要 token。正式環境可視需求移除或加上 IP 限制。

## 5.8 完整變更摘要

| 檔案 | 變更內容 |
|------|---------|
| `auth-service/pom.xml` | +`spring-boot-starter-actuator` |
| `auth-service/Dockerfile` | non-root user (appuser) |
| `auth-service/src/main/resources/application.properties` | graceful shutdown、HikariCP、actuator |
| `auth-service/src/main/java/.../config/CorsConfig.java` | 新增，CORS 設定 |
| `auth-service/src/main/java/.../config/SecurityConfig.java` | `permitAll()` 加入 `/actuator/health`、`/actuator/info` |
| `nginx/nginx.conf` | 新增 `location /actuator/` → `auth:8081` |
| `docker-compose.yml` | deploy.resources.limits 給 app / auth / nginx |

## 5.9 驗證步驟

### 5.9.1 重啟服務

```bash
docker compose build auth
docker compose up -d --no-deps auth
```

### 5.9.2 非 root 驗證

```bash
docker compose exec auth id
# uid=100(appuser) gid=101(appgroup) groups=101(appgroup)
```

### 5.9.3 Actuator

Actuator 需要 Nginx 路由才能從 `localhost:28080` 存取。確認 `nginx/nginx.conf` 包含：

```nginx
location /actuator/ {
    proxy_pass http://auth:8081;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
}
```

之後

```bash
curl http://localhost:28080/actuator/health
# {"status":"UP"}
```

### 5.9.4 CORS Preflight

```bash
curl -s -X OPTIONS http://localhost:28080/auth/v1/login \
  -H "Origin: http://localhost:3000" \
  -H "Access-Control-Request-Method: POST" \
  -H "Access-Control-Request-Headers: content-type" \
  -I 2>&1 | grep -i 'access-control'
# access-control-allow-origin: *
# access-control-allow-methods: GET, POST, OPTIONS
# access-control-allow-headers: *
```

### 5.9.5 Resource Limits

```bash
docker stats --no-stream
# 觀察每個 container 的 CPU % 和 MEM USAGE / LIMIT
```

### 5.9.6 Graceful Shutdown

```bash
# 終端 1
while sleep 0.5; do curl -s http://localhost:28080/auth/v1/health; echo; done

# 終端 2
docker compose restart auth
# 觀察終端 1 — 服務重啟過程中不應出現連線失敗
```

---

**Previous**: Phase 4 — Auth 服務企業級強化
**Next**: Phase 6 — Redis 深度應用
