package com.biblocat.api.service;

import com.biblocat.api.entity.FileFormat;
import com.biblocat.api.entity.Source;
import com.biblocat.api.entity.Tag;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class SourceSpecifications {

    private SourceSpecifications() {
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }

    public static Specification<Source> withFilter(String q, UUID authorId, UUID tagId,
                                                   FileFormat format, boolean includeDeleted) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (!includeDeleted) {
                predicates.add(cb.isNull(root.get("deletedAt")));
            }

            if (q != null && !q.isBlank()) {
                String pattern = "%" + escapeLike(q.toLowerCase()) + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("name")), pattern, '\\'),
                        cb.like(cb.lower(root.get("url")), pattern, '\\'),
                        cb.like(cb.lower(root.get("author").get("name")), pattern, '\\')
                ));
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
