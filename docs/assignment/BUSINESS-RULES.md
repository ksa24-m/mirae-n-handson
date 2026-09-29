# 과제 배포(assignment) — 비즈니스 규칙 후보

- 대상 모듈: `legacy/assignment-thymeleaf`
- **분석 범위**: "새 배포" 화면 하나 — `GET /distributions/new`(E1)와 `POST /distributions`(E2), 그리고 두 핸들러가 호출하는 코드까지. 재배포 · 배포 목록 · 과제 목록의 규칙은 여기 없다(재배포 규칙은 `service/DistributionService.java:70` 이후, 범위 밖).
- 경로 표기: Java 는 `src/main/java/com/example/assign/`, 템플릿은 `src/main/resources/` 를 생략한다.
- 모듈 밖 파일 별칭: `schema` = `db/mariadb/init/01-schema.sql`, `seed` = `db/mariadb/init/02-seed.sql`
- 이 모듈에 없음: 배포 수정 · 삭제 화면, 과제 · 학급 등록, 권한(교사별 학급 제한) 검사. 학급 목록은 `teacher_id` 를 표시만 하고 거르지 않는다.

## 1. 선택 목록 (E1)

### BR-01 배포 가능 과제 목록은 상태 O · X · C 만 보여 준다

- 규칙: 새 배포 화면의 과제 선택 목록에는 `status` 가 `O`, `X`, `C` 인 과제만 나오고, 그 밖의 상태(`D` 포함)는 목록에서 빠진다.
- 근거: `dao/AssignmentDao.java:36`, `web/DistributionController.java:42`
- 근거 코드:
  ```java
                  + " WHERE a.status IN ('O', 'X', 'C') "
  ```
- 확신도: 확실
- 비고: 허용 목록(화이트리스트) 방식이다. 서버 검증(BR-08)은 `D` 만 막는 차단 목록 방식이라 둘이 다르다(6절 표). `schema` 가 `utf8mb4_unicode_ci` 라 소문자 `x` · `o` · `c` 도 이 목록에 걸릴 수 있다 — 추정, 실제 데이터에 소문자 상태가 있는지 DB 조회로 확인 필요.

### BR-02 과제 목록은 마감 빠른 순, 같으면 id 오름차순이다

- 규칙: 과제 선택 목록은 `due_at` 오름차순으로, 마감이 같으면 `id` 오름차순으로 정렬한다.
- 근거: `dao/AssignmentDao.java:37`
- 근거 코드:
  ```java
                  + " ORDER BY a.due_at ASC, a.id ASC";
  ```
- 확신도: 확실
- 비고: 마감 지난(비활성) 과제도 정렬 순서대로 섞여 나온다. 따로 뒤로 보내지 않는다.

### BR-03 학급 목록은 전체 학급을 id 오름차순으로 보여 준다

- 규칙: 학급 선택 목록은 조건 없이 모든 학급을 `id` 오름차순으로 보여 준다.
- 근거: `dao/ClassDao.java:21`, `web/DistributionController.java:43`
- 근거 코드:
  ```java
          return jdbc.query("SELECT id, name, teacher_id FROM `class` ORDER BY id ASC", MAPPER);
  ```
- 확신도: 확실
- 비고: 로그인 교사 기준 필터가 없다(이 모듈에 로그인 · 권한 없음).

### BR-04 마감이 지난 과제는 선택지에서 비활성화한다 (상태 X 는 예외)

- 규칙: `dueAt` 이 `now` 보다 앞서고 상태가 `X` 가 아니면 option 을 `disabled` 로 그리고 ` [마감]` 을, 마감이 지났어도 상태가 `X` 면 선택 가능하게 두고 ` [연장]` 을 붙인다.
- 근거: `templates/distribution_form.html:16`, `templates/distribution_form.html:18`
- 근거 코드:
  ```html
                      th:disabled="${a.dueAt != null and a.dueAt.isBefore(now) and a.status != 'X'}"
                               + (${a.dueAt != null and a.dueAt.isBefore(now)} ? (${a.status == 'X'} ? ' [연장]' : ' [마감]') : '')"></option>
  ```
- 확신도: 확실
- 비고: 화면 쪽 제한일 뿐이고 서버 검증은 BR-06 이다. `now` 는 `AppClock.now()`(BR-11). 마감 시각과 `now` 가 정확히 같으면 `isBefore` 가 거짓이라 선택 가능하다. 옵션 문구의 마감 표시는 `MM-dd HH:mm`(연도 없음, `:17`).

## 2. 배포 등록 검증 (E2)

### BR-05 존재하지 않는 과제는 배포할 수 없다

- 규칙: `assignmentId` 로 과제를 찾지 못하면 배포하지 않고 "존재하지 않는 과제입니다." 오류와 함께 새 배포 화면으로 돌려보낸다.
- 근거: `web/DistributionController.java:53-57`, `service/DistributionService.java:48-51`
- 근거 코드:
  ```java
          AssignmentRow a = assignmentDao.findById(assignmentId);
          if (a == null) {
              ra.addFlashAttribute("error", "존재하지 않는 과제입니다.");
  ```
- 확신도: 확실
- 비고: 컨트롤러와 서비스에 두 번 구현돼 있고 메시지가 다르다(서비스는 `(id=…)` 를 붙임). 컨트롤러가 먼저 걸러서 서비스 쪽 메시지는 두 조회 사이에 과제가 지워진 경우에만 보인다. `findById` 는 상태 조건이 없어 `D` 과제도 찾는다(`dao/AssignmentDao.java:42`).

### BR-06 마감이 지난 과제는 새로 배포할 수 없다 (상태 X 만 예외)

- 규칙: 과제의 `dueAt` 이 현재 시각보다 앞서고 상태가 `X` 가 아니면 배포를 거부하고 "마감(yyyy-MM-dd HH:mm)이 지난 과제는 배포할 수 없습니다. [제목]" 오류를 낸다.
- 근거: `web/DistributionController.java:58-64`
- 근거 코드:
  ```java
          if (a.getDueAt() != null && a.getDueAt().isBefore(now)) {
              if (!"X".equals(a.getStatus())) {
                  ra.addFlashAttribute("error", "마감(" + AppClock.fmt(a.getDueAt()) + ")이 지난 과제는 배포할 수 없습니다. [" + a.getTitle() + "]");
  ```
- 확신도: 확실
- 비고: 이 검사는 컨트롤러에만 있고 `DistributionService#distribute` 에는 없다. 판정 기준은 `status` 가 아니라 `due_at` 이다 — `C`(schema 주석상 "마감") 상태라도 `due_at` 이 미래면 통과한다. 마감 시각과 같은 시각은 통과(`isBefore`). `X` 비교는 대소문자를 구분한다. `dueAt != null` 은 `schema:86` 의 `NOT NULL` 때문에 늘 참이다.

### BR-07 존재하지 않는 학급에는 배포할 수 없다

- 규칙: `classId` 로 학급을 찾지 못하면 `IllegalArgumentException("존재하지 않는 학급입니다. (id=…)")` 을 던지고, 컨트롤러가 이 메시지를 오류로 보여 주며 새 배포 화면으로 돌려보낸다.
- 근거: `service/DistributionService.java:52-55`, `web/DistributionController.java:69-71`
- 근거 코드:
  ```java
          ClassRow c = classDao.findById(classId);
          if (c == null) {
              throw new IllegalArgumentException("존재하지 않는 학급입니다. (id=" + classId + ")");
  ```
- 확신도: 확실
- 비고: 학급 검사는 마감 검사(BR-06) 뒤에 한다. 없는 학급 + 마감 지난 과제면 마감 오류가 먼저 나온다.

### BR-08 삭제 상태(D) 과제는 배포할 수 없다

- 규칙: 과제 상태가 정확히 `D` 면 `IllegalStateException("삭제된 과제는 배포할 수 없습니다.")` 으로 거부한다. 다른 상태 값은 이 검사를 통과한다.
- 근거: `service/DistributionService.java:56-58`
- 근거 코드:
  ```java
          if ("D".equals(a.getStatus())) {
              throw new IllegalStateException("삭제된 과제는 배포할 수 없습니다.");
  ```
- 확신도: 확실
- 비고: 선택 목록(BR-01)은 O · X · C 만 허용하지만 서버는 D 만 막는다. 목록에 없는 상태(예: 알 수 없는 코드, 소문자 `d`)의 과제 id 를 직접 POST 하면 마감 전인 한 배포된다. `D` 의 뜻("삭제")은 오류 문구에서만 확인되고 `schema:87` 주석에는 없다.

### BR-09 같은 과제를 같은 학급에 두 번 배포할 수 없다

- 규칙: `distribution` 에 같은 `(assignment_id, class_id)` 행이 한 건이라도 있으면 "이미 이 학급에 배포된 과제입니다. 다시 내려면 배포 목록에서 재배포를 사용하세요." 로 거부한다.
- 근거: `service/DistributionService.java:59-61`, `dao/DistributionDao.java:47-50`
- 근거 코드:
  ```java
          if (distributionDao.countByAssignmentAndClass(assignmentId, classId) > 0) {
              throw new IllegalStateException("이미 이 학급에 배포된 과제입니다. 다시 내려면 배포 목록에서 재배포를 사용하세요.");
  ```
- 확신도: 확실(단일 요청 기준) / 동시 요청은 추정
- 비고: 세는 조건에 `redistributed` 나 과제 상태가 없어 재배포 행도 "기존 배포"로 센다. DB UNIQUE 제약이 없고(`schema:98-100`) 조회에 잠금이 없어, 같은 조합을 동시에 두 번 제출하면 둘 다 통과할 수 있다 — 추정. 확인: 두 세션에서 동시 POST 를 보내 보거나 MariaDB 격리 수준(`@@tx_isolation`)을 조회한다. `seed:133-134` 에 이미 같은 조합이 두 건 있다.

## 3. 배포 저장 (E2)

### BR-10 배포 id 는 현재 최대 id + 1 이다

- 규칙: 새 배포의 id 는 `distribution` 의 `MAX(id)` 에 1 을 더한 값이고, 테이블이 비었으면 1 이다.
- 근거: `dao/DistributionDao.java:54-57`, `service/DistributionService.java:62`
- 근거 코드:
  ```java
          Long max = jdbc.queryForObject("SELECT COALESCE(MAX(id), 0) FROM distribution", Long.class);
          return (max == null ? 0L : max.longValue()) + 1L;
  ```
- 확신도: 확실(단일 요청 기준) / 동시 요청은 추정
- 비고: `schema:93` 에 AUTO_INCREMENT 가 없다. 동시 배포 두 건이 같은 id 를 받으면 뒤 INSERT 가 PK 충돌로 `DataAccessException` → "DB 오류로 배포에 실패했습니다."(`web/DistributionController.java:72-74`)가 될 것으로 보인다 — 추정. 확인: 동시 POST 실행.

### BR-11 ⚠ 배포 시각 · 마감 판정 기준 시각은 고정값 2026-09-15 00:00:00 이다

- 규칙: 시스템 속성 `app.clock=system` 이 없으면 `AppClock.now()` 는 항상 `2026-09-15 00:00:00` 을 돌려주고, 이 값이 마감 판정(BR-04, BR-06)과 `distributed_at` 저장에 쓰인다.
- 근거: `AppClock.java:8-9`, `AppClock.java:15-19`, `service/DistributionService.java:63`
- 근거 코드:
  ```java
      // 2024-03 QA 기간 중 임시로 고정. 운영 반영 전 LocalDateTime.now() 로 되돌릴 것 (아직 안 되돌림)
      private static final String FIXED = "2026-09-15 00:00:00";
  ```
- 확신도: 확실
- 비고: 주석은 "2024-03 QA 기간 중 임시 고정"이라지만 고정값은 2026-09-15 이고, 컨테이너 실행 명령(`Dockerfile:11`)에 `-Dapp.clock=system` 이 없어 운영에서도 고정값으로 동작한다. 같은 날 여러 번 배포해도 `distributed_at` 이 모두 같다. 이관 시 "현재 시각"으로 옮길지, 이 고정값을 재현할지 결정이 필요하다.

### BR-12 새 배포는 재배포 아님(redistributed = 0)으로 저장한다

- 규칙: 새 배포 행은 `redistributed` 를 SQL 리터럴 `0` 으로 넣는다.
- 근거: `dao/DistributionDao.java:61`
- 근거 코드:
  ```java
                  "INSERT INTO distribution (id, assignment_id, class_id, distributed_at, redistributed) VALUES (?, ?, ?, ?, 0)",
  ```
- 확신도: 확실
- 비고: 컬럼 기본값도 0 이다(`schema:97`).

### BR-13 INSERT 결과가 정확히 1행이 아니면 실패로 본다

- 규칙: INSERT 영향 행 수가 1 이 아니면 `IllegalStateException("배포 저장에 실패했습니다.")` 을 던져 트랜잭션을 롤백한다.
- 근거: `service/DistributionService.java:63-66`
- 근거 코드:
  ```java
          int n = distributionDao.insert(id, assignmentId, classId, AppClock.now());
          if (n != 1) {
              throw new IllegalStateException("배포 저장에 실패했습니다.");
  ```
- 확신도: 확실
- 비고: 단건 INSERT 라 정상 경로에서는 1 이 아닐 일이 사실상 없다. 롤백 여부는 `@Transactional`(`:46`)의 런타임 예외 기본 롤백에 따른다.

## 4. 오류 처리

### BR-14 배포 중 DB 오류는 일반 문구로, 조회 중 DB 오류는 DB 오류 화면으로 간다

- 규칙: `distribute` 안에서 `DataAccessException` 이 나면 "DB 오류로 배포에 실패했습니다." 를 새 배포 화면에 보여 주고, 그 밖(E1 목록 조회, E2 첫 과제 조회)의 DB 오류는 `DbErrorAdvice` 가 `db_error` 화면으로 원인 메시지와 함께 보여 준다.
- 근거: `web/DistributionController.java:72-74`, `web/DbErrorAdvice.java:11-16`
- 근거 코드:
  ```java
          } catch (DataAccessException e) {
              ra.addFlashAttribute("error", "DB 오류로 배포에 실패했습니다.");
  ```
- 확신도: 확실
- 비고: `db_error` 화면은 DB 원인 메시지(`detail`)를 그대로 노출한다(`templates/db_error.html:8`).

### BR-15 과제 · 학급 파라미터가 비었거나 숫자가 아니면 핸들러가 실행되지 않는다

- 규칙: `assignmentId` · `classId` 는 필수 `long` 파라미터라, 빠졌거나 숫자가 아니면 컨트롤러 본문에 들어가기 전에 Spring 이 요청을 거부한다.
- 근거: `web/DistributionController.java:50-51`, `templates/distribution_form.html:11`, `:23`
- 근거 코드:
  ```java
      public String create(@RequestParam("assignmentId") long assignmentId,
                           @RequestParam("classId") long classId,
  ```
- 확신도: 추정
- 비고: 거부 시 응답(상태 코드 · 화면)은 코드에 없고 Spring Boot 기본 오류 처리에 따른다. 확인: `curl -X POST localhost:8082/distributions -d assignmentId=` 실행. 화면의 `required` 속성은 브라우저 쪽 제한일 뿐이다.

## 5. 매직 넘버 · 리터럴

| 값 | 의미(코드에서 확인한 쓰임) | 위치 |
|----|---------------------------|------|
| `'O'`, `'X'`, `'C'` | 선택 목록에 넣을 과제 상태 | `dao/AssignmentDao.java:36` |
| `"X"` | 마감 지나도 배포 허용하는 상태 | `web/DistributionController.java:61`, `templates/distribution_form.html:16`, `:18` |
| `"D"` | 배포 거부 상태 | `service/DistributionService.java:56` |
| `"2026-09-15 00:00:00"` | 고정 현재 시각 | `AppClock.java:9` |
| `"system"` | `app.clock` 이 이 값일 때만 실제 시각 사용 | `AppClock.java:16` |
| `0` · `+ 1L` | 빈 테이블의 최대 id, 다음 id 증가분 | `dao/DistributionDao.java:55-56` |
| `0` | 새 배포의 `redistributed` | `dao/DistributionDao.java:61` |
| `1` | 정상 INSERT 영향 행 수 | `service/DistributionService.java:64` |
| `> 0` | 중복 배포 판정 기준 건수 | `service/DistributionService.java:59` |
| `'MM-dd HH:mm'` | 선택지의 마감 표시(연도 없음) | `templates/distribution_form.html:17` |
| `yyyy-MM-dd HH:mm` | 마감 오류 문구의 날짜 포맷 | `AppClock.java:12`, `:26` |
| `4`, `5000` | Hikari 최대 풀, 연결 대기 ms | `src/main/resources/application.properties:6-7` |

## 6. 주석만 있고 코드로 확인되지 않은 것

| 주석 · 문구 | 위치 | 코드에서 확인한 것 |
|-------------|------|-------------------|
| `-- O=진행 C=마감` (상태 뜻) | `schema:87` | 코드는 `C` 를 특별 취급하지 않는다. "마감" 판정은 `due_at` 비교(BR-06)이고 `C` 상태도 목록에 나온다(BR-01). `X` · `D` 는 이 주석에 없다. |
| "연장(X) 상태" | `web/DistributionController.java:59`, 화면 문구 ` [연장]`(`templates/distribution_form.html:18`) | 코드는 리터럴 `"X"` 비교뿐. 연장 마감일 같은 별도 값은 없고, `X` 로 바꾸는 코드는 범위 안에 없다. |
| "삭제된 과제" (D 의 뜻) | 오류 문구 `service/DistributionService.java:57` | `D` 를 막는 것만 확인. 과제를 `D` 로 바꾸는 코드는 범위 안에 없다. |
| "같은 학급 + 같은 과제 는 한 건만 존재해야 한다" | `dao/DistributionDao.java:46` | 새 배포 시 기존 건수 > 0 이면 막는 것만 확인(BR-09). 존재 자체를 보장하지 않는다 — UNIQUE 없음, `seed:133-134` 에 중복이 있다. |
| "운영 반영 전 LocalDateTime.now() 로 되돌릴 것" | `AppClock.java:8` | 되돌리지 않았다. 고정값이 기본 동작(BR-11). |
| "마감 지난 과제는 선택 불가 (연장 상태 제외)" | `templates/distribution_form.html:13` | 코드와 일치(BR-04). 다만 화면 제한이라 서버 검증은 별도(BR-06). |

## 7. 같은 규칙이 여러 곳에 다르게 구현된 곳

| 규칙 | 구현 위치 | 차이 |
|------|-----------|------|
| 배포 가능한 과제 상태 | 선택 목록 `dao/AssignmentDao.java:36` / 서버 검증 `service/DistributionService.java:56` | 목록은 `O·X·C` 만 허용(화이트리스트, SQL · collation 영향), 서버는 `D` 만 거부(블랙리스트, Java 대소문자 구분). 목록에 없는 상태도 POST 로는 배포된다. |
| 마감 지난 과제 차단 | 화면 `templates/distribution_form.html:16` / 컨트롤러 `web/DistributionController.java:60-61` / 서비스(없음) | 화면 · 컨트롤러 조건은 같다. 서비스 `distribute` 에는 이 검사가 없어 컨트롤러를 거치지 않는 호출은 막히지 않는다. |
| 과제 존재 검사 | `web/DistributionController.java:53-57` / `service/DistributionService.java:48-51` | 두 번 조회. 오류 문구가 다르다("존재하지 않는 과제입니다." vs "…(id=N)"). 전자는 트랜잭션 밖, 후자는 안. |
| 배포 중복 방지 | 코드 `service/DistributionService.java:59` / 스키마 `schema:98-100` / 시드 `seed:133-134` | 코드는 막지만 DB 제약은 없고, 시드에는 이미 같은 조합이 두 건 있다. |
| 마감 시각 표시 | `templates/distribution_form.html:17` / `AppClock.java:12` | 선택지는 `MM-dd HH:mm`, 오류 문구는 `yyyy-MM-dd HH:mm`. |
