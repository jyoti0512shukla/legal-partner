package com.legalpartner.model.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Request to save a generated draft as a Document.
 * If matterId is provided, the document is attached to that matter.
 * The user must be a member of the matter (or ADMIN).
 */
@Data
public class SaveDraftRequest {

    @NotBlank
    private String draftHtml;

    /** Optional — when present, document is attached to this matter. */
    private String matterId;

    /** Optional — used to derive the file name. */
    private String contractTypeName;

    private String partyA;
    private String partyB;
    private String jurisdiction;
}
