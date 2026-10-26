# 백엔드 골격 + CI/CD 설계

- 작성일: 2026-10-23
- 이슈: SG-1
- 레포: bombo-dev/STOCK_GUIDE (백엔드 전용. 프론트엔드는 별도 레포)
- 도메인 문서: `004_WORKSPACE/wiki/asset-management/`

## 목표

빌드만 되는 최소 백엔드를 만들고, 홈서버 self-hosted runner로 CI와 CD를 끝까지 돌린다.
기능 코드는 이 범위에 없다.

## 완료 기준

1. PR에서 CI(lint, test, build)가 통과한다. CI가 실패하면 main 머지가 막힌다.
2. 서버를 골라 수동 실행한 CD가 그 서버에 api와 scheduler 컨테이너를 띄운다.
3. 두 앱의 actuator health가 UP이다. health는 `stock_guide` DB 접속을 포함한다.
4. CD는 공용 PostgreSQL(`shared-postgres`)을 재시작하지 않는다.

## 기술 버전

| 항목 | 버전 | 근거 |
|------|------|------|
| JDK | 25 | 사용자 결정 |
| Kotlin | 2.3.21 | start.spring.io가 Boot 4.1.1과 짝짓는 버전 (2.4.x는 미검증) |
| Spring Boot | 4.1.1 | 2026-10-23 start.spring.io 기본값 |
| Gradle | 9.8.2 | 2026-10-23 최신 |
| 린트 | kotlinter (`formatKotlin`, `lintKotlin`) | 전역 CLAUDE.md |
| 이미지 | `eclipse-temurin:25-jdk` 빌드, `25-jre-alpine` 실행 | 홈서버에 JDK 미설치 |
| 빌더 | Docker BuildKit (`docker-buildx` 0.30.1) | Gradle 캐시 마운트. 2026-10-23 홈서버에 설치 |

## 모듈 구조

```
api ─────────┐
             ├──▶ application ──▶ domain ◀── infra
scheduler ───┘
api, scheduler ──implementation──▶ infra
```

| 모듈 | 역할 | 의존 |
|------|------|------|
| `domain` | 핵심 모델, 규칙, 포트(인터페이스). Spring 의존 없음 | 없음 |
| `application` | 유스케이스 | `domain` |
| `infra` | 포트 구현. DB, 외부 API 클라이언트 | `domain` |
| `api` | Spring Boot 앱. REST 진입점 | `application`, `infra` |
| `scheduler` | Spring Boot 앱. 스케줄 트리거 진입점 | `application`, `infra` |

- `api`, `scheduler`는 `infra`를 `implementation`으로 의존한다 (사용자 결정).
- 골격의 `infra`는 PostgreSQL 드라이버와 DataSource 설정만 가진다.
- 골격에는 테스트를 두지 않는다 (2026-10-26 사용자 결정). 앱 기동과 DB 연결은 CD의 컨테이너 healthcheck(readiness)로 확인한다. 첫 기능 코드부터 테스트를 쓴다.
- 엔티티가 없으므로 마이그레이션 도구는 넣지 않는다. 첫 테이블을 만들 때 정한다. FK 제약은 쓰지 않는다.

## 레포 배치

```
settings.gradle.kts
build.gradle.kts           # 플러그인 버전은 루트 plugins 블록에 둔다
domain/  application/  infra/  api/  scheduler/
docker/Dockerfile          # --build-arg MODULE=api|scheduler
deploy/compose.yml         # api, scheduler만. DB 없음
.github/workflows/ci.yml
.github/workflows/cd.yml
.github/workflows/runner-check.yml   # 수동 실행만
```

## 실행 환경 (홈서버)

| 항목 | api | scheduler |
|------|-----|-----------|
| 컨테이너 | `stock-guide-api` | `stock-guide-scheduler` |
| 포트 | `127.0.0.1:8080` | 공개 안 함 |
| 메모리 제한 | 512MB | 384MB |
| 네트워크 | 외부 네트워크 `db` | 외부 네트워크 `db` |
| 재시작 | `unless-stopped` | `unless-stopped` |
| 헬스체크 | `/actuator/health/readiness` (컨테이너 내부 `wget`) | 같음 |

- DB 접속 정보는 `${HOME}/postgres/stock_guide.env`를 `env_file`로 읽는다 (홈서버는 `/home/bombo/postgres/stock_guide.env`).
  배포 대상 서버는 모두 이 경로에 같은 키로 파일을 둔다.
  키: `STOCK_GUIDE_DB_URL`, `STOCK_GUIDE_DB_USER`, `STOCK_GUIDE_DB_PASSWORD`.
- compose 파일에 PostgreSQL을 넣지 않는다. 넣으면 배포가 공용 DB를 재시작한다.
- 공개 도메인과 HTTPS는 이 범위에 없다.

## CI (`ci.yml`)

- 실행: main 대상 `pull_request`
- 러너: `homeserver` (러너 라벨 하나로 지정)
- 머지 차단: main 룰셋이 `build` 체크 통과와 PR을 요구한다 (2026-10-26 사용자 결정, 레포 public 전환)
- 단계: Docker 빌드의 `test` 스테이지에서 `./gradlew lintKotlin test`, 이어서 두 앱의 `runtime` 이미지 빌드
- Gradle 캐시: BuildKit 캐시 마운트

## CD (`cd.yml`)

- 실행: 수동 실행(`workflow_dispatch`)만. 입력 `server`로 배포 대상 서버를 고른다 (현재 `homeserver` 하나)
- 러너: `<server>` 라벨. 서버마다 그 이름을 라벨로 단 러너를 둔다
- 동시 실행 방지: `concurrency: deploy-<server>`, 진행 중 배포는 취소하지 않는다
- 단계
  1. `test` 스테이지를 다시 돌린다 (수동 실행은 main이 아닌 브랜치에서도 가능하다).
  2. api, scheduler 이미지를 커밋 SHA 태그와 `latest` 태그로 빌드한다.
  3. `deploy/compose.yml`로 `docker compose up -d --wait`를 실행한다.
  4. 두 컨테이너가 healthy가 될 때까지 기다린다. 제한 시간 안에 안 되면 실패로 끝낸다.
- 롤백: 직전 SHA 이미지 태그로 `up -d`. 자동 롤백은 범위에 없다.
- 서버 추가: 러너를 서버 이름 라벨로 등록하고, `cd.yml`의 `server` 선택지에 이름을 더한다.

## 코드 규칙

기준: `~/Documents/0000_CLAUDE_WORKSPACE/rules/` (`kotlin-style.md`, `spring-boot.md`, `kotlin-junit5-test.md`)

- `.editorconfig`는 `0000_CLAUDE_WORKSPACE/templates/kotlin/.editorconfig`를 복사해 커밋한다 (심링크 금지).
- Kotlin 수정 후 `formatKotlin` → `lintKotlin` 순서로 실행한다. 규칙을 끄거나 suppress하지 않는다.
- 설정은 `@ConfigurationProperties` + `data class`로 바인딩한다. 시간은 `Clock` 빈을 주입한다.
- 테스트: 영문 메서드명 + 한글 `@DisplayName`, `@Nested`는 `inner class`,
  `src/test/resources/junit-platform.properties`에 PER_CLASS 라이프사이클.
- 테스트는 범위에 맞는 슬라이스를 쓴다. `@SpringBootTest`는 통합 테스트에만 쓴다.
- JPA all-open 설정은 첫 엔티티를 만들 때 추가한다 (골격에는 엔티티 없음).

## 사전 검증 (2026-10-23, 프로토타입)

- 로컬: `formatKotlin`, `lintKotlin`, `build` 통과. 테스트 4개 통과.
- 홈서버: `test` 스테이지 1분 32초. 캐시가 있으면 `runtime` 이미지 22초.
- 실행 이미지 363MB, 사용자 `app`, `wget` 있음.
- 공용 DB 접속 시 readiness UP, 기동 4.5초, 메모리 약 200MiB.

## 보안

- 레포는 public이다 (2026-10-26 전환. 무료 플랜에서 머지 차단을 쓰기 위해서). 전환 전 이력에 비밀값이 없음을 확인했다.
- self-hosted runner는 PR 코드를 홈서버에서 실행한다. 외부 기여자의 PR 워크플로는 모두 승인을 받아야 실행된다
  (`fork-pr-contributor-approval: all_external_contributors`). 외부 PR의 워크플로 실행을 승인하지 않는다.
- 워크플로 기본 토큰 권한은 읽기 전용이다. 워크플로에 `secrets`를 쓰지 않는다.
- DB 비밀번호는 레포와 워크플로 로그에 넣지 않는다.
- `stock_guide` 계정은 `mail_routing` DB에 접속할 수 없다 (2026-10-23 검증).

## 범위 밖

- 기능 코드 (수집, 집계, 점수화, 로그인)
- 공개 도메인, 리버스 프록시, HTTPS
- 프론트엔드 레포와 그 러너
- 자동 롤백
