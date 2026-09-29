package com.example.item;

import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Sort;
import org.springframework.util.MultiValueMap;

/**
 * 문항 검색 조건. 원시 GET 파라미터를 레거시 {@code search.php} 규칙(BR-02 ~ BR-23)대로 해석한 결과다.
 * 규칙 ID 는 {@code docs/item-bank/BUSINESS-RULES.md} 기준.
 *
 * @param keyword  제목 · 지문 부분 일치 키워드. 없으면 {@code null}
 * @param unitCode 단원 코드(입력값 그대로). 없으면 {@code null}
 * @param level    난이도. {@code null} 이면 "비어 있음" — 난이도 5 를 뺀다(BR-10)
 * @param tag      태그 이름. 없으면 {@code null}
 * @param sort     정렬 기준(소문자). 모르는 값은 {@code ""}(기본 정렬)
 * @param dir      정렬 방향 {@code asc} | {@code desc} | {@code ""}(기준별 기본 방향)
 * @param page     페이지 번호 1 ~ 999
 */
public record ItemSearchCondition(
    String keyword,
    String unitCode,
    Integer level,
    String tag,
    String sort,
    String dir,
    int page) {

    static final int KEYWORD_MAX_LENGTH = 100;
    static final int TAG_MAX_LENGTH = 50;
    static final int MAX_PAGE = 999;

    private static final List<String> SORT_KEYS = List.of("id", "title", "unit", "level", "created");

    /** GET 파라미터 → 검색 조건. 형식이 틀린 값도 거부하지 않고 레거시처럼 보정한다. */
    public static ItemSearchCondition from(MultiValueMap<String, String> params) {
        // BR-03, BR-04: trim 후 100자까지. 자른 뒤에는 다시 trim 하지 않는다
        String q = LegacyPhpStrings.head(LegacyPhpStrings.trim(param(params, "q", "")), KEYWORD_MAX_LENGTH);
        // BR-07, BR-08: trim 만 하고 형식 · 대소문자는 그대로
        String unit = LegacyPhpStrings.trim(param(params, "unit", ""));
        // BR-13, BR-14: trim 후 비어 있지 않으면 50자까지
        String tag = LegacyPhpStrings.trim(param(params, "tag", ""));
        if (!tag.isEmpty()) {
            tag = LegacyPhpStrings.head(tag, TAG_MAX_LENGTH);
        }
        return new ItemSearchCondition(
            q.isEmpty() ? null : q,
            unit.isEmpty() ? null : unit,
            parseLevel(LegacyPhpStrings.trim(param(params, "level", ""))),
            tag.isEmpty() ? null : tag,
            parseSort(param(params, "sort", "")),
            parseDir(param(params, "dir", "")),
            parsePage(param(params, "page", "1")));
    }

    /**
     * BR-02: PHP {@code $_GET} 과 같은 값 선택.
     * {@code name[]=a&name[]=b} 처럼 배열로 오면 첫 값, {@code name=a&name=b} 처럼 같은 이름이 반복되면 마지막 값(PHP 는 덮어쓴다).
     */
    static String param(MultiValueMap<String, String> params, String name, String absent) {
        for (Map.Entry<String, List<String>> entry : params.entrySet()) {
            if (entry.getKey().startsWith(name + "[") && !entry.getValue().isEmpty()) {
                return entry.getValue().get(0);
            }
        }
        List<String> values = params.get(name);
        if (values == null || values.isEmpty()) {
            return absent;
        }
        String last = values.get(values.size() - 1);
        return last == null ? "" : last;
    }

    /** BR-10 ~ BR-12: 빈값 → {@code null}, 그 밖은 PHP {@code (int)} 변환 값({@code 3abc} → 3, {@code -1} → -1). */
    private static Integer parseLevel(String level) {
        if (level.isEmpty()) {
            return null;
        }
        if (level.matches("[1-5]")) {
            return Integer.valueOf(level);
        }
        return LegacyPhpStrings.clampToInt(LegacyPhpStrings.toInt(level));
    }

    /** BR-18: 소문자로 바꿔 비교하고, 모르는 기준은 기본 정렬({@code ""}). */
    private static String parseSort(String raw) {
        String sort = LegacyPhpStrings.toLowerAscii(LegacyPhpStrings.trim(raw));
        return SORT_KEYS.contains(sort) ? sort : "";
    }

    /** BR-19: {@code asc} · {@code desc} 가 아니면 지정하지 않은 것으로 본다. */
    private static String parseDir(String raw) {
        String dir = LegacyPhpStrings.toLowerAscii(LegacyPhpStrings.trim(raw));
        return dir.equals("asc") || dir.equals("desc") ? dir : "";
    }

    /**
     * BR-23: 숫자가 아니면 1, 1 미만이면 1, 999 초과면 999. 페이지 값은 trim 하지 않는다.
     * PHP {@code preg_match('/^[0-9]+$/')} 의 {@code $} 는 끝의 줄바꿈 하나를 허용하므로 {@code "2\n"} 도 숫자로 본다.
     */
    private static int parsePage(String raw) {
        long page = 1;
        if (raw.matches("[0-9]+\n?")) {
            page = LegacyPhpStrings.toInt(raw);
        }
        if (page < 1) {
            page = 1;
        }
        if (page > MAX_PAGE) {
            page = MAX_PAGE;
        }
        return (int) page;
    }

    /** BR-17, BR-20, BR-21: 정렬 기준 · 방향 → ORDER BY. 2차 키는 방향과 관계없이 고정이다. */
    public Sort toSort() {
        Sort byIdAsc = Sort.by(Sort.Order.asc("id"));
        Sort levelDesc = Sort.by(Sort.Order.desc("level"));
        return switch (sort) {
            case "id" -> dir.equals("desc") ? Sort.by(Sort.Order.desc("id")) : byIdAsc;
            case "title" -> Sort.by(dir.equals("desc") ? Sort.Order.desc("title") : Sort.Order.asc("title")).and(byIdAsc);
            case "unit" -> Sort.by(dir.equals("desc") ? Sort.Order.desc("unit.code") : Sort.Order.asc("unit.code"))
                .and(levelDesc).and(byIdAsc);
            case "level" -> Sort.by(dir.equals("asc") ? Sort.Order.asc("level") : Sort.Order.desc("level")).and(byIdAsc);
            case "created" -> Sort.by(dir.equals("asc") ? Sort.Order.asc("createdAt") : Sort.Order.desc("createdAt"))
                .and(byIdAsc);
            default -> levelDesc.and(byIdAsc);
        };
    }
}
