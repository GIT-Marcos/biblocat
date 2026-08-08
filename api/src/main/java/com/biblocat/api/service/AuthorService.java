package com.biblocat.api.service;

import com.biblocat.api.dto.response.AuthorResponse;
import com.biblocat.api.entity.Author;
import com.biblocat.api.exception.DuplicateAuthorException;
import com.biblocat.api.mapper.AuthorMapper;
import com.biblocat.api.repository.AuthorRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

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

    @Transactional
    public Author findOrCreate(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String trimmed = name.strip();
        try {
            return authorRepository.findByNameIgnoreCase(trimmed)
                    .orElseGet(() -> authorRepository.save(new Author(trimmed)));
        } catch (DataIntegrityViolationException e) {
            throw new DuplicateAuthorException(trimmed);
        }
    }
}
