package com.legalpartner.model.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RefineClauseRequest {

    @NotBlank(message = "Selected text is required")
    private String selectedText;

    private String documentContext;

    private String instruction;
}
