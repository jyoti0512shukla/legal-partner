package com.legalpartner.service;

import com.legalpartner.model.dto.matter.MatterRequest;
import com.legalpartner.model.dto.matter.MatterResponse;
import com.legalpartner.model.entity.Matter;
import com.legalpartner.model.enums.MatterStatus;
import com.legalpartner.model.enums.PracticeArea;
import com.legalpartner.repository.DocumentMetadataRepository;
import com.legalpartner.repository.MatterRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class MatterService {

    private final MatterRepository matterRepository;
    private final DocumentMetadataRepository documentRepository;

    public MatterResponse createMatter(MatterRequest request, String username) {
        if (matterRepository.findByMatterRef(request.matterRef()).isPresent()) {
            throw new IllegalArgumentException("Matter reference already exists: " + request.matterRef());
        }
        Matter matter = Matter.builder()
                .name(request.name())
                .matterRef(request.matterRef())
                .clientName(request.clientName())
                .practiceArea(parsePracticeArea(request.practiceArea()))
                .description(request.description())
                .createdBy(username)
                .build();
        matter = matterRepository.save(matter);
        log.info("Matter created: {} by {}", matter.getMatterRef(), username);
        return MatterResponse.from(matter, 0);
    }

    public List<MatterResponse> listMatters() {
        return matterRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(m -> MatterResponse.from(m, documentRepository.countByMatterUuid(m.getId())))
                .toList();
    }

    public List<MatterResponse> listActiveMatters() {
        return matterRepository.findByStatusOrderByCreatedAtDesc(MatterStatus.ACTIVE).stream()
                .map(m -> MatterResponse.from(m, documentRepository.countByMatterUuid(m.getId())))
                .toList();
    }

    public MatterResponse getMatter(UUID id) {
        Matter matter = matterRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Matter not found: " + id));
        return MatterResponse.from(matter, documentRepository.countByMatterUuid(id));
    }

    public MatterResponse updateStatus(UUID id, String status, String username) {
        Matter matter = matterRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Matter not found: " + id));
        matter.setStatus(MatterStatus.valueOf(status.toUpperCase()));
        matter = matterRepository.save(matter);
        log.info("Matter {} status updated to {} by {}", matter.getMatterRef(), status, username);
        return MatterResponse.from(matter, documentRepository.countByMatterUuid(id));
    }

    public Matter requireMatter(UUID matterId) {
        return matterRepository.findById(matterId)
                .orElseThrow(() -> new NoSuchElementException("Matter not found: " + matterId));
    }

    private PracticeArea parsePracticeArea(String s) {
        if (s == null || s.isBlank()) return null;
        try { return PracticeArea.valueOf(s.toUpperCase()); } catch (Exception e) { return null; }
    }
}
