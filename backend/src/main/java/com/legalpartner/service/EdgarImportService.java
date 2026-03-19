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
    private static final String USER_AGENT = "LegalPartner Research Tool legal-partner-app/1.0";
    private static final int MAX_DOWNLOAD_BYTES = 500_000; // 500 KB per doc

    /** Predefined queries mapping a preset name to an EDGAR search phrase. */
    public static final Map<String, String> PRESET_QUERIES = new LinkedHashMap<>();
    static {
        PRESET_QUERIES.put("IT_SERVICES_MSA",  "\"master services agreement\" \"information technology\"");
        PRESET_QUERIES.put("SAAS_AGREEMENT",   "\"master subscription agreement\" \"software as a service\"");
        PRESET_QUERIES.put("NDA",              "\"mutual non-disclosure agreement\" \"confidential information\"");
        PRESET_QUERIES.put("SOFTWARE_LICENSE", "\"software license agreement\" \"intellectual property\"");
        PRESET_QUERIES.put("VENDOR_AGREEMENT", "\"vendor agreement\" \"services\" \"indemnification\"");
        PRESET_QUERIES.put("FINTECH_MSA",      "\"master services agreement\" \"financial services\" \"payment\"");
        PRESET_QUERIES.put("PHARMA_SERVICES",  "\"services agreement\" \"clinical\" OR \"pharmaceutical\"");
        PRESET_QUERIES.put("MANUFACTURING",    "\"supply agreement\" \"manufacturing\" \"purchase orders\"");
        PRESET_QUERIES.put("EMPLOYMENT",       "\"executive employment agreement\" \"severance\" \"non-compete\"");
        PRESET_QUERIES.put("IP_LICENSE",       "\"intellectual property license\" \"royalty\" \"sublicense\"");
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
        // EDGAR EFTS only supports: q, forms, dateRange, startdt, enddt, entity, _source (top-level)
        // Do NOT use Elasticsearch-style hits.* params — they cause 500 on EDGAR's side
        String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
        String url = EFTS_URL + "?q=" + encodedQuery
                + "&forms=EX-10"
                + "&dateRange=custom&startdt=2019-01-01&enddt=2024-12-31"
                + "&_source=entity_name,file_date,form_type,period_of_report";

        log.info("EDGAR search: {}", url);

        RestTemplate rt = new RestTemplate();
        HttpHeaders headers = new HttpHeaders();
        // EDGAR requires a descriptive User-Agent with org name and contact email
        headers.set("User-Agent", USER_AGENT);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        ResponseEntity<String> response = rt.exchange(
                URI.create(url), HttpMethod.GET, new HttpEntity<>(headers), String.class);

        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new RuntimeException("EDGAR search failed: " + response.getStatusCode());
        }

        JsonNode root = objectMapper.readTree(response.getBody());
        JsonNode hits = root.path("hits").path("hits");

        List<EdgarSearchResult> results = new ArrayList<>();
        for (JsonNode hit : hits) {
            if (results.size() >= maxResults) break;

            String id = hit.path("_id").asText();
            JsonNode src = hit.path("_source");
            String entityName = src.path("entity_name").asText("Unknown");
            String fileDate   = src.path("file_date").asText(src.path("period_of_report").asText(""));
            String formType   = src.path("form_type").asText("");

            // _id format: "0001234567-23-000001:exhibit10-1.htm"
            // The colon separates accession number from the document filename within the filing
            int colon = id.lastIndexOf(':');
            if (colon < 0) {
                // Fallback: _id is just the accession number — try to build a filing index URL
                String filingIndexUrl = buildFilingIndexUrl(id);
                if (filingIndexUrl != null) {
                    results.add(new EdgarSearchResult(id, entityName, fileDate, formType, filingIndexUrl, id + "-index.htm"));
                }
                continue;
            }
            String accession = id.substring(0, colon);
            String fileName  = id.substring(colon + 1);

            String docUrl = buildDocumentUrl(accession, fileName);
            if (docUrl == null) continue;

            results.add(new EdgarSearchResult(id, entityName, fileDate, formType, docUrl, fileName));
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
                username
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
     * Constructs the direct EDGAR document URL.
     * Accession format: "0001234567-23-000001"
     * CIK = first segment parsed as long (strips leading zeros)
     * URL: https://www.sec.gov/Archives/edgar/data/{cik}/{accessionNoDashes}/{fileName}
     */
    private static String buildDocumentUrl(String accession, String fileName) {
        try {
            String[] parts = accession.split("-");
            if (parts.length != 3) return null;
            long cik = Long.parseLong(parts[0]);
            String accessionNoDashes = accession.replace("-", "");
            return EDGAR_BASE + "/Archives/edgar/data/" + cik + "/" + accessionNoDashes + "/" + fileName;
        } catch (NumberFormatException e) {
            log.warn("Cannot parse CIK from accession: {}", accession);
            return null;
        }
    }

    /** Builds the filing index page URL when we only have the accession number. */
    private static String buildFilingIndexUrl(String accession) {
        try {
            String[] parts = accession.split("-");
            if (parts.length != 3) return null;
            long cik = Long.parseLong(parts[0]);
            String accessionNoDashes = accession.replace("-", "");
            return EDGAR_BASE + "/Archives/edgar/data/" + cik + "/" + accessionNoDashes + "/";
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
