# Contract Conversion Scripts

Convert contract HTML files to **DOCX** (Word) and **PDF** formats.

## Quick Start

### Option 1: Wrapper script (recommended on Mac)

Uses a project venv and WeasyPrint. One command:

```bash
# Install system deps (once)
brew install pandoc pango glib gobject-introspection

# Run (creates venv on first run)
./scripts/run-convert.sh
```

### Option 2: Python script directly

```bash
# Create venv (Homebrew Python is externally managed)
python3 -m venv .venv-convert
source .venv-convert/bin/activate
pip install -r scripts/requirements-convert.txt

# Mac: WeasyPrint needs pango/glib
brew install pandoc pango glib gobject-introspection

# Run (Mac: set lib path for WeasyPrint)
DYLD_LIBRARY_PATH=/opt/homebrew/lib .venv-convert/bin/python scripts/convert-contracts.py
```

### Option 3: Shell script (pandoc + wkhtmltopdf only)

No Python deps. Requires pandoc and wkhtmltopdf.

```bash
brew install pandoc wkhtmltopdf    # Mac
# or: sudo apt install pandoc wkhtmltopdf   # Ubuntu

chmod +x scripts/convert-contracts.sh
./scripts/convert-contracts.sh
```

## Usage

| Command | Result |
|---------|--------|
| `python scripts/convert-contracts.py` | 01,02,03 → PDF; 04,05,06 → DOCX |
| `python scripts/convert-contracts.py --pdf 01 02 03` | Convert 01,02,03 to PDF |
| `python scripts/convert-contracts.py --doc 01 02 03` | Convert 01,02,03 to DOCX |
| `python scripts/convert-contracts.py --all` | All 10 contracts → both formats |
| `python scripts/convert-contracts.py -o ./output` | Custom output directory |

## Output

Files are written to `data/converted/`:

```
data/converted/
├── 01-software-development-agreement.pdf
├── 01-software-development-agreement.docx
├── 02-consulting-services-agreement.pdf
...
```

## Dependencies

| Format | Tool | Install |
|--------|------|---------|
| **PDF** | pdfkit + wkhtmltopdf | `pip install pdfkit` + `brew install wkhtmltopdf` |
| **PDF** | weasyprint | `pip install weasyprint` (+ Mac: `brew install pango glib`) |
| **PDF** | pandoc + wkhtmltopdf | `brew install pandoc wkhtmltopdf` |
| **DOCX** | pandoc | `brew install pandoc` |
