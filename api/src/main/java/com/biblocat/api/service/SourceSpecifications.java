package com.biblocat.api.service;

import com.biblocat.api.entity.Author;
import com.biblocat.api.entity.FileFormat;
import com.biblocat.api.entity.Source;
import com.biblocat.api.entity.Tag;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.domain.Specification;

public final class SourceSpecifications {

    private SourceSpecifications() {
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }

    public static Specification<Source> withFilter(String q, UUID authorId, UUID tagId, FileFormat format, boolean includeDeleted) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (!includeDeleted) {
                predicates.add(cb.isNull(root.get("deletedAt")));
            }

            if (q != null && !q.isBlank()) {
                String pattern = "%" + escapeLike(q.toLowerCase()) + "%";
                Predicate namePred = cb.like(cb.lower(root.get("name")), pattern, '\\');
                Predicate urlPred = cb.like(cb.lower(root.get("url")), pattern, '\\');

                Join<Source, Author> authorJoin = root.join("author", JoinType.LEFT);
                Predicate authorPred = cb.like(cb.lower(authorJoin.get("name")), pattern, '\\');

                predicates.add(cb.or(namePred, urlPred, authorPred));
            }

            if (authorId != null) {
                predicates.add(cb.equal(root.get("author").get("id"), authorId));
            }

            if (tagId != null) {
                Join<Source, Tag> tagJoin = root.join("tags", JoinType.INNER);
                predicates.add(cb.equal(tagJoin.get("id"), tagId));
                query.distinct(true);
            }

            if (format != null) {
                predicates.add(cb.equal(root.get("fileFormat"), format));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
