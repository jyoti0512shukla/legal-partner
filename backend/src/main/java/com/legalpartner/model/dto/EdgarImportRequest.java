package com.legalpartner.model.dto;

import lombok.Data;
import java.util.List;
import java.util.Map;

@Data
public class EdgarImportRequest {
    /** List of raw _id values to import (e.g. "0001234567-23-000001:exhibit10-1.htm") */
    private List<String> docIds;
    /** docId → documentUrl map (sent back from search results) */
    private Map<String, String> docIdToUrl;
    /** docId → entity name map */
    private Map<String, String> docIdToEntity;
    private String contractType;    // NDA, MSA, etc.
    private String industry;        // FINTECH, IT_SERVICES, etc.
    private String practiceArea;    // CORPORATE, IP, etc.
}
