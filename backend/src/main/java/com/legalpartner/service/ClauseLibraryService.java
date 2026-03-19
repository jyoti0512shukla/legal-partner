package com.legalpartner.service;

import com.legalpartner.model.dto.ClauseLibraryEntryDto;
import com.legalpartner.model.dto.CreateClauseLibraryRequest;
import com.legalpartner.model.entity.ClauseLibraryEntry;
import com.legalpartner.repository.ClauseLibraryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class ClauseLibraryService {

    private final ClauseLibraryRepository repo;

    public List<ClauseLibraryEntryDto> listAll() {
        return repo.findAllByOrderByClauseTypeAscGoldenDescCreatedAtDesc()
                .stream().map(this::toDto).toList();
    }

    public List<ClauseLibraryEntryDto> listByType(String clauseType) {
        return repo.findByClauseTypeOrderByGoldenDescUsageCountDesc(clauseType.toUpperCase())
                .stream().map(this::toDto).toList();
    }

    /**
     * Returns clauses for use in draft generation — matched by type, contractType, industry, practiceArea.
     * Null parameters are treated as wildcards. Golden entries come first.
     */
    public List<ClauseLibraryEntry> findForDraft(String clauseType, String contractType,
                                                  String industry, String practiceArea) {
        return repo.findForDraft(
                clauseType.toUpperCase(),
                contractType != null ? contractType.toUpperCase() : null,
                industry != null && !industry.isBlank() ? industry.toUpperCase() : null,
                practiceArea != null && !practiceArea.isBlank() ? practiceArea.toUpperCase() : null
        );
    }

    public ClauseLibraryEntryDto create(CreateClauseLibraryRequest req, String username) {
        ClauseLibraryEntry entry = ClauseLibraryEntry.builder()
                .clauseType(req.getClauseType().toUpperCase())
                .title(req.getTitle().trim())
                .content(req.getContent().trim())
                .contractType(blankToNull(req.getContractType()))
                .practiceArea(blankToNull(req.getPracticeArea()))
                .industry(blankToNull(req.getIndustry()))
                .counterpartyType(blankToNull(req.getCounterpartyType()))
                .jurisdiction(blankToNull(req.getJurisdiction()))
                .golden(req.isGolden())
                .createdBy(username)
                .build();
        return toDto(repo.save(entry));
    }

    public ClauseLibraryEntryDto toggleGolden(UUID id, String username) {
        ClauseLibraryEntry entry = findOrThrow(id);
        entry.setGolden(!entry.isGolden());
        return toDto(repo.save(entry));
    }

    public void delete(UUID id, String username) {
        ClauseLibraryEntry entry = findOrThrow(id);
        repo.delete(entry);
    }

    public void incrementUsage(UUID id) {
        repo.findById(id).ifPresent(e -> {
            e.setUsageCount(e.getUsageCount() + 1);
            repo.save(e);
        });
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private ClauseLibraryEntry findOrThrow(UUID id) {
        return repo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Clause not found"));
    }

    private ClauseLibraryEntryDto toDto(ClauseLibraryEntry e) {
        return ClauseLibraryEntryDto.builder()
                .id(e.getId())
                .clauseType(e.getClauseType())
                .title(e.getTitle())
                .content(e.getContent())
                .contractType(e.getContractType())
                .practiceArea(e.getPracticeArea())
                .industry(e.getIndustry())
                .counterpartyType(e.getCounterpartyType())
                .jurisdiction(e.getJurisdiction())
                .golden(e.isGolden())
                .usageCount(e.getUsageCount())
                .createdBy(e.getCreatedBy())
                .createdAt(e.getCreatedAt())
                .build();
    }

    private static String blankToNull(String v) {
        return (v == null || v.isBlank()) ? null : v.trim();
    }
}
