package com.biblocat.api.service;

import com.biblocat.api.dto.request.TagCreateRequest;
import com.biblocat.api.dto.request.TagPatchRequest;
import com.biblocat.api.dto.response.TagResponse;
import com.biblocat.api.entity.Tag;
import com.biblocat.api.exception.TagAlreadyExistsException;
import com.biblocat.api.exception.TagNotFoundException;
import com.biblocat.api.mapper.TagMapper;
import com.biblocat.api.repository.TagRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class TagService {

    private final TagRepository tagRepository;

    public TagService(TagRepository tagRepository) {
        this.tagRepository = tagRepository;
    }

    @Transactional(readOnly = true)
    public List<TagResponse> findAll(String q) {
        if (q == null || q.isBlank()) {
            return tagRepository.findAll().stream()
                    .map(TagMapper::toResponse)
                    .toList();
        }
        return tagRepository.findByNameContainingIgnoreCase(q.strip()).stream()
                .map(TagMapper::toResponse)
                .toList();
    }

    public TagResponse create(TagCreateRequest request) {
        String normalized = normalize(request.name());
        if (tagRepository.existsByNameIgnoreCase(normalized)) {
            throw new TagAlreadyExistsException(normalized);
        }
        try {
            Tag tag = tagRepository.saveAndFlush(new Tag(normalized));
            return TagMapper.toResponse(tag);
        } catch (DataIntegrityViolationException e) {
            throw new TagAlreadyExistsException(normalized);
        }
    }

    public TagResponse update(UUID id, TagPatchRequest request) {
        Tag tag = tagRepository.findById(id)
                .orElseThrow(() -> new TagNotFoundException(id));

        String normalized = normalize(request.name());
        if (!tag.getName().equalsIgnoreCase(normalized) && tagRepository.existsByNameIgnoreCase(normalized)) {
            throw new TagAlreadyExistsException(normalized);
        }

        tag.setName(normalized);
        try {
            return TagMapper.toResponse(tagRepository.saveAndFlush(tag));
        } catch (DataIntegrityViolationException e) {
            throw new TagAlreadyExistsException(normalized);
        }
    }

    public void delete(UUID id) {
        if (!tagRepository.existsById(id)) {
            throw new TagNotFoundException(id);
        }
        tagRepository.deleteById(id);
    }

    private static String normalize(String name) {
        return name.strip().toLowerCase();
    }
}
