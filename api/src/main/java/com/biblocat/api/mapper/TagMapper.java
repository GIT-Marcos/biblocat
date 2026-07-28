package com.biblocat.api.mapper;

import com.biblocat.api.dto.response.TagResponse;
import com.biblocat.api.entity.Tag;

public final class TagMapper {

    private TagMapper() {
    }

    public static TagResponse toResponse(Tag tag) {
        return new TagResponse(tag.getId(), tag.getName());
    }
}
