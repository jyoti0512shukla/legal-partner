package com.legalpartner.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.legalpartner.model.entity.DocumentMetadata;
import com.legalpartner.repository.DocumentMetadataRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Firm-wide entity denylist derived from the anonymization maps of every
 * ingested precedent.
 *
 * When a document is ingested, {@link AnonymizationService} identifies real
 * entities (party names, amounts, dates, jurisdictions, addresses) and stores
 * an encrypted raw→synthetic map on the document row. The KEYS of that map
 * are the real things from that client's deal — exactly the things that
 * must NOT appear in drafts for OTHER matters/clients.
 *
 * This service walks all those maps at startup and periodically, builds a
 * flat Set of originals, and hands it to the draft QA layer alongside the
 * static seed list (v3 training-known leaks).
 *
 * Architecture note: current deployment is single-tenant-per-VM, so "firm-wide"
 * = "the whole VM". When we move to multi-tenant, this becomes per-firm_id.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DynamicEntityDenylistService {

    private final DocumentMetadataRepository documentRepository;
    private final EncryptionService encryptionService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Minimum length for an entity to be denylisted. Filters "USA" -> "Synthland" from breaking "USAGE". */
    @Value("${legalpartner.denylist.min-entity-length:4}")
    private int minEntityLength;

    /** Rebuild cadence in minutes. */
    @Value("${legalpartner.denylist.refresh-minutes:10}")
    private int refreshMinutes;

    private final AtomicReference<Set<String>> cached = new AtomicReference<>(Collections.emptySet());

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        refresh();
    }

    /** Run periodically — rebuilds from all current anonymization maps. */
    @Scheduled(fixedDelayString = "#{${legalpartner.denylist.refresh-minutes:10} * 60 * 1000}",
               initialDelay = 600_000)
    public void refresh() {
        try {
            Set<String> next = buildFromAll();
            cached.set(Collections.unmodifiableSet(next));
            log.info("Dynamic entity denylist refreshed: {} entities from firm corpus", next.size());
        } catch (Exception e) {
            log.warn("Failed to refresh dynamic entity denylist: {}", e.getMessage());
        }
    }

    /** Force a rebuild — call this after a new document finishes ingest. */
    public void refreshNow() { refresh(); }

    /** Current firm-wide denylist. Empty if never built or rebuild failed. */
    public Set<String> all() { return cached.get(); }

    private Set<String> buildFromAll() {
        Set<String> out = new HashSet<>();
        List<DocumentMetadata> docs = documentRepository.findAll();
        for (DocumentMetadata doc : docs) {
            if (doc.getAnonymizationMapJson() == null) continue;
            try {
                String plainJson = encryptionService.decrypt(doc.getAnonymizationMapJson());
                Map<String, String> map = objectMapper.readValue(plainJson,
                        new TypeReference<Map<String, String>>() {});
                for (String raw : map.keySet()) {
                    if (raw == null || raw.length() < minEntityLength) continue;
                    out.add(raw);
                }
            } catch (Exception e) {
                log.debug("Skip doc {}: can't decrypt/parse anon map ({})",
                        doc.getId(), e.getMessage());
            }
        }
        return out;
    }
}
