# 進階主題參考（各 Phase 對照）

此文件收錄各 Phase 中可額外實作的企業級細節，依所屬 Phase 分類，**非主線強制進度**。建議各 Phase 先完成核心功能後，再回頭挑選加入。

---

## Phase 1 — Foundation 可追加

### Makefile

```makefile
build: docker compose build
up: docker compose up -d
logs: docker compose logs -f
test: cd auth-service && mvn test
clean: docker compose down -v --rmi all
```

**面試話術**：「Makefile 將常用指令標準化，新成員 onboarding 只需 `make up`。」

---

## Phase 4 — Auth Service 可追加

以下項目已納入 Phase 4 主線計畫，此處保留參考。

### BCrypt 密碼編碼

```java
String encoded = new BCryptPasswordEncoder().encode(password);
boolean matched = new BCryptPasswordEncoder().matches(rawPassword, encoded);
```

**面試話術**：「BCrypt 內建 salt + adaptive cost factor，即使 DB 被 dump 也無法逆向。」

### Flyway 資料庫遷移

```sql
-- V1__create_users.sql
-- V2__add_role_column.sql
```

**面試話術**：「Flyway 確保每個環境的 schema 版本一致。SQL 檔案版本化、不可修改，多人開發和 production 部署時至關重要。」

### @ControllerAdvice 全域異常處理

```java
@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(...) { ... }
}
```

**面試話術**：「統一錯誤格式讓前端和 API client 可以一致地處理錯誤。」

### API 版本策略

選擇 URL path 版本 (`/auth/v1/login`) 或 Header 版本 (`Accept: application/vnd.identity.v1+json`)。

**面試話術**：「API 版本從第一天就決定，避免日後 breaking change 無法逐步遷移。」

---

## Phase 5 — Production Hardening

### Graceful Shutdown

```properties
server.shutdown=graceful
spring.lifecycle.timeout-per-shutdown-phase=30s
```

```yaml
stop_grace_period: 45s
```

**面試話術**：「Rolling update 時舊 pod 收到 SIGTERM，Spring 停止接受新請求，等待進行中的請求完成才關閉，避免斷線。」

### HikariCP Connection Pool Tuning

```properties
spring.datasource.hikari.maximum-pool-size=10
spring.datasource.hikari.leak-detection-threshold=10000
```

**面試話術**：「`leak-detection-threshold` 在開發期就能抓到忘記關閉連線的 bug。」

### Docker Resource Limits

```yaml
deploy:
  resources:
    limits: { cpus: '0.5', memory: 512M }
    reservations: { cpus: '0.25', memory: 256M }
```

**面試話術**：「缺少 resource limits 的 container 可能吃掉所有主機資源，導致其他服務 OOM。」

### CORS Configuration

```java
registry.addMapping("/api/**")
        .allowedOrigins("http://localhost:3000")
        .allowedMethods("GET", "POST")
        .allowCredentials(true);
```

**面試話術**：「微服務 + SPA 架構必備，CORS 設定錯誤是前端整合最常見的問題源。」

### Spring Boot Actuator（基礎端點）

```properties
management.endpoints.web.exposure.include=health,info
management.endpoint.health.show-details=always
```

**面試話術**：「Actuator 提供標準化 health check，K8s 的 liveness/readiness probe 可直接對接。」

---

### Docker 非 root 使用者

```dockerfile
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser
```

**面試話術**：「Container 預設以 root 執行，入侵即 root 權限。非 root 使用者是 container security 最基本的一步。」

### Spring Boot Layered JAR

```xml
<plugin>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-maven-plugin</artifactId>
    <configuration><layers><enabled>true</enabled></layers></configuration>
</plugin>
```

**面試話術**：「Layered JAR 讓 dependencies 層可被 Docker layer cache，每次 build 只傳輸 application 層（通常幾 KB）。」

---

## Phase 9 — Observability 可追加

### 結構化 JSON 日誌

```xml
<dependency>
    <groupId>net.logstash.logback</groupId>
    <artifactId>logstash-logback-encoder</artifactId>
    <version>8.0</version>
</dependency>
```

輸出：`{"@timestamp":"...","level":"INFO","logger":"...","message":"login success","username":"alice"}`

**面試話術**：「結構化日誌讓 Loki / ELK 可直接解析欄位，不需要 regex 硬拆文字。」

---

## Phase 10 — 整合測試可追加

### Testcontainers

```java
@SpringBootTest
@Testcontainers
class AuthControllerTest {
    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
}
```

**面試話術**：「Testcontainers 啟動真實 PG + Redis 執行測試，比 H2 內嵌資料庫更可靠。」

---

## 面試話術速查表

| 項目 | 一句話 |
|------|--------|
| Non-root user | 「Container 被入侵也不是 root 權限」 |
| Layered JAR | 「Docker build dependencies 層可 cache，只傳 application 層」 |
| BCrypt | 「密碼不存明碼，BCrypt 內建 salt + adaptive cost」 |
| Flyway | 「Schema 版本化，每個環境保證一致」 |
| ControllerAdvice | 「統一錯誤格式，前端不用為每個 API 寫不同 handler」 |
| Graceful Shutdown | 「Rolling update 不中斷正在處理的請求」 |
| HikariCP tuning | 「leak-detection-threshold 在開發期抓連線洩漏」 |
| Resource limits | 「避免 container 吃掉所有主機資源」 |
| Actuator | 「K8s liveness/readiness probe 直接對接」 |
| JSON logging | 「Loki 直接解析結構化欄位，不用 regex 硬拆」 |
| Testcontainers | 「測試用真實 PG + Redis，比 H2 可靠」 |
