# RCA: a-connection-pool

대상 로그: `incident-logs/a-connection-pool/` (4개 파일). 장애일: 2026-09-16.

## 요약

- **무슨 일:** 2026-09-16 **14:37:42 ~ 14:51:47 KST**(약 14분) item-bank API 의 DB 커넥션 풀(5개)이 바닥나, 학급 리포트뿐 아니라 단원 · 문항 · 배포 조회까지 실패했다. 사용자 영향은 14:40:52 ~ 14:51:47 의 5xx **186건**(500 49 · 502 82 · 504 55).
- **원인:** `ReportService.buildClassReport` 가 DB 조회를 마친 뒤 SHA-256 200,000회 서명 루프를 **트랜잭션 안에서** 돌려, 리포트 1건이 커넥션을 15~21초 쥐었다. 리포트 반복 요청이 겹치면서 5개가 모두 묶였다.
- **확신 수준: 중간.** 메커니즘은 코드 · 설정 · 로그 순서로 확인했다. 다만 14:37 부터 리포트 1건이 평소(10초 미만)보다 느려진 이유는 밝히지 못했다(9절).
- **조치:** 서명을 트랜잭션 밖으로 옮기는 한 줄 수정(6절). 첫 누수 경고(14:37:52) 시점에 경보를 울려 첫 5xx 보다 3분 먼저 알아챈다(8절).

## 1. 수집 범위

> 파일을 통째로 읽지 않고 `wc` · `head` · `tail` · `grep -c` · `grep -n` · `awk` 집계로만 확인했다. 이 절은 범위만 정한다. 원인은 다루지 않는다.

### 1.1 파일별 기록 기간 · 형식

| 파일 | 줄 수 | 첫 기록 (원문) | 마지막 기록 (원문) | 시각 형식 | 타임존 |
|---|---:|---|---|---|---|
| `app.log` | 5,371 (타임스탬프 줄 1,927 + 스택트레이스 등 연속 줄 3,444) | `2026-09-16T06:43:56.753+09:00` | `2026-09-16T15:29:51.760+09:00` | ISO-8601, 밀리초, 오프셋 포함 | `+09:00` (KST). 타임스탬프 줄 1,927개 모두 같은 오프셋 |
| `nginx-access.log` | 21,086 | `[16/Sep/2026:06:43:56 +0900]` | `[16/Sep/2026:15:29:59 +0900]` | nginx combined `dd/Mon/yyyy:HH:MM:SS ±zzzz` | `+0900` (KST). 21,086줄 모두 같은 오프셋 |
| `nginx-error.log` | 168 | `2026/09/16 09:12:03` | `2026/09/16 14:51:47` | `yyyy/MM/dd HH:MM:SS`, 오프셋 **없음** | KST로 판단 (아래 근거) |
| `mariadb-slow.log` | 21 (항목 3건) | `# Time: 260916  0:14:09` | `# Time: 260916  5:37:52` | `# Time: yyMMdd H:MM:SS` + `SET timestamp=<epoch>` | **UTC** (아래 근거) |

**타임존 판단 근거**

- `nginx-error.log`: `14:51:03 upstream timed out … GET /api/classes/3/report` 가 `nginx-access.log:19351` 의 `[16/Sep/2026:14:51:03 +0900] "GET /api/classes/3/report" 504` 와 초 단위로 일치한다. 따라서 KST 로컬 시각이다.
- `mariadb-slow.log`: `SET timestamp=` epoch 을 변환하면 `# Time` 과 UTC 기준으로 맞는다(`# Time` 은 쿼리 종료 시각, epoch 은 시작 시각이라 `Query_time` 만큼 차이 난다).

  | `# Time` (원문) | `Query_time` | epoch → UTC | epoch → KST |
  |---|---:|---|---|
  | `260916  0:14:09` | 2.32s | 00:14:07 | **09:14:07** |
  | `260916  2:02:42` | 2.04s | 02:02:40 | **11:02:40** |
  | `260916  5:37:52` | 9.81s | 05:37:42 | **14:37:42** |

**KST 변환 규칙**

- `app.log`, `nginx-access.log`, `nginx-error.log`: 그대로 KST다. 변환하지 않는다.
- `mariadb-slow.log`: `# Time` 에 **+9시간**을 더하면 KST다(같은 날짜 안에서는 날짜가 바뀌지 않음. UTC 15:00 이후면 KST 다음 날). 정확한 쿼리 시작 시각이 필요하면 `SET timestamp` epoch 을 `TZ=Asia/Seoul date -d @<epoch>` 로 변환한다.
- 공통 기간(KST): 4개 파일이 모두 덮는 구간은 **09:12:03 ~ 14:51:47** (`nginx-error.log` 기준). `app.log` · `nginx-access.log` 는 06:43:56 ~ 15:29:59.

### 1.2 이상 신호 분 단위 집계

**센 대상**

| 파일 | 센 줄 | 평소에도 나오는 것(제외 · 별도) |
|---|---|---|
| `app.log` | 레벨 `WARN` · `ERROR` 인 타임스탬프 줄. 그중 `ProxyLeakTask` "Connection leak detection triggered"(leak), Hikari "Connection is not available, request timed out after 3000ms"(pool 타임아웃)는 따로 셈 | `WARN … state conflict: 마감된 과제는 재배포할 수 없습니다`(409) 20건은 08:41~14:52에 고르게 1건/분 이하라 제외. `INFO` 의 not found · no handler 도 제외 |
| `nginx-access.log` | 상태 코드 5xx (500 · 502 · 504) | 404(1,250) · 400(25) · 409(20)는 제외 |
| `nginx-error.log` | `[error]` · `[warn]` 줄, 그중 `upstream timed out` 은 따로 셈 | `[info]` 3건 제외 |
| `mariadb-slow.log` | 슬로 쿼리 항목 | — |

**시간대별 개요 (KST)**

| 시 | 요청 수 | 5xx | app WARN+ERROR | leak | pool 타임아웃 | nginx error+warn |
|---|---:|---:|---:|---:|---:|---:|
| 06 | 396 | 0 | 0 | 0 | 0 | 0 |
| 07 | 1,687 | 0 | 0 | 0 | 0 | 0 |
| 08 | 2,185 | 0 | 0 | 0 | 0 | 0 |
| 09 | 2,549 | 0 | 0 | 0 | 0 | 1 |
| 10 | 2,734 | 0 | 0 | 0 | 0 | 0 |
| 11 | 2,664 | 0 | 0 | 0 | 0 | 0 |
| 12 | 2,377 | 0 | 0 | 0 | 0 | 0 |
| 13 | 2,525 | 0 | 0 | 0 | 0 | 1 |
| **14** | 2,608 | **186** | **281** | **134** | **49** | **163** |
| 15 | 1,361 | 0 | 0 | 0 | 0 | 0 |

**평소 수준**: 08:00~13:59 요청은 분당 중앙값 42건(최소 20 · 최대 66), 5xx 0건, app WARN/ERROR(409 제외) 0건, nginx error/warn 은 09:12 · 13:05 의 `[warn] upstream response is buffered to a temporary file` 각 1건뿐이다. 슬로 쿼리는 09:14 · 11:02(KST)에 2초대 1건씩.

**이상 구간 분 단위 (KST, 값이 있는 분만)**

| 분 | 요청 | 5xx | app WARN+ERROR | leak | pool 타임아웃 | nginx error+warn | nginx 타임아웃 |
|---|---:|---:|---:|---:|---:|---:|---:|
| 14:37 | 50 | | 1 | 1 | | | |
| 14:38 | 41 | | 2 | 2 | | | |
| 14:39 | 42 | | 3 | 3 | | | |
| 14:40 | 42 | 1 | 17 | 14 | 1 | | |
| 14:41 | 42 | 9 | 31 | 13 | 6 | 3 | 3 |
| 14:42 | 41 | 20 | 16 | 10 | 2 | 20 | 5 |
| 14:43 | 58 | 17 | 44 | 14 | 10 | 8 | 5 |
| 14:44 | 34 | 13 | 17 | 11 | 2 | 15 | 6 |
| 14:45 | 39 | 27 | 33 | 9 | 8 | 22 | 5 |
| 14:46 | 51 | 26 | 24 | 12 | 4 | 25 | 6 |
| 14:47 | 38 | 14 | 24 | 9 | 5 | 10 | 3 |
| 14:48 | 42 | 15 | 22 | 10 | 4 | 15 | 5 |
| 14:49 | 42 | 23 | 33 | 12 | 7 | 20 | 8 |
| 14:50 | 34 | 18 | 11 | 11 | | 22 | 7 |
| 14:51 | 46 | 3 | 3 | 3 | | 3 | 2 |
| 14:52~15:29 | 평소 수준 | 0 | 0 | 0 | 0 | 0 | 0 |

5xx 코드별: 500 은 14:40~14:49, 502 는 14:42~14:51, 504 는 14:41~14:51. 요청 수는 이상 구간에도 평소 범위(34~58/분) 안에 있다.

**경계 시각 (KST, 파일:줄)**

| 신호 | 처음 | 마지막 |
|---|---|---|
| 슬로 쿼리 (9.81s, Rows_examined 1,284,310) | 14:37:42 시작 / 14:37:52 종료 (`mariadb-slow.log:15`) | — |
| app leak 경고 | 14:37:52 (`app.log:1243`) | 14:51:37 (`app.log:5251`) |
| app ERROR · pool 타임아웃 | 14:40:52 (`app.log:1522`) | 14:49:51 (`app.log:4981`) |
| nginx 5xx | 14:40:52 500 (`nginx-access.log:18918`) | 14:51:47 504 (`nginx-access.log:19381`) |
| nginx error | 14:41:06 (`nginx-error.log:6`) | 14:51:47 (`nginx-error.log:168`) |

**제안하는 이상 구간: 2026-09-16 14:37:42 ~ 14:51:47 KST** (약 14분)

- 시작: 평소와 달라지는 첫 신호는 14:37:52 의 leak 경고다. `leak-detection-threshold` 가 10초(`modern/api/src/main/resources/application.yml:18`)라 경고 10초 전부터 커넥션이 잡혀 있었다는 뜻이고, 같은 시각(14:37:42)에 시작한 슬로 쿼리가 있어 시작을 14:37:42 로 잡는다. 사용자 영향(5xx)의 시작은 14:40:52.
- 끝: 마지막 5xx · nginx error 가 14:51:47, 이후 15:29 까지 모든 신호가 0 이다.

### 1.3 앞뒤 여유

| | 여유 | 수집 시각 (KST) | 이유 |
|---|---|---|---|
| 앞 | **30분** | **14:07:00** 부터 | leak 경고는 커넥션을 쥔 지 10초 뒤에야 찍히고, 그 이전의 점진적 변화(요청 패턴, 409 등 평소 경고)는 로그 레벨상 드러나지 않을 수 있다. 같은 길이의 평소 구간과 비교할 수 있게 넉넉히 둔다 |
| 뒤 | **10분** | **15:02:00** 까지 | 14:52 이후 신호가 바로 0 으로 돌아왔으므로 회복이 유지되는지만 확인하면 된다 |

**수집 범위: 2026-09-16 14:07:00 ~ 15:02:00 KST** (UTC 05:07:00 ~ 06:02:00)

파일별 추출 기준:

- `app.log`: `2026-09-16T14:07` ~ `2026-09-16T15:01` 로 시작하는 줄과 그 뒤 연속 줄(스택트레이스).
- `nginx-access.log`: `16/Sep/2026:14:07` ~ `16/Sep/2026:15:01`.
- `nginx-error.log`: `2026/09/16 14:07` ~ `2026/09/16 15:01` (해당 구간 줄은 6~168행).
- `mariadb-slow.log`: UTC 기준 `05:07` ~ `06:02` → 해당 항목은 3번째(14~21행) 1건. 파일이 21줄뿐이라 09:14 · 11:02 의 2건도 평소 비교용으로 함께 둔다.
- 비교용 평소 구간: 같은 길이의 **13:07:00 ~ 14:02:00 KST** (요청 수가 비슷하고 이상 신호 0).

## 2. 타임라인

> 대상: 2026-09-16 **14:07:00 ~ 15:02:00 KST** (1.3 의 수집 범위). 4개 파일을 KST 한 시간축으로 합쳤다. 로그에 있는 사건만 적고 해석은 넣지 않는다.
>
> - 시각 정밀도: `app.log` 밀리초, `nginx-access.log` · `nginx-error.log` · `mariadb-slow.log` 초. 따라서 파일이 다르고 차이가 **2초 이내**인 사건은 **(선후 불확실)** 로 표시했다.
> - `mariadb-slow.log` 는 UTC → +9h 변환. `# Time` 은 종료 시각, `SET timestamp` 는 시작 시각이다.
> - 반복 메시지는 "첫 발생 · 이후 N건/분" 으로 묶었다. 분별 수는 `분=건수` 로 적었다.
> - 줄번호의 파일명은 줄여 쓴다: `app` = `app.log`, `acc` = `nginx-access.log`, `err` = `nginx-error.log`, `slow` = `mariadb-slow.log`.

### 2.1 가장 많은 오류 이전 (14:07:00 ~ 14:40:51)

가장 많이 나오는 오류 계열(5xx · pool 타임아웃 · nginx upstream 오류)은 14:40:52 에 시작한다. 그 앞의 줄을 모두 훑어, 평소와 다른 것과 평소에도 있는 것을 나눠 적었다.

| 시각 (KST) | 출처 | 사건 요약 | 원문 발췌 |
|---|---|---|---|
| 14:07:00~14:37:41 | acc 전체 | 요청 수 분당 32~56건(평소 범위). 요청 종류 구성도 13:07~14:02 와 같은 순서(units/items > items > units > distributions). 5xx 0건 | — |
| 14:10:05 | app:1148 · acc | 학급 리포트 조회. 이후 14:13 · 14:15 · 14:17 · 14:18 각 1건(평소 시간당 2~11건 수준) | `built class report for class 3: 3 distributions, 10 submissions` |
| 14:11:38 | app:1154 | 재배포 409 경고. 평소에도 있음(08:41 부터 1건/분 이하). 구간 내 7건 | `state conflict: 마감된 과제는 재배포할 수 없습니다: distributionId=3` |
| 14:19:38 | app:1182 | 재배포 성공 (구간 내 3건, 평소 POST 수준) | `redistributed distribution 7 (assignment 5, class 3) reason=학생A 결석으로 재배포` |
| **14:37:42** | slow:15–21 (`SET timestamp=1789537062`) | **느린 쿼리 시작.** 9.81초, 검사 행 1,284,310. 오늘 앞선 슬로 쿼리 2건(09:14 · 11:02)은 2초대 · 검사 행 41,822 이하였다 | `Query_time: 9.812417  Lock_time: 0.000071  Rows_sent: 4  Rows_examined: 1284310` |
| | | 쿼리 원문 | `select … from submission s1_0 where s1_0.distribution_id=5 order by …student_id` |
| **14:37:52** | slow:15 (`# Time: 260916  5:37:52`) | 느린 쿼리 종료 기록 **(선후 불확실 ↔ 다음 줄, 1초 미만 차이)** | `# Time: 260916  5:37:52` |
| **14:37:52.199** | app:1243 | **첫 커넥션 누수 경고.** 커넥션 `@6e8f14d3`, 스레드 exec-6. 스택에 `ReportController.classReport` (app:1254) | `Connection leak detection triggered for org.mariadb.jdbc.Connection@6e8f14d3` |
| 14:38:01 | acc:18801 | 학급 리포트 요청 200 (교사단말A, class 3). 14:18 이후 첫 리포트 요청 **(선후 불확실 ↔ 다음 두 줄, 같은 초)** | `"GET /api/classes/3/report HTTP/1.1" 200 266` |
| 14:38:01.900 | app:1258 | exec-6 리포트 생성 완료 | `[nio-8080-exec-6] … built class report for class 3: 3 distributions` |
| 14:38:01.902 | app:1259 | 누수 경고된 커넥션 `@6e8f14d3` 반환. 이후 14:38=3, 14:39=3, 14:40=10, 14:41=15, 14:42=10, 14:43=15, 14:44=11, 14:45=8, 14:46=13, 14:47=9, 14:48=10, 14:49=13, 14:50=9, 14:51=5 건/분 (총 134) | `Previously reported leaked connection …@6e8f14d3 … exec-6 was returned` |
| 14:38:01~ | acc | **트래픽 구성 변화:** `/api/classes/{N}/report` 요청이 분당 0~1건 → 14:38=3, 14:39=3, 14:40=10, 14:41=16, 14:42=15, 14:43=19, 14:44=14, 14:45=15, 14:46=19, 14:47=12, 14:48=14, 14:49=16, 14:50=10, 14:51=5 건/분 (14시 합계 179건, 평소 시간당 2~11건). 전체 요청 수는 분당 34~58건으로 평소 범위 그대로 | — |
| 14:38:01~14:39:55 | acc:18801 · 18815 · 18839 · 18862 · 18882 | 교사단말A 가 class 3 리포트를 약 27~30초 간격으로 반복 요청 | `교사단말A … "GET /api/classes/3/report HTTP/1.1" 200` |
| 14:38:51.641 | app:1280 | 누수 경고(두 번째 커넥션 `@2ac19f07`). 누수 경고 전체 분별: 14:37=1, 14:38=2, 14:39=3, 14:40=14, 14:41=13, 14:42=10, 14:43=14, 14:44=11, 14:45=9, 14:46=12, 14:47=9, 14:48=10, 14:49=12, 14:50=11, 14:51=3 건/분 (총 134. 134건 모두 스택에 `ReportController.classReport`) | `Connection leak detection triggered for org.mariadb.jdbc.Connection@2ac19f07` |
| 14:39:50.132 | app:1331 | 누수 경고 대상 커넥션이 3개째(`@71d4b8e2`) | `Connection leak detection triggered for …Connection@71d4b8e2` |
| 14:39:54 | acc:18881 | 두 번째 클라이언트 교사단말B 가 class 1 리포트 요청. 14시대 리포트 요청 클라이언트는 교사단말B · 3.12 · 3.17 · 3.23 | `교사단말B … "GET /api/classes/1/report HTTP/1.1" 200 271` |
| 14:40:04.730 | app:1351 | 누수 경고 대상 커넥션 4개째(`@3f6c2a91`) | `Connection leak detection triggered for …Connection@3f6c2a91` |
| 14:40:12.901 | app:1370 | 누수 경고 대상 커넥션 5개째(`@5b0e9e0c`). 이후 누수 경고는 이 5개 커넥션에서만 나옴 | `Connection leak detection triggered for …Connection@5b0e9e0c` |

이 구간에서 찾았지만 **없었던 것**: 설정 재적재 · 재시작 · 시그널(`reload` · `reconfigur` · `signal` · 기동 메시지) 0건(app · nginx-error 모두). nginx-error 는 14:07~14:41:05 사이 줄이 없다. POST(재배포)는 4건으로 13:07~14:02 의 3건과 비슷하다.

### 2.2 오류 구간 (14:40:52 ~ 14:51:49)

| 시각 (KST) | 출처 | 사건 요약 | 원문 발췌 |
|---|---|---|---|
| **14:40:52.319** | app:1521–1522 | **첫 커넥션 풀 획득 타임아웃** (exec-3). 이후 14:40=1, 14:41=6, 14:42=2, 14:43=10, 14:44=2, 14:45=8, 14:46=4, 14:47=5, 14:48=4, 14:49=7 건/분 (총 49, 마지막 14:49:51.552 app:4980) **(선후 불확실 ↔ acc:18918, 같은 초)** | `Connection is not available, request timed out after 3000ms (… waiting=4)` |
| 14:40:52.320 | app:1523 | 처리되지 않은 예외 → 500. 이후 경로별 첫 발생: `/api/items/N` 14:41:17 (app:1690), `/api/units` 14:41:20 (app:1775), `/api/classes/N/report` 14:41:27 (app:1864), `/api/distributions/N` 14:42:59 (app:2262). 분별 수는 pool 타임아웃과 같음(총 49) | `unhandled exception on /api/units/M6-2/items` |
| 14:40:52 | acc:18918 | **첫 5xx (500).** 500 은 이후 14:41=6, 14:42=2, 14:43=10, 14:44=2, 14:45=8, 14:46=4, 14:47=5, 14:48=4, 14:49=7 건/분 (총 49, 마지막 14:49:51 acc:19305) | `교사단말B … "GET /api/units/M6-2/items HTTP/1.1" 500` |
| **14:41:06** | err:6 · acc:18933 | **첫 upstream 타임아웃 → 504** (API-1:8080, class 3 리포트). 두 줄 같은 초 **(선후 불확실)**. 이후 nginx 타임아웃 14:41=3, 14:42=5, 14:43=5, 14:44=6, 14:45=5, 14:46=6, 14:47=3, 14:48=5, 14:49=8, 14:50=7, 14:51=2 건/분 (총 55), 504 도 총 55 | `upstream timed out (110: Connection timed out) while reading response header` |
| **14:42:01** | err:10 | **upstream 일시 비활성화** (API-1:8080). 이후 14:42=2, 14:43=1, 14:44=4, 14:45=3, 14:46=3, 14:47=1, 14:48=4, 14:49=4, 14:50=4 건/분 (총 26, 마지막 14:50:52 err:158) **(선후 불확실 ↔ 다음 두 줄, 같은 초)** | `upstream server temporarily disabled while reading response header from upstream` |
| 14:42:01 | err:11 | **두 번째 upstream API-2:8080 연결 거부.** nginx-error.log 에서 API-2 가 처음 등장(그 전 upstream 주소는 API-1 뿐). 이후 14:42=2, 14:43=1, 14:44=1, 14:45=2, 14:46=2, 14:47=1, 14:48=1, 14:49=2, 14:50=2 건/분 (총 14, 마지막 14:50:52 err:159) | `connect() failed (111: Connection refused) … upstream: "http://API-2:8080` |
| 14:42:01 | acc:18970 | **첫 502** (class 2 리포트). 502 는 이후 14:42=13, 14:43=2, 14:44=5, 14:45=14, 14:46=16, 14:47=6, 14:48=6, 14:49=8, 14:50=11, 14:51=1 건/분 (총 82) | `교사단말B … "GET /api/classes/2/report HTTP/1.1" 502` |
| 14:42:04 | err:12 | **사용 가능한 upstream 없음.** 이후 14:42=11, 14:43=1, 14:44=4, 14:45=12, 14:46=14, 14:47=5, 14:48=5, 14:49=6, 14:50=9, 14:51=1 건/분 (총 68, 마지막 14:51:02 err:166). 리포트 외 경로(units · items · distributions)도 포함 | `no live upstreams while connecting to upstream … "GET /api/units/M6-2/items` |
| 14:44:19.827 | app:2929 | 재배포 성공 1건 (오류 구간 중 정상 처리) | `redistributed distribution 8 (assignment 6, class 2) reason=보강 수업 후 재배포` |
| 14:49:51.553 | app:4979–4981 | 마지막 pool 타임아웃 · 500 (waiting=3) **(선후 불확실 ↔ acc:19305, 같은 초)** | `request timed out after 3000ms (total=5, active=5, idle=0, waiting=3)` |
| 14:50:52 | err:158–159 | 마지막 upstream 비활성화 · API-2 연결 거부 | `upstream server temporarily disabled …` |
| 14:51:02 | err:166 · acc:19347 | 마지막 `no live upstreams` · 마지막 502. 같은 초 **(선후 불확실)** | `"GET /api/units/M5-3/items?student=학생B HTTP/1.1" 502` |
| 14:51:37.674 | app:5251 | 마지막 누수 경고 (`@6e8f14d3`, exec-10) | `Connection leak detection triggered for …@6e8f14d3 on thread …exec-10` |
| **14:51:47** | err:168 · acc:19381 | **마지막 upstream 타임아웃 · 마지막 504** (class 3 리포트, 교사단말C). 같은 초 **(선후 불확실)**. 다음 줄(app 14:51:49)과는 2초 차이 **(선후 불확실)** | `upstream timed out (110: Connection timed out) … "GET /api/classes/3/report` |
| 14:51:49.148 | app:5267 | 마지막 누수 커넥션 반환 | `Previously reported leaked connection …@6e8f14d3 … exec-10 was returned` |

### 2.3 오류 이후 (14:51:50 ~ 15:02:00)

| 시각 (KST) | 출처 | 사건 요약 | 원문 발췌 |
|---|---|---|---|
| 14:52:09 | app:5269 | 재배포 409 경고 (평소에도 있는 종류) | `state conflict: 마감된 과제는 재배포할 수 없습니다: distributionId=4` |
| 14:52~15:01 | acc | 5xx 0건. 요청 수 분당 35~47건. 리포트 요청 14:59=2, 15:01=1 (평소 수준) | — |
| 14:59 · 15:01:20 | app:5287 외 | 리포트 생성 정상 완료, 누수 경고 없음 | `built class report for class 3: 3 distributions, 10 submissions` |
| 14:51:48~15:02:00 | err | 줄 없음 (파일 마지막 줄이 14:51:47) | — |

## 3. 가설과 검증

> 이 절은 가설과 그 검증 계획을 적고, 받은 로그 4개로 확인할 수 있는 반증 조건만 실제로 실행해 1차 판정했다(3.1 표의 오른쪽 열, 실행 기록은 3.7). 코드 실측 · 운영 설정 · 다른 호스트 로그가 필요한 확인은 아직 하지 않았으므로 **최종 판정이 아니다**. 가설끼리 서로 배타적이지 않으므로 여러 개가 함께 맞을 수도 있다.
>
> - 지지 근거의 `rca:<줄>` 은 이 문서(`docs/rca/a-connection-pool.md`)의 줄번호다. 로그 · 코드 파일은 경로를 그대로 적었다.
> - 확인 방법의 명령은 저장소 루트에서 `L=incident-logs/a-connection-pool` 로 두고 실행한다고 가정한다.

### 3.0 증상과 원인의 구분

가장 많이 나온 오류 메시지는 원인이 아니라 **증상**으로 본다.

| 증상 (가장 많이 나온 메시지) | 건수 | 이 메시지가 알려 주는 것 |
|---|---:|---|
| `Connection is not available, request timed out after 3000ms (total=5, active=5, idle=0, …)` (rca:165, rca:174) | 49 | 풀 5개가 모두 사용 중이었다는 사실. **왜** 5개가 다 잡혀 있었는지는 말하지 않는다 |
| `no live upstreams` · 502 (rca:171–172) | 68 · 82 | nginx 가 보낼 upstream 이 없었다는 사실. 앞단의 결과다 |
| `upstream timed out` · 504 (rca:168) | 55 | 앱 응답이 nginx 타임아웃보다 늦었다는 사실 |
| `Connection leak detection triggered` (rca:153) | 134 | 커넥션을 10초 넘게 쥐었다는 사실(`application.yml:18`). 무엇 때문에 오래 쥐었는지는 말하지 않는다 |

아래 가설은 "무엇이 커넥션 5개를 오래 · 동시에 붙잡게 했는가" 와 "왜 받아 줄 다른 곳이 없었는가" 를 설명하려는 것이다.

### 3.1 가설 요약

| # | 계층 | 가설 한 문장 | 로그 확인 결과 (3.7 의 명령) | 1차 판정 | 이유 (한 줄) | 판단 불가일 때 필요했던 로그 |
|---|---|---|---|---|---|---|
| H1 | 애플리케이션 코드 | `ReportService.buildClassReport` 가 DB 조회를 마친 뒤에도 CPU 를 많이 쓰는 서명 루프(SHA-256 200,000회)를 `@Transactional` 안에서 돌려, 리포트 요청 하나가 커넥션을 10초 넘게 쥐었다 | 누수 경고 134건 중 스택에 `ReportController.classReport` 134건, 다른 컨트롤러 0건(C1). 경고 ↔ 반환 134쌍 모두 짝지어짐, 보유 시간 최소 15.5s · 중앙값 18.9s · 최대 21.5s(C2). 같은 시간대 슬로 쿼리 1건(C3) | **유지** | 반증 ④(리포트 외 경로 섞임) 해당 없음, 반증 ②(보유 시간이 슬로 쿼리로 설명됨)도 해당 없음. 단, 리포트 내용(3 distributions · 10 submissions)이 평소와 같은데 평소에는 누수 경고 0건(10초 미만)이었으므로, **14:37 부터 1건당 시간이 늘어난 이유는 H1 만으로 설명되지 않는다** | — (반증 ①③ 은 로그가 아니라 `sign()` 실측 · 배포 기록으로 확인할 항목) |
| H2 | DB · 쿼리 | `submission` 조회(`where distribution_id=? order by student_id`)가 인덱스를 제대로 타지 못해 느려졌고, 그 쿼리를 기다리는 동안 커넥션이 오래 잡혔다 | 이상 구간(UTC 05:37~05:52) 슬로 쿼리 1건(9.81s), 이후 0건(C3). 그 1건은 첫 요청 보유 19.7s 의 절반쯤. 동시 보유 1~2개였던 14:37~14:39 에도 보유 15.6~19.7s(C2 · C4) | **판단 불가 (로그 부족)** | 9.81s 쿼리 1건은 확인되지만 나머지 133건을 쿼리로 설명할 기록이 없고, 반대로 "슬로 로그에 없으니 쿼리는 빨랐다" 고 말하려면 이 슬로 로그가 빠짐없는 기록이고 임계값이 2초 이하라는 전제가 필요한데 로그에서 확인되지 않는다 | ① 리포트 요청 안의 구간별 시각(조회 종료 · `sign()` 시작/종료) 앱 로그. ② 슬로 로그 설정(`long_query_time` · `log_slow_rate_limit` · `log_slow_filter`)과 잘리지 않은 슬로 로그. ③ Hibernate 통계나 SQL 실행 시간 로그(`org.hibernate.stat` / `org.hibernate.SQL_SLOW`) 또는 `performance_schema` 문장 다이제스트 |
| H3 | 트래픽 | 교사 클라이언트들이 학급 리포트를 반복 요청해 리포트의 동시 처리 수가 평소보다 크게 늘었고, 그 수가 풀 크기(5)를 넘었다 | 리포트 요청 시간대별 07=2 · 08=5 · 09=2 · 10=11 · 11=7 · 12=11 · 13=8 · **14=179** · 15=4, 08~13시 분당 최대 2건(C5). 동시 보유 커넥션 분별 최대 14:37=1 · 14:38=1 · 14:39=2 · **14:40~14:50=5** · 14:51=2 · 14:52=0(C4). `교사단말A` 연속 요청은 직전 응답 뒤 8.1 · 14.6 · 11.0 · 15.7s 후 시작(C6). 14:40:52 까지 리포트 응답 모두 200(C5) | **유지** | 반증 ①(응답 직후 재시도), ②(평소에도 동시 5건 이상), ③(장애 후에도 같은 빈도) 모두 해당 없음. 단, 첫 느린 요청(14:37:42 시작)이 반복 요청보다 먼저라서 "느린 화면을 보고 다시 누른 것" 인지 "자동 폴링" 인지는 가르지 못했다 | — (원인 · 결과 방향을 가르려면 nginx `$request_time` 과 교사 화면의 폴링 코드가 있으면 좋다) |
| H4 | 인프라 · 배포 | 두 번째 API 인스턴스 `API-2` 가 장애 전부터 내려가 있어, 모든 요청이 `API-1` 한 대(풀 5)로만 몰렸다 | `API-2` 가 나온 nginx-error 줄 14건, 전부 `Connection refused`, 처음 14:42:01 · 마지막 14:50:52(C7). 14:42:01 이전 요청 18,967건 동안 `API-2` 연결 실패 기록 0건. nginx-error 는 `[info]` 까지 남아 있다(info 3 · warn 28 · error 137) | **기각** | "장애 전부터 내려가 있던 일반 upstream" 이면 앞선 18,967건 중 일부가 그쪽으로 가서 `connect() failed` 가 남았어야 하는데 0건이다. 남은 경우(① `backup` 이라 평소엔 안 씀, ② 14:42 무렵에 같이 죽음)는 둘 다 3.5 의 반증 조건에 해당한다 | — (①과 ② 중 무엇인지는 nginx `upstream` 설정과 `API-2` 의 앱 로그가 있어야 가릴 수 있다. 판정 자체에는 필요 없음) |

### 3.2 H1 — 애플리케이션 코드: 트랜잭션 안의 긴 서명 루프

| 항목 | 내용 |
|---|---|
| 가설 | `buildClassReport` 가 DB 작업 후 서명 루프를 트랜잭션 안에서 돌려 커넥션 보유 시간이 길어졌고, 리포트 요청이 겹치자 풀 5개가 리포트 요청에 모두 묶였다 |
| 지지 근거 | rca:153 — 누수 경고 134건 **모두** 스택에 `ReportController.classReport`. rca:157 — 누수 경고 대상 커넥션이 5개(풀 최대치)로 늘어난 뒤 그 5개에서만 반복. rca:174 — 타임아웃 시점 `total=5, active=5, idle=0`. rca:147 · rca:149–150 — 14:37:42 이전에 잡은 커넥션을 리포트 생성 로그(14:38:01.900) 2ms 뒤에 반환(약 19초 보유). rca:187 — 장애 후 리포트는 누수 경고 없이 완료. 코드: `modern/api/src/main/java/com/example/assignment/ReportService.java:65`(`@Transactional`), `:93–95`(TODO 주석 "커넥션(트랜잭션)을 쥔 채로 돈다", `sign()` 호출), `:47`(`SIGNATURE_ROUNDS = 200_000`) |
| 반증 조건 | ① 운영과 같은 사양에서 `sign()` 한 번이 **1초 미만**이고, 동시 5개를 돌려도 10초에 한참 못 미친다. ② 누수 경고된 요청의 보유 시간 대부분이 쿼리 실행 시간으로 설명된다(해당 요청마다 슬로 쿼리 항목이 있다). ③ 장애 당시 배포된 버전에서는 `sign()` 이 트랜잭션 밖에 있었다(현재 코드와 다르다). ④ 누수 경고 스택에 리포트 외 경로가 섞여 있다 |
| 확인 방법 | ① `sign()` 실측: `cd modern/api && ./gradlew compileJava` 후 `jshell --class-path build/classes/java/main` 에서 `long t=System.nanoTime(); com.example.assignment.ReportService.sign("3"+"\|학생A".repeat(10)); (System.nanoTime()-t)/1e6` (패키지 비공개 메서드라 안 되면 같은 패키지의 임시 테스트를 스크래치에서 실행). 운영 노드의 코어 수와 함께 본다. ② 보유 시간 계산: `grep -nE 'leak detection triggered\|was returned to the pool\|built class report' $L/app.log \| sed -n '1,60p'` 로 커넥션 · 스레드별 (경고 시각 − 10초) → 반환 시각을 짝지은 뒤, `grep -n '^# Time' $L/mariadb-slow.log` (14:37~14:51 KST 에 해당하는 항목이 1건뿐인지)와 대조. ③ 배포 기록에서 2026-09-16 기준 배포 커밋을 찾아 `git show <커밋>:modern/api/src/main/java/com/example/assignment/ReportService.java`. ④ `grep -A12 'leak detection triggered' $L/app.log \| grep -c 'ReportController.classReport'` 가 134 와 같은지 |

### 3.3 H2 — DB · 쿼리: `submission` 조회의 풀 스캔

| 항목 | 내용 |
|---|---|
| 가설 | `submission` 조회가 인덱스(`idx_submission_distribution`)를 쓰지 못하거나 통계가 틀어져 대량 행을 읽었고, 쿼리 대기 시간만큼 커넥션이 잡혔다 |
| 지지 근거 | rca:144–145 — 14:37:42 시작 쿼리가 9.81초, `Rows_examined: 1284310`, `Rows_sent: 4` (오늘 앞선 슬로 쿼리는 2초대 · 41,822행 이하). rca:146–147 — 그 쿼리 종료(14:37:52)와 첫 누수 경고(14:37:52.199)가 같은 초. `mariadb-slow.log:16` — 쿼리 출처가 앱 서버 `API-1`. 쿼리 대상 `submission` 은 리포트 루프(`ReportService.java:77–78`)가 배포마다 부르는 조회와 같은 모양 |
| 반증 조건 | ① 운영 `long_query_time` 이 2초 이하인데(평소 2.04s 항목이 기록됨, rca:33) 14:38~14:51 에는 슬로 쿼리 항목이 하나도 없다 → 나머지 누수 경고 133건의 보유 시간은 쿼리로 설명되지 않는다. ② `EXPLAIN` 에서 `distribution_id` 인덱스를 쓰고 예상 행 수가 작다. ③ 9.81초 쿼리의 `Thread_id 48211` 커넥션이 누수 경고된 커넥션(`@6e8f14d3` 등)과 이어지지 않는다 |
| 확인 방법 | ① 운영 DB 에서 `SHOW GLOBAL VARIABLES LIKE 'long_query_time';` · `SHOW GLOBAL VARIABLES LIKE 'log_slow%';`, 그리고 `grep -n '^# Time' $L/mariadb-slow.log` (UTC 05:37~05:52 항목 수). ② `docker compose --profile modern up -d` 후 MariaDB 에서 `SHOW INDEX FROM submission;` · `EXPLAIN select … from submission s1_0 where s1_0.distribution_id=5 order by s1_0.student_id;` · `SELECT COUNT(*) FROM submission;` — 운영과 행 수가 다르므로 운영 DB 의 `EXPLAIN` · `ANALYZE TABLE` 기록도 요청. 스키마 원본은 `db/mariadb/init/01-schema.sql:105–114`. ③ DB 의 `performance_schema` 또는 general log 가 있으면 `Thread_id 48211` 의 접속 포트 · 시각을 앱 커넥션 생성 시각과 대조 |

### 3.4 H3 — 트래픽: 학급 리포트 반복 요청 증가

| 항목 | 내용 |
|---|---|
| 가설 | 교사 화면(`/teacher/classes/N/report`)에서 여러 클라이언트가 리포트를 짧은 간격으로 반복 요청해, 리포트 동시 처리 수가 풀 크기 5를 넘었다 |
| 지지 근거 | rca:151 — `/api/classes/{N}/report` 가 평소 시간당 2~11건에서 14:40 이후 분당 10~19건(14시 179건). 전체 요청 수는 평소 범위. rca:152 — `교사단말A` 가 class 3 리포트를 약 27~30초 간격으로 반복. rca:155 — 리포트 요청 클라이언트가 4개(`교사단말B` · `교사단말A` · `교사단말C` · `교사단말D`)로 늘어남. rca:157 — 누수 대상 커넥션이 리포트 요청 증가와 함께 1 → 5개로 늘어남 |
| 반증 조건 | ① 리포트 요청 증가가 첫 지연(14:37:42 무렵) **이후**에 시작됐고, 각 요청이 직전 요청의 응답 직후(또는 실패 직후)에 다시 온다 → 증가는 원인이 아니라 지연에 따른 재시도 · 새로고침(결과)이다. ② 평소 시간대에도 분당 리포트 동시 요청이 5건 이상인 구간이 있었는데 누수 경고가 없었다. ③ 장애 후(14:52~)에도 같은 빈도로 요청이 계속됐는데 문제가 없었다 |
| 확인 방법 | ① 클라이언트별 리포트 요청 시각 · 상태: `grep -E '"GET /api/classes/[0-9]+/report' $L/nginx-access.log \| awk '$4 ~ /16\/Sep\/2026:14:(3[0-9]\|4[0-9]\|5[0-2])/ {print $1, substr($4,14,8), $7, $9}' \| sort -k1,1 -k2,2` — 간격이 응답과 무관하게 일정(폴링)인지, 응답 직후 재요청인지 본다. access 로그 시각은 요청 **완료** 시각이고 `$request_time` 이 없으므로, 요청 도착 시각은 app 로그의 처리 시작 · 완료와 함께 추정한다. ② 평소 구간 분당 리포트 수: 위 명령의 시각 조건을 `(0[8-9]\|1[0-3])` 으로 바꿔 `\| awk '{print substr($2,1,5)}' \| uniq -c \| sort -rn \| head`. ③ 교사 화면(`lms.example.com`)에 자동 새로고침 · 폴링이 있는지 프런트엔드 담당 확인(이 저장소의 `modern/web` 에는 리포트 화면이 없다) |

### 3.5 H4 — 인프라 · 배포: 두 번째 API 인스턴스 부재

| 항목 | 내용 |
|---|---|
| 가설 | upstream `item_bank_api` 의 두 번째 인스턴스 `API-2:8080` 이 장애 전부터 내려가 있어(배포 중 · 기동 실패 등) 전체 요청을 `API-1` 한 대의 풀 5개가 감당했다 |
| 지지 근거 | rca:170 — `API-2:8080` 이 nginx-error.log 에 처음 나오는 순간(14:42:01)부터 `Connection refused`, 마지막(14:50:52)까지 한 번도 성공 기록 없음. rca:169 · rca:172 — `API-1` 이 비활성화되자 곧바로 `no live upstreams` (다른 살아 있는 upstream 이 없었다). `mariadb-slow.log:2` · `:9` · `:16` — 오늘 슬로 쿼리 3건의 접속 호스트가 모두 `API-1`. rca:159 — 같은 시간대 앱 재기동 · 설정 재적재 기록 없음(즉 `app.log` 는 한 인스턴스만 보여 준다) |
| 반증 조건 | ① nginx 설정에서 `API-2` 가 `backup` 이거나 평소 트래픽을 받지 않는 구성이다 → 평소에도 1대로 운영했으므로 "용량이 줄었다" 는 가설이 틀린다. ② `API-2` 가 14:42 직전까지 정상 응답했다(그 인스턴스의 앱 · 접근 로그가 있다) → 장애 **중**에 함께 죽은 것이므로 선행 원인이 아니다. ③ 평소(08~13시)에도 `API-1` 하나만 요청을 받았다 |
| 확인 방법 | ① 운영 nginx 에서 `nginx -T \| grep -A10 'upstream item_bank_api'` (이 저장소에는 nginx 설정이 없으므로 인프라 담당에게 요청). ② `API-2` 의 2026-09-16 `app.log` · 배포 / 오케스트레이터 이벤트(재시작 · 헬스체크 실패 · 배포 시각) 확보. 지금 받은 `app.log` 가 어느 호스트 것인지도 함께 확인. ③ 현재 access 로그에는 upstream 주소 필드가 없다(`grep -c 'API-2' $L/nginx-access.log` = 0). `log_format` 에 `$upstream_addr` 가 있는 다른 로그(예: 다른 날짜 · LB 로그)가 있으면 평소 분배 비율을 센다. 없으면 DB 쪽에서 `SELECT host FROM information_schema.processlist WHERE user='app';` 를 평소 시간에 떠 둔 기록이 있는지 확인 |

### 3.6 다음 단계

- 각 가설의 **반증 조건**을 먼저 확인한다. 반증된 가설은 지우지 않고 "반증됨 + 근거" 로 남긴다.
- 로그로 확인할 수 있는 것은 3.7 에서 실행했다. 남은 확인: H1 ①③(`sign()` 실측, 배포 버전), H2 ①②(운영 슬로 로그 설정, `EXPLAIN`).
- 로그 확인에서 새로 나온 질문: 리포트 1건의 내용은 평소와 같은데, 동시 처리 1~2건이던 14:37~14:39 에도 보유 시간이 15.6~19.7s 로 평소(누수 경고 0건, 즉 10초 미만)보다 길다. 이 증가분이 쿼리 쪽(H2)인지 서명 쪽(H1, 예: 호스트 CPU 부족)인지는 위 로그로 가르지 못한다.
- 최종 판정은 남은 확인이 끝난 뒤 별도 절에서 한다.

### 3.7 로그 확인 실행 기록

> 2026-10-06 실행. 저장소 루트에서 `L=incident-logs/a-connection-pool`. 결과는 출력 그대로 옮겼다(긴 출력은 앞부분만, 생략 표시). 로그 파일은 고치지 않았다.

**C1. 누수 경고 스택의 경로 (H1 반증 ④)**

```bash
grep -c 'leak detection triggered' $L/app.log
grep -A12 'leak detection triggered' $L/app.log | grep -c 'ReportController.classReport'
grep -A12 'leak detection triggered' $L/app.log | grep -oE 'com\.example\.[a-z]+\.[A-Za-z]+Controller\.[a-zA-Z]+' | sort | uniq -c
```

```
134
134
    134 com.example.assignment.ReportController.classReport
```

**C2. 커넥션 보유 시간 (H1 반증 ②, H2)** — 누수 경고와 반환 로그를 `커넥션 + 스레드` 로 짝짓고, 보유 시간 = 반환 시각 − (경고 시각 − 10초). 경고는 획득 10초 뒤에 찍히므로(`application.yml:18`) 획득 시각을 그렇게 추정했다.

```bash
grep -E 'leak detection triggered|was returned to the pool' $L/app.log | awk '
function sec(t){split(substr(t,12,12),a,":");return a[1]*3600+a[2]*60+a[3]}
/triggered/{match($0,/exec-[0-9]+/);th=substr($0,RSTART,RLENGTH);match($0,/Connection@[0-9a-f]+/);c=substr($0,RSTART+11,RLENGTH-11);k=c" "th;w[k]=sec($1);wt[k]=substr($1,12,12);n++}
/returned/{match($0,/exec-[0-9]+/);th=substr($0,RSTART,RLENGTH);match($0,/Connection@[0-9a-f]+/);c=substr($0,RSTART+11,RLENGTH-11);k=c" "th;if(k in w){h=sec($1)-w[k]+10;hold[++m]=h;if(m<=5||h>mx)printf "%s %s warn=%s hold>=%.1fs\n",c,th,wt[k],h;if(h>mx)mx=h;s+=h;delete w[k]}}
END{asort(hold);printf "warn=%d paired=%d min=%.1f median=%.1f max=%.1f mean=%.1f\n",n,m,hold[1],hold[int((m+1)/2)],hold[m],s/m}'
```

```
6e8f14d3 exec-6 warn=14:37:52.199 hold>=19.7s
6e8f14d3 exec-3 warn=14:38:20.016 hold>=17.1s
2ac19f07 exec-6 warn=14:38:51.641 hold>=16.1s
6e8f14d3 exec-1 warn=14:39:18.720 hold>=15.7s
2ac19f07 exec-5 warn=14:39:48.603 hold>=15.6s
5b0e9e0c exec-9 warn=14:40:56.917 hold>=20.5s
5b0e9e0c exec-7 warn=14:41:17.440 hold>=21.1s
5b0e9e0c exec-6 warn=14:42:27.519 hold>=21.5s
warn=134 paired=134 min=15.5 median=18.9 max=21.5 mean=19.0
```

(앞 5줄은 처음 5쌍, 그 뒤는 최대값이 갱신될 때만 출력.) 평소 시간대 누수 경고: `grep 'leak detection triggered' $L/app.log | awk '{print substr($1,12,5)}' | sort | head -1` → `14:37` (14:37 이전 0건).

**C3. 이상 구간의 슬로 쿼리 수 (H2 반증 ①의 로그 부분)**

```bash
grep -n '^# Time' $L/mariadb-slow.log
grep -c '^# Time: 260916  5:\(3[7-9]\|[45][0-9]\)' $L/mariadb-slow.log   # UTC 05:37~05:59 = KST 14:37~14:59
grep -n 'User@Host' $L/mariadb-slow.log
```

```
1:# Time: 260916  0:14:09
8:# Time: 260916  2:02:42
15:# Time: 260916  5:37:52
1
2:# User@Host: app[app] @  [API-1]
9:# User@Host: app[app] @  [API-1]
16:# User@Host: app[app] @  [API-1]
```

**C4. 동시에 잡혀 있던 커넥션 수, 분별 최대 (H3)** — C2 의 [획득 추정, 반환) 구간을 1초 단위로 겹쳐 셌다.

```bash
grep -E 'leak detection triggered|was returned to the pool' $L/app.log | awk '
function sec(t){split(substr(t,12,12),a,":");return a[1]*3600+a[2]*60+a[3]}
/triggered/{match($0,/exec-[0-9]+/);th=substr($0,RSTART,RLENGTH);match($0,/Connection@[0-9a-f]+/);k=substr($0,RSTART,RLENGTH)th;st[k]=sec($1)-10}
/returned/{match($0,/exec-[0-9]+/);th=substr($0,RSTART,RLENGTH);match($0,/Connection@[0-9a-f]+/);k=substr($0,RSTART,RLENGTH)th;if(k in st){n++;s[n]=st[k];e[n]=sec($1);delete st[k]}}
END{for(t=sec("xxxxxxxxxxx14:37:30.000");t<=sec("xxxxxxxxxxx14:52:00.000");t++){c=0;for(i=1;i<=n;i++)if(s[i]<=t&&t<e[i])c++;m=int(t/60);if(c>mx[m])mx[m]=c}for(m=int(sec("xxxxxxxxxxx14:37:30.000")/60);m<=int(sec("xxxxxxxxxxx14:52:00.000")/60);m++)printf "%02d:%02d=%d ",int(m/60),m%60,mx[m];print ""}'
```

```
14:37=1 14:38=1 14:39=2 14:40=5 14:41=5 14:42=5 14:43=5 14:44=5 14:45=5 14:46=5 14:47=5 14:48=5 14:49=5 14:50=5 14:51=2 14:52=0
```

**C5. 리포트 요청 수 · 평소 동시성 · 장애 후 빈도 (H3 반증 ②③)**

```bash
grep -E '"GET /api/classes/[0-9]+/report' $L/nginx-access.log | awk '{print substr($4,14,2)}' | uniq -c | tr '\n' ' '
grep -E '"GET /api/classes/[0-9]+/report' $L/nginx-access.log | awk '$4 ~ /16\/Sep\/2026:(0[8-9]|1[0-3]):/ {print substr($4,14,5)}' | uniq -c | sort -rn | head -3
grep -E '"GET /api/classes/[0-9]+/report' $L/nginx-access.log | awk '$4 ~ /16\/Sep\/2026:14:/ {t=substr($4,14,8); if(!($1 in f))f[$1]=t; l[$1]=t; c[$1]++} END{for(k in c)print k, f[k], l[k], c[k]}' | sort
grep -E '"GET /api/classes/[0-9]+/report' $L/nginx-access.log | awk '$4 !~ /16\/Sep\/2026:14:/ {print $1}' | sort | uniq -c
```

```
      2 07       5 08       2 09      11 10       7 11      11 12       8 13     179 14       4 15
      2 12:20
      1 13:59
      1 13:51
교사단말B 14:05:42 14:59:52 70
교사단말A 14:10:05 14:51:24 43
교사단말C 14:13:38 14:59:15 37
교사단말D 14:40:43 14:49:53 29
     17 교사단말B
     14 교사단말A
     19 교사단말C
```

`교사단말D` 는 14시 이전에는 리포트를 요청한 적이 없다. 14:00~14:40:52 리포트 요청 22건은 모두 200 (`… | awk '$4 ~ /16\/Sep\/2026:14:([0-3][0-9]|40)/ {print $1, substr($4,14,8), $7, $9}'`).

**C6. 반복 요청이 응답 직후 재시도인지 (H3 반증 ①)** — `교사단말A` 의 class 3 요청을 app 로그의 처리 스레드와 맞췄다.

```bash
grep -E '^교사단말A .*"GET /api/classes/3/report' $L/nginx-access.log | awk '$4 ~ /14:3[89]|14:40:[0-4]/ {print substr($4,14,8)}' | tr '\n' ' '
grep -nE 'T14:3[89].*built class report|T14:40:[0-4].*built class report' $L/app.log | grep -oE 'T[0-9:.]+|exec-[0-9]+|class [0-9]+:' | paste - - - | head -5
grep -E 'leak detection triggered' $L/app.log | awk '$0 ~ /T14:(3[789]|40:[0-3])/' | grep -oE 'T[0-9:.]+|exec-[0-9]+' | paste - - | head -6
```

```
14:38:01 14:38:27 14:38:57 14:39:24 14:39:55 14:40:19 14:40:44
T14:38:01.900	exec-6	class 3:
T14:38:27.072	exec-3	class 3:
T14:38:57.712	exec-6	class 3:
T14:39:24.422	exec-1	class 3:
T14:39:54.236	exec-5	class 1:
T14:37:52.199	exec-6
T14:38:20.016	exec-3
T14:38:51.641	exec-6
T14:39:18.720	exec-1
T14:39:48.603	exec-5
T14:39:50.132	exec-4
```

다음 요청 시작(경고 − 10초) − 직전 응답 완료: 14:38:10.0 − 14:38:01.9 = **8.1s**, 14:38:41.6 − 14:38:27.1 = **14.6s**, 14:39:08.7 − 14:38:57.7 = **11.0s**, 14:39:40.1(exec-4, 14:39:55 완료분) − 14:39:24.4 = **15.7s**.

**C7. 두 번째 upstream `API-2` (H4)**

```bash
grep -oE 'upstream: "http://[^/]+' $L/nginx-error.log | sort | uniq -c
grep -n 'API-2' $L/nginx-error.log | sed -n '1p;$p' | cut -c1-120
grep -oE '\[(info|warn|error|crit|alert|emerg|notice)\]' $L/nginx-error.log | sort | uniq -c
awk '$4 < "[16/Sep/2026:14:42:01"' $L/nginx-access.log | wc -l
grep -c 'API-[12]' $L/nginx-access.log
grep -cE 'Started |Shutting down|Tomcat started' $L/app.log
```

```
     83 upstream: "http://API-1:8080
     14 upstream: "http://API-2:8080
     68 upstream: "http://item_bank_api
11:2026/09/16 14:42:01 [error] 29#29: *225045 connect() failed (111: Connection refused) while connecting to upstream, c
159:2026/09/16 14:50:52 [error] 29#29: *225778 connect() failed (111: Connection refused) while connecting to upstream,
    137 [error]
      3 [info]
     28 [warn]
18967
0
0
```

`API-2` 줄 14건의 메시지는 모두 `connect() failed (111: Connection refused)`. access 로그에는 upstream 주소 필드가 없고(0건), `app.log` 는 기동 · 종료 기록이 없는 한 인스턴스의 로그다.

### 3.8 판정

> 대조 범위: 3.1 에서 유지된 H1 · H3 을 `modern/api` 의 설정 파일과 로그에 찍힌 클래스의 코드와 맞춰 봤다. 설정값은 파일에서 읽은 값만 적었다. 벤치마크 · 테스트 등 별도 실행은 하지 않았다. H2 · H4 는 코드 대조 대상이 아니지만, 최종 정리를 위해 관련 코드를 참고로 적었다.
>
> 전제: 이 저장소의 코드 · 설정이 장애 당시 배포본과 같다고 본다. `application.yml:11` 주석이 "운영 값과 같게 맞춘다" 고 적고 있다. `ReportService.java` · `application.yml` 의 git 이력은 초기 커밋 `1260542` 하나뿐이다. 배포본과 같은지는 확인하지 않았다(3.2 반증 ③).

#### 3.8.1 로그의 단서 → 코드 · 설정

| 로그에 찍힌 단서 | 찾은 위치 | 읽은 내용 |
|---|---|---|
| 풀 이름 `itembank-pool`, `total=5`, `request timed out after 3000ms` (app.log:1522) | `modern/api/src/main/resources/application.yml:10`, `:14`, `:15` | `pool-name: itembank-pool`, `maximum-pool-size: 5`, `connection-timeout: 3000` |
| `ProxyLeakTask` "Connection leak detection triggered" (app.log:1243) | `application.yml:18` | `leak-detection-threshold: 10000` (10초) |
| 누수 스택 `HibernateJpaDialect.beginTransaction` → `ReportService$$SpringCGLIB$$0.buildClassReport` → `ReportController.classReport(ReportController.java:22)` (app.log:1249 · 1253 · 1254) | `ReportController.java:20–23`, `ReportService.java:65–66` | 컨트롤러는 `reportService.buildClassReport(id)` 만 부른다. 서비스 메서드는 `@Transactional`(readOnly 아님)이다. 커넥션은 트랜잭션이 **시작될 때** 잡힌다(스택의 `beginTransaction`) |
| `ReportService` "built class report for class N: 3 distributions, 10 submissions" (app.log:1258) | `ReportService.java:97–98` | 이 로그는 `sign()` 호출(`:95`) **다음 줄**에서 찍힌다 |
| 위 로그 2ms 뒤 "Previously reported leaked connection … was returned" (app.log:1258 → 1259, .900 → .902) | `ReportService.java:99–106` | 로그 뒤에는 `return new ClassReport(...)` 뿐이고, 커넥션은 메서드가 끝나 트랜잭션이 커밋될 때 반환된다. 즉 **`sign()` 을 도는 내내 커넥션을 쥐고 있었다**는 것이 로그 순서로도 확인된다 |
| 슬로 쿼리 `from submission s1_0 where s1_0.distribution_id=5 order by s1_0.student_id` (mariadb-slow.log:21) | `SubmissionRepository.java:8`, `ReportService.java:76–78` | 파생 쿼리 `findByDistributionIdOrderByStudentIdAsc`, 배포 하나마다 한 번씩(N+1 형태) 호출된다 |
| pool 타임아웃 → `CannotCreateTransactionException` 49건 (app.log:1524) | `GlobalExceptionHandler.java:53–55` | `Exception` 처리기가 `unhandled exception on …` 을 남기고 500 으로 바꾼다. 경로별: `/api/units` 33 · `/api/items` 7 · `/api/distributions` 4 · `/api/classes` 5 |
| 그 피해 경로의 스택 `ItemService$$SpringCGLIB$$0.listActiveItemsByUnit` (app.log:1533) | `ItemService.java:14`, `UnitService.java:10`, `DistributionService.java:30` | 모두 `@Transactional(readOnly = true)` 라 시작할 때 커넥션이 있어야 한다. 이 요청들은 풀을 오래 쥐는 쪽이 아니라 **기다리다 실패한 쪽**이다 |

#### 3.8.2 가설별 코드 · 설정 대조

| # | 판정 | 근거 (파일:줄번호, 인용) |
|---|---|---|
| H1 | **코드 · 설정이 가설을 지지한다** | ① `ReportService.java:65` `@Transactional` 메서드 안에서 DB 조회(`:67–87`)가 끝난 뒤 `:95` `String signature = sign(payload.toString());` 를 호출한다. 바로 위 `:93–94` 주석이 `// TODO: 트랜잭션 밖으로 — … DB 작업은 위에서 끝났는데 커넥션(트랜잭션)을 쥔 채로 돈다.` 라고 적고 있다. ② `:47` `static final int SIGNATURE_ROUNDS = 200_000;`, `:112–115` 에서 SHA-256 을 그 횟수만큼 반복한다. 순수 CPU 작업이고 DB 를 쓰지 않는다. ③ 3.8.1 의 로그 순서(서명 뒤 로그 → 2ms 뒤 커넥션 반환)가 이 구조와 맞는다. ④ `application.yml:14` `maximum-pool-size: 5`, `:15` `connection-timeout: 3000` 이라, 리포트 5건이 동시에 서명 중이면 다른 요청은 3초 뒤 실패한다. 실제 실패 49건 중 44건이 리포트가 아닌 짧은 읽기 요청이었다 |
| H3 | **코드 · 설정이 가설을 지지한다** (트래픽을 만든 쪽의 코드는 찾지 못함) | ① `ReportController.java:20–23` 에 캐시 · 중복 요청 억제가 없다. `modern/api/src/main/java` 전체에 `@Cacheable` · `@EnableCaching` · rate limit · `@Async` 가 없다(grep 0건). 따라서 같은 학급 리포트를 반복 요청하면 매번 커넥션 1개를 15~21초 쓴다. ② 풀 상한이 `application.yml:14` 의 5 이므로, 3.7 C4 의 "동시 보유 5" 는 이 설정값에 정확히 닿은 것이다. ③ 반복 요청을 만든 교사 화면(`referer: https://lms.example.com/teacher/classes/N/report`)의 코드는 이 저장소에 없다. `modern/web` 에도 리포트 화면이 없고, `WebConfig.java:19–20` 은 `localhost:5173` 의 GET 만 허용한다. 그래서 폴링인지 사용자의 반복 클릭인지는 코드로 확인하지 못했다 |
| H2 (참고) | 관련 코드는 찾았으나 가설을 가르지 못한다 | `SubmissionRepository.java:8` 의 조회 조건 컬럼에는 스키마상 인덱스 `KEY idx_submission_distribution (distribution_id)` 가 있다(`db/mariadb/init/01-schema.sql:112`). 코드 · 스키마만 보면 풀 스캔이 날 이유가 없다. 따라서 `Rows_examined: 1284310` 은 운영 DB 쪽(통계 · 인덱스 상태)을 봐야 하며, 이 저장소로는 확인할 수 없다. `Submission.java:24–26` 매핑은 스키마와 같다 |
| H4 (참고) | 관련 코드를 찾지 못했다 | upstream 구성(`item_bank_api`)은 nginx 설정에 있고, 이 저장소에는 nginx 설정이 없다 |

#### 3.8.3 최종 판정

**채택: H1 — `ReportService.buildClassReport` 가 CPU 를 쓰는 서명 루프(SHA-256 200,000회)를 `@Transactional` 안에서 돌려, 리포트 1건이 커넥션을 15~21초 쥐었다. 풀이 5개(`application.yml:14`)라 리포트가 5건 겹치는 순간 풀이 고갈됐고, 다른 모든 API 가 3초(`:15`) 대기 후 500 이 됐다. 앱 응답이 늦어지자 nginx 의 504 · upstream 비활성화 · 502 로 번졌다.**

| 기각 가설 | 기각 사유 |
|---|---|
| H2 DB · 쿼리 | 근본 원인으로는 기각. 이상 구간 슬로 쿼리가 1건(9.81s)뿐이고, 나머지 누수 133건의 보유 시간을 쿼리로 설명하는 기록이 없다(3.7 C3). 반면 커넥션이 서명 루프 동안 잡혀 있다는 것은 코드와 로그 순서로 확인된다(3.8.1). 다만 첫 요청의 보유 시간 19.7s 중 약 9.8s 는 이 쿼리와 겹친다. 그래서 "14:37 에 리포트 1건이 왜 느려졌나" 를 설명하는 **촉발 요인 후보로는 남겨 둔다**(운영 DB 의 `EXPLAIN` · 슬로 로그 설정 필요) |
| H3 트래픽 | 근본 원인으로는 기각. 14시 리포트 요청은 179건(분당 최대 19건)이고 전체 요청 수는 평소 범위였다(rca:151). 요청 1건이 커넥션을 오래 쥐지 않았다면 이 정도 양으로 동시 5건에 닿기 어렵다. 즉 반복 요청은 H1 의 약점을 드러낸 **증폭 요인**이고, 그 자체가 원인은 아니다. 반복 요청을 만든 클라이언트 코드는 확인하지 못했다 |
| H4 인프라 · 배포 | 3.1 · 3.7 C7 에서 기각. 14:42:01 이전 18,967건 동안 `API-2` 연결 실패가 0건이다. 이 인스턴스가 장애 전부터 일반 upstream 으로 내려가 있었다면 실패 기록이 남았어야 한다. 14:42 이후의 `Connection refused` 는 장애의 결과이거나 `backup` 구성일 것으로 보이며, 원인은 아니다 |

**확신 수준: 중간**

- 높게 보는 이유
  - 메커니즘이 코드(`ReportService.java:65`, `:93–95`)와 설정(`application.yml:14–15`)에 그대로 있다. 코드 주석도 같은 문제를 적고 있다.
  - 로그의 순서(서명 뒤 로그 → 2ms 뒤 커넥션 반환), 누수 134건 전부 `ReportController.classReport`, 동시 보유가 정확히 5, 실패 49건 중 44건이 리포트 외 경로라는 점이 모두 한 방향을 가리킨다.
- 높음까지 올리지 못하는 이유
  1. 리포트 내용(3 distributions · 10 submissions)과 `SIGNATURE_ROUNDS` 는 하루 종일 같았는데, 평소에는 누수 경고가 0건(10초 미만)이었다. 반면 14:37~14:39 에는 동시 1~2건만 처리하면서도 15.6~19.7s 를 쥐었다. H1 은 "왜 고갈되는가" 는 설명하지만 "왜 14:37 부터 1건이 느려졌는가" 는 설명하지 못한다(H2 의 9.81s 쿼리, 또는 로그에 없는 호스트 CPU 부족 등).
  2. `sign()` 한 번의 실제 소요 시간을 재지 않았다(이번 단계는 별도 실행 금지).
  3. 이 저장소의 코드 · 설정이 장애 당시 배포본과 같은지 확인하지 않았다.
- 확신을 올리려면
  - `sign()` 실측과 장애 당시 배포 커밋을 확인한다(3.2 반증 ①③).
  - 리포트 1건 안에서 조회 종료와 서명 시작 · 종료 시각을 남기는 로그를 추가한다.
  - 운영 DB 의 슬로 로그 설정과 `EXPLAIN` 을 확보한다(3.3).

## 4. 원인

> 3.8.3 에서 채택한 H1 을 인과 순서로 쓴다. 문장 번호(S·B)는 5절의 근거와 짝을 이룬다.

### 4.1 직접 원인 (인과 순서)

- **S1.** 14:37:42 무렵 학급 리포트 요청이 들어왔다. `ReportService.buildClassReport` 가 트랜잭션을 시작하면서 커넥션 1개를 잡았다.
- **S2.** 이 메서드는 DB 조회를 마친 뒤 SHA-256 200,000회 서명 루프를 **같은 트랜잭션 안에서** 돈다. 커넥션은 서명이 끝나고 메서드가 반환될 때에야 풀로 돌아간다. 이번 장애에서 리포트 1건이 커넥션을 쥔 시간은 15.5~21.5초(중앙값 18.9초)였다.
- **S3.** 14:38 부터 교사 클라이언트 4곳이 리포트를 반복 요청했다. 리포트 요청이 분당 0~1건에서 10~19건으로 늘었다(전체 요청 수는 평소 범위).
- **S4.** 리포트가 동시에 쥔 커넥션이 14:40:26 무렵 풀 최대치 5에 닿았다.
- **S5.** 14:40:52 부터 다른 API(단원 · 문항 · 배포 조회)가 커넥션을 3초 기다리다 실패해 500 을 돌려줬다. 49건 중 44건이 리포트가 아닌 요청이었다.
- **S6.** 커넥션을 기다리던 리포트 요청이 nginx 타임아웃을 넘겨 504 가 됐다(14:41:06~). nginx 가 `API-1` 을 일시 비활성화하자, 대체 upstream `API-2` 는 연결을 거부했고 `no live upstreams` 502 로 이어졌다(14:42:01~).
- **S7.** 14:51 에 리포트 요청이 줄면서 커넥션이 반환됐고, 14:51:47 이후 오류가 0건이 됐다.

### 4.2 배경 원인 (S2 가 장애로 번질 수 있었던 조건)

- **B1. 풀이 작고 대기가 짧으며 격리가 없다.** 최대 5개, 대기 3초다. 무거운 엔드포인트와 가벼운 조회가 같은 풀을 쓰므로, 리포트 5건이면 API 전체가 멈춘다.
- **B2. 리포트에 반복 요청을 막는 장치가 없다.** 캐시 · 동시 실행 상한 · 요청 빈도 제한이 없어서, 같은 학급 리포트를 반복 요청해도 매번 커넥션을 15초 넘게 쓴다.
- **B3. 알려진 결함이 방치됐다.** 코드에 "트랜잭션 밖으로" TODO 가 이미 적혀 있었다. 이 메서드는 DB 에 쓰지 않는데도 `readOnly` 가 아니어서 컨벤션(CLAUDE.md 2절)에도 어긋났다.
- **B4. 최초 신호가 경보로 이어지지 않았다.** 첫 누수 경고는 첫 5xx 보다 3분 먼저(14:37:52) 찍혔지만 WARN 로그로만 남았다. 이번 로그에는 경보 · 대응 기록이 없다.

> 미해명: 동시 처리가 1~2건뿐이던 14:37~14:39 에도 1건 보유 시간이 15.6~19.7초였다. 평소에는 10초 미만(누수 경고 0건)이었다. 이 증가분의 원인은 9절에 남긴다.

## 5. 근거 로그 줄

| 문장 | 파일:줄 | 발췌 |
|---|---|---|
| S1 | `app.log:1243` · `1249` · `1253–1254` | `14:37:52.199 … Connection leak detection triggered for …Connection@6e8f14d3 on thread http-nio-8080-exec-6` / `HibernateJpaDialect.beginTransaction` / `ReportService$$SpringCGLIB$$0.buildClassReport` / `ReportController.classReport(ReportController.java:22)` (경고는 획득 10초 뒤 → 획득 14:37:42 무렵) |
| S1 | `nginx-access.log:18801` | `교사단말A … [16/Sep/2026:14:38:01 +0900] "GET /api/classes/3/report HTTP/1.1" 200` |
| S2 | `ReportService.java:65` · `:93–95` · `:47` | `@Transactional` / `// TODO: 트랜잭션 밖으로 … 커넥션(트랜잭션)을 쥔 채로 돈다.` / `String signature = sign(payload.toString());` / `SIGNATURE_ROUNDS = 200_000` |
| S2 | `app.log:1258–1259` | `14:38:01.900 … built class report for class 3` → `14:38:01.902 … Previously reported leaked connection …@6e8f14d3 … was returned` (서명 뒤 로그 2ms 후 반환) |
| S2 | 3.7 C2 (app.log 누수 경고 · 반환 134쌍) | `warn=134 paired=134 min=15.5 median=18.9 max=21.5` |
| S3 | `nginx-access.log:18801` · `18881` · 3.7 C5 | `교사단말B … "GET /api/classes/1/report" 200` / 시간대별 `13시 8 · 14시 179`, 클라이언트 `교사단말B 70 · 교사단말A 43 · 교사단말C 37 · 교사단말D 29` |
| S4 | `app.log:1370` · 3.7 C4 | `14:40:12.901 … leak detection triggered for …Connection@5b0e9e0c` (다섯 번째 커넥션) / `14:40=5 … 14:50=5` |
| S5 | `app.log:1522–1523` | `14:40:52.319 … Connection is not available, request timed out after 3000ms (total=5, active=5, idle=0, waiting=4)` / `unhandled exception on /api/units/M6-2/items` |
| S5 | `nginx-access.log:18918` · 3.8.1 | `14:40:52 … "GET /api/units/M6-2/items HTTP/1.1" 500` / 경로별 `units 33 · items 7 · distributions 4 · classes 5` |
| S6 | `nginx-error.log:6` · `nginx-access.log:18933` | `14:41:06 … upstream timed out (110: Connection timed out) while reading response header` / `"GET /api/classes/3/report" 504` |
| S6 | `nginx-error.log:10` · `11` · `12` · `nginx-access.log:18970` | `upstream server temporarily disabled` / `connect() failed (111: Connection refused) … API-2:8080` / `no live upstreams` / `"GET /api/classes/2/report" 502` |
| S7 | `app.log:5251` · `5267` · `nginx-access.log:19381` · `nginx-error.log:168` | `14:51:37.674` 마지막 누수 경고 / `14:51:49.148` 마지막 반환 / `14:51:47 … 504` 마지막 5xx / `14:51:47 upstream timed out` |
| B1 | `modern/api/src/main/resources/application.yml:14–15` | `maximum-pool-size: 5` / `connection-timeout: 3000` |
| B2 | `ReportController.java:20–23` | `@GetMapping("/{id}/report") … return reportService.buildClassReport(id);` (캐시 · 상한 없음, `@Cacheable` grep 0건) |
| B3 | `ReportService.java:65` · `:93–94` | `@Transactional` (readOnly 아님) / TODO 주석 |
| B4 | `app.log:1243` · `nginx-access.log:18918` | 첫 누수 경고 `14:37:52` ↔ 첫 5xx `14:40:52` |

## 6. 수정안 (지금 적용)

> 코드는 고치지 않았다. 아래는 제안이다.

**6.1 `modern/api/src/main/java/com/example/assignment/ReportService.java` — 메서드 전체를 트랜잭션 밖에서 돌린다**

- `:65` 의 `@Transactional` 을 `@Transactional(propagation = Propagation.NOT_SUPPORTED)` 로 바꾼다. import 에 `org.springframework.transaction.annotation.Propagation` 을 추가한다.
- 효과: 저장소 호출(`findById`, `findByClassRoomIdOrderByDistributedAtAsc`, `findByDistributionIdOrderByStudentIdAsc`)이 Spring Data 기본 읽기 전용 트랜잭션 안에서 **호출마다** 커넥션을 잡고 바로 반환한다. 서명 루프(`:95`) 동안에는 커넥션을 쥐지 않는다.
- 이 형태를 고른 이유:
  - 리포트가 읽는 연관은 `@EntityGraph`(`DistributionRepository.java:22–23`)로 함께 로드되고, `Submission` 은 기본 필드만 읽는다. 그래서 OSIV 가 꺼진 상태에서도 지연 로딩이 일어나지 않는다.
  - `vendor-prs/pr-3-out-of-scope.patch` 가 이 파일의 `:89–106`(평균 계산 · TODO 주석 · `sign` 호출 · 로그 · `return`)을 문맥으로 쓴다. 조회부를 다른 빈으로 옮기거나 `TransactionTemplate` 으로 감싸면 이 문맥이 깨진다. 이번 변경은 `:65` 와 import 만 건드리므로 패치 문맥을 지킨다.
- 바뀌지 않는 것: 응답 `ClassReport` 필드, 경로, 상태 코드, 서명 값(같은 payload 이므로 같은 값). 동작 변경 없이 트랜잭션 경계만 바꾼다.
- 감수할 점: 조회 1+1+N 회가 한 스냅샷이 아니게 된다. 조회 사이에 제출이 들어오면 집계에 반영될 수 있다.
- 남는 정리: TODO 주석(`:93–94`)은 pr-3 패치 문맥이라 이번에는 그대로 둔다. pr-3 처리 뒤 별도 커밋으로 지운다.
- 적용 후 확인(CLAUDE.md 5절):

  ```bash
  cd modern/api && ./gradlew test
  cd characterization && npm test
  for p in vendor-prs/*.patch; do git apply --check "$p"; done
  ```

**6.2 설정 — 바꾸지 않는다**

`maximum-pool-size` 를 늘리는 것은 수정이 아니다. 풀을 10개로 늘려도 리포트 10건이 겹치면 같은 일이 생긴다. 게다가 `application.yml:11` 은 운영 값과 맞춘 설정이다.

## 7. 재발 방지 (구조)

| # | 장치 | 막는 것 | 비고 |
|---|---|---|---|
| 7.1 | CLAUDE.md 2절에 규칙 추가: "`@Transactional` 안에서 외부 호출 · CPU 를 오래 쓰는 계산 · 대기를 하지 않는다". `convention-check` · `verify` 점검 항목에도 넣는다 | 같은 유형 코드의 유입 | 리뷰 단계 |
| 7.2 | **풀 고갈 회귀 테스트**: `test` 프로필(`maximum-pool-size: 2`, `application-test.yml:9`)에서 무거운 엔드포인트를 풀 크기 + 1 만큼 동시에 호출하는 동안, 가벼운 조회가 `connection-timeout` 안에 성공하는지 확인 | 트랜잭션 안 장시간 작업의 재도입 | CI 에서 자동 |
| 7.3 | **격리(bulkhead)**: 리포트처럼 무거운 엔드포인트에 동시 실행 상한(풀 크기보다 작게, 예: 2)을 두어 풀 일부를 다른 API 몫으로 남긴다 | 엔드포인트 하나가 풀 전체를 잡는 것 | 상한 초과 응답(429/503)은 **기존 API 상태 코드 변경이라 합의 필요**. 라이브러리를 쓰면 의존성 추가도 먼저 묻는다 |
| 7.4 | **반복 요청 억제**: 학급별 리포트 결과를 캐시(배포 · 제출 변경 시 무효화)하고, 교사 화면의 갱신 주기 · 중복 클릭 방지는 프런트엔드 담당과 정한다 | 같은 결과의 반복 계산 | 교사 화면 코드는 이 저장소 밖 |
| 7.5 | 커넥션 · 트랜잭션 관련 `TODO` 는 이슈로 등록하고 릴리스 차단 항목으로 관리한다 | 알려진 결함 방치(B3) | 프로세스 |

## 8. 모니터링 항목

> "울렸을 시각" 은 이번 로그로 다시 계산한 값이다. 기준은 첫 5xx(14:40:52). Hikari 지표(active · pending · timeout)를 수집하려면 Actuator/Micrometer 가 필요한데 지금 `build.gradle` 에는 없다(`:22–26`). 의존성 추가라 먼저 묻는다. 로그 기반 항목은 의존성 없이 바로 쓸 수 있다.

| 지표 | 임계값 (제안) | 이번에 울렸을 시각 | 첫 5xx 대비 |
|---|---|---|---|
| Hikari 누수 경고 로그 (`ProxyLeakTask` WARN) | 5분에 1건 이상 | **14:37:52** (`app.log:1243`) | **3분 0초 먼저** |
| 슬로 쿼리 `Query_time` | 5초 초과 1건 | 14:37:52 (`mariadb-slow.log:15`, 9.81s) | 3분 0초 먼저 |
| `/api/classes/{id}/report` 처리 시간 | 10초 초과 (평소 10초 미만, 누수 경고 0건 근거) | 14:38:01 (첫 요청 19.7s) | 2분 51초 먼저 |
| 리포트 요청 수 | 분당 3건 이상이 2분 연속 (평소 분당 최대 2) | 14:40:00 (14:38=3, 14:39=3 마감) | 52초 먼저 |
| Hikari active / max | 100% 가 10초 이상 지속 | 14:40:36 무렵 (5개 도달 14:40:25.9, 3.7 C4 방식으로 계산) | 16초 먼저 |
| Hikari pending(대기 스레드) | 1 이상 | 14:40:49 무렵 (첫 타임아웃 3초 전) | 3초 먼저 |
| Hikari 획득 타임아웃 수 | 1건 이상 | 14:40:52 (`app.log:1522`) | 동시 |
| 5xx 비율 | 1분 1% 초과 | 14:41:00 (14:40 분 1/42 = 2.4%) | 8초 뒤 |
| nginx `upstream timed out` · `no live upstreams` | 1건 이상 | 14:41:06 (`nginx-error.log:6`) · 14:42:04 (`:12`) | 14초 뒤 · 1분 12초 뒤 |

첫 두 항목을 페이징 경보로, 나머지는 대시보드와 보조 경보로 둘 것을 제안한다.

## 9. 확인하지 못한 것

| 판단하지 못한 것 | 이유 | 다음을 위해 남겨야 할 로그 |
|---|---|---|
| 14:37 부터 리포트 1건이 평소(10초 미만)보다 느려진 이유(15.6~19.7초, 동시 1~2건) | 리포트 안에서 조회와 서명이 각각 몇 초 걸렸는지 기록이 없다 | 리포트 단계별 소요 시간 로그(조회 종료, `sign()` 시작 · 종료). 앱 호스트 CPU 사용률 |
| `sign()` 1회의 실제 소요 시간 | 실측하지 않음(별도 실행 제외) | 위 단계별 로그로 대신한다 |
| 9.81초 슬로 쿼리(`Rows_examined 1284310`)의 원인과, 나머지 요청의 쿼리 시간 | 운영 DB 의 `EXPLAIN` · 슬로 로그 설정을 받지 못함. 스키마상 인덱스는 있음(`01-schema.sql:112`) | 슬로 로그 설정 값(`long_query_time` · `log_slow_rate_limit`) 기록, 장애 시 `EXPLAIN` 스냅샷, `performance_schema` 문장 다이제스트 |
| 반복 요청이 자동 폴링인지 사용자 재클릭인지 | access 로그에 요청 처리 시간이 없어 도착 시각을 추정만 했다. 교사 화면 코드는 저장소 밖 | nginx `log_format` 에 `$request_time` · `$upstream_addr` · `$upstream_response_time` 추가 |
| `API-2` 의 역할(`backup` 여부)과 14:42 연결 거부 이유 | nginx 설정과 해당 호스트 로그가 없다 | nginx `upstream` 설정 스냅샷, `API-2` 의 앱 · 오케스트레이터 로그 |
| 저장소 코드 · 설정이 장애 당시 배포본과 같은지 | 배포 기록을 대조하지 않음 | 앱 기동 로그에 빌드 버전 · 커밋 해시 출력 |
| 경보가 실제로 울렸는지, 대응은 무엇이었는지 | 이번 로그에 경보 · 대응 기록이 없다 | 온콜 · 경보 이력 |

---

마스킹 적용: IP 주소 69건(역할 별칭 6종, 명령 속 주소도 치환돼 그대로 실행되지 않음) · 이메일 0건 · 학생 식별자 3건(별칭 2명) · 토큰 · 세션 ID · API 키 · Authorization 0건 · 비밀번호 · 접속 문자열 0건
