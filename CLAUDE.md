# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

- 답변과 문서는 한국어로 쓴다.
- `legacy/` 는 분석 · 이관 대상이며 허락 없이 수정하지 않는다.

Claude Code 심화 과정 실습 저장소다. 코드 · 데이터 · 로그는 전부 더미 에듀테크 도메인(문항 은행 · 과제 배포 · 성적 집계)이고, Claude Code 는 저장소 루트에서 실행한다.
도메인 용어: 문항 `item` · 단원 `unit`(코드 `M5-1` = 학년-순번) · 난이도 `level`(1~5) · 태그 `tag` / 학급 `class` · 과제 `assignment` · 배포 `distribution` · 제출 `submission` / 학생 식별자 `STU-<숫자>`.

## 1. 빌드 · 테스트 명령

`modern/api` (Spring Boot 3.3 · Java 21 · Gradle)

```bash
cd modern/api && ./gradlew test                                            # 전체 테스트. DB 없이 H2 로 돈다
cd modern/api && ./gradlew test --tests com.example.item.UnitServiceTest   # 클래스 하나
cd modern/api && ./gradlew bootRun                                         # 8080. 먼저 루트에서 docker compose --profile modern up -d
```

`modern/web` (React 18 · TypeScript · Vite)

```bash
cd modern/web && npm ci                                         # 의존성 설치. npm install 을 쓰지 않는다(lock 파일 보존)
cd modern/web && npm run lint && npm run typecheck && npm test  # 검증 3종
cd modern/web && npx vitest run src/components/ItemTable.test.tsx   # 파일 하나
cd modern/web && npm run build                                  # dist/ 생성
cd modern/web && npm run dev                                    # 5173. API 는 8080 을 호출한다
```

## 2. 코딩 컨벤션 (`modern/` 에 적용)

### modern/api

- 컨트롤러 클래스는 `*Repository` 타입을 import 하거나 필드 · 생성자 인자로 갖지 않는다. 컨트롤러는 서비스만 호출한다.
- `catch` 블록은 예외를 다시 던지거나, 잡은 예외를 cause 로 넘긴 새 예외를 던진다. 비어 있는 `catch`, 로그만 남기고 끝나는 `catch` 를 쓰지 않는다.
- 로그는 `org.slf4j.Logger`(`LoggerFactory.getLogger(현재클래스.class)`)로만 남긴다. `System.out`, `System.err`, `printStackTrace()` 를 쓰지 않는다.
- 서비스의 public 메서드는 클래스 또는 메서드 수준의 `@Transactional` 범위 안에 있다. DB 에 쓰지 않는 메서드는 `readOnly = true` 다.
- 컨트롤러 메서드는 엔티티(`@Entity` 클래스)를 반환하지 않는다. 응답은 `record` DTO 이고, 엔티티 → DTO 변환은 서비스 안에서 `XxxResponse.from(entity)` 로 한다.
- 조회 대상이 없으면 `com.example.common.NotFoundException` 을 던진다. 컨트롤러에서 `ResponseEntity` 로 404 를 직접 만들지 않는다.
- 외부에 노출하는 문항 조회 · 집계는 `ItemStatus.ACTIVE`(`"A"`)로만 거른다. 상태 문자열 `"A"` · `"D"` · `"R"` 을 코드에 직접 쓰지 않고 `ItemStatus` 상수를 쓴다.
- 서비스 클래스는 현재 시각을 주입받은 `java.time.Clock` 으로 구한다(`LocalDateTime.now(clock)`). 인자 없는 `now()` 를 쓰지 않는다.
- `@Query` 에 값을 넣을 때는 `:이름` + `@Param` 바인딩을 쓴다. 쿼리 문자열을 `+` 나 `String.format` 으로 만들지 않는다.
- 새로 만드는 메서드는 100줄을 넘기지 않는다.
- 테스트 메서드에는 `@DisplayName("메서드명: 기대 동작")` 형식의 한국어 이름을 붙인다.

### modern/web

- API 호출은 `src/api/client.ts` 의 `getJson` 으로만 한다. 컴포넌트 · 훅에서 `fetch` 를 직접 부르지 않는다.
- 데이터 로딩은 `src/hooks/` 의 훅(`useApiQuery` 기반)으로 하고, 컴포넌트 안에서 `useEffect` 로 API 를 부르지 않는다.
- `catch` 는 오류를 상태(`status: 'error'`)로 옮기거나 다시 던진다. 비어 있는 `catch` 를 쓰지 않는다.
- `dangerouslySetInnerHTML` 을 쓰지 않는다.
- `console.log` 를 커밋하지 않는다.

### 공통

- 기존 파일의 들여쓰기 · 따옴표 · import 순서를 그대로 따른다. 파일 전체를 다시 포맷하지 않는다.

## 3. 금지 사항

- 의존성 추가 · 버전 변경(`build.gradle`, `package.json`, lock 파일)은 먼저 묻는다.
- DB 스키마 · 시드 변경(`db/` 아래 SQL, `@Entity` 의 테이블 · 컬럼 매핑)은 먼저 묻는다.
- 한 커밋에 리팩토링과 동작 변경(정렬 기준, 기본값, 제외 조건)을 섞지 않는다.
- 기존 API 의 경로, 응답 필드 이름 · 타입, HTTP 상태 코드를 요청 없이 바꾸지 않는다. `characterization/` 스냅샷이 이 값을 비교한다.
- `application.yml` 에 `localhost` 가 아닌 DB 호스트를 넣지 않는다.
- 실습 중 참가자가 만드는 파일 · 기능(README 의 "이 저장소에 넣지 않은 것" 표: `.claude/`, `hooks/`, `.mcp.json`, `docs/` 문서, `/api/items/search`, `characterization/tests/<모듈>.test.js` 등)을 요청 없이 만들지 않는다.
- `modern/api` 를 바꾼 뒤에도 `git apply --check vendor-prs/<파일>.patch` 가 세 파일 모두 통과해야 한다. 이 패치들은 실습 자료다.
- `incident-logs/`, `pipeline-samples/`, `characterization/__snapshots__/` 의 파일을 손으로 고치지 않는다.

## 4. 아키텍처 안내

저장소 전체의 축은 **레거시 → 현행 이관**이다.

- `legacy/` 세 모듈이 원본 동작이다: 문항 은행(PHP 7.4, 8081) · 과제 배포(Spring MVC + Thymeleaf + JDBC, 8082) · 성적 집계(MS-SQL 저장 프로시저, 8083). 이관할 때 규칙(세는 상태, 정렬 순서 등)의 기준은 레거시 코드다.
- `modern/api` 가 이관 대상, `modern/web` 이 그 API 를 쓰는 화면이다.
- `characterization/` 은 레거시 HTML 응답과 새 API JSON 응답을 같은 `{status, rows, count, message}` 로 정규화해 같은 스냅샷과 비교한다. 규칙은 `characterization/README.md`.
- 스키마 · 시드는 `db/mariadb/init/`, `db/mssql/init/`. `php` · `thymeleaf` · `modern` compose 프로필이 MariaDB 하나를 공유하고, 데이터가 tmpfs 라 컨테이너를 다시 만들면 시드 상태로 돌아간다.

### modern/api

- 패키지는 기능별(`item`, `assignment`), 공통은 `common`, 설정은 `config`. 계층은 컨트롤러 → 서비스 → Spring Data JPA 저장소.
- OSIV 가 꺼져 있다(`open-in-view: false`). 트랜잭션 밖에서 지연 로딩하면 실패하므로, 연관이 필요하면 저장소에서 `@EntityGraph` 나 fetch 쿼리로 함께 읽는다.
- `GlobalExceptionHandler` 가 예외를 `ErrorResponse` JSON 으로 바꾼다: `NotFoundException` · 없는 경로 → 404, 타입 불일치 · 검증 실패 · `IllegalArgumentException` → 400, `IllegalStateException` → 409, 그 밖 → 500.
- 문항 상태는 `item.status CHAR(1)` 코드(`ItemStatus`: `A` 공개 · `D` 삭제 · `R` 검수 중)다. 삭제는 플래그가 아니라 상태 `D` 다.
- Hikari 풀은 최대 5개, 대기 3초(운영 값과 같음)라서 트랜잭션을 오래 쥐면 금방 고갈된다.
- CORS 는 `/api/**` 에 `http://localhost:5173` 의 GET 만 허용한다(`WebConfig`).
- 테스트는 `test` 프로필(H2 MariaDB 모드, `ddl-auto: create-drop`)이다. 저장소는 `@DataJpaTest` 로 실제 SQL, 서비스는 Mockito, 컨트롤러는 `@WebMvcTest` + `@MockBean`. 테스트 엔티티는 `ItemFixtures` 로 만든다.

### modern/web

- API 주소는 `VITE_API_BASE`(기본 `http://localhost:8080`). 빈 문자열이면 Vite 프록시(`/api` → 8080)를 탄다.
- `useApiQuery` 는 언마운트 · key 변경 시 이전 요청을 AbortSignal 로 취소한다.
- 테스트는 vitest + jsdom + Testing Library, fetch 목은 `src/test/mockFetch.ts`, 픽스처는 `src/test/fixtures.ts`.

### 그 밖의 폴더

- `mcp-skeleton/`: stdio MCP 서버 골격. SDK v2(`@modelcontextprotocol/server`)라서 v1(`@modelcontextprotocol/sdk`) 예제는 그대로 쓸 수 없다.
- `specs/`: 신규 개발 스펙 2건과 시작 골격(`starters/java`, `starters/python`). 명령은 각 골격의 README.
- `templates/`: 프로젝트 유형별 CLAUDE.md 템플릿과 체크리스트.
- `scripts/hook-node.sh`: Hook 실행기. node 를 못 찾으면 명령을 막는다(fail-closed). 사용 형태: `bash "$CLAUDE_PROJECT_DIR"/scripts/hook-node.sh hooks/<파일>.mjs`.

## 5. 완료 기준

- 이관 · 리팩토링 작업은 `characterization/` 의 `npm test` 가 전부 통과하기 전에는 완료라고 보고하지 않는다.
- 테스트가 실패하면 실패한 케이스와 차이를 그대로 보고한다. 요약해서 "거의 됐다"고 말하지 않는다.
- 테스트를 통과시키려고 `characterization/` 의 테스트 코드나 스냅샷 파일을 고치지 않는다. 스냅샷을 바꿔야 한다고 판단되면 멈추고 묻는다.
- 레거시 동작이 버그로 보여도 이관 중에는 고치지 않는다. "의심 동작" 목록으로 따로 보고한다.
