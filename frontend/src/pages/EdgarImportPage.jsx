import { useState, useEffect } from 'react';
import { Search, Download, CheckSquare, Square, Loader, ExternalLink, Building2, Calendar, FileText, AlertTriangle, CheckCircle } from 'lucide-react';
import api from '../api/client';

const INDUSTRIES = ['', 'IT_SERVICES', 'FINTECH', 'PHARMA', 'MANUFACTURING', 'GENERAL'];
const CONTRACT_TYPES = ['MSA', 'NDA', 'SOW', 'EMPLOYMENT', 'VENDOR', 'OTHER'];
const PRACTICE_AREAS = ['CORPORATE', 'IP', 'TAX', 'LABOR', 'BANKING', 'OTHER'];

const PRESET_LABELS = {
  IT_SERVICES_MSA:  'IT Services MSA',
  SAAS_AGREEMENT:   'SaaS / Subscription',
  NDA:              'Non-Disclosure (NDA)',
  SOFTWARE_LICENSE: 'Software License',
  VENDOR_AGREEMENT: 'Vendor Agreement',
  FINTECH_MSA:      'Fintech MSA',
  PHARMA_SERVICES:  'Pharma / Clinical',
  MANUFACTURING:    'Supply / Manufacturing',
  EMPLOYMENT:       'Executive Employment',
  IP_LICENSE:       'IP License',
};

const PRESET_DEFAULTS = {
  IT_SERVICES_MSA:  { contractType: 'MSA',        industry: 'IT_SERVICES', practiceArea: 'CORPORATE' },
  SAAS_AGREEMENT:   { contractType: 'MSA',        industry: 'IT_SERVICES', practiceArea: 'CORPORATE' },
  NDA:              { contractType: 'NDA',        industry: '',            practiceArea: 'CORPORATE' },
  SOFTWARE_LICENSE: { contractType: 'MSA',        industry: 'IT_SERVICES', practiceArea: 'IP' },
  VENDOR_AGREEMENT: { contractType: 'VENDOR',     industry: '',            practiceArea: 'CORPORATE' },
  FINTECH_MSA:      { contractType: 'MSA',        industry: 'FINTECH',     practiceArea: 'BANKING' },
  PHARMA_SERVICES:  { contractType: 'MSA',        industry: 'PHARMA',      practiceArea: 'CORPORATE' },
  MANUFACTURING:    { contractType: 'VENDOR',     industry: 'MANUFACTURING',practiceArea: 'CORPORATE' },
  EMPLOYMENT:       { contractType: 'EMPLOYMENT', industry: '',            practiceArea: 'LABOR' },
  IP_LICENSE:       { contractType: 'MSA',        industry: '',            practiceArea: 'IP' },
};

export default function EdgarImportPage() {
  const [presets, setPresets] = useState({});
  const [selectedPreset, setSelectedPreset] = useState('');
  const [customQuery, setCustomQuery] = useState('');
  const [maxResults, setMaxResults] = useState(20);
  const [contractType, setContractType] = useState('MSA');
  const [industry, setIndustry] = useState('');
  const [practiceArea, setPracticeArea] = useState('CORPORATE');

  const [searching, setSearching] = useState(false);
  const [results, setResults] = useState(null);
  const [searchError, setSearchError] = useState('');

  const [selected, setSelected] = useState(new Set());
  const [importing, setImporting] = useState(false);
  const [importResults, setImportResults] = useState(null);

  useEffect(() => {
    api.get('/edgar/presets').then(r => setPresets(r.data || {})).catch(() => {});
  }, []);

  const handlePresetClick = (key) => {
    setSelectedPreset(key);
    setCustomQuery('');
    const defaults = PRESET_DEFAULTS[key] || {};
    if (defaults.contractType) setContractType(defaults.contractType);
    if (defaults.industry !== undefined) setIndustry(defaults.industry);
    if (defaults.practiceArea) setPracticeArea(defaults.practiceArea);
  };

  const handleSearch = async () => {
    setSearching(true);
    setSearchError('');
    setResults(null);
    setSelected(new Set());
    setImportResults(null);
    try {
      const payload = { maxResults };
      if (customQuery.trim()) {
        payload.query = customQuery.trim();
      } else {
        payload.preset = selectedPreset;
      }
      const r = await api.post('/edgar/search', payload);
      setResults(r.data || []);
    } catch (e) {
      setSearchError(e.response?.data?.message || e.message || 'Search failed — EDGAR may be unavailable');
    } finally {
      setSearching(false);
    }
  };

  const toggleSelect = (id) => {
    setSelected(prev => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id); else next.add(id);
      return next;
    });
  };

  const toggleAll = () => {
    if (!results) return;
    if (selected.size === results.length) {
      setSelected(new Set());
    } else {
      setSelected(new Set(results.map(r => r.docId)));
    }
  };

  const handleImport = async () => {
    if (!results || selected.size === 0) return;
    setImporting(true);
    setImportResults(null);
    try {
      const selectedResults = results.filter(r => selected.has(r.docId));
      const docIdToUrl = {};
      const docIdToEntity = {};
      selectedResults.forEach(r => {
        docIdToUrl[r.docId] = r.documentUrl;
        docIdToEntity[r.docId] = r.entityName;
      });
      const r = await api.post('/edgar/import', {
        docIds: [...selected],
        docIdToUrl,
        docIdToEntity,
        contractType,
        industry: industry || null,
        practiceArea,
      });
      setImportResults(r.data || []);
    } catch (e) {
      setSearchError(e.response?.data?.message || e.message || 'Import failed');
    } finally {
      setImporting(false);
    }
  };

  const successCount = importResults?.filter(r => r.success).length ?? 0;
  const failCount = importResults?.filter(r => !r.success).length ?? 0;

  return (
    <div>
      <div className="mb-6">
        <h1 className="text-2xl font-bold flex items-center gap-2">
          <Download className="w-7 h-7" /> EDGAR Corpus Seeder
        </h1>
        <p className="text-sm text-text-muted mt-1">
          Search SEC EDGAR for real commercial agreements filed by public companies.
          Import them directly into your RAG corpus to improve draft quality.
        </p>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
        {/* Left: search controls */}
        <div className="lg:col-span-1 space-y-5">
          <div className="card">
            <h3 className="font-semibold mb-3 text-sm">Contract Type Presets</h3>
            <div className="flex flex-wrap gap-2">
              {Object.keys(PRESET_LABELS).map(key => (
                <button
                  key={key}
                  onClick={() => handlePresetClick(key)}
                  className={`text-xs px-2.5 py-1.5 rounded-full border transition-colors ${
                    selectedPreset === key
                      ? 'bg-primary text-white border-primary'
                      : 'border-border text-text-secondary hover:border-primary/50 hover:text-text-primary'
                  }`}
                >
                  {PRESET_LABELS[key]}
                </button>
              ))}
            </div>

            <div className="mt-4">
              <label className="text-xs text-text-muted mb-1 block">Or custom query</label>
              <input
                value={customQuery}
                onChange={e => { setCustomQuery(e.target.value); setSelectedPreset(''); }}
                placeholder={`e.g. "cloud services agreement" "data processing"`}
                className="input-field w-full text-sm font-mono"
              />
            </div>
          </div>

          <div className="card space-y-3">
            <h3 className="font-semibold text-sm mb-1">Tag imported docs as</h3>
            <div>
              <label className="text-xs text-text-muted mb-1 block">Contract Type</label>
              <select value={contractType} onChange={e => setContractType(e.target.value)} className="input-field w-full text-sm">
                {CONTRACT_TYPES.map(t => <option key={t} value={t}>{t}</option>)}
              </select>
            </div>
            <div>
              <label className="text-xs text-text-muted mb-1 block">Industry</label>
              <select value={industry} onChange={e => setIndustry(e.target.value)} className="input-field w-full text-sm">
                <option value="">General / Unspecified</option>
                {INDUSTRIES.filter(Boolean).map(i => <option key={i} value={i}>{i.replace('_', ' ')}</option>)}
              </select>
            </div>
            <div>
              <label className="text-xs text-text-muted mb-1 block">Practice Area</label>
              <select value={practiceArea} onChange={e => setPracticeArea(e.target.value)} className="input-field w-full text-sm">
                {PRACTICE_AREAS.map(p => <option key={p} value={p}>{p}</option>)}
              </select>
            </div>
            <div>
              <label className="text-xs text-text-muted mb-1 block">Max results</label>
              <select value={maxResults} onChange={e => setMaxResults(Number(e.target.value))} className="input-field w-full text-sm">
                {[10, 20, 30, 40].map(n => <option key={n} value={n}>{n}</option>)}
              </select>
            </div>

            <button
              onClick={handleSearch}
              disabled={searching || (!selectedPreset && !customQuery.trim())}
              className="btn-primary w-full flex items-center justify-center gap-2 py-2.5 text-sm mt-1"
            >
              {searching
                ? <><Loader className="w-4 h-4 animate-spin" /> Searching EDGAR…</>
                : <><Search className="w-4 h-4" /> Search EDGAR</>}
            </button>
          </div>

          <div className="card bg-surface-el/30 border border-border/50 text-xs text-text-muted space-y-1.5">
            <p className="font-semibold text-text-secondary">How this works</p>
            <p>EDGAR is the SEC's public filing database. Exhibit EX-10.x filings are material contracts — NDAs, MSAs, license agreements — filed by US public companies.</p>
            <p>Selected documents are downloaded and fed through the same indexing pipeline as manually uploaded documents. They appear in <span className="text-text-primary">Documents</span> with source "EDGAR Filing".</p>
            <p>Tags (contract type, industry) are applied at ingestion so RAG retrieval can filter them precisely when drafting.</p>
          </div>
        </div>

        {/* Right: results */}
        <div className="lg:col-span-2 space-y-4">
          {searchError && (
            <div className="card border-l-4 border-danger bg-danger/5 flex items-start gap-2">
              <AlertTriangle className="w-4 h-4 text-danger shrink-0 mt-0.5" />
              <p className="text-sm text-danger">{searchError}</p>
            </div>
          )}

          {importResults && (
            <div className={`card border-l-4 ${failCount > 0 ? 'border-warning' : 'border-success'}`}>
              <div className="flex items-center gap-2 mb-2">
                <CheckCircle className="w-4 h-4 text-success" />
                <span className="font-semibold text-sm">
                  Import complete — {successCount} indexed, {failCount} failed
                </span>
              </div>
              <div className="space-y-1 max-h-40 overflow-y-auto">
                {importResults.map((r, i) => (
                  <div key={i} className={`text-xs flex items-center gap-2 ${r.success ? 'text-text-secondary' : 'text-danger'}`}>
                    {r.success
                      ? <CheckCircle className="w-3 h-3 text-success shrink-0" />
                      : <AlertTriangle className="w-3 h-3 shrink-0" />}
                    <span className="truncate">{r.entityName}</span>
                    {!r.success && <span className="text-text-muted">— {r.error}</span>}
                  </div>
                ))}
              </div>
            </div>
          )}

          {results === null && !searching && (
            <div className="card border-2 border-dashed border-border text-center py-16">
              <Search className="w-10 h-10 text-text-muted mx-auto mb-3" />
              <p className="text-text-muted text-sm">Select a preset or enter a query, then search EDGAR.</p>
              <p className="text-xs text-text-muted mt-1">Results are real SEC filings — publicly available, free to use.</p>
            </div>
          )}

          {results !== null && results.length === 0 && (
            <div className="card text-center py-10">
              <p className="text-text-muted text-sm">No results found. Try a different query or preset.</p>
            </div>
          )}

          {results !== null && results.length > 0 && (
            <div className="card overflow-hidden">
              <div className="flex items-center justify-between mb-3 flex-wrap gap-3">
                <div className="flex items-center gap-3">
                  <button onClick={toggleAll} className="flex items-center gap-1.5 text-xs text-text-muted hover:text-text-primary">
                    {selected.size === results.length
                      ? <CheckSquare className="w-4 h-4 text-primary" />
                      : <Square className="w-4 h-4" />}
                    {selected.size === results.length ? 'Deselect all' : 'Select all'}
                  </button>
                  <span className="text-xs text-text-muted">{results.length} results · {selected.size} selected</span>
                </div>
                {selected.size > 0 && (
                  <button
                    onClick={handleImport}
                    disabled={importing}
                    className="btn-primary text-sm flex items-center gap-2 py-1.5"
                  >
                    {importing
                      ? <><Loader className="w-3.5 h-3.5 animate-spin" /> Importing…</>
                      : <><Download className="w-3.5 h-3.5" /> Import {selected.size} doc{selected.size !== 1 ? 's' : ''}</>}
                  </button>
                )}
              </div>

              <div className="divide-y divide-border/50">
                {results.map(result => {
                  const isSelected = selected.has(result.docId);
                  const importedResult = importResults?.find(r => r.docId === result.docId);
                  return (
                    <div
                      key={result.docId}
                      onClick={() => !importedResult?.success && toggleSelect(result.docId)}
                      className={`flex items-start gap-3 py-3 px-1 transition-colors rounded ${
                        importedResult?.success
                          ? 'opacity-50 cursor-default'
                          : 'cursor-pointer hover:bg-surface-el/50'
                      }`}
                    >
                      <div className="mt-0.5 shrink-0">
                        {importedResult?.success
                          ? <CheckCircle className="w-4 h-4 text-success" />
                          : isSelected
                          ? <CheckSquare className="w-4 h-4 text-primary" />
                          : <Square className="w-4 h-4 text-text-muted" />}
                      </div>
                      <div className="flex-1 min-w-0">
                        <div className="flex items-center gap-2 mb-0.5">
                          <Building2 className="w-3.5 h-3.5 text-text-muted shrink-0" />
                          <span className="text-sm font-medium text-text-primary truncate">{result.entityName}</span>
                          <span className="text-[10px] px-1.5 py-0.5 rounded bg-surface-el text-text-muted border border-border shrink-0">
                            {result.formType}
                          </span>
                        </div>
                        <div className="flex items-center gap-3 text-xs text-text-muted">
                          <span className="flex items-center gap-1"><Calendar className="w-3 h-3" /> {result.fileDate}</span>
                          <span className="flex items-center gap-1"><FileText className="w-3 h-3 shrink-0" /><span className="truncate max-w-[200px]">{result.fileName}</span></span>
                          <a href={result.documentUrl} target="_blank" rel="noreferrer"
                            onClick={e => e.stopPropagation()}
                            className="flex items-center gap-0.5 text-primary hover:underline shrink-0">
                            Preview <ExternalLink className="w-2.5 h-2.5" />
                          </a>
                        </div>
                      </div>
                    </div>
                  );
                })}
              </div>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
