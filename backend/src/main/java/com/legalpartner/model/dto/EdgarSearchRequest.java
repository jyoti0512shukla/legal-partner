package com.legalpartner.model.dto;

import lombok.Data;

@Data
public class EdgarSearchRequest {
    private String query;       // raw search phrase, or null if using preset
    private String preset;      // key from EdgarImportService.PRESET_QUERIES
    private int maxResults = 20;
}
