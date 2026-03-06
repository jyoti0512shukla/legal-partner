#!/usr/bin/env python3
"""
Convert contract HTML files to DOCX and PDF formats.

Usage:
  python convert-contracts.py                    # Convert default 3 files to each format
  python convert-contracts.py --pdf 01 02 03     # Convert 01,02,03 to PDF
  python convert-contracts.py --doc 04 05 06     # Convert 04,05,06 to DOCX
  python convert-contracts.py --all              # Convert all 10 files to both formats

Dependencies:
  pip install -r scripts/requirements-convert.txt
  For PDF: weasyprint (or install wkhtmltopdf for pandoc)
  For DOCX: pandoc (brew install pandoc / apt install pandoc)
"""

import argparse
import os
import subprocess
import sys
from pathlib import Path

# Paths
SCRIPT_DIR = Path(__file__).resolve().parent
REPO_ROOT = SCRIPT_DIR.parent
DATA_DIR = REPO_ROOT / "data"
OUTPUT_DIR = REPO_ROOT / "data" / "converted"

# Default: 3 to PDF, 3 to DOCX (01,02,03 each)
DEFAULT_PDF = ["01", "02", "03"]
DEFAULT_DOC = ["04", "05", "06"]


def get_html_path(prefix: str) -> Path:
    """Find HTML file by prefix (e.g. '01' -> 01-software-development-agreement.html)."""
    pattern = f"{prefix}-*.html"
    matches = list(DATA_DIR.glob(pattern))
    if not matches:
        raise FileNotFoundError(f"No file matching {pattern} in {DATA_DIR}")
    return matches[0]


def convert_to_pdf_weasyprint(html_path: Path, pdf_path: Path) -> bool:
    """Convert HTML to PDF using weasyprint."""
    try:
        from weasyprint import HTML, CSS

        html = HTML(filename=str(html_path))
        # Basic styling for better PDF output
        style = CSS(string="""
            @page { margin: 2cm; size: A4; }
            body { font-family: "Times New Roman", serif; font-size: 11pt; line-height: 1.4; }
            h1 { font-size: 18pt; page-break-after: avoid; }
            h2 { font-size: 14pt; page-break-after: avoid; }
            h3 { font-size: 12pt; page-break-after: avoid; }
            table { border-collapse: collapse; margin: 1em 0; }
            th, td { border: 1px solid #333; padding: 4px 8px; }
        """)
        html.write_pdf(str(pdf_path), stylesheets=[style])
        return True
    except ImportError:
        return False
    except Exception as e:
        print(f"  WeasyPrint error: {e}", file=sys.stderr)
        return False


def convert_to_pdf_pdfkit(html_path: Path, pdf_path: Path) -> bool:
    """Convert HTML to PDF using pdfkit (requires wkhtmltopdf)."""
    try:
        import pdfkit
        pdfkit.from_file(str(html_path), str(pdf_path), options={"quiet": ""})
        return True
    except ImportError:
        return False
    except OSError:  # wkhtmltopdf not found
        return False
    except Exception as e:
        print(f"  pdfkit error: {e}", file=sys.stderr)
        return False


def convert_to_pdf_pandoc(html_path: Path, pdf_path: Path) -> bool:
    """Convert HTML to PDF using pandoc (requires wkhtmltopdf or similar)."""
    try:
        result = subprocess.run(
            [
                "pandoc", str(html_path),
                "-f", "html",
                "-o", str(pdf_path),
                "--pdf-engine=wkhtmltopdf",
                "--variable", "papersize=a4",
                "--variable", "margin-top=2cm",
                "--variable", "margin-bottom=2cm",
            ],
            capture_output=True,
            text=True,
        )
        if result.returncode != 0:
            print(f"  Pandoc stderr: {result.stderr}", file=sys.stderr)
            return False
        return True
    except FileNotFoundError:
        return False


def convert_to_pdf(html_path: Path, pdf_path: Path) -> bool:
    """Convert HTML to PDF. Tries weasyprint, then pdfkit, then pandoc."""
    if convert_to_pdf_weasyprint(html_path, pdf_path):
        return True
    if convert_to_pdf_pdfkit(html_path, pdf_path):
        return True
    if convert_to_pdf_pandoc(html_path, pdf_path):
        return True
    return False


def convert_to_docx(html_path: Path, docx_path: Path) -> bool:
    """Convert HTML to DOCX using pandoc."""
    try:
        result = subprocess.run(
            [
                "pandoc", str(html_path),
                "-f", "html",
                "-o", str(docx_path),
                "--from", "html+native_divs+native_spans",
            ],
            capture_output=True,
            text=True,
        )
        if result.returncode != 0:
            print(f"  Pandoc stderr: {result.stderr}", file=sys.stderr)
            return False
        return True
    except FileNotFoundError:
        print("  Pandoc not found. Install: brew install pandoc (Mac) or apt install pandoc (Linux)", file=sys.stderr)
        return False


def main():
    parser = argparse.ArgumentParser(description="Convert contract HTML files to DOCX and PDF")
    parser.add_argument(
        "--pdf", nargs="*", metavar="NN",
        help="Convert these files to PDF (e.g. 01 02 03). Default: 01 02 03",
        default=None,
    )
    parser.add_argument(
        "--doc", nargs="*", metavar="NN",
        help="Convert these files to DOCX (e.g. 04 05 06). Default: 04 05 06",
        default=None,
    )
    parser.add_argument(
        "--all", action="store_true",
        help="Convert all 10 contract files to both PDF and DOCX",
    )
    parser.add_argument(
        "--output", "-o", type=Path, default=OUTPUT_DIR,
        help=f"Output directory (default: {OUTPUT_DIR})",
    )
    parser.add_argument(
        "--long-docs", action="store_true",
        help="Convert the 3 long-doc HTML files (LONG-01, LONG-02, LONG-03) from data/long-docs/ to PDF",
    )
    args = parser.parse_args()

    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)

    if args.long_docs:
        long_dir = REPO_ROOT / "data" / "long-docs"
        out_dir = args.output
        if not long_dir.exists():
            print(f"Directory not found: {long_dir}")
            return 1
        pdf_ok = True
        print("Converting long docs to PDF...")
        for f in sorted(long_dir.glob("LONG-*.html")):
            pdf_path = out_dir / f"{f.stem}.pdf"
            print(f"  {f.name} -> {pdf_path.name} ...", end=" ")
            if convert_to_pdf(f, pdf_path):
                print("OK")
            else:
                print("FAILED")
                pdf_ok = False
        print(f"\nOutput: {out_dir}")
        return 0 if pdf_ok else 1

    if args.all:
        all_files = [f.stem.split("-")[0] for f in DATA_DIR.glob("*-*.html")]
        all_files = sorted(set(all_files))
        to_pdf = all_files
        to_doc = all_files
    else:
        to_pdf = args.pdf if args.pdf is not None else DEFAULT_PDF
        to_doc = args.doc if args.doc is not None else DEFAULT_DOC

    pdf_ok = True
    doc_ok = True

    if to_pdf:
        print("Converting to PDF...")
        for prefix in to_pdf:
            try:
                html_path = get_html_path(prefix)
                pdf_path = OUTPUT_DIR / f"{html_path.stem}.pdf"
                print(f"  {html_path.name} -> {pdf_path.name} ...", end=" ")
                if convert_to_pdf(html_path, pdf_path):
                    print("OK")
                else:
                    print("FAILED (install weasyprint: pip install weasyprint, or pandoc+wkhtmltopdf)")
                    pdf_ok = False
            except FileNotFoundError as e:
                print(f"  SKIP: {e}")
                pdf_ok = False

    if to_doc:
        print("\nConverting to DOCX...")
        for prefix in to_doc:
            try:
                html_path = get_html_path(prefix)
                docx_path = OUTPUT_DIR / f"{html_path.stem}.docx"
                print(f"  {html_path.name} -> {docx_path.name} ...", end=" ")
                if convert_to_docx(html_path, docx_path):
                    print("OK")
                else:
                    print("FAILED (install pandoc: brew install pandoc)")
                    doc_ok = False
            except FileNotFoundError as e:
                print(f"  SKIP: {e}")
                doc_ok = False

    print(f"\nOutput: {OUTPUT_DIR}")
    return 0 if (pdf_ok and doc_ok) else 1


if __name__ == "__main__":
    sys.exit(main())
