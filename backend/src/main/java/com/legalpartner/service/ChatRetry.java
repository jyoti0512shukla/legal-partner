package com.legalpartner.service;

import dev.langchain4j.data.message.AiMessage;
import lombok.extern.slf4j.Slf4j;

import java.util.function.Supplier;

/**
 * Retry wrapper for chatModel.generate() calls. Most user-visible failures on
 * this stack are ngrok or vLLM hiccups — the tunnel dropped mid-response, the
 * backend saw a connection reset, the user saw a 500. Retrying the same prompt
 * succeeds the vast majority of the time.
 *
 * Usage:
 *   AiMessage response = ChatRetry.generate(() -> chatModel.generate(message).content());
 *
 * Policy:
 *   - 3 attempts total (initial + 2 retries)
 *   - backoff: 2s, 4s
 *   - retries on any exception (safer than trying to enumerate transient ones
 *     from a heterogeneous LangChain4j + OkHttp + vLLM stack)
 *   - last attempt's exception is rethrown if all fail
 */
@Slf4j
public final class ChatRetry {

    private ChatRetry() {}

    private static final int MAX_ATTEMPTS = 3;
    private static final long[] BACKOFF_MS = { 2_000L, 4_000L };

    public static AiMessage generate(Supplier<AiMessage> call) {
        return generate(call, "chat");
    }

    public static AiMessage generate(Supplier<AiMessage> call, String label) {
        Exception last = null;
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            try {
                if (attempt > 0) {
                    long wait = BACKOFF_MS[attempt - 1];
                    log.warn("Retrying LLM call [{}] attempt {} after {}ms — last error: {}",
                            label, attempt + 1, wait, last != null ? last.getMessage() : "-");
                    Thread.sleep(wait);
                }
                return call.get();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted during LLM retry backoff", ie);
            } catch (Exception e) {
                last = e;
                if (attempt == MAX_ATTEMPTS - 1) {
                    log.error("LLM call [{}] failed after {} attempts", label, MAX_ATTEMPTS, e);
                    if (e instanceof RuntimeException re) throw re;
                    throw new RuntimeException(e);
                }
            }
        }
        // unreachable
        throw new IllegalStateException("retry loop fell through");
    }
}
