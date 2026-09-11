package com.mockapilab.modules.auth.controller;

import com.mockapilab.common.api.ApiResponse;
import com.mockapilab.modules.auth.dto.AuthResponse;
import com.mockapilab.modules.auth.dto.LoginRequest;
import com.mockapilab.modules.auth.dto.RegisterRequest;
import com.mockapilab.modules.auth.dto.UserResponse;
import com.mockapilab.modules.auth.security.UserPrincipal;
import com.mockapilab.modules.auth.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controller handling user registration, authentication, and identity queries.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    public ResponseEntity<ApiResponse<AuthResponse>> register(@Valid @RequestBody RegisterRequest request) {
        AuthResponse response = authService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("User registered successfully", response));
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<AuthResponse>> login(@Valid @RequestBody LoginRequest request) {
        AuthResponse response = authService.login(request);
        return ResponseEntity.ok(ApiResponse.success("Authentication successful", response));
    }

    @GetMapping("/me")
    public ResponseEntity<ApiResponse<UserResponse>> getCurrentUser(@AuthenticationPrincipal UserPrincipal principal) {
        UserResponse response = new UserResponse(
                principal.getId(),
                principal.getUsername(),
                principal.getDisplayName(),
                null,
                null
        );
        return ResponseEntity.ok(ApiResponse.success("Current user profile retrieved", response));
    }
}
