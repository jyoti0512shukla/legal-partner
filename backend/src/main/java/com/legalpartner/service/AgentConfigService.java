package com.legalpartner.service;

import com.legalpartner.model.dto.agent.AgentConfigDto;
import com.legalpartner.model.entity.AgentConfig;
import com.legalpartner.model.enums.NotifyChannel;
import com.legalpartner.repository.AgentConfigRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalTime;

@Service
@RequiredArgsConstructor
public class AgentConfigService {

    private final AgentConfigRepository repo;

    public AgentConfig getConfig() {
        return repo.findAll().stream().findFirst()
                .orElseGet(() -> repo.save(AgentConfig.builder().build()));
    }

    public AgentConfig updateConfig(AgentConfigDto dto) {
        AgentConfig config = getConfig();
        config.setAutoAnalyzeOnUpload(dto.autoAnalyzeOnUpload());
        config.setCrossReferenceDocs(dto.crossReferenceDocs());
        config.setCheckPlaybook(dto.checkPlaybook());
        config.setNotifyHigh(NotifyChannel.valueOf(dto.notifyHigh()));
        config.setNotifyMedium(NotifyChannel.valueOf(dto.notifyMedium()));
        config.setNotifyLow(NotifyChannel.valueOf(dto.notifyLow()));
        config.setQuietHoursStart(dto.quietHoursStart() != null ? LocalTime.parse(dto.quietHoursStart()) : null);
        config.setQuietHoursEnd(dto.quietHoursEnd() != null ? LocalTime.parse(dto.quietHoursEnd()) : null);
        return repo.save(config);
    }

    public AgentConfigDto toDto(AgentConfig c) {
        return new AgentConfigDto(c.isAutoAnalyzeOnUpload(), c.isCrossReferenceDocs(), c.isCheckPlaybook(),
                c.getNotifyHigh().name(), c.getNotifyMedium().name(), c.getNotifyLow().name(),
                c.getQuietHoursStart() != null ? c.getQuietHoursStart().toString() : null,
                c.getQuietHoursEnd() != null ? c.getQuietHoursEnd().toString() : null);
    }
}
