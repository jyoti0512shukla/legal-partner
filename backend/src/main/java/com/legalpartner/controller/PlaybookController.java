package com.legalpartner.controller;

import com.legalpartner.model.dto.agent.PlaybookCreateRequest;
import com.legalpartner.model.dto.agent.PlaybookDto;
import com.legalpartner.model.dto.agent.PlaybookPositionDto;
import com.legalpartner.service.PlaybookService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/playbooks")
@RequiredArgsConstructor
public class PlaybookController {

    private final PlaybookService playbookService;

    @GetMapping
    public List<PlaybookDto> list(@RequestParam(required = false) String dealType) {
        if (dealType != null) return playbookService.listByDealType(dealType);
        return playbookService.listPlaybooks();
    }

    @GetMapping("/{id}")
    public PlaybookDto get(@PathVariable UUID id) {
        return playbookService.getPlaybook(id);
    }

    @GetMapping("/{id}/positions")
    public List<PlaybookPositionDto> getPositions(@PathVariable UUID id) {
        return playbookService.getPositions(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN','PARTNER')")
    public PlaybookDto create(@Valid @RequestBody PlaybookCreateRequest req, Authentication auth) {
        return playbookService.createPlaybook(req, auth.getName());
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','PARTNER')")
    public PlaybookDto update(@PathVariable UUID id, @Valid @RequestBody PlaybookCreateRequest req) {
        return playbookService.updatePlaybook(id, req);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMIN','PARTNER')")
    public void delete(@PathVariable UUID id) {
        playbookService.deletePlaybook(id);
    }
}
