package com.biblocat.api.service;

import com.biblocat.api.dto.response.AuthorResponse;
import com.biblocat.api.entity.Author;
import com.biblocat.api.mapper.AuthorMapper;
import com.biblocat.api.repository.AuthorRepository;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class AuthorService {

    private final AuthorRepository authorRepository;

    public AuthorService(AuthorRepository authorRepository) {
        this.authorRepository = authorRepository;
    }

    @Transactional(readOnly = true)
    public List<AuthorResponse> findAll(String q) {
        if (q == null || q.isBlank()) {
            return authorRepository.findAll().stream()
                    .map(AuthorMapper::toResponse)
                    .toList();
        }
        return authorRepository.findByNameContainingIgnoreCase(q.strip()).stream()
                .map(AuthorMapper::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public Author findOrCreate(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String trimmed = name.strip();
        return authorRepository.findByNameIgnoreCase(trimmed)
                .orElseGet(() -> authorRepository.save(new Author(trimmed)));
    }
}
