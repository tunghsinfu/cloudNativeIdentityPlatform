# 微服務 Demo — 操作筆記

此專案展示以 Docker Compose 建置最簡易的微服務架構，包含兩個服務：

| 服務 | 角色 | 技術 |
|------|------|------|
| **app** | 後端 API | Spring Boot 3.3.5 / Java 21 |
| **nginx** | API Gateway | Nginx (Alpine) |

---

## 專案結構

```
/workspace/
├── spring-boot-demo/       # 微服務 A：Spring Boot 後端
│   ├── Dockerfile
│   ├── pom.xml
│   └── src/
├── nginx/                  # 微服務 B：Nginx API Gateway
│   ├── Dockerfile
│   ├── nginx.conf
│   └── index.html
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
瀏覽器 → Nginx (port 80) → Spring Boot (port 8080)
         └── /api/* 代理到後端
         └── /* 靜態檔案
```

- Nginx 監聽 80 埠，提供靜態頁面（`index.html`）
- 路徑 `/api/` 反向代理至 Spring Boot 後端（service name: `app`）
- Spring Boot 提供 REST API 於 `http://app:8080/`

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

敏感資料（如資料庫密碼）以 Docker Secret 方式管理：

| 檔案 | 容器內路徑 |
|------|-----------|
| `./secrets/db_password.txt` | `/run/secrets/db_password` |

Spring Boot 可透過讀取該檔案取得密碼：

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

## 各服務說明

### spring-boot-demo

- Spring Boot 3.3.5 + spring-boot-starter-web
- 提供 `GET /` 回傳 "Hello from Spring Boot!"
- Dockerfile 使用多階段構建（Maven build → JRE runtime）

### nginx

- 基於 `nginx:alpine` 輕量映像
- 自訂 `nginx.conf` 設定反向代理規則
- `index.html` 含前端 JavaScript 呼叫後端 API 並顯示狀態

---

## Git 提交紀錄

```bash
$ git log --oneline
7c0bd00 feat: Spring Boot 新增 /config 端點，讀取環境變數與 Docker secrets；Nginx 前端同步顯示
2e8e815 feat: app 服務加入 environment（.env 載入）與 secrets（db_password 檔案掛載）
efbfc9b feat: 建立 .env 環境變數檔、secrets 目錄與 .gitignore（敏感資料排除版控）
e9228cd feat: 建立根目錄 docker-compose.yml，整合 Spring Boot + Nginx 微服務架構
6a028e1 feat: 建立 Nginx 反向代理服務（nginx/）作為 API Gateway
5b1e309 feat: 建立 Spring Boot Maven 專案（spring-boot-demo）作為微服務 A
```
