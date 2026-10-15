package com.picturesAI.user_service.mapper;

import com.picturesAI.user_service.domain.User;
import com.picturesAI.user_service.dto.UserResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface UserMapper {

    @Mapping(source = "public", target = "isPublic")
    UserResponse toResponse(User user);
}