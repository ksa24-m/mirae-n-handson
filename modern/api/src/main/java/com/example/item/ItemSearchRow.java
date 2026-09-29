package com.example.item;

import java.util.List;

/**
 * 문항 검색 결과 한 행. 동작 보존 테스트의 정규화 필드({@code id, title, unit, level, tags})만 담는다.
 * {@code unit} 은 단원 코드, {@code tags} 는 태그 이름 목록(id 순, 없으면 빈 배열).
 */
public record ItemSearchRow(Integer id, String title, String unit, Integer level, List<String> tags) {

    static ItemSearchRow from(Item item) {
        return new ItemSearchRow(
            item.getId(),
            item.getTitle(),
            item.getUnit().getCode(),
            item.getLevel(),
            item.getTags().stream().map(Tag::getName).toList());
    }
}
