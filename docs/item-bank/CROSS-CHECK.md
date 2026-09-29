# 문항 은행(PHP) 비즈니스 규칙 — 코드 기준 추출

- 대상: `legacy/item-bank-php/` 의 `search.php`, `register.php`, `units.php` (외부 라이브러리 `vendor/` 와 `docs/` 는 읽지 않음)
- 원칙: 코드에 있는 규칙만 적었고, 주석과 코드가 다르면 코드를 따랐다.
- 줄번호는 각 파일 기준이다.

## 범위 · 한계

- **수정(edit) 기능은 이 폴더에서 찾지 못했다.** `index.php` 메뉴는 검색 · 등록 · 단원 목록 세 개뿐이고 `edit.php` 도 없다. 그래서 "수정 시 검증"은 추출할 규칙이 없다(파일 목록은 도구 오류로 `ls` 를 못 돌려 메뉴와 파일 존재 확인으로 판단했다).
- 검색이 조회하는 `v_item_public` 뷰의 정의는 `legacy/item-bank-php/` 밖(DB init SQL)에 있어 읽지 않았다. 따라서 **목록에서 제외되는 문항의 정확한 조건은 이 폴더 코드만으로는 확정할 수 없다.** 다만 CX-14 · CX-15 처럼 코드가 직접 보여 주는 부분은 적었다.

## 1. 검색 조건 조합

### CX-01 — 조건은 모두 AND 로 결합된다
- 규칙: 키워드 · 단원 · 난이도 · 태그 조건은 `WHERE 1=1` 에 `AND` 로 이어 붙어 모두 만족하는 문항만 나온다.
- 근거: `search.php:44`, `:98`, `:118`, `:244`
- 코드:
  ```php
  $where    = " WHERE 1=1";
  $where .= " AND unit_code = ?";
  ```

### CX-02 — 키워드는 제목 또는 지문의 부분 일치다
- 규칙: 키워드는 앞뒤 공백을 제거한 뒤 `title` 또는 `stem` 에 `LIKE %키워드%` 로 찾으며, 빈 문자열이면 조건을 넣지 않는다.
- 근거: `search.php:85`, `:90`, `:97-98`
- 코드:
  ```php
  $like = '%' . $q . '%';
  $where .= " AND (title LIKE ? OR stem LIKE ?)";
  ```

### CX-03 — 키워드는 100자까지만 쓰고, `%` · `_` 는 와일드카드로 그대로 동작한다
- 규칙: 100자를 넘으면 잘라서 검색하고(경고만 표시), `%` · `_` 는 이스케이프하지 않아 와일드카드로 작동한다.
- 근거: `search.php:86-89`, `:94-96`, `:97`
- 코드:
  ```php
  $q = mb_substr($q, 0, 100, 'UTF-8');
  $warnings[] = '키워드의 % 와 _ 는 와일드카드로 처리됩니다.';
  ```

### CX-04 — 단원은 코드 정확 일치이며, 대소문자 · 형식이 틀려도 입력값 그대로 조회한다
- 규칙: 단원은 `unit_code = 입력값` 으로만 비교한다. 형식(`M5-1` 꼴) 위반이나 소문자여도 경고만 내고 대문자로 바꾸거나 막지 않는다(결과는 대개 0건).
- 근거: `search.php:111-120`
- 코드:
  ```php
  if ($unit !== strtoupper($unit)) {
      $warnings[] = '단원 코드는 대문자로 입력하세요. (입력값 그대로 조회합니다)';
  }
  $where .= " AND unit_code = ?";
  ```

### CX-05 — 난이도를 비우면 "전체"가 아니라 난이도 5를 뺀 1~4만 나온다 (주석과 코드 불일치)
- 규칙: `level` 이 비어 있으면 `level < 5` 조건이 붙어 난이도 5 문항이 제외된다. 주석은 "1~5 모두 포함"이라 하지만 코드는 그렇지 않다.
- 근거: `search.php:213-216`
- 코드:
  ```php
  // 난이도 값이 비어 있으면 전체 난이도 검색 (1~5 모두 포함)
  if ($level == '') {
      $where .= " AND level < 5";
  ```
- 부가: 난이도 폼 선택지는 "전체"(빈값) + 1~5 이다(`:192-206`). "전체"를 고르면 위 규칙이 적용된다. 난이도 5는 5를 명시해야만 나온다.

### CX-06 — 난이도 1~5 는 정확 일치, 그 밖의 값도 정수로 바꿔 그대로 비교한다
- 규칙: `1`~`5` 한 자리면 `level = 값`. 그 외(예: `9`, `abc`)는 경고 후 `(int)` 변환한 값으로 `level = ?` 를 건다(`abc` 는 0).
- 근거: `search.php:217-229`
- 코드:
  ```php
  else if (preg_match('/^[1-5]$/', $level)) { $where .= " AND level = ?";
  ...
  $warnings[] = '난이도는 1~5 사이여야 합니다.';
  $values[] = (int)$level;
  ```

### CX-07 — 태그는 이름 정확 일치이며 문항에 그 태그가 하나라도 붙어 있으면 된다
- 규칙: 태그는 `tag.name = 입력값` 인 태그가 붙은 문항만 찾는다(`EXISTS`). 부분 일치 미지원, 한 개만 지정 가능하다.
- 근거: `search.php:235-248`
- 코드:
  ```php
  $where .= " AND EXISTS (SELECT 1 FROM item_tag it JOIN tag t ON t.id = it.tag_id"
          . " WHERE it.item_id = v_item_public.id AND t.name = ?)";
  ```

### CX-08 — 태그 이름은 50자까지만 쓰고, 미등록 태그도 조회는 그대로 한다
- 규칙: 50자 초과는 잘라서 검색하고, 등록되지 않은 태그는 경고만 낸 채 조회해 0건이 된다.
- 근거: `search.php:240-243`, `:258-260`
- 코드:
  ```php
  $tag = mb_substr($tag, 0, 50, 'UTF-8');
  $warnings[] = '등록되지 않은 태그입니다: ' . $tag;
  ```

### CX-09 — 한 페이지 20건, 페이지는 1~999 로 보정된다
- 규칙: `LIMIT 20 OFFSET (page-1)*20`. 숫자가 아니거나 1 미만이면 1, 999 초과면 999 로 보정한다.
- 근거: `search.php:336-349`, `:523`
- 코드:
  ```php
  if ($page > 999) { $page = 999;
  $limit  = " LIMIT 20 OFFSET " . (int)$offset;
  ```

## 2. 정렬

### CX-10 — 기본 정렬은 난이도 내림차순, 같으면 id 오름차순이다
- 규칙: `sort` 를 비우면(또는 알 수 없는 값이면) `level DESC, id ASC`.
- 근거: `search.php:317-320`, `:322-326`
- 코드:
  ```php
  // 기본 정렬: 어려운 문항부터, 같은 난이도면 번호 순
  $orderBy = " ORDER BY level DESC, id ASC";
  ```

### CX-11 — 지정 정렬은 기준마다 2차 키와 기본 방향이 다르다
- 규칙: `sort` 는 `id | title | unit | level | created` 만 허용한다(소문자화). `dir` 이 없거나 잘못되면 `id` · `title` · `unit` 은 ASC, `level` · `created` 는 DESC 로 동작한다. 2차 키는 title/level/created 가 `id ASC`, unit 은 `level DESC, id ASC`.
- 근거: `search.php:266-273`, `:277-315`
- 코드:
  ```php
  $orderBy = " ORDER BY unit_code ASC, level DESC, id ASC";   // unit (asc)
  $orderBy = " ORDER BY level DESC, id ASC";                  // level (asc 가 아니면)
  ```
- 부가: `id` 정렬은 `id` 만 쓴다(`:277-283`). `level` · `created` 는 `dir=asc` 일 때만 오름차순이다(`:302`, `:310`).

## 3. 목록의 제외 조건

### CX-12 — 검색 결과는 공개 문항 뷰(`v_item_public`)에서만 나온다
- 규칙: 검색은 `item` 테이블이 아니라 `v_item_public` 뷰를 조회하므로, 뷰가 걸러 내는 문항은 어떤 조건으로도 나오지 않는다. 뷰의 제외 조건 자체는 이 폴더 코드에서 확인되지 않는다(위 "한계" 참조).
- 근거: `search.php:521-526`
- 코드:
  ```php
  $from   = " FROM v_item_public";
  $countSql = "SELECT COUNT(*) AS cnt" . $from . $where;
  ```

### CX-13 — 등록 직후 문항은 검수중(`R`)이며 검수 완료 전에는 검색에 나오지 않는다
- 규칙: 등록 시 `status` 는 항상 `'R'` 로 들어간다. 코드 주석은 이 상태가 검색 화면에 나오지 않는다고 하나, 그 제외 자체는 뷰(CX-12)가 담당하므로 이 폴더에서 직접 확인되는 것은 "`R` 로 저장한다"까지다.
- 근거: `register.php:84`, `:93-94`, `:116`
- 코드:
  ```php
  VALUES (?, ?, ?, ?, ?, 'R', NOW(), NOW())
  ```

### CX-14 — 단원 목록의 문항 수는 공개(`A`) 문항만 센다
- 규칙: 단원별 문항 수는 `status = 'A'` 만 세며 삭제 · 검수중 문항은 세지 않는다. 단원은 학년 → 코드 오름차순으로 나열한다.
- 근거: `units.php:15-19`
- 코드:
  ```php
  (SELECT COUNT(*) FROM item i WHERE i.unit_id = u.id AND i.status = 'A') AS item_count
  ORDER BY u.grade ASC, u.code ASC
  ```

## 4. 등록 시 검증

### CX-15 — 제목은 앞뒤 공백 제거 후 5자 이상 200자 이하다
- 규칙: 제목을 `trim` 한 뒤 글자 수(`mb_strlen`)가 5 미만이면 오류, 200 초과여도 오류다.
- 근거: `register.php:41`, `:49-54`
- 코드:
  ```php
  if (mb_strlen($title, 'UTF-8') < 5) {
  if (mb_strlen($title, 'UTF-8') > 200) {
  ```

### CX-16 — 지문은 필수이며 길이 상한 검증은 없다
- 규칙: 앞뒤 공백 제거 후 빈 문자열이면 오류다. 최대 길이는 검사하지 않는다.
- 근거: `register.php:42`, `:56-58`
- 코드:
  ```php
  if ($stem === '') {
      $errors[] = '지문을 입력해야 합니다.';
  ```

### CX-17 — 단원은 필수이며 존재하는 단원 id 여야 한다
- 규칙: 제출한 `unit_id` 가 `unit` 테이블의 id 목록 중 하나와 일치해야 하고, 아니면 오류다.
- 근거: `register.php:27-31`, `:60-68`
- 코드:
  ```php
  if ((string)$u['id'] === $unitId) { $unitOk = true; }
  $errors[] = '단원을 선택해야 합니다.';
  ```

### CX-18 — 난이도는 정수 1~5 한 자리여야 한다
- 규칙: `^[1-5]$` 에 맞지 않으면(빈값 포함) 오류다. 검색(CX-06)과 달리 범위 밖 값을 허용하지 않는다.
- 근거: `register.php:70-72`
- 코드:
  ```php
  if (!preg_match('/^[1-5]$/', $level)) {
      $errors[] = '난이도는 1~5 사이여야 합니다.';
  ```

### CX-19 — 태그는 선택 사항이며, 목록에 없는 태그 id 는 오류 없이 버려진다
- 규칙: 태그를 하나도 안 골라도 등록된다. 등록된 태그 id 만 저장 대상에 남기고 나머지는 오류 없이 무시한다.
- 근거: `register.php:45`, `:74-81`, `:104-113`
- 코드:
  ```php
  if ((string)$t['id'] === (string)$tid) {
      $validTagIds[] = (int)$tid;
  ```

### CX-20 — 검증 오류가 하나라도 있으면 저장하지 않고, 저장은 한 트랜잭션이다
- 규칙: 오류 배열이 비어 있을 때만 저장한다. 새 id 는 `MAX(id)+1`(`FOR UPDATE`), 문항과 태그 연결을 한 트랜잭션에 넣고 실패하면 롤백한다.
- 근거: `register.php:83-90`, `:114`, `:119-123`
- 코드:
  ```php
  if (empty($errors)) {
  $res = $conn->query("SELECT COALESCE(MAX(id), 0) + 1 AS next_id FROM item FOR UPDATE");
  $conn->rollback();
  ```
