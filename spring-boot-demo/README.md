# 最簡易 Spring Boot Maven 專案 — 操作筆記

## 環境需求

- Java 21+
- Maven 3.8+
- Git

---

## Step 1：初始化 Git 倉庫與目錄結構

```bash
mkdir spring-boot-demo
cd spring-boot-demo
git init
```

建立標準 Maven 專案目錄：

```bash
mkdir -p src/main/java/com/example/demo
mkdir -p src/main/resources
```

---

## Step 2：建立 pom.xml

在專案根目錄建立 `pom.xml`，使用 `spring-boot-starter-parent` 3.3.5 作為 parent POM，加入 `spring-boot-starter-web` 依賴（包含內嵌 Tomcat 與 Spring MVC）。

```xml
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.3.5</version>
</parent>

<dependencies>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
</dependencies>
```

提交記錄：

```bash
git add pom.xml
git commit -m "Step 1: 建立 pom.xml，加入 Spring Boot 3.3.5 parent 與 spring-boot-starter-web 依賴"
```

---

## Step 3：建立主程式類別

在 `src/main/java/com/example/demo/DemoApplication.java` 建立啟動類別，加上 `@SpringBootApplication` 與一個簡易的 REST 端點作為測試。

```java
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
```

提交記錄：

```bash
git add src/main/java/com/example/demo/DemoApplication.java
git commit -m "Step 2: 建立主程式類別 DemoApplication，加入 @SpringBootApplication 與簡易 REST 端點"
```

---

## Step 4：建立 application.properties

設定伺服器埠號與應用程式名稱。

```properties
server.port=8080
spring.application.name=demo
```

提交記錄：

```bash
git add src/main/resources/application.properties
git commit -m "Step 3: 建立 application.properties，設定埠號與應用程式名稱"
```

---

## Step 5：驗證專案

執行 Maven 編譯與啟動測試：

```bash
# 編譯專案
mvn clean compile

# 啟動 Spring Boot（Ctrl+C 停止）
mvn spring-boot:run
```

啟動後開啟瀏覽器至 `http://localhost:8080/`，應看到 **Hello from Spring Boot!** 字樣。

---

## Git 提交紀錄

```bash
$ git log --oneline
a76613c Step 6: 建立 docker-compose.yml，定義 app 服務並映射埠號 8080
cdacdca Step 5: 建立 Dockerfile，使用多階段構建（Maven build + JRE runtime）
1dc49aa Step 3: 建立 application.properties，設定埠號與應用程式名稱
cc40a9f Step 2: 建立主程式類別 DemoApplication，加入 @SpringBootApplication 與簡易 REST 端點
85845ee Step 1: 建立 pom.xml，加入 Spring Boot 3.3.5 parent 與 spring-boot-starter-web 依賴
```

---

## Step 6：建立 .dockerignore

```bash
echo "target/
.git/
.gitignore
*.md" > .dockerignore
```

避免將本機構建產物與 Git 資料送入 Docker context。

---

## Step 7：建立 Dockerfile（多階段構建）

採用多階段構建（multi-stage build）以縮小最終映像檔大小：

- **Stage 1（builder）**：使用 `maven:3.9-eclipse-temurin-21-alpine` 映像進行 Maven 編譯與打包。
- **Stage 2（runtime）**：使用 `eclipse-temurin:21-jre-alpine` 僅包含 JRE，將 build 階段的 jar 複製過來執行。

```dockerfile
FROM maven:3.9-eclipse-temurin-21-alpine AS builder
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline
COPY src ./src
RUN mvn clean package -DskipTests

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=builder /app/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

提交記錄：

```bash
git add Dockerfile .dockerignore
git commit -m "Step 5: 建立 Dockerfile，使用多階段構建（Maven build + JRE runtime）"
```

---

## Step 8：建立 docker-compose.yml

```yaml
services:
  app:
    build: .
    container_name: spring-demo
    ports:
      - "8080:8080"
    restart: unless-stopped
```

提交記錄：

```bash
git add docker-compose.yml
git commit -m "Step 6: 建立 docker-compose.yml，定義 app 服務並映射埠號 8080"
```

---

## 啟動容器

```bash
# 構建並啟動（背景執行）
docker compose up -d

# 查看日誌
docker compose logs -f

# 測試
curl http://localhost:8080/

# 停止並移除容器
docker compose down
```

---

## 總結

以上即為最簡易 Spring Boot Maven 專案的完整建置流程。透過 `spring-boot-starter-parent` 管理依賴版本，僅需一個 `spring-boot-starter-web` 依賴即可擁有一個可執行的 Web 應用程式，並搭配 Docker / Docker Compose 進行容器化部署。
