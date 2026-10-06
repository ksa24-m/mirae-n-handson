# 머지 전 검증 — migration-review-2

- 검증 대상: `git diff upstream/main...HEAD` (브랜치 `day1`, 커밋 4개: `ec2f524`, `15c5725`, `f51ff0c`, `68b7968`)
- 요청 범위: 레거시 엔드포인트 1개를 동작을 바꾸지 않고 modern 으로 이관.
- 기준 문서: `templates/approval-checklist.md` v0, `templates/verification-loop.md` v0, `CLAUDE.md`
- 리뷰 세션이므로 코드는 수정하지 않았다.
- 1차 리뷰(`migration-review-1.md`) 이후 변화: 커밋 `68b7968` 은 리뷰 문서만 추가했고 코드 · 테스트는 바뀌지 않았다. 따라서 1차의 이슈 #1 · #2 가 그대로 남아 있다.

판정: 반려

| 기준 | 결과 | 근거 |
|---|---|---|
| 테스트 통과 | 충족 | `cd modern/api && ./gradlew cleanTest test` → 51건 실행, 실패 0 · 오류 0 · skip 0, BUILD SUCCESSFUL. `cd characterization && npm test`(레거시 대상) → 33건 통과. `cd characterization && TARGET_BASE_URL=http://localhost:8080 npm test`(새 API 대상) → 32건 통과 · 1건 skip(`tests/example-units.test.js:21` `it.skipIf(legacyOnly)`, 이번 변경과 무관). 실패한 테스트 없음. 단, 새 API 대상 결과의 신뢰도는 "확인 필요" 1번 참고 |
| 치명 이슈 0건 | 충족 | 치명 이슈 없음. 리터럴 비밀값 없음, 쿼리 문자열 이어붙이기 없음(`ItemSearchSpecs` 는 Criteria 파라미터 바인딩), 삭제 · 덮어쓰기 코드 없음 |
| 요청 범위 이탈 없음 | 미충족 | 요청 문장은 "엔드포인트 1개 이관". `GET /api/items/search` 구현 7개 + 테스트 4개 + `ItemRepository` 수정 + 동작 보존 테스트 · 스냅샷은 요청으로 설명된다. 그러나 `docs/assignment/*`(3개), `docs/item-bank/*`(4개), `.claude/skills/*`(2개), `CLAUDE.md`, `docs/verify/migration-review-1.md` 는 설명되지 않는다 → 이슈 #2 |
| 컨벤션 준수 | 미충족 | 이슈 #1(`@DisplayName` 형식 위반 2건). `vendor-prs/*.patch` 3개 `git apply --check` 통과 |

## 이슈 목록

| # | 심각도 | 파일:줄번호 | 근거 | 수정 방향 |
|---|---|---|---|---|
| 1 | 경고 | `modern/api/src/test/java/com/example/item/ItemSearchControllerTest.java:33`, `:50` | CLAUDE.md "`@DisplayName("메서드명: 기대 동작")`". `"GET /api/items/search → 200, …"`, `"GET /api/items/search 형식 밖 값 → 400 이 아니라 …"` 는 메서드명 자리에 HTTP 경로를 쓰고 `:` 구분이 없다. 나머지 테스트는 `"from: …"`, `"search: …"` 형식을 지킨다 | `"search: 200 으로 rows · count · message 만 있는 JSON 을 돌려준다"` 처럼 맞춘다 |
| 2 | 경고 | `docs/assignment/ARCHITECTURE.md:1` (`BUSINESS-RULES.md`, `ERD.md` 포함), `docs/item-bank/ARCHITECTURE.md:1` (`BUSINESS-RULES.md`, `CROSS-CHECK.md`, `ERD.md` 포함), `.claude/skills/convention-check/SKILL.md:1`, `.claude/skills/document-module/SKILL.md:1`, `CLAUDE.md:1`, `docs/verify/migration-review-1.md:1` | 요청은 "엔드포인트 1개 이관"인데 과제 배포 모듈 문서, 문서화 산출물, Skill 2개, CLAUDE.md, 리뷰 문서가 같은 범위에 들어 있다. 체크리스트 3-2 "변경 파일 목록의 모든 파일이 요청 문장으로 설명된다" 미충족. CLAUDE.md 금지 사항도 `.claude/`, `docs/` 문서를 "요청 없이 만들지 않는다"로 둔다 | 이관 변경(`modern/api`, `characterization/`)과 문서 · Skill 변경을 별도 커밋 · PR 로 나눈다. 의도적으로 묶은 것이면 사람이 요청 범위를 다시 확정한다 |
| 3 | 제안 | `modern/api/src/main/java/com/example/item/ItemSearchCondition.java:62` | `name[]` 형태 키가 있으면 같은 이름의 일반 값보다 항상 우선한다. PHP `$_GET` 은 쿼리에서 나중에 나온 쪽이 앞의 값을 덮어쓰므로 `level[]=2&level=3` 은 레거시에서 `3`, 여기서는 `2` 일 수 있다. 순서 혼합 케이스를 검증하는 테스트가 없다 | 레거시 실제 응답으로 확인한 뒤 다르면 이관 차이로 보고한다. 동작 보존 테스트 후보 |
| 4 | 제안 | `modern/api/src/main/java/com/example/item/ItemSearchSpecs.java:49-50` | 레거시 `LIKE ?` 의 `\` 이스케이프를 `LIKE_ESCAPE = '\\'` 로 재현했지만 `\` 가 든 키워드를 검증하는 테스트가 없다. H2 는 MariaDB 와 이스케이프 처리가 달라 단위 테스트로 보장되지 않는다 | `q=a\%b` 같은 케이스를 동작 보존 테스트 후보로 남긴다(스냅샷은 사람이 정한다) |
| 5 | 제안 | `modern/api/src/main/java/com/example/item/ItemSearchService.java:46` | `ids.stream().map(details::get)` — 두 번째 조회에서 행이 빠지면 `null` 이 `ItemSearchRow.from` 으로 들어가 NPE. 같은 읽기 전용 트랜잭션이라 발생 가능성은 낮다 | 가정을 주석으로 남기거나 누락 시 명시적 예외 |
| 6 | 제안 | `modern/api/src/test/java/com/example/item/ItemSearchServiceTest.java:25` | Mockito 만 쓰는 테스트에 `@ActiveProfiles("test")` 가 붙어 있어 효과가 없다 | 제거 |
| 7 | 제안 | `characterization/__snapshots__/item-bank.test.js.snap:1` | CLAUDE.md "`__snapshots__/` 의 파일을 손으로 고치지 않는다". 신규 파일이라 diff 만으로 생성 방식을 판별할 수 없다. 레거시 대상 `npm test` 33건 통과로 값은 레거시 응답과 일치 | 테스트 실행으로 생성했음을 커밋 메시지에 남긴다 |

## 확인 필요

- 새 API 대상 characterization(8080)이 현재 `HEAD` 코드로 기동된 서버인지 확인하지 못했다. 실행 중인 `bootRun` 은 12:30 에 시작됐고 `68b7968` 커밋(12:59)보다 앞선다. `cd modern/api && ./gradlew bootRun` 으로 다시 띄운 뒤 `TARGET_BASE_URL=http://localhost:8080 npm test` 를 재실행해 달라. 재실행 전까지 새 API 대상 결과는 참고용이다.
- 검증 범위 확정: `upstream/main...HEAD` 는 4개 커밋 전부를 포함한다. 이번 요청이 이관 커밋 `f51ff0c` 하나만 대상이면 #2 는 해당되지 않을 수 있다. 사람이 범위를 확정해 달라.
- 이관 중 레거시 동작이 버그로 보이는 "의심 동작" 목록은 이번 리뷰에서 따로 찾지 않았다. `docs/item-bank/CROSS-CHECK.md` 와 대조가 필요하다.
