package com.example.item;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 문항 검색 서비스 — 레거시 {@code search.php} 이관.
 * 없는 단원 · 태그, 범위 밖 난이도도 오류가 아니라 0건으로 응답한다(BR-09, BR-12, BR-16).
 */
@Service
@Transactional(readOnly = true)
public class ItemSearchService {

    private static final Logger log = LoggerFactory.getLogger(ItemSearchService.class);

    /** 한 페이지 행 수(BR-22). */
    static final int PAGE_SIZE = 20;

    /** 0건일 때 안내 문구(BR-24). */
    static final String NO_RESULT_MESSAGE = "검색 결과가 없습니다";

    private final ItemRepository itemRepository;

    public ItemSearchService(ItemRepository itemRepository) {
        this.itemRepository = itemRepository;
    }

    public ItemSearchResponse search(ItemSearchCondition condition) {
        PageRequest pageable = PageRequest.of(condition.page() - 1, PAGE_SIZE, condition.toSort());
        Page<Item> page = itemRepository.findAll(ItemSearchSpecs.matching(condition), pageable);

        // 페이지 조회에 컬렉션 fetch 를 섞지 않고, 현재 페이지 id 로 단원 · 태그를 따로 읽는다(OSIV 꺼져 있음)
        List<Integer> ids = page.getContent().stream().map(Item::getId).toList();
        Map<Integer, Item> details = ids.isEmpty()
            ? Map.of()
            : itemRepository.findWithDetailsByIdIn(ids).stream()
                .collect(Collectors.toMap(Item::getId, Function.identity()));
        List<ItemSearchRow> rows = ids.stream().map(details::get).map(ItemSearchRow::from).toList();

        long count = page.getTotalElements();
        log.debug("item search page={} count={} rows={}", condition.page(), count, rows.size());
        // 범위 밖 페이지는 count > 0 이고 rows 가 비어 있어도 안내 문구가 없다(BR-24)
        return new ItemSearchResponse(rows, count, count == 0 ? NO_RESULT_MESSAGE : null);
    }
}
