package com.biblocat.api.service;

import com.biblocat.api.entity.Author;
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

    public record FilterResult(
            Specification<Source> specification,
            boolean authorJoinNeeded
    ) {
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }

    public static FilterResult withFilter(String q, UUID authorId, UUID tagId,
                                          FileFormat format, boolean includeDeleted) {
        boolean[] needsAuthorJoin = {false};

        Specification<Source> spec = (root, query, cb) -> {
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
                needsAuthorJoin[0] = true;
            }

            if (authorId != null) {
                if (!needsAuthorJoin[0]) {
                    Join<Source, Author> authorJoin = root.join("author", JoinType.INNER);
                    predicates.add(cb.equal(authorJoin.get("id"), authorId));
                } else {
                    predicates.add(cb.equal(root.get("author").get("id"), authorId));
                }
                needsAuthorJoin[0] = true;
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

        return new FilterResult(spec, needsAuthorJoin[0]);
    }
}
