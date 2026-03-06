# Long-Form Legal Documents (India Context)

Three comprehensive legal documents (~10,000 lines each), drafted in a senior-partner style with extensive India-specific detail.

| Document | Description | PDF |
|----------|-------------|-----|
| LONG-01 | Comprehensive Software Development and Technology Licensing Agreement | `../converted/LONG-01.pdf` |
| LONG-02 | Commercial Real Estate Lease and Development Agreement | `../converted/LONG-02.pdf` |
| LONG-03 | Joint Venture and Shareholders Agreement | `../converted/LONG-03.pdf` |

**India context:** References to Indian Contract Act, Companies Act 2013, IT Act, Transfer of Property Act, Arbitration Act, FEMA, RBI, SEBI, PMLA, DPDP Act 2023, RERA, GST, and other Indian statutes.

**Regenerate:** `python3 scripts/generate-long-docs.py`  
**Convert to PDF:** `./scripts/run-convert.sh` (with `--long-docs` support in convert-contracts.py)
