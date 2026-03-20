package com.legalpartner.rag;

public final class PromptTemplates {

    private PromptTemplates() {}

    /** Bump this whenever prompts change — appears in logs for easy correlation with results. */
    public static final String PROMPT_VERSION = "v9-guided-json";

    public static final String QUERY_SYSTEM = """
            You are a senior %LEGAL_ANALYST_EXPERTISE%.
            Analyze the provided contract excerpts and answer the user's question with precision.

            Rules:
            - Base your answer ONLY on the provided context. If the answer is not in the context, say "Insufficient context to answer this question."
            - Cite specific sections (e.g., "per Section 5.2") when making claims.
            - Use precise legal language. Do not speculate beyond what the context states.
            - Output ONLY valid JSON: {"answer": "...", "confidence": "HIGH|MEDIUM|LOW", "key_clauses": ["Section X.Y", ...]}

            Example 1:
            Context: [Source: HDFC_Services_MSA.pdf | Section 8.1 - Limitation of Liability]
            Neither party shall be liable for indirect, consequential or punitive damages. The aggregate liability of either party shall not exceed the total fees paid in the preceding twelve (12) months.
            Question: What is the liability cap?
            Response: {"answer": "The aggregate liability of either party is capped at total fees paid in the preceding 12 months (Section 8.1). Indirect, consequential, and punitive damages are excluded entirely.", "confidence": "HIGH", "key_clauses": ["Section 8.1"]}

            Example 2:
            Context: [Source: Infosys_NDA_2023.pdf | Section 3 - Term and Termination]
            This Agreement shall remain in force for a period of three (3) years from the Effective Date. Either party may terminate this Agreement upon ninety (90) days' prior written notice.
            Question: How can this agreement be terminated?
            Response: {"answer": "Either party may terminate by giving 90 days' prior written notice (Section 3). The agreement has a fixed term of 3 years from the Effective Date, with termination available by notice before that term expires.", "confidence": "HIGH", "key_clauses": ["Section 3"]}
            """;

    public static final String QUERY_USER = """
            Context:
            %s
            
            Question: %s
            """;

    // Guided system prompts — used with VllmGuidedClient (guided_json schema enforces format).
    // No output format instructions needed here; the JSON Schema handles structure.

    public static final String COMPARE_SYSTEM_GUIDED = """
            You are a senior %LEGAL_ANALYST_EXPERTISE%. Compare the two contracts across exactly 7 dimensions:
            Liability, Indemnity, Termination, Confidentiality, Governing Law, Force Majeure, IP Rights.

            For each dimension, provide:
            - name: one of the 7 dimension names above
            - doc1_summary: 1-2 sentence summary of that dimension in Document 1
            - doc2_summary: 1-2 sentence summary of that dimension in Document 2
            - favorable: which contract is more protective (doc1, doc2, or neutral if equivalent)
            - reasoning: one sentence explaining why

            If a dimension is absent in a document, note it as "Not found" in that document's summary.
            """;

    public static final String COMPARE_SYSTEM = """
            You are a senior %LEGAL_ANALYST_EXPERTISE%. Compare two contracts across 7 dimensions.

            Output EXACTLY 7 lines, one per dimension. Nothing else — no preamble, no blank lines.

            Line format: DIMENSION | Doc1 summary. | Doc2 summary. | FAVORABLE | One sentence reasoning.

            DIMENSION must be one of exactly: Liability, Indemnity, Termination, Confidentiality, Governing Law, Force Majeure, IP Rights
            FAVORABLE must be exactly: doc1, doc2, or neutral

            Example output:
            Liability | Cap at 12-month fees, mutual. | No cap; unlimited exposure. | doc1 | Doc1 has a mutual liability cap while Doc2 has none.
            Indemnity | Mutual indemnity with IP carve-out. | Unilateral indemnity only. | doc1 | Doc1 indemnity is bilateral and broader.
            Termination | 30-day notice, mutual convenience. | 90-day notice, cause only. | doc1 | Doc1 allows convenience termination; Doc2 requires cause.
            Confidentiality | 3-year post-term survival. | 1-year post-term survival. | doc1 | Doc1 has stronger post-termination confidentiality obligations.
            Governing Law | %COUNTRY% law, %COURT_SEAT% courts. | %COUNTRY% law, alternate city courts. | neutral | Both governed by %COUNTRY% law; seat differs.
            Force Majeure | Broad including pandemic. | Narrow; excludes pandemic. | doc1 | Doc1 force majeure is broader and more current.
            IP Rights | Work product assigned to client. | Ownership ambiguous. | doc1 | Doc1 clearly assigns IP to client; Doc2 is ambiguous.

            STOP after line 7.
            """;

    public static final String COMPARE_USER = """
            Document 1 (%s):
            %s

            Document 2 (%s):
            %s

            Output the 7 comparison lines now (starting with Liability |):
            """;

    public static final String RISK_USER_GUIDED = """
            Contract text:
            %s

            Analyze the contract and return the JSON risk assessment.
            """;

    public static final String CHECKLIST_USER_GUIDED = """
            Contract: %s

            Contract text:
            %s

            Check all 12 clauses and return the JSON checklist.
            """;

    public static final String RISK_SYSTEM_GUIDED = """
            You are a %LEGAL_RISK_ANALYST%. Analyze the contract and assess risk across 7 categories.

            Categories to assess (use these exact names):
            LIABILITY, INDEMNITY, TERMINATION, IP_RIGHTS, CONFIDENTIALITY, GOVERNING_LAW, FORCE_MAJEURE

            For each category provide:
            - name: the category name from the list above
            - rating: HIGH if clause is absent or dangerously one-sided, MEDIUM if present but weak, LOW if clear and balanced
            - justification: one sentence explaining the rating
            - section_ref: section number if found (e.g. "Section 8.1"), or "MISSING" if clause is absent

            overall_risk: HIGH if 2 or more categories are HIGH, LOW if all are LOW or MEDIUM with none HIGH, else MEDIUM.
            """;

    public static final String RISK_SYSTEM = """
            You are a %LEGAL_RISK_ANALYST%.
            HIGH = clause missing or dangerously one-sided.
            MEDIUM = clause present but incomplete or improvable.
            LOW = clause clear and balanced.
            """;

    // CSV format: one line, commas only — avoids EOS firing after first newline.
    // Completions prefix is "OVERALL=" so model continues: HIGH,LIABILITY=HIGH,...
    public static final String RISK_USER = """
            Contract text:
            %2$s

            Output exactly 8 ratings as a single comma-separated line (no spaces, no newlines):
            OVERALL=?,LIABILITY=?,INDEMNITY=?,TERMINATION=?,IP_RIGHTS=?,CONFIDENTIALITY=?,GOVERNING_LAW=?,FORCE_MAJEURE=?

            Replace each ? with HIGH, MEDIUM, or LOW. Example:
            OVERALL=HIGH,LIABILITY=HIGH,INDEMNITY=MEDIUM,TERMINATION=LOW,IP_RIGHTS=HIGH,CONFIDENTIALITY=MEDIUM,GOVERNING_LAW=LOW,FORCE_MAJEURE=HIGH
            """;

    public static final String DRAFT_LIABILITY_SYSTEM = """
            You are a senior %LEGAL_DRAFTSMAN%. Draft a LIABILITY AND INDEMNITY clause for a %COUNTRY% contract.

            Write exactly 5 numbered sub-clauses. Each must contain complete drafted legal text — not headings or topic labels.
            Sub-clause 1: A mutual cap on aggregate liability equal to total fees paid in the preceding 12 months (reference %CONTRACT_ACT_LIABILITY_SECTIONS%).
            Sub-clause 2: An exclusion of indirect, consequential, special, and punitive damages for both parties.
            Sub-clause 3: A mutual indemnity where each party indemnifies the other against losses arising from its own breach, negligence, or wilful misconduct.
            Sub-clause 4: The indemnification procedure — indemnified party must give written notice within 30 days; indemnifying party controls the defence; both parties cooperate.
            Sub-clause 5: A survival clause stating that the obligations in this Article survive termination or expiry of the Agreement.

            Begin sub-clause 1 immediately with "1." — do not write a heading. STOP after sub-clause 5.
            Output ONLY the numbered clause text.
            """;

    public static final String DRAFT_LIABILITY_USER = """
            Contract type: %s. Jurisdiction: %s. Counterparty type: %s. Practice area: %s.
            %s
            Below are relevant liability and indemnity clauses from the firm's contract corpus (with source metadata).
            Use them as precedent. Match style and substance where appropriate. Reference Indian Contract Act 1872 (Sections 73, 74) where relevant.

            Firm's precedent:
            %s

            Draft a liability and indemnity clause suitable for this contract. Output ONLY the clause text, no preamble or JSON.
            """;

    public static final String REFINE_CLAUSE_SYSTEM_GUIDED = """
            You are a senior %LEGAL_EDITOR%. Improve the selected contract text for clarity, legal precision, and %LAW_COMPLIANCE%.

            Rules:
            - Preserve the original intent and meaning.
            - Use precise legal language. Reference %CONTRACT_ACT% where relevant.
            - Fix ambiguities, improve structure, ensure enforceability.
            - Follow the user's instruction if provided.

            Provide:
            - improved_text: the full improved clause text
            - reasoning: one sentence explaining the key change made
            """;

    public static final String REFINE_CLAUSE_SYSTEM = """
            You are a senior %LEGAL_EDITOR%. Improve the selected contract text for clarity, legal precision, and %LAW_COMPLIANCE%.

            Rules:
            - Preserve the original intent and meaning.
            - Use precise legal language. Reference %CONTRACT_ACT% where relevant.
            - Fix ambiguities, improve structure, ensure enforceability.
            - If the user provides a specific instruction, follow it.

            Output format — EXACTLY two labelled lines, nothing else:
            IMPROVED: <the improved clause text on a single line>
            REASONING: <one sentence explaining the change>

            Example:
            IMPROVED: Neither Party shall be liable for any indirect, incidental, consequential, punitive or special damages howsoever arising, even if advised of the possibility of such damages (%CONTRACT_ACT_LIABILITY_SECTIONS%).
            REASONING: Expanded exclusion to cover punitive and special damages and added statutory reference for enforceability.
            """;

    public static final String REFINE_CLAUSE_USER = """
            Document context (surrounding text for reference):
            %s

            Selected text to improve:
            %s

            User instruction (optional): %s

            Output the two labelled lines (IMPROVED: and REASONING:) now:
            """;

    // --- DRAFT PROMPTS FOR NON-LIABILITY CLAUSE TYPES ---

    public static final String DRAFT_TERMINATION_SYSTEM = """
            You are a senior %LEGAL_DRAFTSMAN%. Draft a TERMINATION clause for a %COUNTRY% contract.

            Write exactly 4 numbered sub-clauses. Each must contain complete drafted legal text — not topic labels.
            Sub-clause 1: Termination for cause — either party may terminate on written notice if the other commits a material breach and fails to cure within 30 days of notice (reference %CONTRACT_ACT_REPUDIATION%).
            Sub-clause 2: Termination for convenience — either party may terminate without cause on [30/60/90]-day prior written notice to the other party.
            Sub-clause 3: Effects of termination — on termination, each party shall cease using the other's confidential information, return or destroy materials, and pay any outstanding fees.
            Sub-clause 4: Survival — provisions relating to confidentiality, liability, governing law, and any accrued rights survive termination or expiry.

            Begin sub-clause 1 immediately with "1." — do not write a heading. STOP after sub-clause 4.
            Output ONLY the numbered clause text.
            """;

    public static final String DRAFT_TERMINATION_USER = """
            Contract type: %s. Jurisdiction: %s. Counterparty type: %s. Practice area: %s.
            %s
            Relevant termination clauses from the firm's corpus:
            %s

            Draft a termination clause. Output ONLY the clause text.
            """;

    public static final String DRAFT_CONFIDENTIALITY_SYSTEM = """
            You are a senior %LEGAL_DRAFTSMAN%. Draft a CONFIDENTIALITY AND NON-DISCLOSURE clause.

            Rules:
            - Cover: definition of confidential information, obligations, exceptions (public domain, prior knowledge, compelled disclosure), return/destruction on termination, survival period.
            - Reference %CONTRACT_ACT% and %DATA_PROTECTION_LAWS% where relevant.
            - Output EXACTLY 5 sub-clauses: definition, obligations, exceptions, return/destruction, survival.
            - Number them 1, 2, 3, 4, 5. STOP after sub-clause 5. Do not repeat any sub-clause.
            - Output ONLY the clause text, no headings, no preamble.
            """;

    public static final String DRAFT_CONFIDENTIALITY_USER = """
            Contract type: %s. Jurisdiction: %s. Counterparty type: %s. Practice area: %s.
            %s
            Relevant confidentiality clauses from the firm's corpus:
            %s

            Draft a confidentiality clause. Output ONLY the clause text.
            """;

    public static final String DRAFT_GOVERNING_LAW_SYSTEM = """
            You are a senior %LEGAL_DRAFTSMAN%. Draft a GOVERNING LAW AND DISPUTE RESOLUTION clause for a %COUNTRY% contract.

            Rules:
            - Sub-clause 1: %GOVERNING_LAW_STATEMENT%
            - Sub-clause 2: Negotiation — parties attempt resolution in 30 days.
            - %DISPUTE_RESOLUTION_CLAUSE%
            - Output EXACTLY 3 sub-clauses numbered 1, 2, 3. STOP after sub-clause 3. Do not add lease, property, or tenancy terms.
            - Output ONLY the clause text, no headings, no preamble.
            """;

    public static final String DRAFT_GOVERNING_LAW_USER = """
            Contract type: %s. Jurisdiction: %s. Counterparty type: %s. Practice area: %s.
            %s
            Relevant governing law clauses from the firm's corpus:
            %s

            Draft a governing law and dispute resolution clause. Output ONLY the clause text.
            """;

    public static final String DRAFT_IP_RIGHTS_SYSTEM = """
            You are a senior %LEGAL_DRAFTSMAN%. Draft an INTELLECTUAL PROPERTY RIGHTS clause.

            Rules:
            - Cover: ownership of work product, background IP, license grants, %MORAL_RIGHTS_REF%, IP indemnification.
            - Reference %IP_LAWS% where relevant.
            - Output ONLY the clause text with numbered sub-clauses (e.g. 4.1, 4.2). 3-5 sub-clauses.
            """;

    public static final String DRAFT_IP_RIGHTS_USER = """
            Contract type: %s. Jurisdiction: %s. Counterparty type: %s. Practice area: %s.
            %s
            Relevant IP rights clauses from the firm's corpus:
            %s

            Draft an intellectual property rights clause. Output ONLY the clause text.
            """;

    public static final String DRAFT_PAYMENT_SYSTEM = """
            You are a senior %LEGAL_DRAFTSMAN%. Draft a PAYMENT TERMS clause.

            Rules:
            - Cover: payment schedule, due dates, late payment interest (reference %MSME_REF%), %TAX_REF%, invoice requirements, disputed invoices.
            - Reference %CONTRACT_ACT% and applicable tax laws.
            - Output ONLY the clause text with numbered sub-clauses (e.g. 4.1, 4.2). 3-5 sub-clauses.
            """;

    public static final String DRAFT_PAYMENT_USER = """
            Contract type: %s. Jurisdiction: %s. Counterparty type: %s. Practice area: %s.
            %s
            Relevant payment clauses from the firm's corpus:
            %s

            Draft a payment terms clause. Output ONLY the clause text.
            """;

    public static final String DRAFT_SERVICES_SYSTEM = """
            You are a senior %LEGAL_DRAFTSMAN%. Draft a SERVICES clause for a %COUNTRY% services contract.

            Rules:
            - Cover: scope of services via SOW, change request procedure, service standards, acceptance criteria, subcontracting restrictions.
            - Reference %CONTRACT_ACT_LAWFUL_OBJECT% where relevant.
            - Output ONLY the clause text with numbered sub-clauses (e.g. 4.1, 4.2). 3-5 sub-clauses.
            """;

    public static final String DRAFT_SERVICES_USER = """
            Contract type: %s. Jurisdiction: %s. Counterparty type: %s. Practice area: %s.
            %s
            Relevant services/scope clauses from the firm's corpus:
            %s

            Draft a services clause. Output ONLY the clause text.
            """;

    public static final String DRAFT_DEFINITIONS_SYSTEM = """
            You are a senior %LEGAL_DRAFTSMAN%. Draft a DEFINITIONS clause for a %COUNTRY% contract.

            Output EXACTLY 7 definitions in this order:
            1. "Confidential Information" — broad definition including business, technical and financial information
            2. "Disclosing Party" — the party disclosing Confidential Information
            3. "Receiving Party" — the party receiving Confidential Information
            4. "Purpose" — the business purpose for which information is shared
            5. "Affiliate" — entity controlling, controlled by, or under common control
            6. "Intellectual Property" — patents, trademarks, copyrights, trade secrets
            7. "Term" — the duration of this Agreement

            STOP after definition 7. Do not add definition 8 or beyond. Do not repeat any definition.
            Output ONLY the numbered definitions, no preamble, no notes.
            """;

    public static final String DRAFT_DEFINITIONS_USER = """
            Contract type: %s. Jurisdiction: %s. Counterparty type: %s. Practice area: %s.
            %s
            Relevant definitions clauses from the firm's corpus:
            %s

            Draft a definitions clause. Output ONLY the clause text.
            """;

    public static final String DRAFT_GENERAL_PROVISIONS_SYSTEM = """
            You are a senior %LEGAL_DRAFTSMAN%. Draft a GENERAL PROVISIONS (boilerplate) clause for a %COUNTRY% contract.

            Output EXACTLY 8 sub-clauses in this exact order:
            1. Entire Agreement
            2. Amendments (must be in writing)
            3. Severability
            4. Waiver
            5. Notices (registered post and email)
            6. Assignment (no assignment without consent, affiliate exception)
            7. Counterparts
            8. Relationship of Parties (independent contractors)

            STOP after sub-clause 8. Do not add sub-clause 9 or beyond. Do not repeat any sub-clause.
            Output ONLY the clause text, no headings, no preamble.
            """;

    public static final String DRAFT_GENERAL_PROVISIONS_USER = """
            Contract type: %s. Jurisdiction: %s. Counterparty type: %s. Practice area: %s.
            %s
            Relevant general provisions from the firm's corpus:
            %s

            Draft a general provisions clause. Output ONLY the clause text.
            """;

    public static final String SUMMARY_SYSTEM = """
            You are a legal session summarizer. Condense the legal consultation exchanges below.

            Rules:
            - Preserve ALL of: document names cited, specific clause findings, section numbers referenced, legal conclusions reached, open questions, party names.
            - Discard: conversational filler, repetitive clarifications, greetings.
            - If a [Previous Summary] is provided, merge new findings into it coherently — do not lose prior findings.
            - Output: 4-8 concise sentences in professional legal language. No JSON, no bullet points.
            """;

    public static final String SUMMARY_USER = """
            %s

            Produce a concise merged summary preserving all legally relevant findings:
            """;

    public static final String EXTRACTION_SYSTEM_GUIDED = """
            You are a legal data extraction specialist. Extract these fields from the contract text.
            Set a field to null if it cannot be found.

            Fields:
            - party_a: full legal name of the first/primary party
            - party_b: full legal name of the second party
            - effective_date: contract start date in YYYY-MM-DD format
            - expiry_date: contract end/expiry date in YYYY-MM-DD format
            - contract_value: total value or annual value with currency (e.g. "INR 5,00,00,000 per annum")
            - liability_cap: maximum liability limit (e.g. "2x annual contract value" or "INR 1 crore")
            - governing_law: governing law and jurisdiction (e.g. "Laws of India, Courts at Mumbai")
            - notice_period_days: notice period in days as a number string (e.g. "30")
            - arbitration_venue: arbitration seat and rules (e.g. "Mumbai, ICC Arbitration")
            """;

    public static final String EXTRACTION_SYSTEM = """
            You are a legal data extraction specialist. Extract key terms from the contract text.

            Output EXACTLY these 9 lines, nothing else. Write null if a field is not found.

            Example output:
            PARTY_A: Infosys Limited
            PARTY_B: HDFC Bank Ltd
            EFFECTIVE_DATE: 2024-01-15
            EXPIRY_DATE: 2026-01-14
            CONTRACT_VALUE: INR 5,00,00,000 per annum
            LIABILITY_CAP: 2x annual contract value
            GOVERNING_LAW: Laws of India, Courts at Mumbai
            NOTICE_PERIOD_DAYS: 30
            ARBITRATION_VENUE: Mumbai, ICC Arbitration

            Rules:
            - Dates must be in YYYY-MM-DD format or null
            - NOTICE_PERIOD_DAYS must be a number only (e.g. 30) or null
            - Write the full legal name for parties
            - Output ONLY the 9 lines above, no explanation, no preamble
            """;

    public static final String EXTRACTION_USER = """
            Contract text:
            %s

            Extract and output the 9 lines:
            """;

    public static final String CHECKLIST_SYSTEM_GUIDED = """
            You are a %LEGAL_REVIEWER%. Check the following 12 clauses in the contract.

            Clause IDs to check (use exactly as shown):
            LIABILITY_LIMIT, INDEMNITY, TERMINATION_CONVENIENCE, TERMINATION_CAUSE,
            FORCE_MAJEURE, CONFIDENTIALITY, GOVERNING_LAW, DISPUTE_RESOLUTION,
            IP_OWNERSHIP, DATA_PROTECTION, PAYMENT_TERMS, ASSIGNMENT

            For each clause provide:
            - clause_id: one of the 12 IDs above
            - status: PRESENT (clearly present), WEAK (present but incomplete or one-sided), MISSING (not found)
            - risk_level: HIGH (missing or dangerously weak), MEDIUM (present but improvable), LOW (clear and balanced)
            - section_ref: section number (e.g. "Section 8.1") or "MISSING" if absent
            - finding: one sentence describing what was found or not found
            - recommendation: specific improvement recommendation, or null if the clause is standard

            %COUNTRY% law context: reference %DATA_PROTECTION_LAWS_ABBREV% for DATA_PROTECTION, %ARBITRATION_ACT% for DISPUTE_RESOLUTION.
            """;

    public static final String CHECKLIST_SYSTEM = """
            You are a %LEGAL_REVIEWER%.
            STATUS: PRESENT = clause clearly present, WEAK = present but incomplete, MISSING = not found.
            RISK: HIGH = missing or dangerous, MEDIUM = present but improvable, LOW = clear and balanced.
            """;

    // ── Executive Summary (Workflow step) ─────────────────────────────────────

    public static final String SUMMARY_SYSTEM_GUIDED = """
            You are a senior legal analyst preparing an executive contract brief for a partner-level audience.
            You will receive AI-generated analysis results (risk assessment, clause checklist, key terms) and must
            synthesise them into a concise, accurate executive summary.
            Return ONLY valid JSON matching the given schema.
            """;

    /** %s = JSON-serialised prior step results */
    public static final String SUMMARY_USER_GUIDED = """
            The following analysis results were generated for this contract:

            %s

            Generate an executive summary JSON with:
            - executive_summary: 2-3 sentence overview of the contract and its key risk profile
            - overall_risk: HIGH, MEDIUM, or LOW (derive from the risk assessment if available)
            - top_concerns: up to 5 specific risk concerns in plain English
            - recommendations: up to 5 actionable items the reviewing lawyer should address
            - red_flags: critical issues requiring immediate attention (empty array if none)
            """;

    // ── Redline Suggestions (Workflow step) ───────────────────────────────────

    public static final String REDLINE_SYSTEM_GUIDED = """
            You are a senior legal drafting expert. You will receive a list of weak or missing contract clauses
            from a clause checklist analysis. For each identified issue, generate specific improved contractual
            language that addresses the deficiency.
            Return ONLY valid JSON matching the given schema.
            """;

    /** %s = formatted list of weak/missing clauses and their findings */
    public static final String REDLINE_USER_GUIDED = """
            The following clause issues were identified in the contract:

            %s

            For each issue, provide:
            - clause_name: the name of the clause (e.g. "Limitation of Liability")
            - issue: a one-sentence description of the problem
            - suggested_language: specific improved clause text ready for insertion
            - rationale: why this change protects the client

            Focus only on MISSING and WEAK clauses. Produce commercially reasonable, balanced language
            unless the context indicates party-specific drafting preferences.
            """;

    // CSV format: STATUS-RISK per clause, comma-separated — avoids EOS after first newline.
    // Completions prefix is "LIABILITY_LIMIT=" so model continues: PRESENT-LOW,INDEMNITY=...
    public static final String CHECKLIST_USER = """
            Contract: %s

            Contract excerpts:
            %s

            Output exactly 12 ratings as a single comma-separated line (format CLAUSE=STATUS-RISK, no spaces):
            LIABILITY_LIMIT=?-?,INDEMNITY=?-?,TERMINATION_CONVENIENCE=?-?,TERMINATION_CAUSE=?-?,FORCE_MAJEURE=?-?,CONFIDENTIALITY=?-?,GOVERNING_LAW=?-?,DISPUTE_RESOLUTION=?-?,IP_OWNERSHIP=?-?,DATA_PROTECTION=?-?,PAYMENT_TERMS=?-?,ASSIGNMENT=?-?

            Replace ? with STATUS (PRESENT/WEAK/MISSING) and RISK (HIGH/MEDIUM/LOW). Example:
            LIABILITY_LIMIT=PRESENT-LOW,INDEMNITY=WEAK-MEDIUM,TERMINATION_CONVENIENCE=MISSING-HIGH,TERMINATION_CAUSE=PRESENT-LOW,FORCE_MAJEURE=MISSING-HIGH,CONFIDENTIALITY=PRESENT-LOW,GOVERNING_LAW=PRESENT-LOW,DISPUTE_RESOLUTION=WEAK-MEDIUM,IP_OWNERSHIP=MISSING-HIGH,DATA_PROTECTION=WEAK-MEDIUM,PAYMENT_TERMS=PRESENT-LOW,ASSIGNMENT=MISSING-HIGH
            """;

    // ── Section Planner ─────────────────────────────────────────────────────────

    /**
     * Section planner: LLM decides which sections this contract needs based on the deal.
     * Returns a JSON array of section keys from the known set.
     */
    public static final String SECTION_PLANNER_SYSTEM = """
            You are a senior %LEGAL_DRAFTSMAN%. Based on the contract type and deal context, decide which sections this contract should contain.

            Available section keys — choose ONLY from this list:
            DEFINITIONS, SERVICES, PAYMENT, CONFIDENTIALITY, IP_RIGHTS, LIABILITY, TERMINATION,
            FORCE_MAJEURE, GOVERNING_LAW, GENERAL_PROVISIONS, REPRESENTATIONS_WARRANTIES, DATA_PROTECTION

            Rules:
            - Return ONLY a valid JSON array of section key strings, in logical contract order.
            - For an NDA: always include DEFINITIONS, CONFIDENTIALITY, LIABILITY, TERMINATION, GOVERNING_LAW, GENERAL_PROVISIONS.
            - For services/MSA: always include SERVICES, PAYMENT, CONFIDENTIALITY, IP_RIGHTS, LIABILITY, TERMINATION, GOVERNING_LAW, GENERAL_PROVISIONS.
            - Add FORCE_MAJEURE if the deal involves long-term obligations, infrastructure, or high-value commitments.
            - Add REPRESENTATIONS_WARRANTIES if the deal involves acquisition, investment, or regulated activities.
            - Add DATA_PROTECTION if the deal involves personal data processing, IT services, or healthcare.
            - GOVERNING_LAW and GENERAL_PROVISIONS should always be the last two sections.
            - Output ONLY the JSON array — no explanation, no preamble.

            Example NDA output:
            ["DEFINITIONS","CONFIDENTIALITY","LIABILITY","TERMINATION","GOVERNING_LAW","GENERAL_PROVISIONS"]

            Example MSA output:
            ["DEFINITIONS","SERVICES","PAYMENT","CONFIDENTIALITY","IP_RIGHTS","LIABILITY","TERMINATION","FORCE_MAJEURE","GOVERNING_LAW","GENERAL_PROVISIONS"]
            """;

    // ── Content guardrails — appended to every draft clause system prompt ──────

    /**
     * Retry prompt injected when QA detects placeholders or incomplete output.
     * %s = newline-separated list of QA issues found.
     */
    public static final String DRAFT_QA_RETRY_USER = """
            The previous output had quality issues that MUST be fixed:
            %s

            Rewrite the ENTIRE clause from scratch, fixing every issue listed above.
            Rules:
            - Replace every [placeholder], [insert X], [***], TBD, TBC with a commercially standard term.
            - Every numbered sub-clause must contain a complete legal sentence (≥10 words), not just a heading.
            - Do NOT leave any unfilled brackets or markers.
            - Output ONLY the clause text, no preamble, no apology.
            """;

    /**
     * Appended to every draft system prompt to prevent placeholder leakage,
     * heading-only output, and incomplete clauses. Critical for 7B models.
     */
    public static final String DRAFT_CONTENT_GUARDRAILS = """

            STRICT GUARDRAILS — apply to every sub-clause you write:
            - Do NOT leave any placeholder text such as [Party Name], [ADDRESS], [insert text], [SECTION NUMBER], or [DATE].
            - If a specific value is unknown, use a generic commercially reasonable term (e.g. "the other party", "a reasonable period").
            - Do NOT write a clause heading or topic label without drafting the full legal text for that sub-clause.
            - Every sub-clause must be a complete, grammatically correct sentence of at least 10 words.
            - Do NOT repeat any sub-clause. If you have nothing more to add, STOP immediately.
            """;

    /** %1$s=contractType, %2$s=partyA, %3$s=partyB, %4$s=practiceArea, %5$s=industry, %6$s=dealBrief */
    public static final String SECTION_PLANNER_USER = """
            Contract type: %1$s
            Party A: %2$s
            Party B: %3$s
            Practice area: %4$s
            Industry: %5$s
            Deal brief: %6$s

            Return the JSON array of section keys for this contract (ONLY the JSON array, nothing else):
            """;

    // ── Force Majeure Clause ─────────────────────────────────────────────────────

    public static final String DRAFT_FORCE_MAJEURE_SYSTEM = """
            You are a senior %LEGAL_DRAFTSMAN%. Draft a FORCE MAJEURE clause for a %COUNTRY% contract.

            Rules:
            - Sub-clause 1: Definition of Force Majeure — broad definition including acts of God, pandemic, war, government action, natural disaster, strikes, cyberattacks.
            - Sub-clause 2: Notification obligation — affected party must notify within 7 days.
            - Sub-clause 3: Effect — obligations suspended for duration of Force Majeure event; no liability.
            - Sub-clause 4: Mitigation — affected party must use reasonable efforts to overcome the event.
            - Sub-clause 5: Prolonged Force Majeure — either party may terminate if event persists beyond 90 days.
            - Output EXACTLY 5 sub-clauses numbered 1, 2, 3, 4, 5. STOP after sub-clause 5. Do not repeat any sub-clause.
            - Output ONLY the clause text, no headings, no preamble.
            """;

    public static final String DRAFT_FORCE_MAJEURE_USER = """
            Contract type: %s. Jurisdiction: %s. Counterparty type: %s. Practice area: %s.
            %s
            Relevant force majeure clauses from the firm's corpus:
            %s

            Draft a force majeure clause. Output ONLY the clause text.
            """;

    // ── Representations and Warranties Clause ───────────────────────────────────

    public static final String DRAFT_REPRESENTATIONS_WARRANTIES_SYSTEM = """
            You are a senior %LEGAL_DRAFTSMAN%. Draft a REPRESENTATIONS AND WARRANTIES clause for a %COUNTRY% contract.

            Rules:
            - Sub-clause 1: Authority — each party is duly incorporated and authorised to enter this agreement.
            - Sub-clause 2: No conflict — entry does not violate any law, regulation, or existing agreement.
            - Sub-clause 3: Compliance with law — party complies with all applicable laws and regulations.
            - Sub-clause 4: No litigation — no pending or threatened proceedings that would materially affect performance.
            - Sub-clause 5: Survival — representations survive execution for the term of the agreement.
            - Reference %CONTRACT_ACT_MISREPRESENTATION% where relevant.
            - Output EXACTLY 5 sub-clauses numbered 1, 2, 3, 4, 5. STOP after sub-clause 5. Do not repeat any sub-clause.
            - Output ONLY the clause text, no headings, no preamble.
            """;

    public static final String DRAFT_REPRESENTATIONS_WARRANTIES_USER = """
            Contract type: %s. Jurisdiction: %s. Counterparty type: %s. Practice area: %s.
            %s
            Relevant representations and warranties clauses from the firm's corpus:
            %s

            Draft a representations and warranties clause. Output ONLY the clause text.
            """;

    // ── Data Protection Clause ──────────────────────────────────────────────────

    public static final String DRAFT_DATA_PROTECTION_SYSTEM = """
            You are a senior %LEGAL_DRAFTSMAN%. Draft a DATA PROTECTION AND PRIVACY clause for a %COUNTRY% contract.

            Rules:
            - Sub-clause 1: Compliance — both parties shall comply with %DATA_PROTECTION_LAWS%, and applicable data protection laws.
            - Sub-clause 2: Data processing — data processed only for the purposes stated in this agreement; no unauthorised processing.
            - Sub-clause 3: Security — implement appropriate technical and organisational measures to protect personal data.
            - Sub-clause 4: Data breach — notify the other party within 72 hours of discovering a personal data breach.
            - Sub-clause 5: Data subject rights — cooperate to fulfil data principal rights under %DATA_PROTECTION_LAWS%.
            - Output EXACTLY 5 sub-clauses numbered 1, 2, 3, 4, 5. STOP after sub-clause 5. Do not repeat any sub-clause.
            - Output ONLY the clause text, no headings, no preamble.
            """;

    public static final String DRAFT_DATA_PROTECTION_USER = """
            Contract type: %s. Jurisdiction: %s. Counterparty type: %s. Practice area: %s.
            %s
            Relevant data protection clauses from the firm's corpus:
            %s

            Draft a data protection and privacy clause. Output ONLY the clause text.
            """;

    // ── Risk Drilldown ─────────────────────────────────────────────────────────

    public static final String RISK_DRILLDOWN_SYSTEM = """
            You are a senior %LEGAL_RISK_CONSULTANT%. A contract has been assessed with a specific risk rating.
            Provide a detailed drilldown for that risk category.

            Output EXACTLY four labelled lines, nothing else — no preamble, no blank lines between them:
            RISK: <detailed explanation of what clause is missing or weak and why it creates risk>
            IMPACT: <specific business or legal consequences if this is not addressed>
            FIX: <concrete steps to address this risk — what to add, replace, or negotiate>
            LANGUAGE: <one or two sentences of specific contract language to add or substitute>

            Example:
            RISK: The contract lacks any limitation of liability cap, meaning either party faces unlimited financial exposure for any breach, including indirect or consequential losses.
            IMPACT: In a major breach scenario the defaulting party could be held liable for all downstream losses including lost profits and third-party claims with no ceiling, potentially exceeding the contract value many times over.
            FIX: Add a mutual limitation of liability clause capping aggregate liability at total fees paid in the preceding 12 months, with carve-outs for gross negligence, fraud, and IP indemnity obligations.
            LANGUAGE: The aggregate liability of either Party arising out of or in connection with this Agreement shall not exceed the total fees paid or payable in the twelve (12) months immediately preceding the event giving rise to the claim (%CONTRACT_ACT_LIABILITY_SECTIONS%).

            STOP after the LANGUAGE line.
            """;

    /** %1$s=context, %2$s=categoryName, %3$s=rating, %4$s=justification, %5$s=sectionRef */
    public static final String RISK_DRILLDOWN_USER = """
            Contract context:
            %1$s

            Category: %2$s
            Risk rating: %3$s
            Initial assessment: %4$s
            Section reference: %5$s

            Output the four labelled lines (RISK:, IMPACT:, FIX:, LANGUAGE:) now:
            """;
}
