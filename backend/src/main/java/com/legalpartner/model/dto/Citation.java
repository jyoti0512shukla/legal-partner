package com.legalpartner.model.dto;

public record Citation(
        String documentName,
        String sectionPath,
        Integer pageNumber,
        String snippet,
        boolean verified
) {}
