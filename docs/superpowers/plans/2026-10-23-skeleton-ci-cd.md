# 백엔드 골격 + CI/CD 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 빌드만 되는 Kotlin 멀티모듈 백엔드를 만들고, 홈서버 self-hosted runner로 CI(PR 검증)와 CD(api·scheduler 배포)를 끝까지 돌린다.

**Architecture:** Gradle 멀티모듈(domain, application, infra, api, scheduler). 빌드·테스트·패키징은 모두 `docker/Dockerfile`의 스테이지에서 실행해서, 홈서버에 JDK를 설치하지 않는다. 배포는 `deploy/compose.yml`로 api와 scheduler 컨테이너만 올린다. DB는 이미 떠 있는 공용 PostgreSQL(`shared-postgres`)을 외부 네트워크 `db`로 쓴다.

**Tech Stack:** JDK 25, Kotlin 2.3.21, Spring Boot 4.1.1, Gradle 9.8.2, kotlinter 5.7.0, Docker BuildKit(buildx 0.30.1), Docker Compose 2.40.3, GitHub Actions self-hosted runner(라벨 `homeserver`).

**Spec:** `docs/specs/2026-10-23-skeleton-ci-cd-design.md`

## Global Constraints

- 브랜치: `SG-1/ci-cd`. 커밋 메시지는 한국어, 첫 줄에 `SG-1` 이슈 키. 끝에 `Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>`.
- git 상태 변경(commit, push, PR)은 `git-master` 에이전트에 위임한다. PR 머지는 하지 않는다 (사용자가 머지한다).
- 버전: Kotlin `2.3.21`, Spring Boot `4.1.1`, `io.spring.dependency-management` `1.1.7`, kotlinter `5.7.0`, Gradle `9.8.2`, JDK toolchain `25`.
- `gradle/libs.versions.toml`을 만들지 않는다. 플러그인 버전은 루트 `build.gradle.kts`의 plugins 블록에 둔다.
- 패키지 루트: `com.bombo.stockguide`. group `com.bombo.stockguide`, version `0.0.1-SNAPSHOT`.
- Kotlin 수정 후 `./gradlew formatKotlin` → `./gradlew lintKotlin` 순서로 실행한다. 규칙을 끄거나 suppress하지 않는다.
- 테스트: 영문 메서드명 + 한글 `@DisplayName`, `@Nested`는 `inner class`, PER_CLASS 라이프사이클(`junit-platform.properties`).
- DB 비밀번호를 레포, 워크플로, 로그에 넣지 않는다. 접속 정보는 홈서버 `/home/bombo/postgres/stock_guide.env`에서만 읽는다.
- compose 파일에 PostgreSQL 서비스를 넣지 않는다. 넣으면 배포가 공용 DB를 재시작한다.
- 홈서버에서 DB 컨테이너(`shared-postgres`)와 `~/postgres` 스택을 건드리지 않는다.
- 레포는 public이다. 외부 PR의 워크플로 실행을 승인하지 않는다. 워크플로에 `secrets`를 쓰지 않고 `permissions: contents: read`를 둔다.
- 로컬 Docker 데몬은 꺼져 있다. Docker 검증은 홈서버(`ssh homeserver`)에서 하거나 CI 결과로 확인한다.

## File Structure

| 파일 | 책임 |
|------|------|
| `.gitignore` | 빌드 산출물, IDE 파일 제외 |
| `.editorconfig` | ktlint 규칙. `~/Documents/0000_CLAUDE_WORKSPACE/templates/kotlin/.editorconfig` 복사본 |
| `gradlew`, `gradlew.bat`, `gradle/wrapper/*` | Gradle 9.8.2 래퍼 |
| `settings.gradle.kts` | 모듈 목록 |
| `build.gradle.kts` | 플러그인 버전, 공통 Kotlin·BOM·린트·테스트 설정 |
| `domain/build.gradle.kts` | 의존 없음 |
| `application/build.gradle.kts` | `domain` 의존 |
| `infra/build.gradle.kts` | `domain` 의존, JDBC, PostgreSQL 드라이버 |
| `infra/src/main/resources/infra.yaml` | DataSource 설정 (환경 변수에서 읽음) |
| `api/**` | api 앱, 설정, 헬스 테스트 |
| `scheduler/**` | scheduler 앱, 설정, 헬스 테스트 |
| `docker/Dockerfile` | `source` → `test` / `package` → `runtime` 스테이지 |
| `.dockerignore` | 빌드 컨텍스트 축소 |
| `deploy/compose.yml` | api, scheduler 실행 정의. DB 없음 |
| `.github/workflows/ci.yml` | main 대상 PR 검증. 실패 시 룰셋이 머지를 막는다 |
| `.github/workflows/cd.yml` | 서버를 골라 수동 배포 |
| `.github/workflows/runner-check.yml` | 수동 실행만 남김 |

---

### Task 1: Gradle 멀티모듈 골격

**Files:**
- Create: `.gitignore`, `.editorconfig`, `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`, `gradle/wrapper/gradle-wrapper.properties`
- Create: `settings.gradle.kts`, `build.gradle.kts`
- Create: `domain/build.gradle.kts`, `application/build.gradle.kts`, `infra/build.gradle.kts`, `infra/src/main/resources/infra.yaml`

**Interfaces:**
- Produces: 모듈 `:domain`, `:application`, `:infra`. `:infra`는 `spring-boot-starter-jdbc`를 `api`로 노출한다. 환경 변수 키 `STOCK_GUIDE_DB_URL`, `STOCK_GUIDE_DB_USER`, `STOCK_GUIDE_DB_PASSWORD`. 클래스패스 리소스 `infra.yaml`.

- [ ] **Step 1: Gradle 래퍼 생성**

검증된 프로토타입의 래퍼를 복사한다. 프로토타입이 없으면 로컬 Gradle로 생성한다.

```bash
PROTO=/private/tmp/claude-501/-Users-bombo-Documents-004-WORKSPACE-projects/8e104236-5e2c-410e-beae-2310a5ee19e6/scratchpad/proto
cp -R "$PROTO/gradlew" "$PROTO/gradlew.bat" "$PROTO/gradle" .
# 프로토타입이 없을 때: gradle wrapper --gradle-version 9.8.2
grep distributionUrl gradle/wrapper/gradle-wrapper.properties
```

Expected: `distributionUrl=https\://services.gradle.org/distributions/gradle-9.8.2-bin.zip`

- [ ] **Step 2: `.editorconfig` 복사, `.gitignore` 작성**

```bash
cp ~/Documents/0000_CLAUDE_WORKSPACE/templates/kotlin/.editorconfig .editorconfig
```

`.gitignore`:

```gitignore
.gradle/
build/
!gradle/wrapper/gradle-wrapper.jar
.kotlin/
.idea/
*.iml
out/
.DS_Store
```

- [ ] **Step 3: `settings.gradle.kts` 작성**

```kotlin
rootProject.name = "stock-guide"

include("domain", "application", "infra")
```

- [ ] **Step 4: 루트 `build.gradle.kts` 작성**

```kotlin
import io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.springframework.boot.gradle.plugin.SpringBootPlugin

plugins {
    kotlin("jvm") version "2.3.21" apply false
    kotlin("plugin.spring") version "2.3.21" apply false
    id("org.springframework.boot") version "4.1.1" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
    id("org.jmailen.kotlinter") version "5.7.0" apply false
}

allprojects {
    group = "com.bombo.stockguide"
    version = "0.0.1-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "io.spring.dependency-management")
    apply(plugin = "org.jmailen.kotlinter")

    configure<DependencyManagementExtension> {
        imports {
            mavenBom(SpringBootPlugin.BOM_COORDINATES)
        }
    }

    configure<KotlinJvmProjectExtension> {
        jvmToolchain(25)
        compilerOptions {
            freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
        }
    }

    dependencies {
        "testImplementation"("org.jetbrains.kotlin:kotlin-test-junit5")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }
}
```

- [ ] **Step 5: 하위 모듈 빌드 파일 작성**

`domain/build.gradle.kts`:

```kotlin
// 핵심 모델과 포트. Spring에 의존하지 않는다.
```

`application/build.gradle.kts`:

```kotlin
dependencies {
    implementation(project(":domain"))
}
```

`infra/build.gradle.kts`:

```kotlin
dependencies {
    implementation(project(":domain"))
    api("org.springframework.boot:spring-boot-starter-jdbc")
    runtimeOnly("org.postgresql:postgresql")
}
```

`infra/src/main/resources/infra.yaml`:

```yaml
# DB 접속 정보는 홈서버 ~/postgres/stock_guide.env 에서 주입한다. 기본값을 두지 않는다.
spring:
  datasource:
    url: ${STOCK_GUIDE_DB_URL}
    username: ${STOCK_GUIDE_DB_USER}
    password: ${STOCK_GUIDE_DB_PASSWORD}
```

- [ ] **Step 6: 빌드 확인**

`api`, `scheduler`는 Task 2·3에서 각자 `settings.gradle.kts`에 추가한다.

```bash
./gradlew --console=plain build lintKotlin
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 7: 커밋 (git-master 위임)**

대상: Step 1–5의 파일 전부. 메시지: `SG-1 Gradle 멀티모듈 골격 추가`

---

### Task 2: api 앱과 헬스체크

**Files:**
- Modify: `settings.gradle.kts` (`api` 추가)
- Create: `api/build.gradle.kts`
- Create: `api/src/main/kotlin/com/bombo/stockguide/api/ApiApplication.kt`
- Create: `api/src/main/resources/application.yaml`
- Test: `api/src/test/kotlin/com/bombo/stockguide/api/ApiHealthTest.kt`, `api/src/test/resources/junit-platform.properties`

**Interfaces:**
- Consumes: Task 1의 `:application`, `:infra`, `infra.yaml`.
- Produces: bootJar 파일명 `app.jar`. 포트 `8080`. 엔드포인트 `/actuator/health/liveness`, `/actuator/health/readiness`(readiness는 `db` 포함).

- [ ] **Step 1: 모듈 등록, 빌드 파일과 테스트 설정 작성**

`settings.gradle.kts`의 include를 바꾼다.

```kotlin
include("domain", "application", "infra", "api")
```

`api/build.gradle.kts`:

```kotlin
plugins {
    kotlin("plugin.spring")
    id("org.springframework.boot")
}

dependencies {
    implementation(project(":application"))
    implementation(project(":infra"))
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("tools.jackson.module:jackson-module-kotlin")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
}

tasks.bootJar {
    archiveFileName.set("app.jar")
}
```

`api/src/test/resources/junit-platform.properties`:

```properties
junit.jupiter.testinstance.lifecycle.default = per_class
```

- [ ] **Step 2: 실패하는 테스트 작성**

DB 없이 띄웠을 때 liveness는 UP, readiness는 DB 때문에 DOWN이어야 한다. 이 테스트는 readiness 그룹에 `db`가 들어갔는지를 검증한다.

`api/src/test/kotlin/com/bombo/stockguide/api/ApiHealthTest.kt`:

```kotlin
package com.bombo.stockguide.api

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

@SpringBootTest(
    properties = [
        "STOCK_GUIDE_DB_URL=jdbc:postgresql://localhost:1/unreachable",
        "STOCK_GUIDE_DB_USER=test",
        "STOCK_GUIDE_DB_PASSWORD=test",
    ],
)
@AutoConfigureMockMvc
@DisplayName("ApiApplication 헬스체크")
class ApiHealthTest(@Autowired private val mockMvc: MockMvc) {
    @Nested
    @DisplayName("DB에 접속할 수 없을 때")
    inner class WhenDatabaseIsUnreachable {
        @Test
        @DisplayName("liveness는 UP이다")
        fun livenessIsUp() {
            mockMvc.get("/actuator/health/liveness").andExpect {
                status { isOk() }
                jsonPath("$.status") { value("UP") }
            }
        }

        @Test
        @DisplayName("readiness는 DB를 포함해 DOWN이다")
        fun readinessIsDown() {
            mockMvc.get("/actuator/health/readiness").andExpect {
                status { isServiceUnavailable() }
                jsonPath("$.status") { value("DOWN") }
            }
        }
    }
}
```

- [ ] **Step 3: 테스트 실패 확인**

Run: `./gradlew --console=plain :api:test`
Expected: FAIL. `@SpringBootConfiguration`을 찾지 못해 컨텍스트 로딩 실패.

- [ ] **Step 4: 앱 클래스와 설정 작성**

`api/src/main/kotlin/com/bombo/stockguide/api/ApiApplication.kt`:

```kotlin
package com.bombo.stockguide.api

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class ApiApplication

fun main(args: Array<String>) {
    runApplication<ApiApplication>(*args)
}
```

`api/src/main/resources/application.yaml`:

```yaml
spring:
  application:
    name: stock-guide-api
  config:
    import: classpath:infra.yaml

server:
  port: 8080

management:
  endpoint:
    health:
      probes:
        enabled: true
      group:
        readiness:
          include: readinessState,db
```

- [ ] **Step 5: 테스트 통과 확인**

Run: `./gradlew --console=plain formatKotlin lintKotlin :api:test`
Expected: `BUILD SUCCESSFUL`, 테스트 2개 통과.

- [ ] **Step 6: 커밋 (git-master 위임)**

대상: `settings.gradle.kts`, `api/` 전체. 메시지: `SG-1 api 앱과 헬스체크 추가`

---

### Task 3: scheduler 앱과 헬스체크

**Files:**
- Modify: `settings.gradle.kts` (`scheduler` 추가)
- Create: `scheduler/build.gradle.kts`
- Create: `scheduler/src/main/kotlin/com/bombo/stockguide/scheduler/SchedulerApplication.kt`
- Create: `scheduler/src/main/resources/application.yaml`
- Test: `scheduler/src/test/kotlin/com/bombo/stockguide/scheduler/SchedulerHealthTest.kt`, `scheduler/src/test/resources/junit-platform.properties`

**Interfaces:**
- Consumes: Task 1의 `:application`, `:infra`, `infra.yaml`.
- Produces: bootJar 파일명 `app.jar`. 컨테이너 내부 포트 `8081`(외부 공개 안 함). 엔드포인트는 Task 2와 같다.

- [ ] **Step 1: 모듈 등록, 빌드 파일과 테스트 설정 작성**

`settings.gradle.kts`의 include를 바꾼다.

```kotlin
include("domain", "application", "infra", "api", "scheduler")
```

`scheduler/build.gradle.kts`:

```kotlin
plugins {
    kotlin("plugin.spring")
    id("org.springframework.boot")
}

dependencies {
    implementation(project(":application"))
    implementation(project(":infra"))
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("tools.jackson.module:jackson-module-kotlin")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
}

tasks.bootJar {
    archiveFileName.set("app.jar")
}
```

`scheduler/src/test/resources/junit-platform.properties`:

```properties
junit.jupiter.testinstance.lifecycle.default = per_class
```

- [ ] **Step 2: 실패하는 테스트 작성**

`scheduler/src/test/kotlin/com/bombo/stockguide/scheduler/SchedulerHealthTest.kt`:

```kotlin
package com.bombo.stockguide.scheduler

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

@SpringBootTest(
    properties = [
        "STOCK_GUIDE_DB_URL=jdbc:postgresql://localhost:1/unreachable",
        "STOCK_GUIDE_DB_USER=test",
        "STOCK_GUIDE_DB_PASSWORD=test",
    ],
)
@AutoConfigureMockMvc
@DisplayName("SchedulerApplication 헬스체크")
class SchedulerHealthTest(@Autowired private val mockMvc: MockMvc) {
    @Nested
    @DisplayName("DB에 접속할 수 없을 때")
    inner class WhenDatabaseIsUnreachable {
        @Test
        @DisplayName("liveness는 UP이다")
        fun livenessIsUp() {
            mockMvc.get("/actuator/health/liveness").andExpect {
                status { isOk() }
                jsonPath("$.status") { value("UP") }
            }
        }

        @Test
        @DisplayName("readiness는 DB를 포함해 DOWN이다")
        fun readinessIsDown() {
            mockMvc.get("/actuator/health/readiness").andExpect {
                status { isServiceUnavailable() }
                jsonPath("$.status") { value("DOWN") }
            }
        }
    }
}
```

- [ ] **Step 3: 테스트 실패 확인**

Run: `./gradlew --console=plain :scheduler:test`
Expected: FAIL. `@SpringBootConfiguration`을 찾지 못해 컨텍스트 로딩 실패.

- [ ] **Step 4: 앱 클래스와 설정 작성**

`scheduler/src/main/kotlin/com/bombo/stockguide/scheduler/SchedulerApplication.kt`:

```kotlin
package com.bombo.stockguide.scheduler

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableScheduling
class SchedulerApplication

fun main(args: Array<String>) {
    runApplication<SchedulerApplication>(*args)
}
```

`scheduler/src/main/resources/application.yaml`:

```yaml
spring:
  application:
    name: stock-guide-scheduler
  config:
    import: classpath:infra.yaml

server:
  port: 8081

management:
  endpoint:
    health:
      probes:
        enabled: true
      group:
        readiness:
          include: readinessState,db
```

- [ ] **Step 5: 전체 빌드 확인**

Run: `./gradlew --console=plain formatKotlin lintKotlin build`
Expected: `BUILD SUCCESSFUL`. api 2개, scheduler 2개 테스트 통과. `api/build/libs/app.jar`, `scheduler/build/libs/app.jar` 생성.

- [ ] **Step 6: 커밋 (git-master 위임)**

대상: `settings.gradle.kts`, `scheduler/` 전체. 메시지: `SG-1 scheduler 앱과 헬스체크 추가`

---

### Task 4: Docker 이미지

**Files:**
- Create: `docker/Dockerfile`, `.dockerignore`

**Interfaces:**
- Consumes: Task 2·3의 `:api:bootJar`, `:scheduler:bootJar` (`app.jar`).
- Produces: 스테이지 `test`(린트·테스트), `runtime`(빌드 인자 `MODULE=api|scheduler`). 실행 사용자 `app`, 작업 디렉토리 `/app`, `wget` 포함(alpine busybox).

- [ ] **Step 1: Dockerfile 작성**

`docker/Dockerfile`:

```dockerfile
# syntax=docker/dockerfile:1
FROM eclipse-temurin:25-jdk AS source
WORKDIR /workspace
COPY . .

FROM source AS test
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon --console=plain lintKotlin test

FROM source AS package
ARG MODULE
RUN test -n "$MODULE" || { echo "MODULE 빌드 인자가 필요하다 (api 또는 scheduler)"; exit 1; }
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon --console=plain ":${MODULE}:bootJar" \
    && cp "${MODULE}/build/libs/app.jar" /app.jar

FROM eclipse-temurin:25-jre-alpine AS runtime
RUN addgroup -S app && adduser -S app -G app
USER app
WORKDIR /app
COPY --from=package /app.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
```

`.dockerignore`:

```
.git
.gradle
**/build
.idea
*.iml
docs
deploy
.github
.kotlin
```

- [ ] **Step 2: 홈서버에서 빌드 확인**

로컬 Docker 데몬은 꺼져 있다. 작업 트리를 홈서버 임시 디렉토리로 보내 빌드한다.

```bash
ssh homeserver 'mkdir -p ~/tmp/sg-build'
rsync -a --delete --exclude .git --exclude .gradle --exclude build ./ homeserver:/home/bombo/tmp/sg-build/
ssh homeserver 'cd ~/tmp/sg-build && docker build --progress=plain -f docker/Dockerfile --target test -t sg-check:test . 2>&1 | tail -5'
ssh homeserver 'cd ~/tmp/sg-build && docker build -q -f docker/Dockerfile --target runtime --build-arg MODULE=api -t sg-check:api . && docker run --rm --entrypoint sh sg-check:api -c "whoami; which wget"'
```

Expected: test 스테이지 `BUILD SUCCESSFUL`. 실행 결과 `app`, `/usr/bin/wget`.

- [ ] **Step 3: MODULE 누락 시 실패 확인**

```bash
ssh homeserver 'cd ~/tmp/sg-build && docker build -q -f docker/Dockerfile --target runtime . 2>&1 | grep -c "MODULE 빌드 인자가 필요하다"'
```

Expected: `1` 이상.

- [ ] **Step 4: 홈서버 정리**

```bash
ssh homeserver 'rm -rf ~/tmp/sg-build && rmdir ~/tmp 2>/dev/null; docker image rm sg-check:test sg-check:api'
```

- [ ] **Step 5: 커밋 (git-master 위임)**

대상: `docker/Dockerfile`, `.dockerignore`. 메시지: `SG-1 빌드·실행 Docker 이미지 추가`

---

### Task 5: 배포용 compose

**Files:**
- Create: `deploy/compose.yml`

**Interfaces:**
- Consumes: 이미지 `stock-guide-api:<태그>`, `stock-guide-scheduler:<태그>` (Task 7에서 빌드). 외부 네트워크 `db`. 환경 파일 `${HOME}/postgres/stock_guide.env` (러너 사용자의 홈. 홈서버는 `/home/bombo`).
- Produces: compose 프로젝트 이름 `stock-guide`. 태그 변수 `IMAGE_TAG`(기본 `latest`). 컨테이너 `stock-guide-api`, `stock-guide-scheduler`.

- [ ] **Step 1: compose 파일 작성**

`deploy/compose.yml`:

```yaml
# stock-guide 배포 정의. CD(.github/workflows/cd.yml)가 홈서버에서 실행한다.
# DB는 공용 스택(~/postgres, shared-postgres)을 쓴다. 이 파일에 PostgreSQL을 넣지 않는다.
# 배포 대상 서버는 모두 ${HOME}/postgres/stock_guide.env 를 둔다.
# 수동 롤백: IMAGE_TAG=<이전 커밋 SHA> docker compose -f deploy/compose.yml up -d --wait
name: stock-guide

services:
  api:
    image: stock-guide-api:${IMAGE_TAG:-latest}
    container_name: stock-guide-api
    restart: unless-stopped
    env_file: ${HOME}/postgres/stock_guide.env
    ports:
      - "127.0.0.1:8080:8080"
    mem_limit: 512m
    networks: [db]
    healthcheck:
      test: ["CMD", "wget", "-qO-", "http://localhost:8080/actuator/health/readiness"]
      interval: 10s
      timeout: 3s
      retries: 3
      start_period: 60s

  scheduler:
    image: stock-guide-scheduler:${IMAGE_TAG:-latest}
    container_name: stock-guide-scheduler
    restart: unless-stopped
    env_file: ${HOME}/postgres/stock_guide.env
    mem_limit: 384m
    networks: [db]
    healthcheck:
      test: ["CMD", "wget", "-qO-", "http://localhost:8081/actuator/health/readiness"]
      interval: 10s
      timeout: 3s
      retries: 3
      start_period: 60s

networks:
  db:
    external: true
```

- [ ] **Step 2: 홈서버에서 문법 확인**

비밀값이 출력되지 않도록 서비스 이름만 출력한다.

```bash
scp deploy/compose.yml homeserver:/home/bombo/sg-compose-check.yml
ssh homeserver 'docker compose -f ~/sg-compose-check.yml config --services; docker compose -f ~/sg-compose-check.yml config -q && echo VALID; rm ~/sg-compose-check.yml'
```

Expected: `api`, `scheduler`, `VALID`.

- [ ] **Step 3: 커밋 (git-master 위임)**

대상: `deploy/compose.yml`. 메시지: `SG-1 홈서버 배포 compose 추가`

---

### Task 6: CI 워크플로

**Files:**
- Create: `.github/workflows/ci.yml`
- Modify: `.github/workflows/runner-check.yml` (`push` 트리거 제거)
- GitHub 설정: main 룰셋 (PR 필수, `build` 체크 필수)

**Interfaces:**
- Consumes: Task 4의 `docker/Dockerfile` 스테이지 `test`, `runtime`.
- Produces: GitHub 체크 컨텍스트 `build`. main 룰셋 `main 보호`.

- [ ] **Step 1: `ci.yml` 작성**

```yaml
name: ci

on:
  pull_request:
    branches: [main]

permissions:
  contents: read

concurrency:
  group: ci-${{ github.ref }}
  cancel-in-progress: true

jobs:
  build:
    runs-on: [self-hosted, homeserver]
    timeout-minutes: 20
    steps:
      - uses: actions/checkout@v4

      - name: 린트와 테스트
        run: docker build --progress=plain -f docker/Dockerfile --target test .

      - name: 실행 이미지 빌드
        run: |
          for module in api scheduler; do
            docker build --progress=plain -f docker/Dockerfile --target runtime \
              --build-arg MODULE="$module" -t "stock-guide-$module:ci" .
          done
```

- [ ] **Step 2: `runner-check.yml`에서 push 트리거 제거**

변경 전:

```yaml
on:
  push:
  workflow_dispatch:
```

변경 후:

```yaml
on:
  workflow_dispatch:
```

- [ ] **Step 3: 커밋, push, draft PR 생성 (git-master 위임)**

대상: 두 워크플로 파일. 메시지: `SG-1 CI 워크플로 추가, runner-check를 수동 실행으로 변경`. push 대상 `origin SG-1/ci-cd`.
CI는 PR에서만 돌므로 `SG-1/ci-cd` → `main` **draft** PR을 만든다. 제목: `SG-1 백엔드 골격과 홈서버 CI/CD`. 본문 끝에 `🤖 Generated with [Claude Code](https://claude.com/claude-code)`.

- [ ] **Step 4: CI 결과 확인**

```bash
gh pr checks SG-1/ci-cd --repo bombo-dev/STOCK_GUIDE --watch
```

Expected: `build` 통과. `runner-check`는 새로 실행되지 않는다.

- [ ] **Step 5: main 룰셋 설정**

`ruleset.json` (스크래치 디렉토리에 만든다. 레포에 넣지 않는다):

```json
{
  "name": "main 보호",
  "target": "branch",
  "enforcement": "active",
  "conditions": { "ref_name": { "include": ["~DEFAULT_BRANCH"], "exclude": [] } },
  "rules": [
    { "type": "deletion" },
    { "type": "non_fast_forward" },
    {
      "type": "pull_request",
      "parameters": {
        "required_approving_review_count": 0,
        "dismiss_stale_reviews_on_push": false,
        "require_code_owner_review": false,
        "require_last_push_approval": false,
        "required_review_thread_resolution": false
      }
    },
    {
      "type": "required_status_checks",
      "parameters": {
        "strict_required_status_checks_policy": false,
        "required_status_checks": [{ "context": "build" }]
      }
    }
  ]
}
```

```bash
gh api -X POST repos/bombo-dev/STOCK_GUIDE/rulesets --input ruleset.json -q '{id, name, enforcement}'
gh api repos/bombo-dev/STOCK_GUIDE/rules/branches/main -q '.[].type'
```

Expected: `deletion`, `non_fast_forward`, `pull_request`, `required_status_checks`.

---

### Task 7: CD 워크플로

**Files:**
- Create: `.github/workflows/cd.yml`

**Interfaces:**
- Consumes: Task 4의 Dockerfile, Task 5의 `deploy/compose.yml`(`IMAGE_TAG`, 프로젝트 `stock-guide`).
- Produces: 이미지 태그 `stock-guide-{api,scheduler}:<github.sha>`와 `:latest`. 실행 중인 컨테이너 `stock-guide-api`, `stock-guide-scheduler`.

- [ ] **Step 1: `cd.yml` 작성**

```yaml
name: cd

on:
  workflow_dispatch:
    inputs:
      server:
        description: 배포할 서버 (러너 라벨)
        type: choice
        required: true
        options:
          - homeserver

permissions:
  contents: read

concurrency:
  group: deploy-${{ inputs.server }}
  cancel-in-progress: false

jobs:
  deploy:
    runs-on: [self-hosted, "${{ inputs.server }}"]
    timeout-minutes: 20
    env:
      IMAGE_TAG: ${{ github.sha }}
    steps:
      - uses: actions/checkout@v4

      # 수동 실행은 main이 아닌 브랜치에서도 가능하다. 배포 전에 다시 검증한다.
      - name: 린트와 테스트
        run: docker build --progress=plain -f docker/Dockerfile --target test .

      - name: 이미지 빌드
        run: |
          for module in api scheduler; do
            docker build --progress=plain -f docker/Dockerfile --target runtime \
              --build-arg MODULE="$module" \
              -t "stock-guide-$module:$IMAGE_TAG" -t "stock-guide-$module:latest" .
          done

      - name: 배포
        run: docker compose -f deploy/compose.yml up -d --wait --wait-timeout 180

      - name: 실패 시 상태 출력
        if: failure()
        run: |
          docker compose -f deploy/compose.yml ps
          docker compose -f deploy/compose.yml logs --tail 100
```

- [ ] **Step 2: 워크플로 문법 확인**

```bash
ruby -ryaml -e 'ARGV.each { |f| YAML.load_file(f); puts "ok #{f}" }' .github/workflows/*.yml
```

Expected: 파일마다 `ok`.

- [ ] **Step 3: 커밋하고 push (git-master 위임)**

대상: `.github/workflows/cd.yml`. 메시지: `SG-1 서버 선택형 CD 워크플로 추가`. push 대상 `origin SG-1/ci-cd`. CD는 수동 실행만 있으므로 실행되지 않고, 열린 PR의 CI만 다시 돈다.

- [ ] **Step 4: CI 재확인**

Run: `gh pr checks SG-1/ci-cd --repo bombo-dev/STOCK_GUIDE --watch`
Expected: `build` 통과.

---

### Task 8: PR, 머지 후 배포 검증

**Files:**
- Modify: 없음 (검증만)

**Interfaces:**
- Consumes: Task 1–7 전체.

- [ ] **Step 1: 로컬 품질 게이트**

Run: `./gradlew --console=plain formatKotlin lintKotlin build`
Expected: `BUILD SUCCESSFUL`, 테스트 4개 통과. `git status`에 포맷 변경이 없어야 한다.

- [ ] **Step 2: PR을 리뷰 가능 상태로 전환 (git-master 위임)**

Task 6의 draft PR 본문에 완료 기준 4개를 체크리스트로 넣고 ready for review로 바꾼다. **머지하지 않는다.** PR 링크를 사용자에게 전달한다.

- [ ] **Step 3: 사용자가 머지한 뒤 CD 실행과 결과 확인**

`workflow_dispatch`는 워크플로 파일이 기본 브랜치에 있어야 실행된다. 머지 후 실행한다.

```bash
gh workflow run cd --repo bombo-dev/STOCK_GUIDE --ref main -f server=homeserver
sleep 5
gh run watch --repo bombo-dev/STOCK_GUIDE --exit-status $(gh run list --repo bombo-dev/STOCK_GUIDE --workflow cd --limit 1 --json databaseId -q '.[0].databaseId')
```

Expected: `cd` 실행 성공.

- [ ] **Step 4: 완료 기준 검증**

```bash
ssh homeserver 'docker ps --filter name=stock-guide --format "{{.Names}} {{.Status}}"'
ssh homeserver 'curl -s http://127.0.0.1:8080/actuator/health/readiness; echo; docker exec stock-guide-scheduler wget -qO- http://localhost:8081/actuator/health/readiness; echo'
ssh homeserver 'docker inspect -f "{{.State.StartedAt}}" shared-postgres'
```

Expected:
- 두 컨테이너가 `(healthy)`.
- 두 readiness 응답이 `{"status":"UP"}`. readiness에 `db`가 들어 있으므로 DB 접속까지 확인된다.
- `shared-postgres`의 StartedAt이 배포 시각보다 이전이다 (CD가 DB를 재시작하지 않았다).
