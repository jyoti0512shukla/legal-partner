#!/bin/bash
# Convert contract HTML files to DOCX and PDF using pandoc.
#
# Requires: pandoc, wkhtmltopdf (for PDF)
#   Mac:    brew install pandoc wkhtmltopdf
#   Ubuntu: sudo apt install pandoc wkhtmltopdf
#
# Usage:
#   ./convert-contracts.sh              # 01,02,03 -> PDF; 04,05,06 -> DOCX
#   ./convert-contracts.sh --pdf 01 02 03
#   ./convert-contracts.sh --doc 01 02 03
#   ./convert-contracts.sh --all        # All 10 to both formats

set -e
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DATA_DIR="$(dirname "$SCRIPT_DIR")/data"
OUT_DIR="$DATA_DIR/converted"
mkdir -p "$OUT_DIR"

to_pdf() {
  for prefix in "$@"; do
    for f in "$DATA_DIR"/${prefix}-*.html; do
      [ -f "$f" ] || continue
      base=$(basename "$f" .html)
      echo "  $base.html -> $base.pdf"
      pandoc "$f" -f html -o "$OUT_DIR/$base.pdf" \
        --pdf-engine=wkhtmltopdf \
        -V papersize=a4 -V margin-top=2cm -V margin-bottom=2cm
    done
  done
}

to_docx() {
  for prefix in "$@"; do
    for f in "$DATA_DIR"/${prefix}-*.html; do
      [ -f "$f" ] || continue
      base=$(basename "$f" .html)
      echo "  $base.html -> $base.docx"
      pandoc "$f" -f html -o "$OUT_DIR/$base.docx"
    done
  done
}

case "${1:-}" in
  --all)
    echo "Converting all to PDF..."
    to_pdf 01 02 03 04 05 06 07 08 09 10
    echo "Converting all to DOCX..."
    to_docx 01 02 03 04 05 06 07 08 09 10
    ;;
  --pdf)
    shift
    echo "Converting to PDF: $*"
    to_pdf "$@"
    ;;
  --doc)
    shift
    echo "Converting to DOCX: $*"
    to_docx "$@"
    ;;
  *)
    echo "Converting 01,02,03 to PDF..."
    to_pdf 01 02 03
    echo "Converting 04,05,06 to DOCX..."
    to_docx 04 05 06
    ;;
esac

echo ""
echo "Output: $OUT_DIR"
