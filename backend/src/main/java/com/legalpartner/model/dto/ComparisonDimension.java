package com.legalpartner.model.dto;

public record ComparisonDimension(
        String name,
        String doc1Summary,
        String doc2Summary,
        String favorableTo,
        String reasoning
) {}
