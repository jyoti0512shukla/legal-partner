package com.legalpartner.service;

import com.legalpartner.model.entity.EthicalWall;
import com.legalpartner.repository.DocumentMetadataRepository;
import com.legalpartner.repository.EthicalWallRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EthicalWallServiceTest {

    @Mock
    private EthicalWallRepository wallRepo;
    @Mock
    private DocumentMetadataRepository docRepo;

    @InjectMocks
    private EthicalWallService service;

    @Test
    void noWallsReturnsEmptySet() {
        UUID matterId = UUID.randomUUID();
        when(wallRepo.findBlockedMatterIds(matterId)).thenReturn(Set.of());
        assertThat(service.getBlockedDocumentIds(matterId)).isEmpty();
    }

    @Test
    void nullMatterIdReturnsEmpty() {
        assertThat(service.getBlockedDocumentIds(null)).isEmpty();
    }

    @Test
    void wallBlocksDocuments() {
        UUID matterA = UUID.randomUUID();
        UUID matterB = UUID.randomUUID();
        when(wallRepo.findBlockedMatterIds(matterA)).thenReturn(Set.of(matterB));
        when(docRepo.findIdStringsByMatterUuid(matterB)).thenReturn(List.of("doc-1", "doc-2"));

        Set<String> blocked = service.getBlockedDocumentIds(matterA);
        assertThat(blocked).containsExactlyInAnyOrder("doc-1", "doc-2");
    }

    @Test
    void wallAuditDetailsReturned() {
        UUID matterId = UUID.randomUUID();
        UUID otherMatter = UUID.randomUUID();
        EthicalWall wall = EthicalWall.builder()
                .matterAId(matterId).matterBId(otherMatter)
                .reason("Conflicting clients").active(true).build();
        when(wallRepo.findActiveWallsForMatter(matterId)).thenReturn(List.of(wall));

        List<String> details = service.getWallAuditDetails(matterId);
        assertThat(details).hasSize(1);
        assertThat(details.get(0)).contains("Conflicting clients");
    }

    @Test
    void createWallDuplicateThrows() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID first = a.compareTo(b) < 0 ? a : b;
        UUID second = a.compareTo(b) < 0 ? b : a;
        when(wallRepo.existsByMatterAIdAndMatterBIdAndActiveTrue(first, second)).thenReturn(true);

        assertThatThrownBy(() -> service.createWall(a, b, "test", UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already exists");
    }
}
