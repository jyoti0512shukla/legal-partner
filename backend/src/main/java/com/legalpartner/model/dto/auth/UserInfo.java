package com.legalpartner.model.dto.auth;

import com.legalpartner.model.entity.User;
import lombok.Builder;
import lombok.Data;

import java.util.UUID;

@Data
@Builder
public class UserInfo {

    private UUID id;
    private String email;
    private String displayName;
    private String role;
    private boolean mfaEnabled;

    public static UserInfo from(User user) {
        return UserInfo.builder()
                .id(user.getId())
                .email(user.getEmail())
                .displayName(user.getDisplayName())
                .role("ROLE_" + user.getRole().name())
                .mfaEnabled(user.isMfaEnabled())
                .build();
    }
}
