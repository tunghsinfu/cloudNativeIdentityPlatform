# Phase 7 — User Service

建立一個獨立的 user-service，負責管理使用者個人資料，與 auth-service 共用 JWT secret 實現跨服務 Token 驗證。

| 步驟 | 內容 |
|------|------|
| 7.1 | 建立 user-service 專案（Spring Boot, port 8082） |
| 7.2 | 跨服務 Token 驗證（共用 JWT secret） |
| 7.3 | Nginx 路由與端到端流程 |

## 7.1 建立 user-service

### 7.1.1 專案結構

```
user-service/
├── Dockerfile
├── pom.xml
└── src/
    └── main/
        ├── java/com/example/user/
        │   ├── UserApplication.java
        │   ├── JwtUtil.java
        │   ├── config/
        │   │   ├── CorsConfig.java
        │   │   └── SecurityConfig.java
        │   ├── filter/
        │   │   └── JwtAuthenticationFilter.java
        │   ├── model/
        │   │   └── UserProfile.java
        │   ├── repository/
        │   │   └── UserProfileRepository.java
        │   ├── controller/
        │   │   └── UserController.java
        │   └── handler/
        │       ├── ErrorResponse.java
        │       └── GlobalExceptionHandler.java
        └── resources/
            ├── application.properties
            └── db/migration/
                └── V1__create_user_profiles.sql
```

### 7.1.2 pom.xml

套用 Phase 5 的模式，加入與 auth-service 相同的依賴：

```bash
cp samples/phase7/pom.xml user-service/pom.xml
```

核心依賴與 auth-service 相同：web、jdbc、postgresql、flyway、security、actuator、jjwt、redis。

### 7.1.3 Dockerfile

使用非 root user（Phase 5.1）的相同模式：

```bash
cp samples/phase7/Dockerfile user-service/Dockerfile
```

```dockerfile
FROM maven:3.9-eclipse-temurin-21-alpine AS builder
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline
COPY src ./src
RUN mvn clean package -DskipTests

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
WORKDIR /app
COPY --from=builder /app/target/*.jar app.jar
EXPOSE 8082
USER appuser
ENTRYPOINT ["java", "-jar", "app.jar"]
```

### 7.1.4 application.properties

```bash
cp samples/phase7/application.properties user-service/src/main/resources/application.properties
```

Port 8082，共用 `POSTGRES_DB`、`POSTGRES_USER`、`POSTGRES_PASSWORD` 與 `JWT_SECRET`。user-service 不需要 jwt.access-token-expiration-ms（它只驗證不產生 token）。

### 7.1.5 Flyway Migration

```bash
cp samples/phase7/V1__create_user_profiles.sql user-service/src/main/resources/db/migration/V1__create_user_profiles.sql
```

```sql
CREATE TABLE IF NOT EXISTS user_profiles (
    id BIGSERIAL PRIMARY KEY,
    username VARCHAR(100) NOT NULL UNIQUE,
    display_name VARCHAR(200),
    email VARCHAR(255),
    avatar_url VARCHAR(500),
    bio TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
```

### 7.1.6 UserProfile Model

```bash
cp samples/phase7/UserProfile.java user-service/src/main/java/com/example/user/model/UserProfile.java
```

```java
public record UserProfile(
    Long id,
    String username,
    String displayName,
    String email,
    String avatarUrl,
    String bio
) {}
```

### 7.1.7 UserProfileRepository

```bash
cp samples/phase7/UserProfileRepository.java user-service/src/main/java/com/example/user/repository/UserProfileRepository.java
```

使用 `JdbcTemplate` 封裝 CRUD 操作：

| 方法 | SQL | 用途 |
|------|-----|------|
| `findByUsername` | `SELECT ... WHERE username = ?` | 查詢個人資料 |
| `save` | `INSERT INTO user_profiles ...` | 建立新資料 |
| `update` | `UPDATE ... SET ... WHERE username = ?` | 更新現有資料 |

### 7.1.8 UserController

```bash
cp samples/phase7/UserController.java user-service/src/main/java/com/example/user/controller/UserController.java
```

| 端點 | 方法 | 說明 |
|------|------|------|
| `GET /user/v1/profile` | 需 token | 回傳當前使用者的個人資料 |
| `POST /user/v1/profile` | 需 token | 建立或更新個人資料 |
| `GET /user/v1/health` | 公開 | 健康檢查 |

Controller 從 `Authentication` 物件中取得 `username`（由 JwtAuthenticationFilter 設定），然後查詢或寫入資料庫。

## 7.2 跨服務 Token 驗證

user-service 使用**共用 JWT secret**（選項 7.2.1）來驗證 auth-service 發出的 token，不需額外的網路呼叫。

### 7.2.1 JwtUtil

```bash
cp samples/phase7/JwtUtil.java user-service/src/main/java/com/example/user/JwtUtil.java
```

與 auth-service 的 `JwtUtil` 不同，user-service 的版本**只驗證不產生**：

```java
@Component
public class JwtUtil {
    private final SecretKey key;

    public JwtUtil(@Value("${jwt.secret}") String secret) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public Claims validateAndGetClaims(String token) {
        try {
            return Jwts.parser().verifyWith(key).build()
                    .parseSignedClaims(token).getPayload();
        } catch (JwtException e) {
            return null;
        }
    }
}
```

### 7.2.2 JwtAuthenticationFilter

```bash
cp samples/phase7/JwtAuthenticationFilter.java user-service/src/main/java/com/example/user/filter/JwtAuthenticationFilter.java
```

與 auth-service 的 filter 類似，但**不檢查 Redis blacklist**（因為 blacklist 由 auth-service 管理）：

```
Request → [JwtAuthenticationFilter] → [Spring Security] → [UserController]
                │
                ├─ Header 有 Bearer token? ──→ 解析 JWT ──→ 驗證簽章與逾期 ──→ OK → 設定 SecurityContext
                │                                                          └─ 失敗 → 不設定
                └─ 沒有 token → 直接放行（讓 Security 決定是否拒絕）
```

> **為什麼 user-service 不檢查 blacklist？** 兩種設計都可行。不檢查 blacklist 的優點是：user-service 不需要 Redis 連線，架構更單純。缺點是已登出的 token 在過期前仍可用於 user-service。實際生產環境可以視安全需求決定是否要跨服務同步 blacklist。

### 7.2.3 SecurityConfig

```bash
cp samples/phase7/SecurityConfig.java user-service/src/main/java/com/example/user/config/SecurityConfig.java
```

僅公開 `/user/v1/health`、`/actuator/health`、`/actuator/info`，其餘都需認證。

## 7.3 Nginx 路由

### 7.3.1 docker-compose.yml

在 `docker-compose.yml` 加入 user-service：

```yaml
user:
  build:
    context: ./user-service
    args:
      APP_NAME: user-service
  container_name: user-service
  ports:
    - "${SERVER_PORT_USER:-18082}:8082"
  environment:
    - LOG_LEVEL=${LOG_LEVEL:-INFO}
    - POSTGRES_DB=${POSTGRES_DB}
    - POSTGRES_USER=${POSTGRES_USER}
    - POSTGRES_PASSWORD=${POSTGRES_PASSWORD}
    - JWT_SECRET=${JWT_SECRET}
  depends_on:
    postgres:
      condition: service_healthy
    redis:
      condition: service_started
  networks:
    - demo-net
  deploy:
    resources:
      limits:
        cpus: '0.5'
        memory: 256M
```

### 7.3.2 nginx.conf

```nginx
location /user/ {
    proxy_pass http://user:8082;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
}
```

### 7.3.3 完整流程

```
Browser                          Nginx                      user-service
  │                                │                            │
  ├─ POST /auth/v1/login ────────→ │                            │
  │                                ├─ /auth/ → auth:8081 ─────→ │
  │                                │   ← JWT (access_token)     │
  │← JWT ──────────────────────────┘                            │
  │                                │                            │
  ├─ GET /user/v1/profile ───────→ │                            │
  │   Authorization: Bearer <jwt>  │                            │
  │                                ├─ /user/ → user:8082 ──────→│
  │                                │   ├─ JwtUtil.validate()    │
  │                                │   ├─ SELECT FROM           │
  │                                │   │   user_profiles        │
  │                                │   └─ JSON response         │
  │← profile JSON ─────────────────┘                            │
```

## 7.4 驗證步驟

### 7.4.1 啟動服務

```bash
docker compose build user
docker compose up -d --no-deps user
```

如果需要重新建立所有服務（含重建 nginx 以更新路由）：

```bash
docker compose build user nginx
docker compose up -d
```

### 7.4.2 健康檢查

```bash
curl http://localhost:28080/user/v1/health
# {"status":"ok","service":"user-service"}
```

### 7.4.3 註冊與登入

```bash
# 註冊
curl -s -X POST http://localhost:28080/auth/v1/register \
  -d "username=alice&password=Str0ng!Pass"

# 登入取得 token
TOKEN=$(curl -s -X POST http://localhost:28080/auth/v1/login \
  -d "username=alice&password=Str0ng!Pass" | python3 -c "import sys,json;print(json.load(sys.stdin)['access_token'])")
echo $TOKEN
```

### 7.4.4 建立個人資料

```bash
curl -s -X POST http://localhost:28080/user/v1/profile \
  -H "Authorization: Bearer $TOKEN" \
  -d "displayName=Alice&email=alice@example.com&bio=Hello, I am Alice!"
# {"status":"ok","message":"profile created"}
```

### 7.4.5 查詢個人資料

```bash
curl -s http://localhost:28080/user/v1/profile \
  -H "Authorization: Bearer $TOKEN" | python3 -m json.tool
# {
#     "id": 1,
#     "username": "alice",
#     "displayName": "Alice",
#     "email": "alice@example.com",
#     "avatarUrl": null,
#     "bio": "Hello, I am Alice!"
# }
```

### 7.4.6 無 Token 請求（應被拒絕）

```bash
curl -s http://localhost:28080/user/v1/profile
# {"code":"UNAUTHORIZED","message":"missing or invalid token"}
```

### 7.4.7 更新個人資料

```bash
curl -s -X POST http://localhost:28080/user/v1/profile \
  -H "Authorization: Bearer $TOKEN" \
  -d "displayName=Alice&email=alice@newdomain.com&bio=Updated bio!"
# {"status":"ok","message":"profile updated"}
```

## 7.5 完整變更摘要

| 檔案 | 說明 |
|------|------|
| `user-service/pom.xml` | 新增，Spring Boot + Security + Flyway + JJWT + Actuator |
| `user-service/Dockerfile` | 新增，多階段建構 + non-root user |
| `user-service/src/main/resources/application.properties` | 新增，port 8082、PG、Redis、JWT |
| `user-service/src/main/resources/db/migration/V1__create_user_profiles.sql` | 新增，user_profiles 資料表 |
| `user-service/src/main/java/.../UserApplication.java` | 新增，啟動類別 |
| `user-service/src/main/java/.../JwtUtil.java` | 新增，JWT 驗證（共用 secret） |
| `user-service/src/main/java/.../config/SecurityConfig.java` | 新增，Security filter chain |
| `user-service/src/main/java/.../filter/JwtAuthenticationFilter.java` | 新增，Bearer token 解析 |
| `user-service/src/main/java/.../model/UserProfile.java` | 新增，資料模型 record |
| `user-service/src/main/java/.../repository/UserProfileRepository.java` | 新增，JDBC DAO |
| `user-service/src/main/java/.../controller/UserController.java` | 新增，GET/POST /user/v1/profile |
| `user-service/src/main/java/.../config/CorsConfig.java` | 新增，CORS 設定 |
| `user-service/src/main/java/.../handler/ErrorResponse.java` | 新增，統一錯誤格式 |
| `user-service/src/main/java/.../handler/GlobalExceptionHandler.java` | 新增，統一例外處理 |
| `docker-compose.yml` | 修改，加入 user-service |
| `nginx/nginx.conf` | 修改，加入 `/user/` 路由 |

---

**Previous**: Phase 6 — Redis 深度應用
**Next**: Phase 8 — k3s 部署
