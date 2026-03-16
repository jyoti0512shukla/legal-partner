package com.legalpartner.controller;

import com.legalpartner.model.dto.matter.MatterRequest;
import com.legalpartner.model.dto.matter.MatterResponse;
import com.legalpartner.service.MatterService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/matters")
@RequiredArgsConstructor
public class MatterController {

    private final MatterService matterService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MatterResponse create(@Valid @RequestBody MatterRequest request, Authentication auth) {
        return matterService.createMatter(request, auth.getName());
    }

    @GetMapping
    public List<MatterResponse> list() {
        return matterService.listMatters();
    }

    @GetMapping("/active")
    public List<MatterResponse> listActive() {
        return matterService.listActiveMatters();
    }

    @GetMapping("/{id}")
    public MatterResponse get(@PathVariable UUID id) {
        return matterService.getMatter(id);
    }

    @PatchMapping("/{id}/status")
    public MatterResponse updateStatus(
            @PathVariable UUID id,
            @RequestParam String status,
            Authentication auth) {
        return matterService.updateStatus(id, status, auth.getName());
    }
}
