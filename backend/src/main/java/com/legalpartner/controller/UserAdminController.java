package com.legalpartner.controller;

import com.legalpartner.model.dto.auth.AuthConfigDto;
import com.legalpartner.model.dto.auth.UserAdminDto;
import com.legalpartner.model.entity.User;
import com.legalpartner.model.enums.UserRole;
import com.legalpartner.repository.UserRepository;
import com.legalpartner.service.InviteService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/users")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class UserAdminController {

    private final UserRepository userRepo;
    private final InviteService inviteService;

    @GetMapping
    public List<UserAdminDto> listUsers() {
        return userRepo.findAll().stream().map(this::toDto).toList();
    }

    public record CreateUserRequest(String email, String displayName, String role, boolean sendInvite) {}

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UserAdminDto createUser(@RequestBody CreateUserRequest req) {
        UserRole role;
        try { role = UserRole.valueOf(req.role().toUpperCase()); }
        catch (Exception e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid role: " + req.role()); }

        User user = inviteService.createInvitedUser(req.email(), role, "ADMIN");
        if (req.displayName() != null && !req.displayName().isBlank()) {
            user.setDisplayName(req.displayName());
            user = userRepo.save(user);
        }

        if (req.sendInvite()) {
            String token = inviteService.generateInviteToken(user);
            inviteService.sendInviteEmail(user, token, null);
        }
        return toDto(user);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteUser(@PathVariable UUID id) {
        User user = userRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        userRepo.delete(user);
    }

    @PatchMapping("/{id}/role")
    public UserAdminDto changeRole(@PathVariable UUID id, @RequestParam String role) {
        User user = userRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        user.setRole(UserRole.valueOf(role.toUpperCase()));
        return toDto(userRepo.save(user));
    }

    @PatchMapping("/{id}/enable")
    public UserAdminDto toggleEnabled(@PathVariable UUID id, @RequestParam boolean enabled) {
        User user = userRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        user.setEnabled(enabled);
        return toDto(userRepo.save(user));
    }

    @PostMapping("/{id}/resend-invite")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resendInvite(@PathVariable UUID id) {
        inviteService.resendInvite(id);
    }

    @GetMapping("/config")
    public AuthConfigDto getConfig() {
        return inviteService.toDto(inviteService.getConfig());
    }

    @PutMapping("/config")
    public AuthConfigDto updateConfig(@RequestBody AuthConfigDto dto) {
        return inviteService.toDto(inviteService.updateConfig(dto));
    }

    private UserAdminDto toDto(User u) {
        return new UserAdminDto(u.getId(), u.getEmail(), u.getDisplayName(),
                u.getRole().name(), u.isEnabled() ? "ACTIVE" : "DISABLED",
                u.isEnabled(), u.isMfaEnabled(), u.getLastLoginAt(), u.getCreatedAt());
    }
}
