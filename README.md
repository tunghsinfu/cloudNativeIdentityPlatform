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
e9228cd feat: 建立根目錄 docker-compose.yml，整合 Spring Boot + Nginx 微服務架構
6a028e1 feat: 建立 Nginx 反向代理服務（nginx/）作為 API Gateway
5b1e309 feat: 建立 Spring Boot Maven 專案（spring-boot-demo）作為微服務 A
```
