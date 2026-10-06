# 머지 전 검증 — migration-review-1

- 검증 대상: `git diff upstream/main...HEAD` (브랜치 `day1`, 커밋 3개: `ec2f524`, `15c5725`, `f51ff0c`)
- 요청 범위: 레거시 엔드포인트 1개를 동작을 바꾸지 않고 modern 으로 이관.
- 기준 문서: `templates/approval-checklist.md` v0, `templates/verification-loop.md` v0, `CLAUDE.md`
- 리뷰 세션이므로 코드는 수정하지 않았다.

판정: 반려

| 기준 | 결과 | 근거 |
|---|---|---|
| 테스트 통과 | 충족 | `cd modern/api && ./gradlew test` → 51건 실행, 실패 0 · 오류 0 · skip 0, BUILD SUCCESSFUL. `cd characterization && npm test`(레거시 대상) → 33건 통과. `cd characterization && TARGET_BASE_URL=http://localhost:8080 npm test`(새 API 대상) → 32건 통과 · 1건 skip(`tests/example-units.test.js:21` `it.skipIf(legacyOnly)`, 이번 변경과 무관). 실패한 테스트 없음 |
| 치명 이슈 0건 | 충족 | 치명 이슈 없음. 리터럴 비밀값 없음, 쿼리 문자열 이어붙이기 없음(Criteria 파라미터 바인딩), 삭제 · 덮어쓰기 코드 없음 |
| 요청 범위 이탈 없음 | 미충족 | 요청 문장은 "엔드포인트 1개 이관". `modern/api` 의 `GET /api/items/search` 변경(문항 검색 구현 7개 + 테스트 4개 + `ItemRepository` 수정)은 요청으로 설명된다. 그러나 `docs/assignment/*`(3개), `.claude/skills/*`, `CLAUDE.md`, `docs/item-bank/*` 는 설명되지 않는다 → 이슈 #2 |
| 컨벤션 준수 | 미충족 | 이슈 #1 (`@DisplayName` 형식 위반 2건). 재검토에서 `vendor-prs/*.patch` 3개 `git apply --check` 통과 확인 |

## 이슈 목록

| # | 심각도 | 파일:줄번호 | 근거 | 수정 방향 |
|---|---|---|---|---|
| 1 | 경고 | `modern/api/src/test/java/com/example/item/ItemSearchControllerTest.java:33`, `:50` | CLAUDE.md "`@DisplayName("메서드명: 기대 동작")`". `"GET /api/items/search → 200, …"` 은 메서드명 자리에 HTTP 경로를 쓰고 `:` 구분이 없다. 나머지 테스트 25개(Condition · Service · Specs)는 `"from: …"`, `"search: …"` 형식을 지킨다 | `"search: 200 으로 rows · count · message 만 있는 JSON 을 돌려준다"` 처럼 맞춘다 |
| 2 | 경고 | `docs/assignment/ARCHITECTURE.md:1` (`BUSINESS-RULES.md`, `ERD.md` 포함), `.claude/skills/convention-check/SKILL.md:1`, `.claude/skills/document-module/SKILL.md:1`, `CLAUDE.md:1`, `docs/item-bank/*:1` | 요청은 "엔드포인트 1개 이관"인데 과제 배포(`assignment`) 모듈 문서, Skill 2개, CLAUDE.md, 문서 4개가 같은 범위에 들어 있다. 체크리스트 3-2 "변경 파일 목록의 모든 파일이 요청 문장으로 설명된다" 미충족. CLAUDE.md 금지 사항도 `.claude/`, `docs/` 문서를 "요청 없이 만들지 않는다"로 둔다 | 이관 PR 에서 분리해 별도 커밋 · PR 로 나눈다. 의도적으로 묶은 것이라면 사람이 요청 범위를 다시 확정한다 |
| 3 | 제안 | `characterization/__snapshots__/item-bank.test.js.snap:1` | CLAUDE.md "`characterization/__snapshots__/` 의 파일을 손으로 고치지 않는다". 신규 파일(+1820줄)이라 diff 만으로 판별할 수 없다. 레거시 대상 `npm test` 33건이 통과하므로 현재 값은 레거시 응답과 일치한다 | 스냅샷이 테스트 실행으로 생성된 것임을 커밋 메시지에 남긴다 |
| 4 | 제안 | `modern/api/src/main/java/com/example/item/ItemSearchSpecs.java:40-41` | 레거시 `LIKE ?` 의 `\` 이스케이프 동작을 `LIKE_ESCAPE = '\\'`(22-25행)로 재현했다. 그러나 `\` 가 들어간 키워드를 검증하는 테스트가 없다(`ItemSearchSpecsTest`, `characterization/tests/item-bank.test.js` 에 백슬래시 케이스 0건). H2 는 이스케이프 처리가 MariaDB 와 달라 단위 테스트로도 보장되지 않는다 | `q=a\%b` 같은 케이스를 동작 보존 테스트 후보로 남긴다(스냅샷은 사람이 정한다) |
| 5 | 제안 | `modern/api/src/main/java/com/example/item/ItemSearchCondition.java:62-64` | `name[]` 형태 키가 있으면 같은 이름의 일반 값보다 항상 우선한다. PHP `$_GET` 은 쿼리에서 나중에 나온 쪽이 앞의 값을 덮어쓰므로 `level[]=2&level=3` 은 레거시에서 `3`, 여기서는 `2` 다. 해당 순서를 검증하는 테스트가 없다(`ItemSearchConditionTest.java:96-` 은 두 경우를 따로만 본다) | 순서 혼합 케이스를 레거시 실제 응답으로 확인한 뒤, 다르면 의심 동작이 아니라 이관 차이로 보고한다 |
| 6 | 제안 | `modern/api/src/main/java/com/example/item/ItemSearchService.java:46` | `ids.stream().map(details::get)` — 두 번째 조회에서 행이 빠지면 `null` 이 `ItemSearchRow.from` 으로 들어가 NPE 가 난다. 같은 읽기 전용 트랜잭션이라 현재 DB 격리 수준에서는 발생하기 어렵다 | 방어 코드가 필요하면 누락 시 명시적 예외, 아니면 현재대로 두고 가정을 주석으로 남긴다 |
| 7 | 제안 | `modern/api/src/test/java/com/example/item/ItemSearchServiceTest.java:25` | Mockito 만 쓰는 테스트에 `@ActiveProfiles("test")` 가 붙어 있다. 효과가 없다 | 제거 |
| 8 | 제안 | `modern/api/src/main/java/com/example/item/ItemSearchRow.java:11` | CLAUDE.md 는 변환을 `XxxResponse.from(entity)` 로 하라고 한다. 여기서는 응답 레코드 `ItemSearchResponse` 가 아니라 행 레코드 `ItemSearchRow` 에 `from` 이 있다. 규칙 위반으로 보기는 어렵고, 기존 `ItemResponse.from` 과 이름 결이 다르다는 의견이다 | 유지해도 무방. 통일하려면 팀이 정한다 |

> 정정: 이 문서의 이전 판은 #1 을 "테스트 27개 전부 콜론 누락"으로 적었다. 이는 내가 grep 출력을 가공하다 콜론을 지워서 생긴 오독이었고, 실제 위반은 컨트롤러 테스트 2건뿐이다. 판정(반려)은 #2 와 #1 로 그대로다.

## 확인 필요

- 검증 범위 확정: `upstream/main...HEAD` 는 3개 커밋(베이스라인 테스트, 문서 · Skill, 이관)을 모두 포함한다. 이번 요청이 마지막 커밋 `f51ff0c` 하나만 대상이라면 #2 는 해당되지 않을 수 있다. 사람이 범위를 확정해 달라.
- 새 API 대상 characterization 은 `localhost:8080` 에 떠 있던 서버로 실행했다. 이 서버가 현재 `HEAD` 코드로 기동된 것인지는 확인하지 못했다. 필요하면 `cd modern/api && ./gradlew bootRun` 으로 다시 띄운 뒤 재실행해 달라.
- 이관 중 레거시 동작이 버그로 보이는 "의심 동작" 목록은 이번 리뷰에서 따로 찾지 않았다. `docs/item-bank/CROSS-CHECK.md` 와 대조가 필요하다.
