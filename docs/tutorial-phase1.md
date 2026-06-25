# Phase 1 教學文件：Project Foundation

## 目標

從零開始建立一個 Spring Boot Maven 專案，包含：
- Git 版本控制
- Maven 專案結構
- Spring Boot REST API
- Docker 容器化
- 環境變數管理
- 多服務差異化設定

---

## 目錄

- [1.1 版本控制與目錄結構](#11-版本控制與目錄結構)
- [1.2 Maven 專案](#12-maven-專案)
- [1.3 Docker 容器化](#13-docker-容器化)
- [1.4 環境變數與 Secrets](#14-環境變數與-secrets)
- [1.5 變數預設值與各服務差異](#15-變數預設值與各服務差異)
- [1.6 Developer Experience](#16-developer-experience)

---

## 準備工作

確認環境已安裝以下工具：

```bash
java --version            # 需要 Java 21+
mvn --version             # 需要 Maven 3.8+
git --version             # 需要 Git 2.x+
```

---

## 1.1 版本控制與目錄結構

### 1.1.1 初始化 Git 倉庫

```bash
mkdir spring-boot-demo
cd spring-boot-demo
git init
git config user.email "developer@example.com"
git config user.name "Developer"
```

**說明**：`git init` 在目前目錄建立 `.git` 資料夾，開始進行版本控制。

**驗證**：

```bash
git log --oneline
# 輸出：fatal: your current branch 'master' does not have any commits yet
# （尚未有任何 commit，這是正常的）
```

### 1.1.2 建立標準 Maven 目錄結構

```bash
mkdir -p src/main/java/com/example/demo
mkdir -p src/main/resources
```

**說明**：
- `src/main/java/com/example/demo/` — Java 原始碼
- `src/main/resources/` — 設定檔與靜態資源
- Maven 標準目錄結構，不需要額外設定 IDE 即可識別

**驗證**：

```bash
find src -type d
# 輸出：
# src
# src/main
# src/main/java
# src/main/java/com
# src/main/java/com/example
# src/main/java/com/example/demo
# src/main/resources
```

---

## 1.2 Maven 專案

### 1.2.1 建立 pom.xml

建立 `pom.xml`，定義專案與相依性：

```bash
cat > pom.xml << 'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
         https://maven.apache.org/xsd/maven-4.0.0.xsd">
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

**範本說明**：

```xml
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.3.5</version>
</parent>
```

`spring-boot-starter-parent` 是所有 Spring Boot 專案的 parent POM，它定義了數百個函式庫的版本，讓你的 `pom.xml` 不需要手動指定版本號。

```xml
<dependencies>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
</dependencies>
```

`spring-boot-starter-web` 是一個 starter，它一次引入：
- Spring MVC（REST API）
- 內嵌 Tomcat（不需要額外安裝 web server）
- Jackson（JSON 序列化/反序列化）

**驗證**：

```bash
mvn clean compile
# 輸出：BUILD SUCCESS
# Maven 會下載所有依賴並編譯（第一次需要網路，後續有 cache）
```

如果看到 `BUILD SUCCESS`，表示 Maven 專案設定正確。

### 1.2.2 建立主程式類別

```bash
cat > src/main/java/com/example/demo/DemoApplication.java << 'EOF'
package com.example.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@SpringBootApplication
@RestController
public class DemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }

    @GetMapping("/")
    public String hello() {
        return "Hello from Spring Boot!";
    }
}
EOF
```

**程式說明**：

| Annotation | 作用 |
|-----------|------|
| `@SpringBootApplication` | 告訴 Spring Boot 這是啟動類別（內含 `@ComponentScan` + `@EnableAutoConfiguration`） |
| `@RestController` | 標記此類別可以處理 HTTP 請求並直接回傳（不回傳 view name） |
| `@GetMapping("/")` | 當收到 `GET /` 請求時，執行此方法 |

- `SpringApplication.run()` 啟動內嵌 Tomcat 並註冊所有 Bean
- 方法回傳的 `String` 會直接作為 HTTP response body

**驗證**：

```bash
# 在背景啟動 Spring Boot
mvn spring-boot:run -q &
sleep 10

# 測試 API
curl http://localhost:8080/
# 輸出：Hello from Spring Boot!

# 停止背景程序
kill %1 2>/dev/null
```

看到 `Hello from Spring Boot!` 表示 Spring Boot 應用程式正常運作。

### 1.2.3 建立 application.properties

```bash
cat > src/main/resources/application.properties << 'EOF'
server.port=8080
spring.application.name=demo
EOF
```

**說明**：
- `server.port=8080` — Tomcat 監聽埠號，可透過環境變數 `SERVER_PORT` 覆蓋
- `spring.application.name=demo` — 應用程式名稱，用於日誌與服務註冊

**驗證**：啟動時可以看到日誌顯示 `Tomcat started on port 8080`。

### 1.2.4 第一次 Commit

```bash
git add pom.xml src/
git commit -m "feat: 建立 Spring Boot Maven 專案，含基本 REST API"
```

---

## 1.3 Docker 容器化

### 1.3.1 建立 Dockerfile

```bash
cat > Dockerfile << 'EOF'
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
EOF
```

**多階段構建說明**：

```
Stage 1 (builder):
  maven:3.9 + JDK 21
      目的：編譯與打包
      └── pom.xml + src → target/*.jar

Stage 2 (runtime):
  eclipse-temurin:21-jre
      目的：只執行 jar（不含 Maven、編譯器等）
      └── 僅複製 jar，映像縮小約 60%
```

為什麼需要 `RUN mvn dependency:go-offline`？
- 在複製 source code 之前先下載所有依賴
- Docker layer cache：只要 `pom.xml` 不變，這層就不會重新執行
- 每次開發只改 source code 時，build 時間大幅縮短

**映像大小比較**：
- 單階段（含 Maven + JDK）：約 400MB
- 多階段（僅 JRE + jar）：約 150MB

### 1.3.2 建立 .dockerignore

```bash
cat > .dockerignore << 'EOF'
target/
.git/
.gitignore
*.md
EOF
```

**說明**：避免將本機的 build 產物與 Git metadata 傳送到 Docker daemon，加快 build 速度。

**驗證**：

```bash
docker build -t spring-demo .
# 輸出：Successfully tagged spring-demo:latest

docker image ls spring-demo
# 觀察映像大小（約 150MB）

docker run --rm -p 8080:8080 spring-demo &
sleep 15   # 等待 Spring Boot 啟動
curl http://localhost:8080/
# 輸出：Hello from Spring Boot!
kill %1 2>/dev/null
```

看到 `Hello from Spring Boot!` 表示 Docker 映像正確。

### 1.3.3 Commit

```bash
git add Dockerfile .dockerignore
git commit -m "feat: 建立 Dockerfile（多階段構建）+ .dockerignore"
```

---

## 1.4 環境變數與 Secrets

### 1.4.1 建立 .env

```bash
cat > .env << 'EOF'
APP_ENV=development
APP_VERSION=1.0.0
EOF
```

**說明**：`.env` 檔案由 Docker Compose 自動載入，其中的變數可以在 `docker-compose.yml` 中以 `${APP_ENV}` 形式引用。

**安全注意**：`.env` **絕對不要**提交到 Git。下一步將它加入 `.gitignore`。

### 1.4.2 建立 .gitignore

```bash
cat > .gitignore << 'EOF'
.env
EOF
```

**驗證**：

```bash
git status .env
# 輸出應包含：Ignored: .env
```

如果 `.env` 沒有被忽略，確認 `.gitignore` 檔案內容是否正確。

### 1.4.3 新增 /config 端點讀取環境變數

修改 `DemoApplication.java`：

```bash
cat > src/main/java/com/example/demo/DemoApplication.java << 'EOF'
package com.example.demo;

import java.util.Map;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@SpringBootApplication
@RestController
public class DemoApplication {

    private final Environment env;

    public DemoApplication(Environment env) {
        this.env = env;
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
        return Map.of(
            "APP_ENV", env.getProperty("APP_ENV", "undefined"),
            "APP_VERSION", env.getProperty("APP_VERSION", "undefined")
        );
    }
}
EOF
```

**關鍵概念**：`Environment` 是 Spring 的核心介面，它封裝了所有外部化設定的來源：

```
.env → docker-compose.yml (${VAR}) → container 環境變數
                                            ↓
                                    Spring Environment
                                            ↓
                              @Value("${VAR}") / env.getProperty("VAR")
```

`env.getProperty("APP_ENV", "undefined")` 第二個參數是預設值，當變數未設定時回傳。

**驗證**：

```bash
# 先測試直接用 Java 執行
mvn spring-boot:run -q &
sleep 10
curl http://localhost:8080/config
# 輸出：{"APP_ENV":"undefined","APP_VERSION":"undefined"}
# （因為沒有 container，只有 JVM 環境變數）

kill %1 2>/dev/null
```

上面的輸出顯示 `undefined`，這是預期行為 — 因為 `APP_ENV` 是 container 層級的環境變數，需要透過 Docker Compose 注入。

### 1.4.4 建立 docker-compose.yml

```bash
cat > docker-compose.yml << 'EOF'
services:
  app:
    build: .
    container_name: spring-demo
    ports:
      - "8080:8080"
    environment:
      - APP_ENV=${APP_ENV}
      - APP_VERSION=${APP_VERSION}
    restart: unless-stopped
EOF
```

**說明**：`environment` 區段中的 `${APP_ENV}` 會從 `.env` 檔案讀取值。

**驗證**：

```bash
docker compose up -d
curl http://localhost:8080/config
# 輸出：{"APP_ENV":"development","APP_VERSION":"1.0.0"}

docker compose down
```

現在 `APP_ENV` 的值是 `development`，來自 `.env` 檔案。

### 1.4.5 Commit

```bash
git add src/main/java/com/example/demo/DemoApplication.java \
       src/main/resources/application.properties \
       .gitignore docker-compose.yml
git commit -m "feat: 新增 /config 端點讀取環境變數，建立 docker-compose.yml"
```

注意：`.env` **沒有**加入 Git。如果已經追蹤了，用 `git rm --cached .env` 移除。

---

## 1.5 變數預設值與各服務差異

### 1.5.1 Docker Compose 變數預設值語法

修改 `.env`，加入新變數：

```bash
cat > .env << 'EOF'
APP_ENV=development
APP_VERSION=1.0.0
LOG_LEVEL=DEBUG
APP_CACHE_TTL=300
AUTH_CACHE_TTL=60
EOF
```

修改 `docker-compose.yml`，示範 `${VAR:-default}` 語法：

```bash
cat > docker-compose.yml << 'EOF'
services:
  app:
    build: .
    container_name: spring-demo
    ports:
      - "8080:8080"
    environment:
      - APP_ENV=${APP_ENV}
      - APP_VERSION=${APP_VERSION}
      - LOG_LEVEL=${LOG_LEVEL:-INFO}
      - CACHE_TTL=${APP_CACHE_TTL:-300}
    restart: unless-stopped
EOF
```

**語法說明**：

| 語法 | 當 VAR 未設定 | 當 VAR 為空字串 |
|------|--------------|----------------|
| `${VAR:-default}` | 使用 `default` | 使用 `default` |
| `${VAR-default}` | 使用 `default` | 使用空字串 |

在這個範例中：
- 如果 `.env` 有 `LOG_LEVEL=DEBUG` → container 收到 `DEBUG`
- 如果 `.env` 移除 `LOG_LEVEL` → container 收到 `INFO`

### 1.5.2 更新 application.properties 使用變數

```bash
cat > src/main/resources/application.properties << 'EOF'
server.port=8080
spring.application.name=${APP_NAME:demo}
logging.level.root=${LOG_LEVEL:INFO}
EOF
```

Spring Boot 的 `application.properties` 也能使用 `${...}` 語法來讀取環境變數，與 Docker Compose 相同的解析規則。

### 1.5.3 Dockerfile ARG → ENV 注入

修改 `Dockerfile`，加入 ARG 與 ENV：

```bash
cat > Dockerfile << 'EOF'
# Stage 1: Build
FROM maven:3.9-eclipse-temurin-21-alpine AS builder
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline
COPY src ./src
RUN mvn clean package -DskipTests

# Stage 2: Run
FROM eclipse-temurin:21-jre-alpine

ARG APP_NAME=unknown
ENV APP_NAME=${APP_NAME}

WORKDIR /app
COPY --from=builder /app/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
EOF
```

**ARG vs ENV 說明**：

| | ARG | ENV |
|---|---|---|
| 作用時間 | build 期間 | container 運行期間 |
| 可被覆寫 | `--build-arg` / compose `args` | `environment` |
| 最終映像中 | 不存在 | 存在（可被 env 覆蓋） |

模式：`ARG` 接收 build-time 參數 → `ENV` 延續到 runtime → `application.properties` 讀取。

修改 `docker-compose.yml` 傳入不同 build args：

```bash
cat > docker-compose.yml << 'EOF'
services:
  app:
    build:
      context: .
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
EOF
```

### 1.5.4 更新 /config 端點顯示更多資訊

```bash
cat > src/main/java/com/example/demo/DemoApplication.java << 'EOF'
package com.example.demo;

import java.util.Map;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@SpringBootApplication
@RestController
public class DemoApplication {

    private final Environment env;

    public DemoApplication(Environment env) {
        this.env = env;
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

    private boolean isEnvSet(String key) {
        String val = env.getProperty(key);
        return val != null && !val.isEmpty();
    }
}
EOF
```

**驗證完整注入鏈**：

```bash
docker compose up -d --build
curl http://localhost:8080/config
# 預期輸出：
# {
#   "service": "spring-demo-backend",
#   "APP_ENV": "development",
#   "APP_VERSION": "1.0.0",
#   "LOG_LEVEL": "DEBUG",
#   "CACHE_TTL": "300"
# }

# 測試 fallback 行為：暫時註解掉 .env 中的 LOG_LEVEL
# 編輯 .env 移除 LOG_LEVEL 那行，然後重啟：
docker compose up -d --build
curl http://localhost:8080/config
# LOG_LEVEL 應顯示 "INFO"（來自 Docker Compose 的 ${LOG_LEVEL:-INFO}）

docker compose down
```

**變數傳遞鏈**：
```
.env (APP_ENV=development)
  ↓ 自動載入
docker-compose.yml (environment: APP_ENV=${APP_ENV})
  ↓ 注入 container
container 環境變數 (APP_ENV=development)
  ↓ Spring 讀取
application.properties (無直接引用，透過 Environment 讀取)
  ↓
DemoApplication.java (env.getProperty("APP_ENV"))
```

### 1.5.5 Commit

```bash
git add Dockerfile docker-compose.yml \
       src/main/java/com/example/demo/DemoApplication.java \
       src/main/resources/application.properties
git commit -m "feat: 示範變數預設值與 Dockerfile ARG/ENV 注入模式"
```

---

## 1.6 Developer Experience

### 1.6.1 建立 Makefile

```bash
cat > Makefile << 'MAKEFILE'
.PHONY: build up down logs test clean

build:
	docker compose build

up:
	docker compose up -d

down:
	docker compose down

logs:
	docker compose logs -f

test:
	mvn test

clean:
	docker compose down -v --rmi all
MAKEFILE
```

**說明**：Makefile 將常用指令包裝成簡短的捷徑，新成員加入時只需記 `make up` 即可啟動。`.PHONY` 表示這些目標不是實際檔案，避免與同名的檔案衝突。

**使用方式**：

```bash
make build     # 等同 docker compose build
make up        # 等同 docker compose up -d
make logs      # 等同 docker compose logs -f
make down      # 等同 docker compose down
make clean     # 移除所有容器、volume、映像
```

### 1.6.2 Commit

```bash
git add Makefile
git commit -m "chore: 加入 Makefile 方便常用指令操作"
```

---

## 驗證完整 Phase 1

執行以下指令確認 Phase 1 的所有功能正常：

```bash
# 1. 編譯
mvn clean compile

# 2. Docker 建置
docker build -t spring-demo .

# 3. Docker Compose 啟動
docker compose up -d

# 4. 測試 API
curl http://localhost:8080/
# 預期：Hello from Spring Boot!

# 5. 測試 config 端點
curl http://localhost:8080/config
# 預期：顯示所有環境變數

# 6. 清理
docker compose down
```

---

## Commit 歷史參考

```bash
git log --oneline
# 你應該會看到類似以下的 commit 歷史：
# feat: 示範變數預設值與 Dockerfile ARG/ENV 注入模式
# feat: 新增 /config 端點讀取環境變數，建立 docker-compose.yml
# feat: 建立 Dockerfile（多階段構建）+ .dockerignore
# feat: 建立 Spring Boot Maven 專案，含基本 REST API
```
