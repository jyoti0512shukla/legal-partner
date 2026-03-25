package com.legalpartner.service;

import com.legalpartner.model.entity.DocumentMetadata;
import com.legalpartner.model.entity.Matter;
import com.legalpartner.model.entity.MatterFinding;
import com.legalpartner.model.enums.FindingSeverity;
import com.legalpartner.model.enums.FindingType;
import com.legalpartner.repository.DocumentMetadataRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
@Slf4j
public class CrossDocConflictDetector {

    private final DocumentMetadataRepository docRepo;

    public List<MatterFinding> detectConflicts(Matter matter, DocumentMetadata newDoc) {
        List<DocumentMetadata> otherDocs = docRepo.findAllByMatterId(matter.getId()).stream()
                .filter(d -> !d.getId().equals(newDoc.getId()))
                .filter(d -> "INDEXED".equals(d.getProcessingStatus() != null ? d.getProcessingStatus().name() : ""))
                .toList();

        if (otherDocs.isEmpty()) return List.of();

        List<MatterFinding> findings = new ArrayList<>();
        for (DocumentMetadata other : otherDocs) {
            compareField("LIABILITY_CAP", newDoc.getLiabilityCap(), other.getLiabilityCap(),
                    newDoc, other, matter, findings);
            compareField("GOVERNING_LAW", newDoc.getGoverningLawJurisdiction(), other.getGoverningLawJurisdiction(),
                    newDoc, other, matter, findings);
            compareField("NOTICE_PERIOD", str(newDoc.getNoticePeriodDays()), str(other.getNoticePeriodDays()),
                    newDoc, other, matter, findings);
            compareField("ARBITRATION_VENUE", newDoc.getArbitrationVenue(), other.getArbitrationVenue(),
                    newDoc, other, matter, findings);
            compareField("CONTRACT_VALUE", newDoc.getContractValue(), other.getContractValue(),
                    newDoc, other, matter, findings);
        }
        return findings;
    }

    private void compareField(String fieldName, String newVal, String otherVal,
                              DocumentMetadata newDoc, DocumentMetadata otherDoc,
                              Matter matter, List<MatterFinding> findings) {
        if (newVal == null || otherVal == null) return;
        if (newVal.isBlank() || otherVal.isBlank()) return;
        if (newVal.equalsIgnoreCase(otherVal)) return;

        findings.add(MatterFinding.builder()
                .matter(matter)
                .document(newDoc)
                .relatedDocument(otherDoc)
                .findingType(FindingType.CROSS_DOC_CONFLICT)
                .severity(FindingSeverity.HIGH)
                .clauseType(fieldName)
                .title(fieldName + ": conflict between documents")
                .description(String.format("%s says \"%s\" but %s says \"%s\"",
                        newDoc.getFileName(), newVal, otherDoc.getFileName(), otherVal))
                .build());
    }

    private String str(Integer val) { return val != null ? val.toString() : null; }
}
