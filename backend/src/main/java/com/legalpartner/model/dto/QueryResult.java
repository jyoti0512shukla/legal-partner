package com.legalpartner.model.dto;

import java.util.List;

public record QueryResult(
        String answer,
        String confidence,
        List<String> keyClauses,
        List<Citation> citations,
        List<String> warnings
) {}
