# Phase 6 — Redis 深度應用

這一章展示 Redis 在認證系統中的真正價值：Rate Limiting 與 Cache-Aside Pattern。

| 功能 | Redis 用法 | 為什麼用 Redis |
|------|-----------|---------------|
| Login Rate Limit | `login_fail:{username}` → INCR + TTL | 記憶體內計數，原子性操作 |
| User Cache-Aside | `user:{username}` → SET/GET with TTL | 減少 DB 查詢，低延遲 |

## 6.1 Login Rate Limit

### 6.1.1 流程設計

```
Client                     auth-service                   Redis
  │                           │                            │
  ├─ POST /auth/v1/login ────→                              │
  │                           ├─ GET login_fail:alice ─────→│
  │                           │← 5 (已達上限) ──────────────│
  │                           ├─ throw 429 RATE_LIMITED     │
  │                           │                            │
  │                           ├─ (正常流程)                 │
  │                           │  ┌─ user not found? ───────→│
  │                           │  │  INCR login_fail:alice ─→│
  │                           │  │  EXPIRE 600s            │
  │                           │  └─ throw 401              │
  │                           │                            │
  │                           │  ┌─ password wrong? ──────→│
  │                           │  │  INCR login_fail:alice ─→│
  │                           │  │  EXPIRE 600s            │
  │                           │  └─ throw 401              │
  │                           │                            │
  │                           │  └─ success ──────────────→│
  │                           │     DEL login_fail:alice    │
  │                         ← json                         │
```

### 6.1.2 實作

在 `AuthController.login()` 加入三個動作：

**Step 1：檢查上限**

```java
String rateKey = "login_fail:" + username;
String val = redis.opsForValue().get(rateKey);
if (val != null && Integer.parseInt(val) >= 5) {
    throw new RateLimitExceededException("too many login attempts, try again later");
}
```

**Step 2：失敗時遞增**

```java
private void incrementRate(String key) {
    Long count = redis.opsForValue().increment(key);
    if (count != null && count == 1) {
        redis.expire(key, Duration.ofSeconds(600));
    }
}
```

- `INCR` 是原子操作，不回有 race condition
- 只有在 count 為 1（第一次建立）時才設 TTL
- TTL 600 秒（10 分鐘）後自動歸零

**Step 3：成功時清除**

```java
redis.delete(rateKey);
```

### 6.1.3 自訂例外與錯誤處理

建立 `RateLimitExceededException`：

```bash
cp samples/phase6/RateLimitExceededException.java auth-service/src/main/java/com/example/auth/handler/RateLimitExceededException.java
```

在 `GlobalExceptionHandler` 加入：

```bash
cp samples/phase6/GlobalExceptionHandler.java auth-service/src/main/java/com/example/auth/handler/GlobalExceptionHandler.java
```

```java
@ExceptionHandler(RateLimitExceededException.class)
public ResponseEntity<ErrorResponse> handleRateLimit(RateLimitExceededException e) {
    return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
            .body(new ErrorResponse("RATE_LIMITED", e.getMessage()));
}
```

回傳 HTTP 429：

```
$ curl -v -X POST http://localhost:28080/auth/v1/login -d "username=alice&password=wrong"
...
< HTTP/1.1 429 Too Many Requests
< Content-Type: application/json
...
{"code":"RATE_LIMITED","message":"too many login attempts, try again later"}
```

## 6.2 Cache-Aside Pattern

### 6.2.1 流程

```
findByUsername("alice")

  1. GET user:alice
      ├─ HIT  → 反序列化 → 直接回傳
      └─ MISS → SELECT FROM users WHERE username = ?
                SET user:alice {json} EX 300
                回傳
```

### 6.2.2 實作

```bash
cp samples/phase6/UserRepository.java auth-service/src/main/java/com/example/auth/repository/UserRepository.java
```

使用者資料用 Jackson 序列化為 JSON 儲存：

```java
private static final long CACHE_TTL_SEC = 300;
private static final ObjectMapper MAPPER = new ObjectMapper();

public Optional<User> findByUsername(String username) {
    String cacheKey = "user:" + username;
    String cached = redis.opsForValue().get(cacheKey);
    if (cached != null) {
        try {
            return Optional.of(MAPPER.readValue(cached, User.class));
        } catch (Exception e) {
            // 反序列化失敗 → 退回 DB 查詢
        }
    }
    // Cache miss → 查 DB
    User user = jdbc.query(...);
    redis.opsForValue().set(cacheKey,
        MAPPER.writeValueAsString(user),
        Duration.ofSeconds(CACHE_TTL_SEC));
    return user;
}
```

快取失效時機：

| 事件 | 動作 | 說明 |
|------|------|------|
| 使用者註冊 | `DEL user:{username}` | 雖然剛註冊 cache 本來就沒有，但清除是習慣 |
| 使用者更新 | `DEL user:{username}` | 下次讀取時重設 cache |
| TTL 過期 | 自動刪除 | 300 秒後 Redis 自動清除 |

### 6.2.3 驗證 Cache

```bash
# 1. 先清除 Redis cache（確保從 DB 讀取）
docker compose exec redis redis-cli DEL "user:alice"

# 2. 登入（觸發 cache-aside write）
curl -s -X POST http://localhost:28080/auth/v1/login \
  -d "username=alice&password=Str0ng!Pass" > /dev/null

# 3. 確認 cache 存在
docker compose exec redis redis-cli GET "user:alice" | python3 -m json.tool
# {
#     "id": 1,
#     "username": "alice",
#     "password": "$2a$10$...",
#     "role": "USER"
# }

# 4. 查看 TTL
docker compose exec redis redis-cli TTL "user:alice"
# (integer) 297   ← 約 5 分鐘
```

### 6.2.4 效能量測

```bash
# 慢速：直接 query DB（無 cache）
# 先在 redis 刪除 cache
docker compose exec redis redis-cli DEL "user:alice"

# 快速：從 cache 讀取
time for i in $(seq 1 10); do
  curl -s -X POST http://localhost:28080/auth/v1/login \
    -d "username=alice&password=Str0ng!Pass" > /dev/null
done
# 第一次是 cache miss（~20ms），後面 9 次是 cache hit（~3ms）
```

## 6.3 完整變更摘要

| 檔案 | 變更 |
|------|------|
| `AuthController.java` | login 加入 rate limit 檢查 + 遞增 + 清除 |
| `UserRepository.java` | 注入 `StringRedisTemplate`，cache-aside 讀寫 |
| `handler/RateLimitExceededException.java` | 新增，429 例外 |
| `handler/GlobalExceptionHandler.java` | 加入 `RateLimitExceededException` handler |

## 6.4 驗證步驟

### 6.4.1 重啟服務

```bash
docker compose build auth && docker compose up -d --no-deps auth
```

### 6.4.2 Rate Limit 測試

```bash
# 連續 5 次錯誤密碼
for i in $(seq 1 5); do
  curl -s -X POST http://localhost:28080/auth/v1/login \
    -d "username=alice&password=wrong$i" | python3 -c "import sys;print(sys.stdin.read())"
done

# 第 6 次 — 應該被擋
curl -s -X POST http://localhost:28080/auth/v1/login \
  -d "username=alice&password=wrong6"
# {"code":"RATE_LIMITED","message":"too many login attempts, try again later"}

# 檢查 Redis 計數器
docker compose exec redis redis-cli GET "login_fail:alice"
# "5"

# 使用正確密碼 — 應清除計數器
curl -s -X POST http://localhost:28080/auth/v1/login \
  -d "username=alice&password=Str0ng!Pass" | python3 -c "import sys,json;d=json.load(sys.stdin);print(d.get('status'))"
# ok

# 確認計數器已清除
docker compose exec redis redis-cli GET "login_fail:alice"
# (nil)
```

### 6.4.3 Cache-Aside 測試

```bash
# 清除 cache 後首次查詢（從 DB）
docker compose exec redis redis-cli DEL "user:alice"
time curl -s -X POST http://localhost:28080/auth/v1/login \
  -d "username=alice&password=Str0ng!Pass" > /dev/null

# 第二次（從 cache）
time curl -s -X POST http://localhost:28080/auth/v1/login \
  -d "username=alice&password=Str0ng!Pass" > /dev/null

# 觀察兩次的耗時差異
```

---

**Previous**: Phase 5 — Production Hardening
**Next**: Phase 7 — User Service
