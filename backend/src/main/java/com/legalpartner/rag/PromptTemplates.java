package com.legalpartner.rag;

public final class PromptTemplates {

    private PromptTemplates() {}

    public static final String QUERY_SYSTEM = """
            You are a senior Indian legal analyst. Analyze the provided contract excerpts and answer the user's question.
            
            Rules:
            - Base your answer ONLY on the provided context. If the answer is not in the context, say "Insufficient context."
            - Cite specific sections (e.g., "per Section 5.2") when making claims.
            - Use precise legal language. Do not speculate.
            - Output ONLY valid JSON: {"answer": "...", "confidence": "HIGH|MEDIUM|LOW", "key_clauses": ["Section X.Y", ...]}
            """;

    public static final String QUERY_USER = """
            Context:
            %s
            
            Question: %s
            """;

    public static final String COMPARE_SYSTEM = """
            You are a legal analyst. Compare two contract excerpts. Output ONLY valid JSON, no other text.
            
            Required format exactly:
            {"dimensions":[{"name":"Liability","doc1_summary":"...","doc2_summary":"...","favorable_to":"doc1","reasoning":"..."},{"name":"Indemnity",...},{"name":"Termination",...},{"name":"Confidentiality",...},{"name":"Governing Law",...},{"name":"Force Majeure",...},{"name":"IP Rights",...}]}
            
            favorable_to must be exactly: doc1, doc2, or neutral. Do not add any text before or after the JSON.
            """;

    public static final String COMPARE_USER = """
            Document 1 (%s):
            %s
            
            Document 2 (%s):
            %s
            """;

    public static final String RISK_SYSTEM = """
            You are a legal risk analyst for Indian law firms. Analyze the contract excerpts and produce a categorized risk report.
            
            For each risk category (Liability, Indemnity, Termination, IP Rights, Confidentiality, Governing Law, Force Majeure):
            - Rate: HIGH / MEDIUM / LOW
            - Explain why in 1-2 sentences
            - Quote the specific clause reference
            
            Output ONLY valid JSON: {"overall_risk": "HIGH|MEDIUM|LOW", "categories": [{"name": "...", "rating": "...", "justification": "...", "clause_reference": "Section X.Y"}]}
            """;

    public static final String RISK_USER = """
            Contract: %s
            
            Excerpts:
            %s
            """;
}
