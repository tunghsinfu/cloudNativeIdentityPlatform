# Cloud Native Identity Platform

生產級 JWT 認證系統，建置於 Spring Boot 3.3.5 + PostgreSQL 16 + Redis 7 + Nginx，以 Docker Compose 編排。

## 架構

```
Browser → Nginx (:28080) → /auth/v1/* → auth-service (:8081) → PostgreSQL (:5432)
                                                              → Redis (:6379)
                          → /actuator/* → auth-service (:8081)
                          → /*          靜態頁面 (index.html)
```

## 服務

| 服務 | 角色 | 技術 |
|------|------|------|
| **auth** | JWT 認證 API | Spring Boot 3.3.5 / Java 21 / Spring Security |
| **nginx** | API Gateway + 靜態頁面 | Nginx (Alpine) |
| **postgres** | 使用者資料庫 | PostgreSQL 16 Alpine |
| **redis** | Token 黑名單 / Rate Limit / Cache | Redis 7 Alpine |

## 快速啟動

```bash
# 複製環境變數（第一次）
cp .env.example .env

# 建置並啟動
docker compose build
docker compose up -d

# 開啟前端
open http://localhost:28080
```

## API 端點（全部 `/auth/v1/`）

| 方法 | 路徑 | 說明 |
|------|------|------|
| POST | `/auth/v1/register` | 註冊（BCrypt 加密） |
| POST | `/auth/v1/login` | 登入（含 Rate Limit） |
| GET | `/auth/v1/verify` | 驗證 JWT（含黑名單檢查） |
| POST | `/auth/v1/logout` | 登出，JWT 加入黑名單 |
| POST | `/auth/v1/refresh` | 換發 Access Token |
| GET | `/auth/v1/health` | 健康檢查 |
| GET | `/actuator/health` | Spring Boot Actuator |

## 已實作功能

| Phase | 功能 |
|-------|------|
| 1 | Project Foundation — Spring Boot, PostgreSQL, Redis |
| 2 | Docker Compose, Nginx API Gateway, 前端靜態頁面 |
| 3 | JWT 認證（jjwt 0.12.6）, 黑名單登出 |
| 4 | Spring Security, BCrypt, Flyway, API 版本控管, 統一錯誤處理 |
| 5 | 非 root 容器, Graceful Shutdown, HikariCP 調優, CORS, Actuator |
| 6 | Login Rate Limit（Redis INCR + TTL）, Cache-Aside Pattern |
| 7-11 | User Service, k3s 部署, Observability, AI Ops（規劃中） |

## 環境變數

所有機密統一在 `.env`（已 `.gitignore`），Docker Compose 自動載入：

```bash
POSTGRES_PASSWORD=pg_s3cr3t!
JWT_SECRET=ZGV2LXNlY3JldC1rZXktZm9yLWRlbW8tcHVycG9zZXMtb25seSE=
```

## 專案結構

```
/workspace/
├── auth-service/          # JWT 認證服務（Spring Boot）
├── nginx/                 # API Gateway + index.html
├── docs/                  # 架構規格 + 教學（tutorial-phase1~6）
├── samples/               # 各階段參考原始碼
├── .env                   # 環境變數（gitignored）
└── docker-compose.yml     # 容器編排
```

## 開發

```bash
# 單獨重建某服務
docker compose build auth
docker compose up -d --no-deps auth

# 查看日誌
docker compose logs -f auth

# 進入 Redis 除錯
docker compose exec redis redis-cli

# 測試 Rate Limit
for i in 1 2 3 4; do curl -s -X POST http://localhost:28080/auth/v1/login -d "username=alice&password=bad$i"; echo; done
curl -s -X POST http://localhost:28080/auth/v1/login -d "username=alice&password=bad5"
# → {"code":"RATE_LIMITED","message":"too many login attempts, try again later"}
```
