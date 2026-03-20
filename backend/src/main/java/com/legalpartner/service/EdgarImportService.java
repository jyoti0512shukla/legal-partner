package com.legalpartner.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.legalpartner.model.entity.DocumentMetadata;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Year;
import java.util.*;

/**
 * Searches SEC EDGAR for real commercial agreements and ingests selected documents
 * directly into the RAG corpus via the existing document ingestion pipeline.
 *
 * EDGAR EFTS API: https://efts.sec.gov/LATEST/search-index
 * - forms=EX-10 matches all EX-10.x exhibit types (real contracts filed with SEC)
 * - _id format: "{accession_number}:{filename}"
 * - accession number first 10 digits = CIK (zero-padded)
 * - Document URL: https://www.sec.gov/Archives/edgar/data/{cik}/{accession_no_dashes}/{filename}
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EdgarImportService {

    private final DocumentService documentService;
    private final ObjectMapper objectMapper;

    private static final String EFTS_URL = "https://efts.sec.gov/LATEST/search-index";
    private static final String EDGAR_BASE = "https://www.sec.gov";
    // EDGAR requires a descriptive User-Agent with contact info
    // SEC requires email in User-Agent for all EDGAR document requests
    private static final String USER_AGENT = "LegalPartner Research Tool legal-partner-app/1.0 (contact@legalpartner.app)";
    private static final int MAX_DOWNLOAD_BYTES = 500_000; // 500 KB per doc

    /** Predefined queries mapping a preset name to an EDGAR search phrase.
     *  Single-phrase queries work best — EDGAR EFTS phrase AND search is very strict. */
    public static final Map<String, String> PRESET_QUERIES = new LinkedHashMap<>();
    static {
        PRESET_QUERIES.put("IT_SERVICES_MSA",  "\"master services agreement\"");
        PRESET_QUERIES.put("SAAS_AGREEMENT",   "\"master subscription agreement\"");
        PRESET_QUERIES.put("NDA",              "\"non-disclosure agreement\"");
        PRESET_QUERIES.put("SOFTWARE_LICENSE", "\"software license agreement\"");
        PRESET_QUERIES.put("VENDOR_AGREEMENT", "\"vendor agreement\"");
        PRESET_QUERIES.put("FINTECH_MSA",      "\"master services agreement\" \"financial services\"");
        PRESET_QUERIES.put("PHARMA_SERVICES",  "\"clinical services agreement\"");
        PRESET_QUERIES.put("MANUFACTURING",    "\"supply agreement\"");
        PRESET_QUERIES.put("EMPLOYMENT",       "\"employment agreement\"");
        PRESET_QUERIES.put("IP_LICENSE",       "\"license agreement\" \"royalty\"");
    }

    public record EdgarSearchResult(
            String docId,         // raw _id: "accession:filename"
            String entityName,
            String fileDate,
            String formType,
            String documentUrl,   // direct download URL
            String fileName
    ) {}

    public record ImportResult(
            String docId,
            String entityName,
            boolean success,
            String error,
            UUID documentId
    ) {}

    // ── Search ────────────────────────────────────────────────────────────────

    public List<EdgarSearchResult> search(String query, int maxResults) throws Exception {
        // EDGAR EFTS: the "forms" parameter is broken for exhibit types (always returns 0).
        // Search without forms filter; filter by file_type starting with "EX-10" in code.
        // _source fields: display_names[], file_type, file_date, adsh
        String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
        String url = EFTS_URL + "?q=" + encodedQuery
                + "&dateRange=custom&startdt=2019-01-01&enddt=2024-12-31";

        log.info("EDGAR search: {}", url);

        RestTemplate rt = new RestTemplate();
        HttpHeaders headers = new HttpHeaders();
        headers.set("User-Agent", USER_AGENT);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        ResponseEntity<String> response = rt.exchange(
                URI.create(url), HttpMethod.GET, new HttpEntity<>(headers), String.class);

        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new RuntimeException("EDGAR search failed: " + response.getStatusCode());
        }

        JsonNode root = objectMapper.readTree(response.getBody());
        JsonNode hits = root.path("hits").path("hits");
        log.info("EDGAR total: {}, page size: {}",
                root.path("hits").path("total").path("value"), hits.size());

        List<EdgarSearchResult> results = new ArrayList<>();
        for (JsonNode hit : hits) {
            if (results.size() >= maxResults) break;

            String id = hit.path("_id").asText();
            JsonNode src = hit.path("_source");

            // Filter: only EX-10.x exhibit documents
            String fileType = src.path("file_type").asText("");
            if (!fileType.startsWith("EX-10")) continue;

            // display_names[0] = "Acme Corp  (ACME)  (CIK 0001234567)" — strip ticker/CIK suffix
            String rawName = src.path("display_names").path(0).asText("Unknown");
            String entityName = rawName.contains("  (") ? rawName.substring(0, rawName.indexOf("  (")).trim() : rawName;

            String fileDate = src.path("file_date").asText("");

            // _id format: "0001234567-23-000001:exhibit10-1.htm"
            int colon = id.lastIndexOf(':');
            if (colon < 0) continue; // skip hits without a specific filename

            String accession = id.substring(0, colon);
            String fileName  = id.substring(colon + 1);

            // ciks[0] is the filer's CIK — files live under this CIK in EDGAR's S3.
            // The accession number prefix is the SUBMITTER's CIK (filing agent), which is different.
            String cikStr = src.path("ciks").path(0).asText("");
            if (cikStr.isBlank()) continue;
            long filerCik = Long.parseLong(cikStr.replaceAll("^0+", "").isEmpty() ? "0" : cikStr.replaceAll("^0+", ""));
            if (filerCik == 0) continue;

            String docUrl = buildDocumentUrl(filerCik, accession, fileName);
            if (docUrl == null) continue;

            results.add(new EdgarSearchResult(id, entityName, fileDate, fileType, docUrl, fileName));
        }
        return results;
    }

    // ── Import ────────────────────────────────────────────────────────────────

    public List<ImportResult> batchImport(
            List<String> docIds,
            Map<String, String> docIdToUrl,
            Map<String, String> docIdToEntity,
            String contractType,
            String industry,
            String practiceArea,
            String username
    ) {
        List<ImportResult> results = new ArrayList<>();
        for (String docId : docIds) {
            try {
                Thread.sleep(200); // respect EDGAR rate limit (max 10 req/s)
                String url = docIdToUrl.get(docId);
                String entity = docIdToEntity.getOrDefault(docId, "EDGAR Filing");
                if (url == null) {
                    results.add(new ImportResult(docId, entity, false, "URL not found", null));
                    continue;
                }
                DocumentMetadata doc = importSingle(
                        url, entity, contractType, industry, practiceArea, username);
                results.add(new ImportResult(docId, entity, true, null, doc.getId()));
                log.info("Imported EDGAR doc: {} → {}", entity, doc.getId());
            } catch (Exception e) {
                log.warn("Failed to import EDGAR doc {}: {}", docId, e.getMessage());
                results.add(new ImportResult(docId, docIdToUrl.getOrDefault(docId, docId),
                        false, e.getMessage(), null));
            }
        }
        return results;
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private DocumentMetadata importSingle(
            String documentUrl, String entityName,
            String contractType, String industry, String practiceArea,
            String username
    ) throws Exception {
        byte[] bytes = downloadDocument(documentUrl);
        String fileName = documentUrl.substring(documentUrl.lastIndexOf('/') + 1);
        // Infer content type from extension
        String contentType = fileName.endsWith(".htm") || fileName.endsWith(".html")
                ? "text/html" : "application/pdf";

        String sanitizedName = "EDGAR_" + entityName.replaceAll("[^a-zA-Z0-9_\\- ]", "")
                .trim().replace(' ', '_') + "_" + fileName;

        return documentService.ingestFromBytes(
                bytes, sanitizedName, contentType,
                "USA",                     // EDGAR filings are US jurisdiction
                Year.now().getValue(),
                false,
                contractType != null ? contractType : "MSA",
                practiceArea != null ? practiceArea : "CORPORATE",
                entityName,
                null,
                industry,
                username,
                "EDGAR"
        );
    }

    private byte[] downloadDocument(String url) throws Exception {
        RestTemplate rt = new RestTemplate();
        HttpHeaders headers = new HttpHeaders();
        headers.set("User-Agent", USER_AGENT);
        ResponseEntity<byte[]> response = rt.exchange(
                URI.create(url), HttpMethod.GET, new HttpEntity<>(headers), byte[].class);

        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new RuntimeException("Download failed: " + response.getStatusCode() + " from " + url);
        }
        byte[] body = response.getBody();
        if (body.length > MAX_DOWNLOAD_BYTES) {
            // Truncate to limit — Tika will still extract meaningful text
            log.info("Truncating large EDGAR doc {} bytes → {} bytes", body.length, MAX_DOWNLOAD_BYTES);
            return Arrays.copyOf(body, MAX_DOWNLOAD_BYTES);
        }
        return body;
    }

    /**
     * Constructs the direct EDGAR document URL using the filer's CIK.
     * EDGAR stores files under the filer's CIK, NOT the accession number prefix
     * (which is the filing agent/submitter CIK and differs for agent-filed docs).
     * URL: https://www.sec.gov/Archives/edgar/data/{filerCik}/{accessionNoDashes}/{fileName}
     */
    private static String buildDocumentUrl(long filerCik, String accession, String fileName) {
        String accessionNoDashes = accession.replace("-", "");
        return EDGAR_BASE + "/Archives/edgar/data/" + filerCik + "/" + accessionNoDashes + "/" + fileName;
    }
}
