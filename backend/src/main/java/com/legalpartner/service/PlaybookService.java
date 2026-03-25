package com.legalpartner.service;

import com.legalpartner.model.dto.agent.PlaybookCreateRequest;
import com.legalpartner.model.dto.agent.PlaybookDto;
import com.legalpartner.model.dto.agent.PlaybookPositionDto;
import com.legalpartner.model.entity.Playbook;
import com.legalpartner.model.entity.PlaybookPosition;
import com.legalpartner.repository.PlaybookPositionRepository;
import com.legalpartner.repository.PlaybookRepository;
import com.legalpartner.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PlaybookService {

    private final PlaybookRepository playbookRepo;
    private final PlaybookPositionRepository positionRepo;
    private final UserRepository userRepo;

    public List<PlaybookDto> listPlaybooks() {
        return playbookRepo.findAllByOrderByCreatedAtDesc().stream().map(this::toDto).toList();
    }

    public List<PlaybookDto> listByDealType(String dealType) {
        return playbookRepo.findByDealType(dealType).stream().map(this::toDto).toList();
    }

    public PlaybookDto getPlaybook(UUID id) {
        return toDto(playbookRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Playbook not found")));
    }

    public List<PlaybookPositionDto> getPositions(UUID playbookId) {
        return positionRepo.findByPlaybookId(playbookId).stream()
                .map(p -> new PlaybookPositionDto(p.getId(), p.getClauseType(), p.getStandardPosition(),
                        p.getMinimumAcceptable(), p.isNonNegotiable(), p.getNotes()))
                .toList();
    }

    @Transactional
    public PlaybookDto createPlaybook(PlaybookCreateRequest req, String username) {
        UUID userId = userRepo.findByEmail(username)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED)).getId();

        if (req.isDefault()) {
            playbookRepo.findByDealTypeAndIsDefaultTrue(req.dealType())
                    .ifPresent(p -> { p.setDefault(false); playbookRepo.save(p); });
        }

        Playbook playbook = Playbook.builder()
                .name(req.name()).dealType(req.dealType()).description(req.description())
                .isDefault(req.isDefault()).createdBy(userId).build();
        playbook = playbookRepo.save(playbook);

        if (req.positions() != null) {
            for (PlaybookPositionDto pd : req.positions()) {
                PlaybookPosition pos = PlaybookPosition.builder()
                        .playbook(playbook).clauseType(pd.clauseType())
                        .standardPosition(pd.standardPosition())
                        .minimumAcceptable(pd.minimumAcceptable())
                        .nonNegotiable(pd.nonNegotiable()).notes(pd.notes()).build();
                positionRepo.save(pos);
            }
        }
        return toDto(playbook);
    }

    @Transactional
    public PlaybookDto updatePlaybook(UUID id, PlaybookCreateRequest req) {
        Playbook playbook = playbookRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        playbook.setName(req.name());
        playbook.setDealType(req.dealType());
        playbook.setDescription(req.description());
        playbook.setDefault(req.isDefault());

        playbook.getPositions().clear();
        if (req.positions() != null) {
            for (PlaybookPositionDto pd : req.positions()) {
                PlaybookPosition pos = PlaybookPosition.builder()
                        .playbook(playbook).clauseType(pd.clauseType())
                        .standardPosition(pd.standardPosition())
                        .minimumAcceptable(pd.minimumAcceptable())
                        .nonNegotiable(pd.nonNegotiable()).notes(pd.notes()).build();
                playbook.getPositions().add(pos);
            }
        }
        return toDto(playbookRepo.save(playbook));
    }

    public void deletePlaybook(UUID id) {
        playbookRepo.deleteById(id);
    }

    private PlaybookDto toDto(Playbook p) {
        int count = positionRepo.findByPlaybookId(p.getId()).size();
        return new PlaybookDto(p.getId(), p.getName(), p.getDealType(), p.getDescription(),
                p.isDefault(), count, p.getCreatedAt());
    }
}
