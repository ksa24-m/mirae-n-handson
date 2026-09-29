package com.example.item;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 레거시 문항 검색(PHP 7.4 {@code search.php})의 문자열 처리와 같은 결과를 내는 도우미.
 * 이관 중에는 레거시 동작이 기준이므로 Java 표준 동작({@code String.trim()} 등)으로 바꾸지 않는다.
 */
final class LegacyPhpStrings {

    /** PHP {@code trim()} 이 지우는 문자: 공백, \t, \n, \r, \0, \x0B. */
    private static final String PHP_TRIM_CHARS = " \t\n\r\0\u000B";

    /** PHP 숫자 문자열 앞에 허용되는 공백: 공백, \t, \n, \r, \x0B, \f. */
    private static final String PHP_NUMERIC_LEADING_WS = " \t\n\r\u000B\f";

    /** PHP 7.1+ {@code (int)} 변환이 읽는 앞부분: 부호, 정수 · 소수, 지수. 뒤에 붙은 글자는 버린다. */
    private static final Pattern PHP_NUMERIC_PREFIX =
        Pattern.compile("^[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?");

    /** long 으로 바로 읽어도 넘치지 않는 자릿수. */
    private static final int SAFE_LONG_DIGITS = 18;

    private LegacyPhpStrings() {
    }

    /** PHP {@code trim()}. */
    static String trim(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && PHP_TRIM_CHARS.indexOf(value.charAt(start)) >= 0) {
            start++;
        }
        while (end > start && PHP_TRIM_CHARS.indexOf(value.charAt(end - 1)) >= 0) {
            end--;
        }
        return value.substring(start, end);
    }

    /** PHP {@code mb_strlen($value, 'UTF-8')} — 코드 포인트 수. */
    static int length(String value) {
        return value.codePointCount(0, value.length());
    }

    /** PHP {@code mb_substr($value, 0, $max, 'UTF-8')} — 앞에서부터 코드 포인트 {@code max} 개. */
    static String head(String value, int max) {
        if (length(value) <= max) {
            return value;
        }
        return value.substring(0, value.offsetByCodePoints(0, max));
    }

    /** PHP 7.4 {@code strtolower()} (C 로캘) — ASCII 대문자만 바꾼다. */
    static String toLowerAscii(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            out.append(c >= 'A' && c <= 'Z' ? (char) (c + ('a' - 'A')) : c);
        }
        return out.toString();
    }

    /**
     * PHP 7.4 {@code (int)$value}.
     * 앞 공백을 건너뛰고 숫자로 읽을 수 있는 앞부분만 쓴다({@code "3abc"} → 3, {@code "abc"} → 0).
     * 소수 · 지수는 실수로 읽어 0 쪽으로 버리고({@code "3.5"} → 3, {@code "1e1"} → 10), long 범위를 넘으면 끝값에 붙인다.
     */
    static long toInt(String value) {
        int start = 0;
        while (start < value.length() && PHP_NUMERIC_LEADING_WS.indexOf(value.charAt(start)) >= 0) {
            start++;
        }
        Matcher m = PHP_NUMERIC_PREFIX.matcher(value.substring(start));
        if (!m.find()) {
            return 0L;
        }
        String number = m.group();
        boolean integerOnly = number.matches("[+-]?[0-9]+");
        String digits = number.replaceFirst("^[+-]", "").replaceFirst("^0+(?=.)", "");
        if (integerOnly && digits.length() <= SAFE_LONG_DIGITS) {
            return Long.parseLong(number);
        }
        // Java 의 double → long 변환은 0 쪽으로 버리고 범위를 넘으면 끝값에 붙는다(PHP zend_dval_to_lval_cap 와 같음)
        return (long) Double.parseDouble(number);
    }

    /** 정수 변환 값을 int 로 좁힌다. 범위 밖이면 끝값 — 어느 쪽이든 TINYINT 난이도와는 같을 수 없다. */
    static int clampToInt(long value) {
        return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, value));
    }
}
