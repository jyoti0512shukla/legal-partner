package com.legalpartner.controller;

import com.legalpartner.model.dto.agent.AgentConfigDto;
import com.legalpartner.service.AgentConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/agent/config")
@RequiredArgsConstructor
public class AgentConfigController {

    private final AgentConfigService configService;

    @GetMapping
    public AgentConfigDto getConfig() {
        return configService.toDto(configService.getConfig());
    }

    @PutMapping
    @PreAuthorize("hasAnyRole('ADMIN','PARTNER')")
    public AgentConfigDto updateConfig(@RequestBody AgentConfigDto dto) {
        return configService.toDto(configService.updateConfig(dto));
    }
}
