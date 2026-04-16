package com.legalpartner.service;

import com.legalpartner.model.entity.DocumentMetadata;
import com.legalpartner.model.enums.ProcessingStatus;
import com.legalpartner.repository.DocumentMetadataRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Keeps the async-draft table honest against two failure modes:
 *
 *   1. Server restart mid-generation — the background thread dies but the DB
 *      row stays at PROCESSING forever. {@link #sweepInterruptedOnStartup()}
 *      runs once at boot and fails any such zombie so the user can retry.
 *
 *   2. Hung LLM call — thread is alive, semaphore is held, but no clause has
 *      completed for 15+ min. {@link #sweepStuckJobs()} runs every minute and
 *      fails these too. (The thread may still finish later, but at that point
 *      its DB writes will be no-ops on a FAILED row.)
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DraftSweeperService {

    private static final String DRAFT_SOURCE = "DRAFT_ASYNC";
    private static final Duration STUCK_THRESHOLD = Duration.ofMinutes(15);

    private final DocumentMetadataRepository documentRepository;

    @EventListener(ApplicationReadyEvent.class)
    public void sweepInterruptedOnStartup() {
        List<DocumentMetadata> stuck = documentRepository
                .findBySourceAndProcessingStatus(DRAFT_SOURCE, ProcessingStatus.PROCESSING);
        if (stuck.isEmpty()) return;

        log.warn("Startup sweeper: {} async draft(s) left in PROCESSING at boot — marking FAILED", stuck.size());
        for (DocumentMetadata doc : stuck) {
            doc.setProcessingStatus(ProcessingStatus.FAILED);
            doc.setErrorMessage("Interrupted by server restart — please retry");
            doc.setLastProgressAt(Instant.now());
            documentRepository.save(doc);
        }
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void sweepStuckJobs() {
        Instant cutoff = Instant.now().minus(STUCK_THRESHOLD);
        List<DocumentMetadata> inFlight = documentRepository
                .findBySourceAndProcessingStatus(DRAFT_SOURCE, ProcessingStatus.PROCESSING);
        int killed = 0;
        for (DocumentMetadata doc : inFlight) {
            Instant lastProgress = doc.getLastProgressAt();
            if (lastProgress != null && lastProgress.isBefore(cutoff)) {
                doc.setProcessingStatus(ProcessingStatus.FAILED);
                doc.setErrorMessage("No progress for " + STUCK_THRESHOLD.toMinutes() + " min — marked failed");
                documentRepository.save(doc);
                killed++;
                log.warn("Stuck-job sweeper: failed async draft {} (last progress {})",
                        doc.getId(), lastProgress);
            }
        }
        if (killed > 0) log.warn("Stuck-job sweeper: failed {} stale async draft(s)", killed);
    }
}
