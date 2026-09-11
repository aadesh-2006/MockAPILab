package com.mockapilab.modules.auth.service;

import com.mockapilab.common.exception.DuplicateResourceException;
import com.mockapilab.common.exception.InvalidCredentialsException;
import com.mockapilab.modules.auth.dto.AuthResponse;
import com.mockapilab.modules.auth.dto.LoginRequest;
import com.mockapilab.modules.auth.dto.RegisterRequest;
import com.mockapilab.modules.auth.dto.UserResponse;
import com.mockapilab.modules.auth.model.User;
import com.mockapilab.modules.auth.repository.UserRepository;
import com.mockapilab.modules.auth.security.JwtService;
import com.mockapilab.modules.auth.security.UserPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            JwtService jwtService
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        String normalizedEmail = request.email().trim().toLowerCase();

        if (userRepository.existsByEmail(normalizedEmail)) {
            throw new DuplicateResourceException("An account with email " + normalizedEmail + " already exists");
        }

        String hashedPassword = passwordEncoder.encode(request.password());
        User user = new User(normalizedEmail, hashedPassword, request.displayName().trim());
        User savedUser = userRepository.save(user);

        UserPrincipal principal = UserPrincipal.fromEntity(savedUser);
        String token = jwtService.generateToken(principal);

        return AuthResponse.of(token, UserResponse.fromEntity(savedUser));
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        String normalizedEmail = request.email().trim().toLowerCase();

        User user = userRepository.findByEmail(normalizedEmail)
                .orElseThrow(() -> new InvalidCredentialsException("Invalid email or password"));

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new InvalidCredentialsException("Invalid email or password");
        }

        UserPrincipal principal = UserPrincipal.fromEntity(user);
        String token = jwtService.generateToken(principal);

        return AuthResponse.of(token, UserResponse.fromEntity(user));
    }
}
