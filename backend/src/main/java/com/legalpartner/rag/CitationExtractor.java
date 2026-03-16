package com.legalpartner.rag;

import com.legalpartner.model.dto.Citation;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class CitationExtractor {

    private static final Pattern SECTION_REF = Pattern.compile(
            "(?i)(?:Section|Clause|Article|Sec\\.)\\s+\\d+(?:\\.\\d+)*"
    );

    public List<Citation> extract(String llmAnswer, List<EmbeddingMatch<TextSegment>> usedChunks) {
        List<Citation> citations = new ArrayList<>();
        List<String> referencedSections = extractSectionReferences(llmAnswer);

        for (EmbeddingMatch<TextSegment> match : usedChunks) {
            TextSegment segment = match.embedded();
            String fileName = segment.metadata().getString("file_name");
            String sectionPath = segment.metadata().getString("section_path");
            String pageStr = segment.metadata().getString("page_number");
            Integer page = pageStr != null ? safeParseInt(pageStr) : null;
            String snippet = segment.text().length() > 120
                    ? segment.text().substring(0, 120) + "..."
                    : segment.text();

            boolean verified = !referencedSections.isEmpty()
                    && sectionPath != null
                    && referencedSections.stream().anyMatch(ref ->
                    sectionPath.toLowerCase().contains(ref.toLowerCase()));

            citations.add(new Citation(
                    fileName != null ? fileName : "Unknown",
                    sectionPath != null ? sectionPath : "",
                    page,
                    snippet,
                    verified
            ));
        }
        return citations;
    }

    private List<String> extractSectionReferences(String text) {
        List<String> refs = new ArrayList<>();
        Matcher matcher = SECTION_REF.matcher(text);
        while (matcher.find()) {
            refs.add(matcher.group());
        }
        return refs;
    }

    private Integer safeParseInt(String s) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return null; }
    }
}
