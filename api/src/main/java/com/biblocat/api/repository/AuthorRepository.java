package com.biblocat.api.repository;

import com.biblocat.api.entity.Author;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AuthorRepository extends JpaRepository<Author, UUID> {

    Optional<Author> findByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCase(String name);

    List<Author> findByNameContainingIgnoreCase(String q);
}
