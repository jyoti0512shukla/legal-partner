package com.legalpartner.model.dto.cloud;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CloudStorageConnectionStatus {
    private String provider;
    private String displayName;
    private boolean connected;
}
