package com.legalpartner.service;

import java.util.List;

/** Exposes package-private ContractReviewService helpers to tests in other packages. */
public final class ContractReviewServiceTestAccess {
    private ContractReviewServiceTestAccess() {}

    public static String fill(String template, List<String> ids) {
        return ContractReviewService.fillChecklistVars(template, ids);
    }
}
