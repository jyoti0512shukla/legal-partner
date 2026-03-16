package com.legalpartner.model.dto.cloud;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CloudFileItem {
    private String id;
    private String name;
    private boolean folder;
    private String mimeType;
    private Long size;
    private String path;
}
