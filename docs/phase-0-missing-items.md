# Phase 0 遺漏重點 — 面試加分項目（Priority 排序）

現有文件涵蓋了基礎，但以下是「企業級運行」的關鍵差異點，面試中提出任一個都能明顯拉高評價。

---

## Priority 1 — 立即加分（半天內可實作）

### 1. 密碼編碼（BCrypt）

**問題**：auth-service 目前存明碼密碼，這是 production 絕對不能發生的事。

```java
// ❌ 現在：明碼
jdbc.update("INSERT INTO users (password) VALUES (?)", password);

// ✅ 應該：BCrypt
String encoded = new BCryptPasswordEncoder().encode(password);
jdbc.update("INSERT INTO users (password) VALUES (?)", encoded);

// 登入驗證
boolean matched = new BCryptPasswordEncoder().matches(rawPassword, encodedPassword);
```

**面試講法**：
> 「密碼儲存使用 BCrypt，內建 salt + adaptive cost factor，即使 DB 被 dump 也無法逆向。」

### 2. Spring Boot Actuator（Health / Readiness / Liveness）

**問題**：目前 `/db-check` 是自訂端點，業界標準是 Spring Boot Actuator。

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
```

```properties
# 開放所有端點（production 應只開 health, info）
management.endpoints.web.exposure.include=health,info
# 顯示完整健康細節
management.endpoint.health.show-details=always
# 自訂 liveness / readiness probe
management.endpoint.health.probes.enabled=true
```

K8s 可直接使用：
```yaml
livenessProbe:  httpGet: path: /actuator/health/liveness
readinessProbe: httpGet: path: /actuator/health/readiness
```

**面試講法**：
> 「Actuator 提供標準化的 health check，K8s 的 liveness/readiness probe 直接對接，不需要自訂端點。」

### 3. 全域異常處理（@ControllerAdvice）

**問題**：目前錯誤回傳格式不一致（有時 `Map.of("status","error")`，有時直接拋例外）。

```java
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException e) {
        return ResponseEntity.badRequest().body(
            new ErrorResponse("VALIDATION_ERROR", e.getMessage()));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException e) {
        return ResponseEntity.status(403).body(
            new ErrorResponse("FORBIDDEN", "insufficient permissions"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnknown(Exception e) {
        return ResponseEntity.status(500).body(
            new ErrorResponse("INTERNAL_ERROR", "unexpected error"));
    }
}

record ErrorResponse(String code, String message) {}
```

**面試講法**：
> 「統一錯誤格式讓前端和 API client 可以一致地處理錯誤，不用為每個端點寫不同邏輯。」

---

## Priority 2 — 強烈推薦（1 天內可實作）

### 4. Graceful Shutdown + Docker Stop Grace Period

```properties
# Spring Boot 優雅關機
server.shutdown=graceful
spring.lifecycle.timeout-per-shutdown-phase=30s
```

```yaml
# docker-compose.yml 配合
stop_grace_period: 45s
```

**面試講法**：
> 「Deployment 時舊 pod 被 SIGTERM，Spring 會停止接受新請求，等待正在處理的請求完成（最多 30s），然後才關機。避免 rolling update 時斷掉正在處理的請求。」

### 5. Docker 非 root 使用者

```dockerfile
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser
WORKDIR /app
COPY --from=builder /app/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

**面試講法**：
> 「Container 預設以 root 執行，如果被入侵攻擊者拿到 shell 就是 root 權限。加入非 root 使用者是 container security 最基本也最容易被忽略的一步。」

### 6. 結構化 JSON 日誌

```xml
<dependency>
    <groupId>net.logstash.logback</groupId>
    <artifactId>logstash-logback-encoder</artifactId>
    <version>8.0</version>
</dependency>
```

```xml
<!-- src/main/resources/logback-spring.xml -->
<configuration>
    <appender name="JSON" class="ch.qos.logback.core.ConsoleAppender">
        <encoder class="net.logstash.logback.encoder.LogstashEncoder"/>
    </appender>
    <root level="INFO">
        <appender-ref ref="JSON"/>
    </root>
</configuration>
```

輸出範例：
```json
{"@timestamp":"2026-06-24T10:00:00.000Z","level":"INFO","logger":"com.example.auth.AuthController","message":"login success","username":"alice","service":"auth-service"}
```

**面試講法**：
> 「結構化日誌讓 Loki / ELK 可以直接解析欄位，而不需要 regex 硬拆文字。用 `logstash-logback-encoder` 可以零配置產出 JSON。」

---

## Priority 3 — 專業加分（1-2 天）

### 7. HikariCP 連線池調校

```properties
# 預設值 vs 生產建議
spring.datasource.hikari.maximum-pool-size=10       # 預設 10，可依需求調整
spring.datasource.hikari.minimum-idle=5              # 預設同 maximum-pool-size
spring.datasource.hikari.connection-timeout=5000     # 單位 ms，預設 30000
spring.datasource.hikari.idle-timeout=300000         # 5 分鐘
spring.datasource.hikari.max-lifetime=600000         # 10 分鐘，建議小於 DB 的 timeout
spring.datasource.hikari.leak-detection-threshold=10000  # 連線洩漏偵測
```

**面試講法**：
> 「HikariCP 是最快的 connection pool，但預設值不適合 production。`leak-detection-threshold` 可以在開發期抓到忘記關閉連線的 bug。」

### 8. Flyway 資料庫遷移

**問題**：目前用 `schema.sql`（Spring Boot 自動執行），但這不適合 production。

```xml
<dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-core</artifactId>
</dependency>
```

```sql
-- V1__create_users.sql
CREATE TABLE users (
    id SERIAL PRIMARY KEY,
    username VARCHAR(50) UNIQUE NOT NULL,
    password VARCHAR(255) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- V2__add_email_column.sql
ALTER TABLE users ADD COLUMN email VARCHAR(255);
```

**面試講法**：
> 「Flyway 確保每個環境的 schema 版本一致，SQL 檔案版本化、不可修改，這在多人開發和 production 部署時至關重要。」

### 9. Spring Boot Layered JAR（Docker Image 優化）

```properties
# pom.xml 的 spring-boot-maven-plugin 配置
<plugin>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-maven-plugin</artifactId>
    <configuration>
        <layers>
            <enabled>true</enabled>
        </layers>
    </configuration>
</plugin>
```

```dockerfile
FROM eclipse-temurin:21-jre-alpine AS builder
COPY --from=build /app/target/*.jar app.jar
RUN java -Djarmode=layertools -jar app.jar extract

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=builder /app/dependencies/ ./
COPY --from=builder /app/spring-boot-loader/ ./
COPY --from=builder /app/snapshot-dependencies/ ./
COPY --from=builder /app/application/ ./
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
```

**面試講法**：
> 「Layered JAR 將 dependency、spring-boot-loader、application code 分層，dependencies 層幾乎不會變，可以充分利用 Docker layer cache，每次 build 只需傳輸 application 層（通常幾 KB）。」

### 10. Testcontainers 整合測試

```java
@SpringBootTest
@Testcontainers
class AuthControllerTest {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
        .withExposedPorts(6379);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", pg::getJdbcUrl);
        r.add("spring.datasource.password", pg::getPassword);
        r.add("spring.data.redis.host", redis::getHost);
        r.add("spring.data.redis.port", redis::getFirstMappedPort);
    }

    @Test
    void registerAndLogin() {
        // 測試完整的 register → login → verify 流程
    }
}
```

**面試講法**：
> 「Testcontainers 測試時啟動真正的 PostgreSQL 和 Redis container，測試通過等於真實環境驗證。比 H2 內嵌資料庫更可靠。」

---

## Priority 4 — 加分但時間較長

### 11. Docker Resource Limits

```yaml
services:
  app:
    deploy:
      resources:
        limits:
          cpus: '0.5'
          memory: 512M
        reservations:
          cpus: '0.25'
          memory: 256M
```

### 12. Makefile

```makefile
.PHONY: build test up down logs

build:
	docker compose build

up:
	docker compose up -d

down:
	docker compose down

logs:
	docker compose logs -f

test:
	cd auth-service && mvn test

clean:
	docker compose down -v --rmi all
```

### 13. CORS 設定

```java
@Configuration
public class CorsConfig implements WebMvcConfigurer {
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins("http://localhost:3000")
                .allowedMethods("GET", "POST", "PUT", "DELETE")
                .allowCredentials(true);
    }
}
```

### 14. API 版本策略

```
# URL Path 版本（最常見）
/api/v1/users
/api/v2/users

# Accept Header 版本（純 REST）
Accept: application/vnd.identity.v1+json
```

---

## 面試話術對照表

| 項目 | 一句話說服力 |
|------|------------|
| BCrypt | 「密碼不存明碼，BCrypt 內建 salt 且 cost 可調」 |
| Actuator |「K8s 的 liveness/readiness probe 直接對接 Actuator」|
| ControllerAdvice |「統一錯誤格式，前端不用為每個 API 寫不同 error handler」|
| Graceful Shutdown |「Rolling update 不中斷正在處理的請求」|
| Non-root user |「Container 被入侵也不是 root 權限」|
| JSON logging |「Loki 直接解析結構化欄位，不用 regex 硬拆」|
| HikariCP tuning |「leak-detection-threshold 在開發期抓連線洩漏」|
| Flyway |「Schema 版本化，每個環境保證一致」|
| Layered JAR |「Docker build 時 dependencies 層可 cache，每次只傳幾 KB」|
| Testcontainers |「測試用真實 PG + Redis，比 H2 可靠」|
