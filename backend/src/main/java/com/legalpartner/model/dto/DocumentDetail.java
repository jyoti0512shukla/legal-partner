package com.legalpartner.model.dto;

import com.legalpartner.model.entity.DocumentMetadata;
import java.util.Map;

public record DocumentDetail(
        DocumentMetadata metadata,
        Map<String, Integer> segmentsByClauseType
) {}
