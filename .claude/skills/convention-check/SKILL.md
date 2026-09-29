---
name: convention-check
description: 변경된 코드(또는 지정한 경로)가 CLAUDE.md 의 코딩 컨벤션 · 금지 사항을 지키는지 점검하고 위반을 파일:줄번호로 보고한다. "컨벤션 점검", "커밋 전 점검", "리뷰 전에 확인", "규칙 어긴 곳 있나 봐 줘" 같은 요청에 쓴다. 코드는 고치지 않는다.
argument-hint: "[경로 ...] (생략하면 git 변경 파일 전체)"
allowed-tools:
  - Read
  - Grep
  - Glob
  - Bash(git diff *)
  - Bash(git status *)
---

# 컨벤션 점검

## 원칙

- 코드를 직접 고치지 않는다. 판정과 수정 방향만 보고한다.
- 근거 라인을 댈 수 없는 지적은 하지 않고 "확인 필요"로 남긴다.

## 1. 점검 대상 정하기

- 인자 `$ARGUMENTS` 가 있으면 그 경로(파일 또는 폴더)만 본다. 폴더면 Glob 으로 `*.java`, `*.ts`, `*.tsx` 를 모은다.
- 인자가 없으면 `git status --porcelain` 과 `git diff --name-only HEAD` 로 변경 파일을 모은다.
  - `??`(아직 add 하지 않은 새 파일)도 포함하고, 새 파일은 Read 로 전체를 본다.
  - 기존 파일은 `git diff HEAD -- <파일>` 로 바뀐 줄을 우선 보되, 줄번호는 Read 한 현재 파일 기준으로 적는다.
- 변경 파일이 없으면 "점검 대상 없음"만 출력하고 끝낸다.

## 2. 점검 항목 (예 = 지킴)

| # | 범위 | 항목 |
|---|---|---|
| 1 | api 컨트롤러 | `*Controller` 가 `*Repository` 를 import · 필드 · 생성자 인자로 갖지 않고, `@Entity` 를 반환하지 않으며, `ResponseEntity` 로 404 를 직접 만들지 않는다 |
| 2 | api · web | 빈 `catch` 가 없다. api 는 로그만 남기고 끝나는 `catch` 도 없다(다시 던지거나 cause 를 넘긴다). web 은 `status: 'error'` 로 옮기거나 다시 던진다 |
| 3 | api · web | `System.out` · `System.err` · `printStackTrace()` · `console.log` 가 없다 |
| 4 | api | 상태 문자열 `"A"` · `"D"` · `"R"` 대신 `ItemStatus` 상수를 쓴다 |
| 5 | api 서비스 | 인자 없는 `now()` 가 없다(`LocalDateTime.now(clock)` 등 주입된 `Clock` 사용) |
| 6 | api | `@Query` 문자열을 `+` · `String.format` 으로 만들지 않고 `:이름` + `@Param` 으로 바인딩한다 |
| 7 | web | 컴포넌트 · 훅에서 `fetch` 를 직접 부르지 않고(`src/api/client.ts` 만 예외), 컴포넌트 `useEffect` 에서 API 를 부르지 않으며, `dangerouslySetInnerHTML` 이 없다 |
| 8 | 금지 사항 | 변경 파일에 `build.gradle` · `package.json` · lock 파일, `db/`, `legacy/`, `incident-logs/`, `pipeline-samples/`, `characterization/__snapshots__/` 가 없다 |

- 대상 파일이 해당 범위가 아니면 그 항목은 "해당 없음"이다.
- 테스트 코드도 같은 기준으로 보되, 픽스처의 상태 값처럼 규칙 적용이 애매하면 "확인 필요"로 둔다.

## 3. 출력 형식 (순서 고정)

### 판정 요약

점검 대상 파일 수와 함께 항목 1~8 을 한 줄씩 `예` / `아니오` / `해당 없음` / `확인 필요` 로 적는다.

### 위반 목록

| 파일:줄번호 | 어긴 규칙 | 수정 방향 |
|---|---|---|
| `modern/api/.../XxxController.java:42` | #1 컨트롤러 Repository 의존 | 서비스 메서드로 옮기고 컨트롤러는 서비스만 호출 |

- 위반이 없으면 표 대신 "위반 없음"이라고 적는다.
- "확인 필요" 항목은 표 아래에 무엇을 확인해야 하는지 한 줄씩 적는다.
