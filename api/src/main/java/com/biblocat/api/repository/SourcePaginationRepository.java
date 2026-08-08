package com.biblocat.api.repository;

import com.biblocat.api.entity.Author;
import com.biblocat.api.entity.Source;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.support.PageableExecutionUtils;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

@Repository
public class SourcePaginationRepository {

    private final EntityManager entityManager;

    public SourcePaginationRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    public Page<Source> findAll(Specification<Source> spec, Pageable pageable) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();

        CriteriaQuery<Source> cq = cb.createQuery(Source.class);
        Root<Source> root = cq.from(Source.class);

        Join<Source, Author> authorJoin = root.join("author", JoinType.LEFT);

        Predicate predicate = spec.toPredicate(root, cq, cb);
        if (predicate != null) {
            cq.where(predicate);
        }

        List<Order> orders = new ArrayList<>();
        for (Sort.Order order : pageable.getSort()) {
            Expression<?> expression = order.getProperty().equals("author.name")
                    ? authorJoin.get("name")
                    : root.get(order.getProperty());
            orders.add(order.isAscending() ? cb.asc(expression) : cb.desc(expression));
        }
        if (!orders.isEmpty()) {
            cq.orderBy(orders);
        }

        TypedQuery<Source> query = entityManager.createQuery(cq);
        query.setFirstResult((int) Math.min(pageable.getOffset(), Integer.MAX_VALUE - 1L));
        query.setMaxResults(pageable.getPageSize());
        List<Source> content = query.getResultList();

        CriteriaQuery<Long> countCq = cb.createQuery(Long.class);
        Root<Source> countRoot = countCq.from(Source.class);
        Predicate countPredicate = spec.toPredicate(countRoot, countCq, cb);
        if (countPredicate != null) {
            countCq.where(countPredicate);
        }
        countCq.select(cb.countDistinct(countRoot));
        Long total = entityManager.createQuery(countCq).getSingleResult();

        return PageableExecutionUtils.getPage(content, pageable, () -> total);
    }
}
