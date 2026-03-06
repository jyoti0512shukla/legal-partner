#!/usr/bin/env python3
"""
Generate 3 comprehensive legal documents (~10,000 lines each), India context,
senior partner style. Output HTML to data/long-docs/
"""

import os
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
OUT_DIR = REPO / "data" / "long-docs"
OUT_DIR.mkdir(parents=True, exist_ok=True)

# Clause libraries for expansion (senior partner, India context)
INDIA_STATUTES = [
    "Indian Contract Act, 1872", "Companies Act, 2013", "Information Technology Act, 2000",
    "Transfer of Property Act, 1882", "Arbitration and Conciliation Act, 1996",
    "Specific Relief Act, 1963", "Indian Evidence Act, 1872", "Code of Civil Procedure, 1908",
    "Companies (Incorporation) Rules, 2014", "SEBI (Listing Obligations and Disclosure Requirements) Regulations, 2015",
    "Foreign Exchange Management Act, 1999", "Competition Act, 2002", "RBI Act, 1934",
    "Prevention of Money Laundering Act, 2002", "Insolvency and Bankruptcy Code, 2016",
    "Digital Personal Data Protection Act, 2023", "Real Estate (Regulation and Development) Act, 2016",
    "Indian Stamp Act, 1899", "Registration Act, 1908", "Labour Codes, 2020",
    "Goods and Services Tax Act, 2017", "Income Tax Act, 1961", "Copyright Act, 1957",
    "Patents Act, 1970", "Trademarks Act, 1999", "Designs Act, 2000",
]

DEFINITIONS_BASE = [
    ("Affiliate", "any entity that directly or indirectly Controls, is Controlled by, or is under common Control with another entity; 'Control' means ownership of more than fifty percent (50%) of the voting equity or the power to direct management and policies"),
    ("Business Day", "a day other than Saturday, Sunday, or a public holiday in India or the relevant State"),
    ("Claim", "any claim, demand, action, suit, proceeding, or liability"),
    ("Confidential Information", "all non-public information disclosed by one Party to the other, including business plans, financial data, technical specifications, customer lists, and trade secrets"),
    ("Control", "the power to direct the management and policies of an entity, whether through ownership, contract, or otherwise"),
    ("Data", "all data, information, and records in any form, including personal data as defined under the Digital Personal Data Protection Act, 2023"),
    ("Effective Date", "the date of execution of this Agreement"),
    ("Force Majeure", "acts of God, war, terrorism, epidemic, pandemic, government action, or other circumstances beyond reasonable control"),
    ("Governmental Authority", "any court, tribunal, regulatory body, or government agency in India having jurisdiction"),
    ("Indemnified Party", "the Party entitled to indemnification under this Agreement"),
    ("Indemnifying Party", "the Party obligated to indemnify"),
    ("Intellectual Property Rights", "patents, copyrights, trademarks, trade secrets, designs, and all other proprietary rights recognized under Indian law"),
    ("Law", "any statute, regulation, ordinance, rule, or order of any Governmental Authority in India"),
    ("Losses", "damages, losses, costs, expenses, and liabilities including reasonable legal fees"),
    ("Material Adverse Effect", "a material adverse effect on the business, assets, operations, or financial condition of a Party"),
    ("Person", "any individual, partnership, company, trust, or other entity"),
    ("Representative", "officers, directors, employees, agents, and professional advisors"),
    ("Subsidiary", "an entity in which another entity holds more than fifty percent (50%) of the equity or voting power"),
    ("Tax", "any tax, duty, levy, or withholding including GST, income tax, and TDS under Indian law"),
    ("Term", "the duration of this Agreement from the Effective Date until termination"),
]

def wrap(tag, content):
    return f"<{tag}>{content}</{tag}>"

def p(text):
    return f"<p>{text}</p>\n"

def h2(text):
    return f"<h2>{text}</h2>\n"

def h3(text):
    return f"<h3>{text}</h3>\n"

def ul(items):
    return "<ul>\n" + "".join(f"<li>{x}</li>\n" for x in items) + "</ul>\n"

def html_doc(title, body_lines):
    return f"""<!DOCTYPE html>
<html lang="en">
<head><meta charset="UTF-8"><title>{title}</title></head>
<body>
{''.join(body_lines)}
</body>
</html>"""


def gen_definitions(count=80):
    """Generate extensive definitions section."""
    lines = []
    lines.append(h2("ARTICLE 1 — DEFINITIONS AND INTERPRETATION"))
    lines.append(h3("1.1 Definitions"))
    seen = set()
    for i, (term, defn) in enumerate(DEFINITIONS_BASE):
        lines.append(p(f'<strong>"{term}"</strong> means {defn}.'))
        seen.add(term)
    # Add more variations
    extra = [
        ("Acceptance Criteria", "the criteria specified in the relevant Schedule for acceptance of Deliverables"),
        ("Additional Rent", "any amount payable by the Lessee in addition to Base Rent, including CAM, utilities, and taxes"),
        ("Affected Party", "a Party whose performance is hindered by Force Majeure"),
        ("Aggregate Limit", "the maximum aggregate liability cap under Article 12"),
        ("Annual Budget", "the annual budget approved by the Board for the Venture"),
        ("Applicable Rate", "the interest rate prescribed under the relevant statute or eighteen percent (18%) per annum, whichever is higher"),
        ("Approved Budget", "the budget approved by the Steering Committee for the relevant period"),
        ("Arbitral Tribunal", "the tribunal constituted under the Arbitration and Conciliation Act, 1996"),
        ("Arbitration Rules", "the rules of the arbitral institution administering the arbitration"),
        ("Base Rent", "the base monthly rent as specified in Article 3"),
        ("Board", "the Board of Directors of the Company"),
        ("Business Plan", "the business plan of the Venture as updated from time to time"),
        ("Change of Control", "any transaction resulting in a change in the person(s) exercising Control"),
        ("Change Request", "a formal request to modify the Scope, Timeline, or Fees"),
        ("Claim Period", "the period within which a claim must be notified"),
        ("Commercial Operation Date", "the date on which the System is deployed to production"),
        ("Commencement Date", "the date on which the Services or Lease Term commences"),
        ("Confidentiality Period", "the Term plus five (5) years thereafter"),
        ("Consent", "prior written consent, not to be unreasonably withheld or delayed"),
        ("Contract Year", "each successive twelve (12) month period from the Effective Date"),
        ("Default Interest", "interest at eighteen percent (18%) per annum or the maximum permitted by law"),
        ("Defect", "a failure of the Deliverables to conform to the Specifications"),
        ("Deliverables", "all work product to be delivered under this Agreement"),
        ("Dispute", "any dispute, controversy, or claim arising out of or relating to this Agreement"),
        ("Effective Tax Rate", "the applicable corporate tax rate under the Income Tax Act, 1961"),
        ("Encumbrance", "any lien, charge, pledge, mortgage, or security interest"),
        ("Event of Default", "any event specified in Article 11 as constituting a default"),
        ("Excluded Claims", "claims for gross negligence, wilful misconduct, fraud, or IP infringement"),
        ("Exclusivity Period", "the period during which exclusivity obligations apply"),
        ("Extension Option", "the Lessee's option to extend the Lease Term"),
        ("Final Acceptance", "acceptance of all Deliverables upon successful UAT"),
        ("Financial Year", "the financial year as defined under the Companies Act, 2013"),
        ("Fixed Fee", "the fixed fee component of the Fees"),
        ("Force Majeure Notice", "written notice of Force Majeure within seven (7) days"),
        ("Go-Live", "the deployment of the System to production"),
        ("Good Industry Practice", "the exercise of that degree of skill and care expected of a reasonably prudent operator in India"),
        ("Governance Matters", "matters requiring approval of the Board or shareholders under the Companies Act, 2013"),
        ("GST", "goods and services tax under the CGST Act, SGST Act, and IGST Act"),
        ("Indemnity Cap", "the aggregate cap on indemnification obligations"),
        ("Initial Term", "the initial period of the Agreement"),
        ("Insolvency Event", "winding up, insolvency, or appointment of resolution professional under the IBC"),
        ("Insurances", "the insurance policies required to be maintained under this Agreement"),
        ("Key Person", "the individual(s) designated as key personnel"),
        ("Knowledge", "actual knowledge after reasonable inquiry of directors and senior officers"),
        ("Material Breach", "a breach that materially impairs the benefit of this Agreement"),
        ("Milestone", "a phase or stage gate upon completion of which payment becomes due"),
        ("Minimum Guarantee", "the minimum amount guaranteed under the revenue share arrangement"),
        ("Negotiation Period", "thirty (30) days for good faith negotiation of disputes"),
        ("Non-Compete Period", "the period during which non-compete obligations apply"),
        ("Notice Period", "the period of notice required for termination"),
        ("Permitted Use", "the use permitted under the Lease or Licence"),
        ("Personnel", "employees, contractors, and agents"),
        ("Pre-Approved Budget", "budget items pre-approved in the annual budget"),
        ("Prevailing Rate", "the rate of interest prevailing for similar transactions in India"),
        ("Project", "the project described in the Scope of Work"),
        ("Pro Rata", "proportionally based on time or usage"),
        ("Qualified Majority", "approval by holders of at least seventy-five percent (75%) of the voting rights"),
        ("Quarter", "each three (3) month period ending 31 March, 30 June, 30 September, 31 December"),
        ("Related Party", "as defined under the Companies Act, 2013 and applicable accounting standards"),
        ("Relevant Authority", "the Governmental Authority having jurisdiction over the matter"),
        ("Relief", "injunction, specific performance, or other equitable relief"),
        ("Renewal Term", "any extension of the Initial Term"),
        ("Representations", "the representations and warranties in Article 7"),
        ("Retention", "the amount retained until Final Acceptance"),
        ("Scope", "the scope of work or services as specified in the Schedules"),
        ("Security", "the security deposit or bank guarantee required under this Agreement"),
        ("Services", "the services to be performed under this Agreement"),
        ("Specifications", "the functional and technical specifications for the Deliverables"),
        ("Steering Committee", "the committee constituted for governance of the Project or Venture"),
        ("Sub-Project", "a discrete component of the Project"),
        ("System", "the integrated system comprising the Deliverables"),
        ("Tax Authority", "the Income Tax Department, GST authorities, or other tax authority in India"),
        ("Territory", "the Republic of India"),
        ("Third Party", "any Person other than the Parties and their Affiliates"),
        ("Timeline", "the project timeline as specified in the Schedules"),
        ("Venture", "the joint venture company or project"),
        ("Warranty Period", "the period during which warranty obligations apply"),
        ("Working Capital", "current assets minus current liabilities"),
        ("Work Product", "all deliverables, code, and materials created in performing the Services"),
    ]
    for term, defn in extra[:count - len(DEFINITIONS_BASE)]:
        if term not in seen:
            lines.append(p(f'<strong>"{term}"</strong> means {defn}.'))
    lines.append(h3("1.2 Interpretation"))
    interp = """In this Agreement, unless the context otherwise requires: (a) headings are for convenience only and shall not affect interpretation; 
(b) words importing the singular include the plural and vice versa; (c) "include", "includes", and "including" are without limitation; 
(d) references to "Articles", "Schedules", and "Annexures" are to this Agreement; (e) "days" means calendar days unless "Business Days" is specified; 
(f) references to any statute include all amendments, re-enactments, consolidations, and subordinate legislation; 
(g) "writing" includes email where acknowledged by the recipient; (h) "or" is not necessarily exclusive; 
(i) "knowledge" of a Party means the actual knowledge of its directors and officers after reasonable inquiry; 
(j) "material" and "materially" connote significance in the context of the affected obligation or representation; 
(k) "reasonable" means reasonable in the circumstances of a prudent person in the same industry in India; 
(l) "prudent" means exercising the care, skill, and diligence expected of an experienced professional; 
(m) references to "₹" or "Rupees" are to Indian Rupees; (n) references to "percent" or "%" are to percentage; 
(o) "herein", "hereunder", "hereby", and similar expressions refer to this Agreement; 
(p) no rule of construction against the drafter shall apply; (q) in case of conflict between the main body and Schedules, the main body shall prevail."""
    lines.append(p(interp))
    return lines


def gen_india_compliance_section():
    """Long India-specific compliance section."""
    lines = []
    lines.append(h2("ARTICLE — REGULATORY COMPLIANCE AND INDIAN LAW"))
    lines.append(h3("Applicable Statutes"))
    lines.append(p("The Parties acknowledge that this Agreement shall be governed by and construed in accordance with the laws of India. Without limiting the foregoing, the Parties shall comply with the following enactments and regulations, as amended from time to time:"))
    for i, statute in enumerate(INDIA_STATUTES):
        lines.append(p(f"({chr(97+i)}) The {statute};"))
    lines.append(h3("Reserve Bank of India"))
    lines.append(p("Where either Party or the subject matter of this Agreement falls within the regulatory purview of the Reserve Bank of India (RBI), the Parties shall comply with all applicable RBI guidelines, circulars, directions, and master directions, including but not limited to: guidelines on digital lending, technology risk management, cybersecurity framework, outsourcing arrangements, and know-your-customer (KYC) norms. Any change in RBI regulations during the Term that materially affects the Parties' obligations shall be notified promptly, and the Parties shall negotiate in good faith to amend this Agreement to ensure continued compliance."))
    lines.append(h3("SEBI and Securities Law"))
    lines.append(p("Where the subject matter involves listed entities, securities, or capital markets, the Parties shall comply with the Securities and Exchange Board of India (SEBI) Act, 1992, the Securities Contract (Regulation) Act, 1956, the SEBI (Listing Obligations and Disclosure Requirements) Regulations, 2015, the SEBI (Prohibition of Insider Trading) Regulations, 2015, and all applicable SEBI circulars and guidelines. Insider trading, front-running, and market manipulation are strictly prohibited. The Parties shall ensure timely disclosure of material events as required under the listing regulations."))
    lines.append(h3("Foreign Exchange Management"))
    lines.append(p("All transactions involving foreign exchange, external commercial borrowings, foreign direct investment, or outward remittances shall comply with the Foreign Exchange Management Act, 1999 (FEMA) and the rules, regulations, and master directions issued by the Reserve Bank of India thereunder. Prior approval or reporting to the RBI shall be obtained or made where required. The Parties shall maintain proper documentation for all foreign exchange transactions."))
    lines.append(h3("Data Protection"))
    lines.append(p("The Parties shall comply with the Digital Personal Data Protection Act, 2023 (DPDP Act) and rules thereunder, and the Information Technology (Reasonable Security Practices and Procedures and Sensitive Personal Data or Information) Rules, 2011. Personal data shall be processed only for lawful purposes, with consent where required, and with appropriate technical and organisational measures to ensure security. Data localisation requirements under applicable law shall be observed. The Parties shall enter into such data processing agreements as may be required for compliance."))
    lines.append(h3("Anti-Money Laundering"))
    lines.append(p("The Parties shall comply with the Prevention of Money Laundering Act, 2002 (PMLA) and the rules, guidelines, and directions issued by the Financial Intelligence Unit (FIU) and other relevant authorities. Know-your-customer (KYC) and customer due diligence (CDD) shall be conducted as required. Suspicious transaction reporting and record-keeping obligations shall be fulfilled. The Parties shall not engage in or facilitate money laundering or financing of terrorism."))
    lines.append(h3("Competition Law"))
    lines.append(p("The Parties shall comply with the Competition Act, 2002 and the orders and regulations of the Competition Commission of India (CCI). No Party shall engage in any anti-competitive agreement, abuse of dominant position, or combination that causes or is likely to cause an appreciable adverse effect on competition in India. Where a combination is subject to CCI approval, the Parties shall obtain such approval before implementation."))
    lines.append(h3("Insolvency"))
    lines.append(p("In the event of insolvency or corporate insolvency resolution process under the Insolvency and Bankruptcy Code, 2016 (IBC), the rights and obligations of the Parties shall be subject to the provisions of the IBC and the Insolvency and Bankruptcy Board of India regulations. No Clause of this Agreement shall operate to override the waterfall of distribution under the IBC or the moratorium provisions."))
    return lines


def gen_arbitration_section():
    """Detailed India arbitration clause."""
    lines = []
    lines.append(h2("ARTICLE — DISPUTE RESOLUTION AND ARBITRATION"))
    lines.append(h3("Escalation"))
    lines.append(p("Any Dispute shall first be referred to the designated senior executives of each Party, who shall use good faith efforts to resolve the Dispute within thirty (30) days. If the Dispute is not resolved within such period, either Party may refer the Dispute to arbitration in accordance with this Article."))
    lines.append(h3("Arbitration"))
    lines.append(p("Any Dispute that cannot be resolved by escalation shall be finally resolved by arbitration in accordance with the Arbitration and Conciliation Act, 1996 (as amended). The seat of arbitration shall be [Mumbai/New Delhi/Bangalore], India. The arbitral tribunal shall consist of three (3) arbitrators, with each Party appointing one arbitrator and the two party-appointed arbitrators appointing the presiding arbitrator. If the two party-appointed arbitrators fail to agree on the presiding arbitrator within thirty (30) days, the presiding arbitrator shall be appointed by [the ICC/the LCIA India/the Delhi International Arbitration Centre] in accordance with its rules."))
    lines.append(p("The arbitration shall be conducted in the English language. The arbitral tribunal shall determine the rules of procedure, subject to the mandatory provisions of the Arbitration and Conciliation Act, 1996. The tribunal shall have the power to grant interim relief, including injunctions and specific performance. The arbitral award shall be final and binding on the Parties. The Parties irrevocably waive any right to appeal to any court, except as permitted under the Arbitration and Conciliation Act, 1996."))
    lines.append(h3("Confidentiality of Arbitration"))
    lines.append(p("The arbitration proceedings and the arbitral award shall be kept confidential, except to the extent necessary for enforcement or as required by law. The Parties shall not disclose the existence, content, or result of the arbitration to any Third Party without the prior written consent of the other Party, unless required by law or regulation."))
    lines.append(h3("Costs"))
    lines.append(p("The arbitral tribunal shall have the discretion to allocate the costs of the arbitration, including the fees of the tribunal and administrative fees, between the Parties. Unless the tribunal orders otherwise, each Party shall bear its own legal costs. The tribunal may award costs to the prevailing Party in such proportion as it deems fit."))
    return lines


def gen_recitals_long(doc_type):
    """Lengthy recitals."""
    lines = []
    lines.append(h2("RECITALS"))
    base = [
        "WHEREAS the Parties are desirous of entering into a commercially prudent arrangement that reflects the allocation of risks and rewards appropriate to their respective roles and responsibilities;",
        "WHEREAS the Parties have each had the opportunity to obtain independent legal, tax, and financial advice and have entered into this Agreement with full knowledge of its terms;",
        "WHEREAS the Parties acknowledge that this Agreement has been negotiated at arm's length and represents the entire understanding of the Parties with respect to the subject matter herein;",
        "WHEREAS the Parties intend that this Agreement shall be binding and enforceable in accordance with the laws of India, including the Indian Contract Act, 1872;",
    ]
    for r in base:
        lines.append(p(r))
    if "software" in doc_type.lower() or "technology" in doc_type.lower():
        lines.append(p("WHEREAS the Client requires a sophisticated technology solution that complies with the regulatory framework applicable to the financial services sector in India, including the guidelines issued by the Reserve Bank of India and the Securities and Exchange Board of India;"))
        lines.append(p("WHEREAS the Developer possesses the technical expertise, infrastructure, and personnel necessary to design, develop, and deliver the said solution in accordance with industry best practices and applicable law;"))
    if "lease" in doc_type.lower() or "real estate" in doc_type.lower():
        lines.append(p("WHEREAS the Lessor is the lawful owner of the Premises and is desirous of leasing the same for commercial use in accordance with the Transfer of Property Act, 1882;"))
        lines.append(p("WHEREAS the Lessee has inspected the Premises and wishes to take the same on lease for the Permitted Use, subject to the terms and conditions set forth herein;"))
    if "joint venture" in doc_type.lower() or "shareholder" in doc_type.lower():
        lines.append(p("WHEREAS the Parties wish to establish a joint venture in accordance with the Companies Act, 2013 and applicable corporate and regulatory requirements;"))
        lines.append(p("WHEREAS the Parties have agreed upon the governance structure, capital contribution, and profit-sharing arrangements for the Venture;"))
    lines.append(p("NOW, THEREFORE, in consideration of the mutual covenants, representations, warranties, and agreements contained herein, and for other good and valuable consideration, the receipt and sufficiency of which are hereby acknowledged, the Parties agree as follows:"))
    return lines


def gen_schedule(name, items_count=150):
    """Generate a schedule with many clauses - senior partner style."""
    lines = []
    lines.append(h2(f"SCHEDULE — {name.upper()}"))
    subj = ["regulatory compliance", "risk allocation", "governance standards", "audit requirements",
            "reporting obligations", "escalation protocols", "change management", "stakeholder engagement",
            "document retention", "conflict resolution", "insurance adequacy", "indemnity triggers"]
    for i in range(1, items_count + 1):
        lines.append(h3(f"Item {i}"))
        s = subj[i % len(subj)]
        lines.append(p(f"In respect of {s}, the Parties (acting through their duly authorised representatives) record their mutual understanding as follows: (a) all obligations shall be performed in accordance with the laws of India, including the {INDIA_STATUTES[i % len(INDIA_STATUTES)]}; (b) the Parties shall act in good faith and deal fairly with each other, consistent with the expectations of experienced commercial parties in similar transactions; (c) any material deviation or anticipated shortfall shall be notified in writing within five (5) Business Days; (d) records sufficient to demonstrate compliance shall be maintained for at least seven (7) years or such longer period as may be required by law; (e) the Parties shall cooperate in connection with any regulatory inquiry, audit, or inspection. This Item forms an integral part of this Agreement and shall be interpreted consistently with the main body. Amendments require prior written consent. Disputes shall be resolved per the arbitration clause."))
    return lines


def gen_annexure(name, items_count=100):
    """Generate an annexure."""
    lines = []
    lines.append(h2(f"ANNEXURE — {name.upper()}"))
    for i in range(1, items_count + 1):
        lines.append(p(f"<strong>{i}.</strong> The parties further agree that {name} shall include, without limitation: (i) such specifications, formats, and standards as are customary in the industry in India; (ii) such additional terms as may be agreed in writing from time to time; (iii) compliance with the regulatory requirements of the relevant sectoral regulators; (iv) implementation of adequate internal controls and audit trails; (v) provision of periodic status reports and certifications as may be required. This item is subject to the dispute resolution provisions in Article [X] and shall be interpreted in accordance with the laws of India."))
    return lines


def expand_article(title, num_clauses=60):
    """Expand an article with many sub-clauses - senior partner drafting."""
    lines = []
    lines.append(h2(f"ARTICLE — {title.upper()}"))
    prov = [
        "Each Party warrants that it has the requisite power and authority to enter into and perform this Agreement.",
        "All payments shall be made in Indian Rupees (INR) unless otherwise agreed, free of set-off or counterclaim.",
        "The Parties shall comply with the anti-corruption provisions of the Prevention of Corruption Act, 1988 and the PMLA.",
        "No Party shall make any payment or gift to any Governmental Authority or public official in violation of applicable law.",
        "Confidential Information shall be protected with the same degree of care as the receiving Party accords its own confidential information, but no less than reasonable care.",
        "Upon termination, each Party shall return or destroy the other Party's Confidential Information as directed.",
        "Indemnification obligations shall survive termination for a period of six (6) years or the applicable limitation period, whichever is longer.",
        "Liability shall be capped at the aggregate fees paid or payable in the twelve (12) months preceding the claim, subject to Excluded Claims.",
        "Neither Party shall be liable for indirect, consequential, or punitive damages, except where such exclusion is prohibited by law.",
        "Force Majeure shall not excuse payment obligations; the Affected Party shall use reasonable efforts to mitigate.",
        "Any waiver must be in writing; no waiver of one breach shall constitute a waiver of any other breach.",
        "This Agreement constitutes the entire agreement and supersedes all prior negotiations and understandings.",
    ]
    for i in range(1, num_clauses + 1):
        lines.append(h3(f"Clause {i}"))
        ptext = prov[i % len(prov)] + " "
        ptext += f"Furthermore, in relation to {title}: (a) the Parties shall use commercially reasonable efforts consistent with Good Industry Practice; (b) material deviations shall be notified in writing within five (5) Business Days; (c) the Parties shall cooperate in good faith; (d) interpretation shall be consistent with the Indian Contract Act, 1872 and applicable Indian law; (e) amendments require prior written consent; (f) regulatory changes in India (including RBI, SEBI, MCA notifications) may necessitate good-faith renegotiation of affected terms; (g) disputes shall be resolved per the arbitration provisions. The Parties acknowledge having been advised to obtain independent legal advice and have either done so or waived such advice."
        lines.append(p(ptext))
    return lines


def build_doc(doc_id, title, doc_type, target_lines=10000):
    """Build a document with approximately target_lines lines."""
    lines = []
    lines.append(f"<h1>{title}</h1>\n")
    lines.append(p(f"<strong>Document Reference:</strong> {doc_id}<br><strong>Date:</strong> 1 March 2025<br><strong>Governing Law:</strong> Laws of India<br><strong>Jurisdiction:</strong> Courts at [Mumbai/Bangalore/Delhi]"))
    lines.append(h2("PARTIES"))
    lines.append(p("This Agreement is entered into between the parties as set out in the signature block below, each a 'Party' and collectively the 'Parties'. Each Party represents that it has the full power and authority to enter into and perform this Agreement."))
    lines.extend(gen_recitals_long(doc_type))
    lines.extend(gen_definitions(85))
    
    # Core articles
    for art in ["SCOPE OF WORK AND DELIVERABLES", "FEES, PAYMENT, AND TAXES", "REPRESENTATIONS AND WARRANTIES",
                "CONFIDENTIALITY", "INTELLECTUAL PROPERTY", "INDEMNIFICATION", "LIMITATION OF LIABILITY",
                "TERM AND TERMINATION", "GENERAL PROVISIONS"]:
        lines.extend(expand_article(art, 45))
    
    lines.extend(gen_india_compliance_section())
    lines.extend(gen_arbitration_section())
    
    # Schedules (each adds ~150+ lines)
    for sched in ["TECHNICAL SPECIFICATIONS", "MILESTONES AND PAYMENT", "ACCEPTANCE CRITERIA",
                  "SERVICE LEVELS", "DATA PROCESSING", "INSURANCE REQUIREMENTS",
                  "ESCALATION MATRIX", "CHANGE REQUEST PROCESS"]:
        lines.extend(gen_schedule(sched, 120))
    
    # Annexures
    for annex in ["DEFINITIONS SUPPLEMENT", "FORM OF NOTICE", "CERTIFICATIONS",
                  "REGULATORY CHECKLIST", "RISK ALLOCATION MATRIX"]:
        lines.extend(gen_annexure(annex, 100))
    
    # Additional expansion to reach ~10000 lines
    while len("".join(lines).split("\n")) < target_lines:
        lines.extend(gen_schedule(f"ADDENDUM {len(lines) % 10}", 80))
    
    return html_doc(title, lines)


def main():
    docs = [
        ("LONG-01", "Comprehensive Software Development and Technology Licensing Agreement", "software"),
        ("LONG-02", "Commercial Real Estate Lease and Development Agreement", "lease real estate"),
        ("LONG-03", "Joint Venture and Shareholders Agreement", "joint venture shareholder"),
    ]
    for doc_id, title, dtype in docs:
        content = build_doc(doc_id, title, dtype, target_lines=10000)
        fname = OUT_DIR / f"{doc_id}-{title.lower().replace(' ', '-')[:50]}.html"
        fname = OUT_DIR / f"{doc_id}.html"  # simpler name
        fname.write_text(content, encoding="utf-8")
        line_count = len(content.split("\n"))
        print(f"Generated {fname.name}: {line_count} lines")
    print(f"Output: {OUT_DIR}")


if __name__ == "__main__":
    main()
