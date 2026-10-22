# 백엔드 골격 + CI/CD 설계

- 작성일: 2026-10-23
- 이슈: SG-1
- 레포: bombo-dev/STOCK_GUIDE (백엔드 전용. 프론트엔드는 별도 레포)
- 도메인 문서: `004_WORKSPACE/wiki/asset-management/`

## 목표

빌드만 되는 최소 백엔드를 만들고, 홈서버 self-hosted runner로 CI와 CD를 끝까지 돌린다.
기능 코드는 이 범위에 없다.

## 완료 기준

1. PR에서 CI(lint, test, build)가 통과한다.
2. main 머지 후 CD가 홈서버에 api와 scheduler 컨테이너를 띄운다.
3. 두 앱의 actuator health가 UP이다. health는 `stock_guide` DB 접속을 포함한다.
4. CD는 공용 PostgreSQL(`shared-postgres`)을 재시작하지 않는다.

## 기술 버전

| 항목 | 버전 | 근거 |
|------|------|------|
| JDK | 25 | 사용자 결정 |
| Kotlin | 2.4.21 | 2026-10-23 최신 릴리스 |
| Spring Boot | 4.1.1 | 2026-10-23 start.spring.io 기본값 |
| Gradle | 9.8.2 | 2026-10-23 최신 |
| 린트 | kotlinter (`formatKotlin`, `lintKotlin`) | 전역 CLAUDE.md |
| 이미지 | `eclipse-temurin:25-jdk` 빌드, `25-jre-alpine` 실행 | 홈서버에 JDK 미설치 |

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
- 엔티티가 없으므로 마이그레이션 도구는 넣지 않는다. 첫 테이블을 만들 때 정한다. FK 제약은 쓰지 않는다.

## 레포 배치

```
settings.gradle.kts
build.gradle.kts
gradle/libs.versions.toml
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
| 헬스체크 | `/actuator/health` | `/actuator/health` (컨테이너 내부) |

- DB 접속 정보는 `/home/bombo/postgres/stock_guide.env`를 `env_file`로 읽는다.
  키: `STOCK_GUIDE_DB_URL`, `STOCK_GUIDE_DB_USER`, `STOCK_GUIDE_DB_PASSWORD`.
- compose 파일에 PostgreSQL을 넣지 않는다. 넣으면 배포가 공용 DB를 재시작한다.
- 공개 도메인과 HTTPS는 이 범위에 없다.

## CI (`ci.yml`)

- 실행: `pull_request`, main이 아닌 브랜치 `push`
- 러너: `[self-hosted, homeserver]`
- 단계: Docker 빌드의 `test` 스테이지에서 `./gradlew lintKotlin test build`
- Gradle 캐시: BuildKit 캐시 마운트

## CD (`cd.yml`)

- 실행: main `push`, 수동 실행
- 동시 실행 방지: `concurrency: deploy-homeserver`, 진행 중 배포는 취소하지 않는다
- 단계
  1. api, scheduler 이미지를 커밋 SHA 태그와 `latest` 태그로 빌드한다.
  2. `deploy/compose.yml`로 `docker compose up -d`를 실행한다.
  3. 두 컨테이너가 healthy가 될 때까지 기다린다. 제한 시간 안에 안 되면 실패로 끝낸다.
- 롤백: 직전 SHA 이미지 태그로 `up -d`. 자동 롤백은 범위에 없다.

## 보안

- 레포는 private이다. self-hosted runner는 push 권한자의 코드를 홈서버에서 실행한다.
- DB 비밀번호는 레포와 워크플로 로그에 넣지 않는다.
- `stock_guide` 계정은 `mail_routing` DB에 접속할 수 없다 (2026-10-23 검증).

## 범위 밖

- 기능 코드 (수집, 집계, 점수화, 로그인)
- 공개 도메인, 리버스 프록시, HTTPS
- 프론트엔드 레포와 그 러너
- 자동 롤백
