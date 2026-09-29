# 과제 배포(assignment) — 아키텍처

- 대상 모듈: `legacy/assignment-thymeleaf` (Spring Boot 3.3.4 + Thymeleaf + JdbcTemplate, compose 프로필 `thymeleaf`, 호스트 포트 8082)
- **분석 범위**: "새 배포" 화면 하나 — `GET /distributions/new` 와 이 화면의 폼이 제출하는 `POST /distributions`, 그리고 두 핸들러가 호출하는 코드까지. 배포 목록(`GET /distributions`), 재배포(`POST /distributions/{id}/redistribute`), 과제 목록(`GET /assignments`)은 읽지 않았거나 범위 밖이다.
- 경로 표기: 모듈 폴더 기준. Java 파일은 `src/main/java/com/example/assign/` 를, 템플릿은 `src/main/resources/` 를 생략해 적는다(예: `web/DistributionController.java`, `templates/distribution_form.html`).
- 모듈 밖 파일 별칭: `schema` = `db/mariadb/init/01-schema.sql`, `seed` = `db/mariadb/init/02-seed.sql`, `compose` = `docker-compose.yml`

## 1. 진입점

| # | 요청 | 핸들러 | 뷰 / 결과 | 근거 |
|---|------|--------|-----------|------|
| E1 | `GET /distributions/new` | `DistributionController#form` | `distribution_form` 템플릿 렌더 | `web/DistributionController.java:40-47` |
| E2 | `POST /distributions` (파라미터 `assignmentId`, `classId` — 둘 다 필수 `long`) | `DistributionController#create` | 성공 → `redirect:/distributions`, 실패 → `redirect:/distributions/new` + flash `error` | `web/DistributionController.java:49-77` |

### E1 화면 구성 (`templates/distribution_form.html`)

- 폼: `method="post"`, `th:action="@{/distributions}"` → E2 로 제출 (`:8`).
- 과제 선택 `assignmentId` (`required`, `:11`): 모델 `assignments` 를 option 으로 나열, 마감 지남 + 상태가 `X` 아님이면 `disabled` (`:14-18`).
- 학급 선택 `classId` (`required`, `:23`): 모델 `classes` 를 `이름 (teacher_id)` 로 나열 (`:25`).
- 공통 조각: `layout :: head`, `layout :: nav(${menu})`, `layout :: flash`, `layout :: footer` (`:3`, `:5`, `:7`, `:32`). flash 조각은 `message` · `error` · `warnings` 를 표시한다(`templates/layout.html:28-33`).

### E1 모델 속성 (`web/DistributionController.java:42-45`)

| 속성 | 값 |
|------|----|
| `assignments` | `assignmentDao.findAllForSelect()` |
| `classes` | `classDao.findAll()` |
| `now` | `AppClock.now()` |
| `menu` | `"new"` (nav 의 "새 배포" 강조, `templates/layout.html:26`) |

### E2 처리 순서 (`web/DistributionController.java:53-76`)

1. `assignmentDao.findById(assignmentId)` — 없으면 flash `error` 후 폼으로 (`:53-57`).
2. `AppClock.now()` 와 마감 비교 — 마감 지남 + 상태 `X` 아님이면 flash `error` 후 폼으로 (`:58-65`).
3. `distributionService.distribute(assignmentId, classId)` 호출 (`:67`).
   - 성공: flash `message` "배포가 등록되었습니다. (배포 #id)" 후 배포 목록으로 (`:68`, `:76`).
   - `IllegalArgumentException` · `IllegalStateException`: 예외 메시지를 flash `error` 로 (`:69-71`).
   - `DataAccessException`: "DB 오류로 배포에 실패했습니다." (`:72-74`).

## 2. 의존 관계

### 호출 그래프 (범위 안)

```
E1 DistributionController#form            (web/DistributionController.java:40)
 ├─ AssignmentDao#findAllForSelect         (:42 → dao/AssignmentDao.java:34-39)
 ├─ ClassDao#findAll                       (:43 → dao/ClassDao.java:20-22)
 └─ AppClock.now                           (:44 → AppClock.java:14-20)

E2 DistributionController#create          (web/DistributionController.java:49)
 ├─ AssignmentDao#findById                 (:53 → dao/AssignmentDao.java:41-48)
 ├─ AppClock.now / AppClock.fmt            (:58, :62 → AppClock.java:14-20, :22-27)
 └─ DistributionService#distribute         (:67 → service/DistributionService.java:46-68, @Transactional)
     ├─ AssignmentDao#findById             (DistributionService.java:48)
     ├─ ClassDao#findById                  (DistributionService.java:52 → dao/ClassDao.java:24-30)
     ├─ DistributionDao#countByAssignmentAndClass (DistributionService.java:59 → dao/DistributionDao.java:47-52)
     ├─ DistributionDao#nextId             (DistributionService.java:62 → dao/DistributionDao.java:54-57)
     ├─ AppClock.now                       (DistributionService.java:63)
     └─ DistributionDao#insert             (DistributionService.java:63 → dao/DistributionDao.java:59-63)
```

- `AssignmentDao#findById` 는 E2 한 요청 안에서 두 번 불린다: 컨트롤러(`web/DistributionController.java:53`, 트랜잭션 밖)와 서비스(`service/DistributionService.java:48`, 트랜잭션 안).
- 컨트롤러가 서비스뿐 아니라 DAO(`AssignmentDao`, `ClassDao`)도 직접 주입받아 호출한다(`web/DistributionController.java:22-30`).

### 공통 코드

| 코드 | 역할 | 근거 |
|------|------|------|
| `AppClock` | 현재 시각. 시스템 속성 `app.clock=system` 일 때만 실제 시각, 그 밖에는 고정값 `2026-09-15 00:00:00` 을 돌려준다. `fmt` 는 `yyyy-MM-dd HH:mm` 포맷 | `AppClock.java:9`, `:14-20`, `:22-27` |
| `DbErrorAdvice` | `@ControllerAdvice`. 핸들러 밖으로 나온 `DataAccessException` 을 `db_error` 화면으로 바꾸고 `System.out` 으로 원인을 찍는다 | `web/DbErrorAdvice.java:11-17` |
| `templates/layout.html` | head · nav · flash · footer 조각 | `templates/layout.html:3`, `:23`, `:28`, `:35` |

- E1 의 DAO 호출과 E2 의 첫 `findById`(`web/DistributionController.java:53`)는 `try` 밖이라 DB 예외가 나면 `DbErrorAdvice` 로 간다. E2 의 `distribute` 호출(`:66-75`)은 컨트롤러가 `DataAccessException` 을 직접 잡아 flash 로 바꾼다.
- 운영 compose 는 `app.clock` 을 넘기지 않는다(`Dockerfile:11` 의 `ENTRYPOINT` 에 `-Dapp.clock` 없음, `compose` 의 `assignment` 서비스 environment 에도 없음). 따라서 컨테이너에서는 항상 `2026-09-15 00:00:00` 기준으로 동작한다.

### 외부 라이브러리 (`build.gradle:24-30`)

| 의존성 | 용도 |
|--------|------|
| `spring-boot-starter-web` | MVC 컨트롤러, `RedirectAttributes` flash |
| `spring-boot-starter-thymeleaf` | 화면 템플릿 (`#temporals` 사용: `templates/distribution_form.html:17`) |
| `spring-boot-starter-jdbc` | `JdbcTemplate`, `@Transactional`, `DataAccessException` |
| `mariadb-java-client` (runtimeOnly) | JDBC 드라이버 |

- Java 17 (`build.gradle:10-17`), 버전 `1.4.2` (`build.gradle:8`, 화면 footer 문구도 `v1.4.2`: `templates/layout.html:35`).

### DB 연결 (`src/main/resources/application.properties`)

| 항목 | 값 | 줄 |
|------|----|----|
| URL | `${SPRING_DATASOURCE_URL:jdbc:mariadb://localhost:3306/itembank}` | `:2` |
| 사용자 / 비밀번호 | `${SPRING_DATASOURCE_USERNAME:app}` / `${SPRING_DATASOURCE_PASSWORD:app-pass}` | `:3-4` |
| Hikari 최대 풀 | 4 | `:6` |
| 연결 대기 | 5000 ms | `:7` |

- compose 에서는 `jdbc:mariadb://mariadb:3306/itembank` 로 덮어쓴다(`compose` 의 `assignment` 서비스). 문항 은행(PHP) · modern 과 같은 MariaDB `itembank` DB 를 공유한다(`schema:73`).
- 범위 안의 DB 접근은 전부 `JdbcTemplate` + `?` 바인딩이다. JPA · ORM 은 없다.
