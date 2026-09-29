package com.example.item;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.jpa.domain.Specification;

/**
 * 문항 검색 WHERE 조건. 값은 모두 Criteria 파라미터로 바인딩한다.
 * 레거시는 뷰 {@code v_item_public}(status='A' + unit inner join)을 조회했고, 여기서는 같은 조건을 엔티티에 건다.
 */
final class ItemSearchSpecs {

    /** 난이도를 비웠을 때 이 값 이상은 뺀다(BR-10). */
    static final int LEVEL_EXCLUDED_WHEN_EMPTY = 5;

    /**
     * MariaDB 의 LIKE 기본 이스케이프 문자. 레거시는 ESCAPE 절 없이 {@code LIKE ?} 를 썼으므로 {@code \} 가 이스케이프로 동작했다.
     * Hibernate 는 ESCAPE 를 주지 않으면 패턴의 {@code \} 를 글자 그대로 바꿔 버리므로, 같은 동작을 위해 명시한다.
     */
    private static final char LIKE_ESCAPE = '\\';

    private ItemSearchSpecs() {
    }

    /** 검색 조건을 모두 AND 로 묶는다(BR-01). */
    static Specification<Item> matching(ItemSearchCondition condition) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            // BR-25: 공개 문항만
            predicates.add(cb.equal(root.get("status"), ItemStatus.ACTIVE));
            if (condition.keyword() != null) {
                // BR-03, BR-05: 제목 또는 지문 부분 일치. % · _ 는 이스케이프하지 않는다
                String like = "%" + condition.keyword() + "%";
                predicates.add(cb.or(
                    cb.like(root.get("title"), like, LIKE_ESCAPE),
                    cb.like(root.get("stem"), like, LIKE_ESCAPE)));
            }
            if (condition.unitCode() != null) {
                // BR-07, BR-08: 단원 코드 = (대소문자 비교는 DB collation 을 따른다)
                predicates.add(cb.equal(root.get("unit").get("code"), condition.unitCode()));
            }
            if (condition.level() == null) {
                // BR-10: 난이도를 비우면 난이도 5 를 뺀다
                predicates.add(cb.lessThan(root.get("level"), LEVEL_EXCLUDED_WHEN_EMPTY));
            } else {
                // BR-11, BR-12
                predicates.add(cb.equal(root.get("level"), condition.level()));
            }
            if (condition.tag() != null) {
                // BR-13, BR-15: 태그 이름 정확 일치가 하나라도 있는 문항
                predicates.add(cb.exists(tagExists(root, query.subquery(Integer.class), cb, condition.tag())));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    private static Subquery<Integer> tagExists(Root<Item> root, Subquery<Integer> sub, CriteriaBuilder cb, String tag) {
        Root<Item> tagged = sub.from(Item.class);
        Join<Item, Tag> tags = tagged.join("tags");
        return sub.select(cb.literal(1))
            .where(cb.equal(tagged.get("id"), root.get("id")), cb.equal(tags.get("name"), tag));
    }
}
