# RCA 리포트: b-deploy-5xx (작성 중 — 1~3절 가설 표까지)

> 이 문서는 1. 수집 범위, 2. 타임라인, 3. 가설과 검증(증상 표 · 가설 표)까지만 담았다.
> 반증 실행 · 코드 대조 · 판정과 4절 이후(요약, 원인, 근거 로그 줄, 수정안, 재발 방지, 모니터링, 확인하지 못한 것)는 이번 범위 밖이라 쓰지 않았다.
> 그래서 이 문서에는 원인 판정이 없다.

대상: `incident-logs/b-deploy-5xx/` (`deploy-history.md`, `app.log`, `nginx-access.log`)

---

## 1. 수집 범위

### 1.1 파일별 개요

| 파일 | 줄 수 | 첫 기록(원문) | 마지막 기록(원문) | 시각 형식 | 타임존 |
|---|---|---|---|---|---|
| `app.log` | 2,938 | `app.log:1` `2026-09-17T09:00:04.878+09:00  INFO 1 --- [item-bank-api] [nio-8080-exec-5] c.example.common.GlobalExceptionHandler  : not found: 문항이 없습니다: id=47 (/api/items/47)` | `app.log:2938` `2026-09-17T14:39:57.905+09:00  INFO 1 --- [item-bank-api] [io-8080-exec-10] c.example.common.GlobalExceptionHandler  : no handler for /favicon.ico` | ISO-8601, 밀리초 | `+09:00` 오프셋이 줄마다 있음 |
| `nginx-access.log` | 20,365 | `nginx-access.log:1` `<IP> - - [17/Sep/2026:09:00:00 +0900] "GET /api/units/M5-3/items HTTP/1.1" 200 1465 "https://lms.example.com/student/home" …(UA 생략)` | `nginx-access.log:20365` `<IP> - - [17/Sep/2026:14:39:58 +0900] "GET /api/units HTTP/1.1" 200 613 "https://lms.example.com/student/home" …(UA 생략)` | nginx combined, 초 단위 | `+0900` 오프셋이 줄마다 있음 |
| `deploy-history.md` | 39 | (표 형식 문서, 로그 아님) | — | `YYYY-MM-DD HH:MM:SS`, 초 단위 | 오프셋 없음. 문서가 KST 라고 명시: `deploy-history.md:3` `시각은 모두 KST(한국 표준시)입니다. 배포 대상 호스트: \`api-1\` (API-1), 대기 호스트 \`api-2\` (API-2, 평소 정지).` |

- 두 로그 모두 오프셋이 찍혀 있어 타임존 추정이 필요 없다. 같은 사건으로 맞춰 봐도 초 단위까지 일치한다: `nginx-access.log:3150` `[17/Sep/2026:09:52:17 +0900] "POST /api/distributions/41/redistribute …" 500` ↔ `app.log:194` `2026-09-17T09:52:17.335+09:00 ERROR … unhandled exception on /api/distributions/41/redistribute`.
- `deploy-history.md` 는 오프셋이 없지만 문서 자체가 KST 라고 적었고, 배포 시작(13:40:05) ~ 완료(13:42:31) 사이에 `app.log` 의 종료 · 기동 줄(13:41:48 ~ 13:42:00)이 들어가므로 같은 시간축으로 본다.
- 응용 로그 · nginx 로그 모두 2026-09-17 09:00 ~ 14:39 의 약 5시간 40분만 담고 있다. 그 이전 · 이후는 없다.

### 1.2 이상 신호 집계

nginx 상태 코드 전체(`awk '{c[$9]++}'`): `200` 19,278 · `404` 969 · `400` 29 · `409` 35 · **`500` 46** · **`502` 8**.
`app.log` 의 `ERROR` 줄은 46건이고, 46건 모두 같은 예외 메시지(`JpaObjectRetrievalFailureException … Assignment … identifier value \`9\` does not exist`)다.

5xx 분 단위 건수(`awk '$9>=500'`, 분 = HH:MM, 괄호 안은 그 분의 전체 요청 수):

| 구간 | 분: 5xx 건수 |
|---|---|
| 배포 전 | 09:52: 3(/60) · 10:31: 1(/47) · 11:17: 1(/62) · 11:18: 2(/69) |
| 배포 중 | 13:41: 8 (전부 502, /72) |
| 배포 후 | 13:46: 2 · 13:47: 1 · 13:50: 4 · 13:52: 1 · 13:53: 1 · 13:55: 3 · 13:58: 2 · 14:02: 2 · 14:03: 4 · 14:06: 1 · 14:11: 3 · 14:15: 1 · 14:16: 2 · 14:22: 3 · 14:27: 2 · 14:28: 1 · 14:31: 2 · 14:32: 1 · 14:34: 3 (전부 500) |

`POST …/redistribute` 상태 코드, 기동 완료(13:42) 전 · 후 비교:

| | 200 | 409 | 500 |
|---|---|---|---|
| 13:42 이전 | 66 | 31 | 6 |
| 13:42 이후 | 6 | 4 | 39 |

10분 단위 전체 요청 수: 09:00 ~ 13:30 구간 516 ~ 682, 13:40 ~ 14:30 구간 549 ~ 665.

### 1.3 범위 결정

- **이상 구간**: 13:41:51(첫 502) ~ 14:34:45(마지막 500). 로그가 14:39:58 에서 끝나므로 이상이 실제로 끝났는지는 이 로그로 알 수 없다(마지막 500 이후 약 5분간 5xx 없음).
- **앞 여유**: 13:30 ~ 13:41 (배포 시작 13:40:05 포함).
- **비교용 평소 구간**: 09:00 ~ 13:39. 이 구간에도 500 이 7건 있다(09:52 · 10:31 · 11:17 · 11:18). 평소 구간에도 있는 신호이므로 "가장 많은 오류 이전 구간" 으로 타임라인에 따로 둔다.
- 이 단계에서는 원인을 말하지 않는다.

---

## 2. 타임라인 (KST, 2026-09-17)

표기: 시각은 각 파일의 정밀도 그대로(nginx 초, app 밀리초). nginx 인용은 UA 를 생략했다.

### 2.1 가장 많은 오류 이전 구간 (09:00 ~ 13:39)

| 시각 | 출처 | 원문 |
|---|---|---|
| 09:52:17 | `nginx-access.log:3150` | `교사단말A - - [17/Sep/2026:09:52:17 +0900] "POST /api/distributions/41/redistribute HTTP/1.1" 500 176 "https://lms.example.com/teacher/distributions/41"` |
| 09:52:17.335 | `app.log:194` | `2026-09-17T09:52:17.335+09:00 ERROR 1 --- [item-bank-api] [nio-8080-exec-8] c.example.common.GlobalExceptionHandler  : unhandled exception on /api/distributions/41/redistribute` |
| 〃 | `app.log:195` | `org.springframework.orm.jpa.JpaObjectRetrievalFailureException: Entity \`com.example.assignment.Assignment\` with identifier value \`9\` does not exist` |
| 〃 | `app.log:204` | `at jdk.proxy2/jdk.proxy2.$Proxy141.findWithDetailsById(Unknown Source)` |
| 〃 | `app.log:205` | `at com.example.assignment.DistributionService.loadOrThrow(DistributionService.java:65)` |
| 〃 | `app.log:214` | `at com.example.assignment.DistributionController.redistribute(DistributionController.java:38)` |
| 09:52:30 · 09:52:41 | `app.log:232`, `app.log:270` / `nginx-access.log:3162`, `:3174` | 같은 경로 · 같은 예외 반복(이후 분=건수: 09:52=3) |
| 10:31:05 | `nginx-access.log:5563` | `교사단말A - - [17/Sep/2026:10:31:05 +0900] "GET /api/distributions/41 HTTP/1.1" 500 176 "https://lms.example.com/teacher/distributions"` |
| 10:31:05.909 | `app.log:451` | `2026-09-17T10:31:05.909+09:00 ERROR … unhandled exception on /api/distributions/41` (스택: `DistributionService.loadOrThrow(DistributionService.java:65)` ← `DistributionService.getDistribution(DistributionService.java:32)`) |
| 11:17:40 ~ 11:18:02 | `app.log:655`, `:695`, `:733` / `nginx-access.log:8442`, `:8466`, `:8468` | `POST /api/distributions/41/redistribute` 500, 같은 예외(11:17=1, 11:18=2) |
| 09:15:53 ~ 13:41:20 | `app.log:59` … `app.log:1238` | 같은 구간에 배포 42 · 43(학급 1)의 재배포는 성공 로그가 18건 있다. 첫 건 `app.log:59` `redistributed distribution 43 (assignment 6, class 1) reason=<STU> 결석…`, 마지막 건 `app.log:1238` `2026-09-17T13:41:20.994+09:00 … redistributed distribution 43 (assignment 6, class 1) reason=<STU> 요청 — 제출 화면 …` |

배포 전 5xx 7건은 전부 배포 41 경로였고, 클라이언트는 모두 `교사단말A` 였다.

### 2.2 배포 · 재기동 (13:38 ~ 13:42)

| 시각 | 출처 | 원문 |
|---|---|---|
| 13:38 | `deploy-history.md:21` | `- 승인: <EMAIL> (13:38)` |
| 13:40:05 (배포 시작) | `deploy-history.md:7` | `\| v1.4.2 \| 2026-09-17 13:40:05 \| 2026-09-17 13:42:31 \| <EMAIL> \| 재배포 응답에 학급 배포 이력(history) 포함 — \`DistributionController.redistribute\` \| …DistributionController.java \|` |
| (시각 없음) | `deploy-history.md:16` | `- 변경 지점: \`DistributionController.redistribute\` 에서 재배포 후 \`DistributionService.listByClass(classId)\` 호출 추가.` |
| (시각 없음) | `deploy-history.md:18` | `- 배포 방식: 단일 호스트 재기동(무중단 아님). 예상 중단 10초 안팎.` |
| (시각 없음) | `deploy-history.md:20` | `curl -s -X POST https://deploy.example.com/api/v1/apps/item-bank-api/releases -H "Authorization: Bearer <TOKEN>" -d '{"version":"1.4.2","host":"API-1"}'` |
| 13:41:20.994 | `app.log:1238` | `… redistributed distribution 43 (assignment 6, class 1) reason=<STU> 요청 — 제출 화면 …` (구 버전에서의 마지막 학급 1 재배포 성공) |
| 13:41:48.214 | `app.log:1240` | `[tomcat-shutdown] o.s.b.w.e.tomcat.GracefulShutdown        : Commencing graceful shutdown. Waiting for active requests to complete` |
| 13:41:50 | `nginx-access.log:16864` | `<IP> - - [17/Sep/2026:13:41:50 +0900] "GET /api/units/M5-2/items?student=<STU> HTTP/1.1" 200 2031` — 502 전 마지막 정상 응답 **(선후 불확실: app.log:1241 과 1초 정밀도 이내)** |
| 13:41:50.144 | `app.log:1241` | `[tomcat-shutdown] o.s.b.w.e.tomcat.GracefulShutdown        : Graceful shutdown complete` |
| 13:41:50.224 | `app.log:1244` | `[ionShutdownHook] com.zaxxer.hikari.HikariDataSource       : itembank-pool - Shutdown completed.` |
| 13:41:51 | `nginx-access.log:16865` | `<IP> - - [17/Sep/2026:13:41:51 +0900] "GET /api/units/M5-2/items?student=<STU> HTTP/1.1" 502 157 "https://lms.example.com/student/home"` — 첫 502 (이후 13:41:52 ~ 13:41:59 에 7건, `nginx-access.log:16866` ~ `:16872`; 13:41=8) |
| 13:41:52 | `nginx-access.log:16866` | `<IP> - - [17/Sep/2026:13:41:52 +0900] "GET / HTTP/1.1" 502 157 "-" "kube-probe/1.29"` |
| 13:41:55.902 | `app.log:1245` | `[           main] com.example.ItemBankApplication          : Starting ItemBankApplication v1.4.2 using Java 21.0.4 with PID 1 …` |
| 13:41:59 | `nginx-access.log:16872` | `<IP> - - [17/Sep/2026:13:41:59 +0900] "GET /api/units/M6-1/items?student=<STU> HTTP/1.1" 502 157` — 마지막 502 |
| 13:42:00.290 | `app.log:1263` | `Tomcat started on port 8080 (http) with context path '/'` |
| 13:42:00.304 | `app.log:1264` | `Started ItemBankApplication in 4.921 seconds (process running for 5.488)` |
| 13:42:01 | `nginx-access.log:16873` | `<IP> - - [17/Sep/2026:13:42:01 +0900] "GET /api/units HTTP/1.1" 200 604` — 재기동 후 첫 응답 |
| 13:42:10.447 | `app.log:1266` | `redistributed distribution 7 (assignment 5, class 3) reason=보강 수업 후 재배포` |
| 13:42:31 (배포 완료) | `deploy-history.md:7` | 배포 완료 시각 `2026-09-17 13:42:31` |

### 2.3 배포 후 5xx 구간 (13:46 ~ 14:39)

| 시각 | 출처 | 원문 |
|---|---|---|
| 13:46:38.107 | `app.log:1274` | `2026-09-17T13:46:38.107+09:00  INFO … c.e.assignment.DistributionService       : redistributed distribution 42 (assignment 4, class 1) reason=보강 수업 후 재배포` |
| 13:46:38.113 | `app.log:1275` | `2026-09-17T13:46:38.113+09:00 ERROR … c.example.common.GlobalExceptionHandler  : unhandled exception on /api/distributions/42/redistribute` |
| 〃 | `app.log:1276` | `org.springframework.orm.jpa.JpaObjectRetrievalFailureException: Entity \`com.example.assignment.Assignment\` with identifier value \`9\` does not exist` |
| 〃 | `app.log:1285` | `at jdk.proxy2/jdk.proxy2.$Proxy141.findByClassRoomIdOrderByDistributedAtAsc(Unknown Source)` |
| 〃 | `app.log:1286` | `at com.example.assignment.DistributionService.listByClass(DistributionService.java:38)` |
| 〃 | `app.log:1294` | `at com.example.assignment.DistributionController.redistribute(DistributionController.java:40)` |
| 13:46:38 | `nginx-access.log:17155` | `교사단말A - - [17/Sep/2026:13:46:38 +0900] "POST /api/distributions/42/redistribute HTTP/1.1" 500 176 "https://lms.example.com/teacher/distributions/42"` — 배포 후 첫 500 |
| 13:46 ~ 14:34 | `app.log:1275` … `app.log:2887` | 같은 패턴(“redistributed distribution 42 또는 43 (… class 1)” INFO 직후 6ms 안에 ERROR) 반복. 이후 분=건수: 13:46=2 · 13:47=1 · 13:50=4 · 13:52=1 · 13:53=1 · 13:55=3 · 13:58=2 · 14:02=2 · 14:03=4 · 14:06=1 · 14:11=3 · 14:15=1 · 14:16=2 · 14:22=3 · 14:27=2 · 14:28=1 · 14:31=2 · 14:32=1 · 14:34=3 (합 39) |
| 13:42 ~ 14:39 | `app.log` | 같은 구간 학급 2 · 3 재배포 성공 로그 6건(배포 44 · 5 · 7 · 8), 이에 대응하는 nginx `POST …/redistribute` 200 6건 |
| 14:34:45 | `nginx-access.log:20084` | `교사단말A - - [17/Sep/2026:14:34:45 +0900] "POST /api/distributions/43/redistribute HTTP/1.1" 500 176 "https://lms.example.com/teacher/distributions/43"` — 마지막 500 |
| 14:34:45.439 | `app.log:2887` | `2026-09-17T14:34:45.439+09:00 ERROR … unhandled exception on /api/distributions/43/redistribute` |
| 14:39:58 | `nginx-access.log:20365` | 로그 끝 |

배포 후 500 39건은 전부 `교사단말A` 의 `POST /api/distributions/{42,43}/redistribute` 였다. `POST …/redistribute` 를 보낸 클라이언트는 하루 전체에서 `교사단말A` 125건, `교사단말B` 27건이다.

---

## 3. 가설과 검증

### 3.1 증상 (가장 많이 나온 오류 메시지)

| 증상 | 건수 | 알려 주는 것 | 알려 주지 않는 것 |
|---|---|---|---|
| `app.log` ERROR `unhandled exception on /api/distributions/{41,42,43}[/redistribute]` + `JpaObjectRetrievalFailureException: Entity \`com.example.assignment.Assignment\` with identifier value \`9\` does not exist` | 46 (배포 전 7 · 후 39) | 엔티티 로딩 중 존재하지 않는 Assignment id 9 를 참조했다. 배포 전에는 `findWithDetailsById`(`loadOrThrow`), 배포 후에는 `findByClassRoomIdOrderByDistributedAtAsc`(`listByClass`) 경로에서 났다. `GlobalExceptionHandler` 가 "그 밖" 예외로 처리했다. | id 9 를 참조하는 행이 어느 배포인지(41 로 보이지만 로그에 행 값이 없음), 배포 41 이 어느 학급인지, Assignment 9 가 왜 없는지(삭제 · 미적재 · 다른 DB), 재배포 성공 로그 뒤 500 이 났을 때 재배포 변경이 커밋됐는지 |
| `nginx-access.log` `500 176` | 46 | 위 ERROR 와 초 단위로 1:1 대응한다. 응답 본문 크기가 모두 176 바이트로 같다. 클라이언트는 전부 `교사단말A`. | 사용자가 같은 동작을 재시도했는지, 다른 교사가 같은 학급을 시도하지 않았는지(재시도 · 미시도 구분 불가) |
| `nginx-access.log` `502 157` | 8 | 13:41:51 ~ 13:41:59 에만 있고, 경로 · 클라이언트가 다양하다(헬스체크 `kube-probe` 포함). | 업스트림 연결 실패 사유(nginx error 로그가 묶음에 없음) |

### 3.2 가설 표

| # | 가설 | 계층 | 지지 근거 | 반증 조건(이것이 보이면 틀림) | 확인 방법 |
|---|---|---|---|---|---|
| H1 | v1.4.2 에서 재배포 뒤 추가된 `listByClass(classId)` 호출이 학급 1 의 배포 목록을 읽으면서, 없는 Assignment 9 를 참조하는 배포 행을 함께 로딩해 실패한다. 그래서 학급 1 재배포만 500 이 된다. | 코드(배포 변경) | 배포 후 39건 스택이 `DistributionController.redistribute(DistributionController.java:40)` → `DistributionService.listByClass(DistributionService.java:38)` → `findByClassRoomIdOrderByDistributedAtAsc` (`app.log:1285`~`1294`). 변경 지점이 정확히 이 호출이다(`deploy-history.md:16`). 배포 후 학급 1(배포 42 · 43) 재배포는 39회 모두 500, 학급 2 · 3 은 6회 모두 200. 같은 배포 42 · 43 이 배포 전에는 18회 성공. | ① 13:42 이후 학급 1 재배포가 200 으로 끝난 건이 있다. ② 13:42 이후 학급 2 · 3 재배포에서 같은 예외가 난다. ③ 배포 후 ERROR 스택에 `listByClass` 가 없는 건이 있다. ④ 배포 전에도 `listByClass` 경로 예외가 있다. | `awk '$7~/redistribute/ && $4>"[17/Sep/2026:13:42"{print $7,$9}' nginx-access.log \| sort \| uniq -c`<br>`grep -n 'redistributed distribution' app.log \| awk -F'T' '$2>"13:42"' \| grep -c 'class 1)'`<br>`grep -A12 ' ERROR ' app.log \| grep -c 'listByClass(DistributionService.java:38)'`<br>`git log -p -- modern/api/src/main/java/com/example/assignment/DistributionController.java` (저장소 코드 = 배포본 전제) |
| H2 | 학급 1 에 속한 배포 하나(41 로 추정)가 존재하지 않는 과제 id 9 를 참조하는 고아 데이터이며, 배포 이전부터 있었다. | DB · 데이터 | 배포 전 09:52 부터 배포 41 조회 · 재배포가 같은 예외로 7회 실패(`app.log:194`, `:451` 등). 예외 식별자가 46건 모두 `9`. | ① DB 에 `assignment.id = 9` 가 있다. ② `distribution.assignment_id = 9` 인 행이 없거나, 그 행의 학급이 1 이 아니다. ③ 배포 후 예외 식별자가 9 가 아닌 건이 있다. | `grep -oE 'identifier value \`[0-9]+\`' app.log \| sort \| uniq -c`<br>(DB, 이번 묶음에 없음) `SELECT id, class_id, assignment_id FROM distribution WHERE id = 41 OR assignment_id = 9;` · `SELECT id FROM assignment WHERE id = 9;` (테이블 · 컬럼 이름은 스키마 확인 후 맞출 것) |
| H3 | 무중단이 아닌 단일 호스트 재기동 동안 업스트림(API-1)이 없어 nginx 가 502 를 냈다. | 인프라 · 배포 방식 | 502 8건이 Graceful shutdown complete(13:41:50.144) 와 Tomcat started(13:42:00.290) 사이에만 있다. 배포 방식이 "단일 호스트 재기동(무중단 아님)"(`deploy-history.md:18`). | ① 13:41:50 ~ 13:42:00 밖에서 502 가 있다. ② 그 사이에 200 응답이 있다. (이 가설은 500 을 설명하지 않는다. 500 은 재기동 완료 뒤에도 50분 넘게 이어졌다.) | `awk '$9==502{print NR,$4}' nginx-access.log`<br>`awk '$4>="[17/Sep/2026:13:41:50" && $4<="[17/Sep/2026:13:42:00"{print NR,$4,$9}' nginx-access.log`<br>`grep -nE 'GracefulShutdown\|Tomcat started' app.log` |
| H4 | 배포 후 트래픽 증가나 사용자 재시도 폭주가 5xx 증가를 만들었다. | 트래픽 | 배포 후 500 39건이 한 클라이언트(`교사단말A`)에서 수 초 ~ 수십 초 간격으로 몰려 있다(예: 13:50:12 · 13:50:13 · 13:50:19). | ① 10분 단위 요청 수가 평소 구간(516 ~ 682) 범위 안이다. ② 같은 클라이언트의 학급 2 · 3 요청은 같은 시간대에 성공한다. ③ 재시도가 없어도(첫 시도부터) 500 이다. | `awk '{split($4,t,":"); print t[2]":"substr(t[3],1,1)"0"}' nginx-access.log \| uniq -c`<br>`grep '^교사단말A ' nginx-access.log \| awk '$7~/redistribute/{print $4,$7,$9}'` |
| H5 | DB 자원(커넥션 풀 고갈 · 쿼리 지연)으로 요청이 실패했다. | DB 자원 | 배포 직후 커넥션 풀이 새로 시작됐다(`app.log:1258`~`1260`). 그 밖의 지지 근거는 아직 없다. | ① Hikari 대기 시간 초과 · `Connection is not available` 로그가 0건이다. ② 예외가 데이터 부재 예외 한 종류뿐이다. ③ 같은 시간대 다른 API 는 정상 응답한다. | `grep -c 'Connection is not available\|HikariPool.*timeout' app.log`<br>`grep -E '^[a-z].*Exception: ' app.log \| sort \| uniq -c`<br>`awk '$4>"[17/Sep/2026:13:42" && $9>=500{print $7}' nginx-access.log \| sort \| uniq -c` |

반증 실행 · 판정(4단계), 코드 대조, 이후 절은 이번 범위 밖이라 진행하지 않았다.

---

마스킹 메모: 로그 원문 인용과 확인 방법 명령 안의 값도 별칭으로 바꿨다. 그래서 `grep '^교사단말A '` 같은 명령은 그대로 실행되지 않는다(원래 값으로 바꿔 실행해야 한다).

마스킹 적용: IP 주소 23건(역할 별칭 4종) · 이메일 2건 · 학생 식별자 7건(별칭 0명, 전부 `<STU>`) · 토큰 · 세션 ID · API 키 · Authorization 1건 · 비밀번호 · 접속 문자열 0건
