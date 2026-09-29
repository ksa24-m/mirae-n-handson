// 동작 보존 테스트 — 문항 은행(item-bank)의 문항 검색 화면 search.php
//
// - 대상 주소는 lib/target.mjs 가 정한다(환경 변수 TARGET_BASE_URL, 없으면 레거시 기본 주소).
//   이관 후에는 /search.php 가 404 이면 PATH_ALIASES 의 새 경로로 다시 요청한다.
// - 응답은 fetchNormalized 로 {status, rows, count, message} 모양으로 바꾼 뒤 스냅샷과 비교한다.
// - 기대값을 손으로 적지 않는다. 지금 시스템의 실제 응답이 기대값이다(npm run baseline -- item-bank).
// - 규칙 ID(BR-xx)는 docs/item-bank/BUSINESS-RULES.md 기준이다.
// - 경고 문구(<ul id="warnings">)는 정규화 대상이 아니다. 경고를 겨냥한 케이스도 행 · 건수 · 안내 문구만 비교한다.
import { describe, expect, it } from 'vitest';
import { fetchNormalized } from '../lib/target.mjs';

const MODULE = 'item-bank';
const PATH = '/search.php';

const search = (params) => fetchNormalized(MODULE, PATH, params);

describe('item-bank · 문항 검색(search.php)', () => {
  describe('정상 입력', () => {
    it('조건 없는 첫 화면 — 난이도 5 제외 · 기본 정렬 · 20건 페이지 · 공개 문항만 (BR-10, BR-17, BR-22, BR-25)', async () => {
      expect(await search({})).toMatchSnapshot();
    });

    it('단원 M5-1 + 난이도 1 — 조건 AND 결합 · 단원 코드 정확 일치 (BR-01, BR-07, BR-09, BR-11)', async () => {
      expect(await search({ unit: 'M5-1', level: '1' })).toMatchSnapshot();
    });

    it('키워드 분수 + 태그 문장제 — 제목/지문 부분 일치 · 태그 이름 정확 일치 (BR-01, BR-03, BR-13, BR-10)', async () => {
      expect(await search({ q: '분수', tag: '문장제' })).toMatchSnapshot();
    });
  });

  describe('경계값', () => {
    it('난이도 5 명시 — 비우면 빠지는 난이도 5 가 나오고 검수중 문항은 빠진다 (BR-11, BR-10, BR-25)', async () => {
      expect(await search({ level: '5' })).toMatchSnapshot();
    });

    it('키워드 정확히 100자 — 잘리지 않아 마지막 글자까지 조건에 들어간다 (BR-04, BR-05)', async () => {
      // '%' 99개 + 시드에 없는 글자 1개: 자르지 않으면 0건
      expect(await search({ q: '%'.repeat(99) + '★' })).toMatchSnapshot();
    });

    it('키워드 101자 — 앞 100자만 써서 마지막 글자가 조건에서 빠진다 (BR-04, BR-05)', async () => {
      // '%' 100개 + 시드에 없는 글자 1개: 100자로 자르면 와일드카드만 남아 전체가 걸린다
      expect(await search({ q: '%'.repeat(100) + '★' })).toMatchSnapshot();
    });

    it('한 글자 키워드 — 막지 않고 검색한다 (BR-06, BR-03)', async () => {
      expect(await search({ q: '분' })).toMatchSnapshot();
    });

    it('page=2 — 결과 20건이라 범위 밖 페이지는 안내 문구 없이 빈 표 (BR-22, BR-24)', async () => {
      expect(await search({ page: '2' })).toMatchSnapshot();
    });

    it('page=1000 — 999 로 보정된다 (BR-23)', async () => {
      expect(await search({ page: '1000' })).toMatchSnapshot();
    });

    it('page=0 — 1 미만은 1페이지로 보정된다 (BR-23)', async () => {
      expect(await search({ page: '0' })).toMatchSnapshot();
    });
  });

  describe('빈 값 · 누락', () => {
    it('모든 파라미터 빈 문자열 — 누락과 같게 처리된다 (BR-03, BR-10, BR-17, BR-19, BR-23)', async () => {
      expect(
        await search({ q: '', unit: '', level: '', tag: '', sort: '', dir: '', page: '' }),
      ).toMatchSnapshot();
    });

    it('공백만 있는 키워드 · 태그 — trim 후 빈 값이라 조건이 붙지 않는다 (BR-03, BR-13)', async () => {
      expect(await search({ q: '   ', tag: ' ' })).toMatchSnapshot();
    });
  });

  describe('이상한 값', () => {
    it('음수 난이도 -1 — 형식 밖 값은 정수 변환 값으로 비교한다 (BR-12)', async () => {
      expect(await search({ level: '-1' })).toMatchSnapshot();
    });

    it('난이도 3abc — 정수 변환으로 3 이 되어 난이도 3 문항이 나온다 (BR-12)', async () => {
      expect(await search({ level: '3abc' })).toMatchSnapshot();
    });

    it('없는 단원 코드 Z9-99 — 조회는 하고 0건 안내 문구 (BR-08, BR-09, BR-24)', async () => {
      expect(await search({ unit: 'Z9-99' })).toMatchSnapshot();
    });

    it('소문자 단원 코드 m5-1 — 입력값 그대로 조회한다 (BR-08)', async () => {
      expect(await search({ unit: 'm5-1' })).toMatchSnapshot();
    });

    it('태그 60자 — 50자로 잘린 뒤 없는 태그로 조회한다 (BR-14, BR-16)', async () => {
      expect(await search({ tag: '가'.repeat(60) })).toMatchSnapshot();
    });

    it('키워드 _ — LIKE 와일드카드로 그대로 쓴다 (BR-05, BR-06)', async () => {
      expect(await search({ q: '_' })).toMatchSnapshot();
    });

    it('정렬 기준 대문자 LEVEL + 잘못된 방향 up — 소문자로 비교하고 방향은 기준별 기본값 (BR-18, BR-19, BR-20)', async () => {
      expect(await search({ sort: 'LEVEL', dir: 'up' })).toMatchSnapshot();
    });
  });
});
