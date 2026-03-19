package com.legalpartner.controller;

import com.legalpartner.model.dto.ClauseLibraryEntryDto;
import com.legalpartner.model.dto.CreateClauseLibraryRequest;
import com.legalpartner.service.ClauseLibraryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/clause-library")
@RequiredArgsConstructor
public class ClauseLibraryController {

    private final ClauseLibraryService service;

    @GetMapping
    public List<ClauseLibraryEntryDto> listAll() {
        return service.listAll();
    }

    @GetMapping("/{clauseType}")
    public List<ClauseLibraryEntryDto> listByType(@PathVariable String clauseType) {
        return service.listByType(clauseType);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ClauseLibraryEntryDto create(@RequestBody CreateClauseLibraryRequest req,
                                        Authentication auth) {
        return service.create(req, auth.getName());
    }

    @PatchMapping("/{id}/golden")
    public ClauseLibraryEntryDto toggleGolden(@PathVariable UUID id, Authentication auth) {
        return service.toggleGolden(id, auth.getName());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id, Authentication auth) {
        service.delete(id, auth.getName());
    }
}
