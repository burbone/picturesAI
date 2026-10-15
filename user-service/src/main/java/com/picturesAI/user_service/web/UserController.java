package com.picturesAI.user_service.web;

import com.picturesAI.user_service.dto.UpdateUserRequest;
import com.picturesAI.user_service.dto.UserResponse;
import com.picturesAI.user_service.service.UserService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/me")
    public UserResponse me() {
        return userService.getOrCreateCurrent();
    }

    @PatchMapping("/me")
    public UserResponse patchMe(@Valid @RequestBody UpdateUserRequest request) {
        return userService.updateCurrent(request);
    }

    @DeleteMapping("/me")
    public ResponseEntity<Void> deleteMe() {
        userService.deleteCurrent();
        return ResponseEntity.status(HttpStatus.ACCEPTED).build();
    }

    @GetMapping("/{id}")
    public UserResponse byId(@PathVariable UUID id) {
        return userService.getById(id);
    }

    @GetMapping("/{id}/generations")
    public ResponseEntity<Void> generations(@PathVariable UUID id) {
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
    }
}