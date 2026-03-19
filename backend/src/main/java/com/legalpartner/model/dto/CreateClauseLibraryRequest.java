package com.legalpartner.model.dto;

import lombok.Data;

@Data
public class CreateClauseLibraryRequest {
    private String clauseType;
    private String title;
    private String content;
    private String contractType;
    private String practiceArea;
    private String industry;
    private String counterpartyType;
    private String jurisdiction;
    private boolean golden;
}
