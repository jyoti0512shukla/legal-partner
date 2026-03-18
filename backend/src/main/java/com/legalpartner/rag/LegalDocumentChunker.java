package com.legalpartner.rag;

import com.legalpartner.model.enums.ClauseType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@Slf4j
public class LegalDocumentChunker {

    private static final List<Pattern> LEGAL_BOUNDARIES = List.of(
            Pattern.compile("(?m)^\\s*(ARTICLE|Article|SECTION|Section|CLAUSE|Clause)\\s+\\d+"),
            Pattern.compile("(?m)^\\s*\\d+\\.\\d*\\.?\\s+[A-Z]"),
            Pattern.compile("(?m)^\\s*(SCHEDULE|ANNEXURE|EXHIBIT|APPENDIX|DEFINITIONS|RECITALS)\\b"),
            Pattern.compile("(?m)^\\s*(WHEREAS|NOW,?\\s+THEREFORE|IN WITNESS WHEREOF)")
    );

    private static final Map<ClauseType, List<String>> CLAUSE_KEYWORDS = Map.ofEntries(
            Map.entry(ClauseType.TERMINATION, List.of("termination", "terminate", "notice period", "expiry", "exit clause")),
            Map.entry(ClauseType.LIABILITY, List.of("liability", "liable", "limitation of liability", "cap on", "damages")),
            Map.entry(ClauseType.INDEMNITY, List.of("indemnify", "indemnification", "hold harmless", "indemnity")),
            Map.entry(ClauseType.WARRANTY, List.of("warranty", "warranties", "represents and warrants", "representation")),
            Map.entry(ClauseType.CONFIDENTIALITY, List.of("confidential", "non-disclosure", "proprietary", "nda")),
            Map.entry(ClauseType.GOVERNING_LAW, List.of("governing law", "jurisdiction", "arbitration", "dispute resolution", "applicable law")),
            Map.entry(ClauseType.FORCE_MAJEURE, List.of("force majeure", "act of god", "unforeseen", "beyond control")),
            Map.entry(ClauseType.IP_RIGHTS, List.of("intellectual property", "ip rights", "patent", "copyright", "trademark", "work product")),
            Map.entry(ClauseType.PAYMENT, List.of("payment", "invoice", "compensation", "fee", "charges", "remuneration"))
    );

    @Value("${legalpartner.chunking.target-size:600}")
    private int targetSize;

    // Raised from 500 → 1500 words so a full clause (liability, termination etc.)
    // stays in one chunk rather than being split across two. Embedding is still
    // truncated to 400 chars (all-MiniLM limit) but the stored text is the full clause.
    @Value("${legalpartner.chunking.max-size:1500}")
    private int maxSize;

    @Value("${legalpartner.chunking.min-size:50}")
    private int minSize;

    @Value("${legalpartner.chunking.overlap:50}")
    private int overlap;

    public List<LegalChunk> chunk(String text, Map<String, String> documentMeta) {
        List<SectionWithOffset> sections = splitAtLegalBoundariesWithOffsets(text);
        List<LegalChunk> chunks = new ArrayList<>();
        int chunkIndex = 0;
        String currentSectionPath = "";

        for (int i = 0; i < sections.size(); i++) {
            SectionWithOffset current = sections.get(i);
            String sectionText = current.text();
            int charOffset = current.charOffset();

            // Prepend overlap from previous section across boundaries
            if (i > 0) {
                String prevText = sections.get(i - 1).text();
                String overlapPrefix = extractOverlap(prevText);
                if (!overlapPrefix.isBlank()) {
                    sectionText = overlapPrefix + " " + sectionText;
                }
            }

            String sectionTitle = extractSectionTitle(sectionText);
            if (!sectionTitle.isEmpty()) {
                currentSectionPath = sectionTitle;
            }

            int estimatedPage = (charOffset / 3000) + 1;
            int wordCount = countWords(sectionText);
            if (wordCount <= maxSize) {
                if (wordCount >= minSize) {
                    chunks.add(buildChunk(sectionText, chunkIndex++, currentSectionPath, estimatedPage, documentMeta));
                } else if (!chunks.isEmpty()) {
                    LegalChunk last = chunks.get(chunks.size() - 1);
                    chunks.set(chunks.size() - 1, last.withAppendedText("\n\n" + sectionText));
                }
            } else {
                List<String> subChunks = splitLargeSection(sectionText);
                for (String sub : subChunks) {
                    if (countWords(sub) >= minSize) {
                        chunks.add(buildChunk(sub, chunkIndex++, currentSectionPath, estimatedPage, documentMeta));
                    }
                }
            }
        }

        log.info("Chunked document into {} segments", chunks.size());
        return chunks;
    }

    private record SectionWithOffset(String text, int charOffset) {}

    private List<SectionWithOffset> splitAtLegalBoundariesWithOffsets(String text) {
        TreeSet<Integer> splitPoints = new TreeSet<>();
        splitPoints.add(0);

        for (Pattern pattern : LEGAL_BOUNDARIES) {
            Matcher matcher = pattern.matcher(text);
            while (matcher.find()) {
                splitPoints.add(matcher.start());
            }
        }

        List<SectionWithOffset> sections = new ArrayList<>();
        Integer[] points = splitPoints.toArray(new Integer[0]);
        for (int i = 0; i < points.length; i++) {
            int start = points[i];
            int end = (i + 1 < points.length) ? points[i + 1] : text.length();
            String section = text.substring(start, end).trim();
            if (!section.isEmpty()) {
                sections.add(new SectionWithOffset(section, start));
            }
        }
        return sections;
    }

    // Keep old splitAtLegalBoundaries for compatibility (delegates to new method)
    private List<String> splitAtLegalBoundaries(String text) {
        return splitAtLegalBoundariesWithOffsets(text).stream()
                .map(SectionWithOffset::text)
                .toList();
    }

    private List<String> splitLargeSection(String section) {
        List<String> result = new ArrayList<>();
        String[] paragraphs = section.split("\\n\\s*\\n");
        StringBuilder current = new StringBuilder();

        for (String para : paragraphs) {
            if (countWords(current.toString()) + countWords(para) > targetSize && countWords(current.toString()) > 0) {
                result.add(current.toString().trim());
                String overlapText = extractOverlap(current.toString());
                current = new StringBuilder(overlapText);
            }
            current.append("\n\n").append(para);
        }
        if (countWords(current.toString()) > 0) {
            result.add(current.toString().trim());
        }
        return result;
    }

    private String extractOverlap(String text) {
        String[] words = text.split("\\s+");
        if (words.length <= overlap) return text;
        StringBuilder sb = new StringBuilder();
        for (int i = words.length - overlap; i < words.length; i++) {
            sb.append(words[i]).append(" ");
        }
        return sb.toString().trim();
    }

    private LegalChunk buildChunk(String text, int index, String sectionPath, int estimatedPage, Map<String, String> docMeta) {
        ClauseType clauseType = classifyClauseType(text);
        Map<String, String> metadata = new HashMap<>(docMeta);
        metadata.put("section_path", sectionPath);
        metadata.put("clause_type", clauseType.name());
        metadata.put("chunk_index", String.valueOf(index));
        metadata.put("page_number", String.valueOf(estimatedPage));
        return new LegalChunk(text, clauseType, sectionPath, index, metadata);
    }

    public ClauseType classifyClauseType(String text) {
        String lower = text.toLowerCase();
        int maxScore = 0;
        ClauseType best = ClauseType.GENERAL;

        for (var entry : CLAUSE_KEYWORDS.entrySet()) {
            int score = 0;
            for (String keyword : entry.getValue()) {
                if (lower.contains(keyword)) score++;
            }
            if (score > maxScore) {
                maxScore = score;
                best = entry.getKey();
            }
        }
        return best;
    }

    private String extractSectionTitle(String section) {
        String firstLine = section.split("\\n")[0].trim();
        for (Pattern p : LEGAL_BOUNDARIES) {
            if (p.matcher(firstLine).find()) {
                return firstLine.length() > 100 ? firstLine.substring(0, 100) : firstLine;
            }
        }
        return "";
    }

    private int countWords(String text) {
        if (text == null || text.isBlank()) return 0;
        return text.trim().split("\\s+").length;
    }

    public record LegalChunk(
            String text,
            ClauseType clauseType,
            String sectionPath,
            int chunkIndex,
            Map<String, String> metadata
    ) {
        public LegalChunk withAppendedText(String extra) {
            return new LegalChunk(text + extra, clauseType, sectionPath, chunkIndex, metadata);
        }
    }
}
