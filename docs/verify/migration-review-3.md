# 머지 전 검증 — migration-review-3

- 검증 대상: `git diff upstream/main...HEAD -- modern characterization` (브랜치 `day1`, 이관 관련 커밋 `ec2f524` 베이스라인 · `f51ff0c` 이관, 14개 파일)
- 요청 범위: 레거시 엔드포인트 1개를 동작을 바꾸지 않고 modern 으로 이관. (실제 대상: item-bank `search.php` → `GET /api/items/search`)
- 기준 문서: `templates/approval-checklist.md` v1, `templates/verification-loop.md`, `CLAUDE.md`
- 리뷰 세션이므로 코드는 수정하지 않았다. 체크리스트 대조는 reviewer, 테스트는 tester Sub-agent 가 수행했고 두 보고는 엇갈리지 않았다.
- 2차 리뷰(`migration-review-2.md`)와 다른 점:
  - 경로 필터(`-- modern characterization`)를 걸어서 2차 이슈 #2(문서 · Skill 이 같은 범위에 섞임)가 이번 대상에서 빠졌다.
  - 체크리스트 v1 에서 네이밍은 `[경고]` 다. 그래서 `@DisplayName` 형식 위반(2차 이슈 #1)이 남아 있어도 컨벤션 기준은 미충족이 되지 않는다.
- 작업 트리 상태:
  - 커밋되지 않은 `LegacyPhpStrings.java` 변경(`toInt` 에서 무한대 → 0)이 있다. 검증 범위(HEAD) 밖이지만, 테스트는 이 변경이 들어간 작업 트리에서 실행했다. 아래 "확인 필요" 1 참고.

판정: 승인

| 기준 | 결과 | 근거 |
|---|---|---|
| 테스트 통과 | 충족 | 실패한 테스트 없음. 세 명령 모두 실패 0. (1) `cd modern/api && ./gradlew test` → BUILD SUCCESSFUL, 62건 통과 / 실패 0. 이 중 11건은 tester 가 추가했다. (2) `cd characterization && npm test`(레거시 8081) → 33건 통과 / 실패 0. (3) `cd characterization && TARGET_BASE_URL=http://localhost:8080 npm test` → 32건 통과 · 1건 skip / 실패 0. skip 1건은 `tests/example-units.test.js:21` `it.skipIf` 로 레거시 전용이며 이번 변경과 무관하다. `item-bank.test.js` 19개 케이스는 새 API 대상에서도 전부 스냅샷과 일치했다. 새 public 메서드(`ItemSearchCondition.from` · `toSort`, `ItemSearchService.search`, `ItemSearchController.search`, `ItemRepository.findWithDetailsByIdIn`)마다 테스트가 있다. 지우거나 `@Disabled` 로 바꾼 테스트는 없다 |
| 치명 이슈 0건 | 충족 | 치명 이슈 없음. 리터럴 비밀값 없음. 삭제 · 덮어쓰기 코드 없음(읽기 전용). CORS · 보안 설정 변경 없음. `ItemSearchSpecs` 는 Criteria 파라미터 바인딩만 쓰고, `"%" + keyword + "%"`(`ItemSearchSpecs.java:38`)는 바인딩할 값을 만드는 코드라 SQL 조립이 아니다 |
| 요청 범위 이탈 없음 | 충족 | 요청은 "엔드포인트 1개 이관"이다. 변경 14개 파일은 모두 이 요청으로 설명된다: 구현 7개(`ItemSearchCondition` · `Controller` · `Response` · `Row` · `Service` · `Specs`, `LegacyPhpStrings`), `ItemRepository` 확장, 단위 테스트 4개, 동작 보존 테스트 · 스냅샷. `/api/items/search` 와 `characterization/tests/item-bank.test.js` 는 CLAUDE.md 의 "참가자가 만드는 파일" 목록에 있지만, 이번 요청이 바로 그 이관이다. 의존성 · 스키마 · 설정 변경은 없다 |
| 컨벤션 준수 | 충족 | `[반려]` 항목은 모두 지켰다: 컨트롤러는 서비스만 호출하고, catch 가 없고, 출력은 slf4j `log.debug` 만 쓴다. 금지 사항 위반도 없다: `vendor-prs/*.patch` 3개 `git apply --check` 통과, 스냅샷은 `ec2f524` 에서 생성된 뒤 바뀐 적 없음. `[경고]` 는 이슈 #1 |

## 이슈 목록

| # | 심각도 | 파일:줄번호 | 근거 | 수정 방향 |
|---|---|---|---|---|
| 1 | 경고 | `modern/api/src/test/java/com/example/item/ItemSearchControllerTest.java:33`, `:50` | CLAUDE.md "`@DisplayName("메서드명: 기대 동작")`". `"GET /api/items/search → 200, rows · count · message 만 있는 JSON"`, `"GET /api/items/search 형식 밖 값 → 400 이 아니라 보정한 조건으로 조회한다"` 는 메서드명 자리에 경로를 썼다. 2차 리뷰 이슈 #1 이 그대로 남아 있다 | `"search: 200 으로 rows · count · message 만 있는 JSON 을 돌려준다"` 처럼 맞춘다 |
| 2 | 경고 | `modern/api/src/main/java/com/example/item/LegacyPhpStrings.java:85` (HEAD 기준) | `return (long) Double.parseDouble(number);` 는 무한대일 때 `Long.MAX_VALUE` 가 된다. PHP 7.4 `zend_dval_to_lval_cap` 은 무한대면 0 이다. 그래서 310자리 이상 숫자인 `page` 를 넣으면 레거시는 1페이지, HEAD 는 999페이지가 되어 "동작을 바꾸지 않고"에 어긋난다. `level=1e999` 는 양쪽 다 0건이라 차이가 없다. 스냅샷이 다루지 않는 경계다 | 작업 트리의 미커밋 수정이 이 차이를 고친다. 그 수정을 tester 가 추가한 테스트와 함께 별도 커밋으로 넣는다("확인 필요" 1) |
| 3 | 경고 | `modern/api/src/main/java/com/example/item/ItemSearchCondition.java:62-70` | `name[` 로 시작하는 키가 하나라도 있으면 순서와 관계없이 배열 값을 고른다. PHP `$_GET` 은 나중에 온 값이 덮어쓴다. `unit[]=M5-1&unit=M5-2` 이면 PHP 는 `M5-2` 일 것으로 보이지만 modern 은 `M5-1` 이다 | 레거시 실제 응답으로 확인한다. 다르면 쿼리 문자열의 등장 순서로 고르게 맞추고, 동작 보존 테스트 후보로 남긴다(스냅샷 추가는 사람이 정한다) |
| 4 | 제안 | `modern/api/src/main/java/com/example/item/ItemSearchService.java:46` | `ids.stream().map(details::get).map(ItemSearchRow::from)`: 두 번째 조회에서 id 가 빠지면 NPE 가 난다. 같은 readOnly 트랜잭션이라 가능성은 낮다 | 누락되면 메시지를 담은 `IllegalStateException` 을 던지거나, 가정을 주석으로 남긴다 |

## 확인 필요

- 커밋되지 않은 `LegacyPhpStrings.java` 변경(무한대 → 0):
  - 레거시 PHP 7.4 동작에 더 가깝고 이슈 #2 를 해소하지만, 검증 범위 밖이고 관찰 가능한 동작(310자리 이상 page)을 바꾼다. 리팩토링과 섞지 말고 별도 커밋으로 넣을지 정해야 한다.
  - 레거시 PHP 를 직접 돌려 확인한 근거는 없다. 근거를 남기려면 `item-bank.test.js` 에 큰 `page` · `level=1e999` 케이스를 넣고 베이스라인을 다시 찍어야 한다. 스냅샷 변경이라 사람의 승인이 필요하다.
- tester 가 추가한 테스트 파일은 아직 커밋되지 않았다:
  - `modern/api/src/test/java/com/example/item/LegacyPhpStringsTest.java`(새 파일)
  - `modern/api/src/test/java/com/example/item/ItemSearchSpecsTest.java`(1건 추가: `%` 와일드카드와 `\` 이스케이프)
  - `LegacyPhpStringsTest` 의 무한대 케이스는 미커밋 `LegacyPhpStrings.java` 수정이 있어야 통과한다. 그 수정과 같은 커밋에 넣어야 한다. HEAD 만으로 실행한 결과는 아니다.
- 8080 에 떠 있던 서버가 미커밋 변경 이후에 빌드됐는지 확인하지 않았다. 동작 보존 케이스 중 지수 · 무한대 입력을 쓰는 것이 없어서 결과에는 영향이 없다.
- LIKE `\` 이스케이프(`ItemSearchSpecs.java:25`, `:40-41`): Hibernate 가 MariaDB 에서 `escape '\\'` 를 바르게 렌더링하는지 실제 DB 로 확인하지 않았다. 단위 테스트는 H2 에서만 돌고, 스냅샷에는 `\` 케이스가 없다. `q=\%` 같은 요청을 레거시와 비교해 봐야 한다.
- 레거시 의심 동작(보존이 맞고, 수정 대상 아님): `legacy/item-bank-php/search.php:213` 주석은 "난이도 값이 비어 있으면 전체 난이도 검색 (1~5 모두 포함)"인데, `:215` 코드는 `level < 5` 로 난이도 5 를 뺀다. modern 은 이 코드 동작을 그대로 옮겼다(`ItemSearchSpecs.java:49`). 의심 동작 목록에 올린다.
- 커밋 `f51ff0c` 에 `docs/item-bank/BUSINESS-RULES.md`(+8줄)가 같이 들어 있다. 이번 경로 필터 밖이라 검토하지 않았다. 머지 단위에 포함할지 따로 정해야 한다.
