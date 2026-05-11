import { useState, useEffect, useCallback, useRef } from 'react';
import { useSearchParams } from 'react-router-dom';
import { useDropzone } from 'react-dropzone';
import {
  Upload, FileText, Search, X, ChevronLeft, ChevronRight,
  Loader, Cloud, Filter,
} from 'lucide-react';
import api from '../api/client';
import CloudImportModal from '../components/CloudImportModal';
import StatusBadge from '../components/contract/StatusBadge';
import ContractDetailPanel from '../components/contract/ContractDetailPanel';

const STATUS_TABS = [
  { value: '', label: 'All' },
  { value: 'DRAFT', label: 'Draft' },
  { value: 'NEGOTIATING', label: 'Negotiating' },
  { value: 'ACTIVE', label: 'Active' },
  { value: 'EXPIRING', label: 'Expiring' },
  { value: 'PENDING_SIGNATURE', label: 'Pending Sig' },
  { value: 'EXECUTED', label: 'Executed' },
  { value: 'APPROVED', label: 'Approved' },
];

const DOC_TYPES = ['NDA', 'MSA', 'SAAS', 'IP_LICENSE', 'SOW', 'EMPLOYMENT', 'LEASE', 'MOU', 'VENDOR', 'SUPPLY', 'OTHER'];

const SORT_OPTIONS = [
  { value: 'uploadDate,desc', label: 'Newest first' },
  { value: 'uploadDate,asc', label: 'Oldest first' },
  { value: 'fileName,asc', label: 'Name A-Z' },
  { value: 'expiryDate,asc', label: 'Expiry soonest' },
];

export default function DocumentsPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const [docs, setDocs] = useState([]);
  const [totalPages, setTotalPages] = useState(0);
  const [totalElements, setTotalElements] = useState(0);
  const [page, setPage] = useState(0);
  const [search, setSearch] = useState('');
  const [statusFilter, setStatusFilter] = useState('');
  const [typeFilter, setTypeFilter] = useState('');
  const [matterFilter, setMatterFilter] = useState('');
  const [sortField, setSortField] = useState('uploadDate,desc');
  const [selectedDocId, setSelectedDocId] = useState(null);
  const [matters, setMatters] = useState([]);
  const [loading, setLoading] = useState(true);

  // Upload state
  const [showUpload, setShowUpload] = useState(false);
  const [showCloudImport, setShowCloudImport] = useState(false);
  const [selectedFile, setSelectedFile] = useState(null);
  const [uploading, setUploading] = useState(false);
  const [meta, setMeta] = useState({ documentType: '', practiceArea: 'OTHER', clientName: '', matterId: '', jurisdiction: '', industry: '' });

  const debounceRef = useRef(null);

  // Load matters for filter dropdown
  useEffect(() => {
    api.get('/matters').then(r => setMatters(r.data || [])).catch(() => {});
  }, []);

  // Read docId from URL for cross-flow linking
  useEffect(() => {
    const docId = searchParams.get('docId');
    if (docId) setSelectedDocId(docId);
    if (searchParams.get('cloud') === 'connected') {
      setSearchParams({}, { replace: true });
      setShowCloudImport(true);
    }
  }, []);

  // Fetch documents
  const fetchDocs = useCallback(() => {
    setLoading(true);
    const params = new URLSearchParams({ size: '30', sort: sortField, page: String(page) });
    if (search) params.set('search', search);
    if (statusFilter) params.set('contractStatus', statusFilter);
    if (typeFilter) params.set('documentType', typeFilter);
    if (matterFilter) params.set('matterId', matterFilter);
    api.get(`/documents?${params}`)
      .then(r => {
        setDocs(r.data.content || []);
        setTotalPages(r.data.totalPages || 0);
        setTotalElements(r.data.totalElements || 0);
      })
      .catch(() => {})
      .finally(() => setLoading(false));
  }, [search, statusFilter, typeFilter, matterFilter, sortField, page]);

  useEffect(() => { fetchDocs(); }, [fetchDocs]);

  // Debounced search
  const handleSearchInput = (val) => {
    setSearch(val);
    clearTimeout(debounceRef.current);
    debounceRef.current = setTimeout(() => { setPage(0); }, 300);
  };

  const handleStatusTab = (val) => { setStatusFilter(val); setPage(0); };

  // Upload
  const onDrop = useCallback((files) => {
    if (files.length > 0) { setSelectedFile(files[0]); setShowUpload(true); }
  }, []);
  const { getRootProps, getInputProps } = useDropzone({
    onDrop, noClick: !showUpload, noDrag: !showUpload,
    accept: { 'application/pdf': ['.pdf'], 'text/html': ['.html'], 'application/vnd.openxmlformats-officedocument.wordprocessingml.document': ['.docx'] },
    maxSize: 50 * 1024 * 1024,
  });

  const handleUpload = async () => {
    if (!selectedFile) return;
    setUploading(true);
    const fd = new FormData();
    fd.append('file', selectedFile);
    Object.entries(meta).forEach(([k, v]) => { if (v) fd.append(k, v); });
    try {
      await api.post('/documents/upload', fd, { headers: { 'Content-Type': undefined } });
      setShowUpload(false); setSelectedFile(null);
      setMeta({ documentType: '', practiceArea: 'OTHER', clientName: '', matterId: '', jurisdiction: '', industry: '' });
      fetchDocs();
    } catch (e) {
      alert(e.response?.data?.message || 'Upload failed');
    } finally { setUploading(false); }
  };

  const daysUntil = (dateStr) => {
    if (!dateStr) return null;
    const d = Math.ceil((new Date(dateStr) - new Date()) / 86400000);
    return d;
  };

  return (
    <div style={{ display: 'flex', height: '100%', overflow: 'hidden' }}>
      {/* ── LEFT: List pane ── */}
      <div style={{
        flex: selectedDocId ? '0 0 55%' : '1',
        display: 'flex', flexDirection: 'column', overflow: 'hidden',
        transition: 'flex 0.2s ease',
      }}>
        {/* Header */}
        <div style={{ padding: '20px 24px 0', flexShrink: 0 }}>
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 16 }}>
            <div>
              <h1 className="page-title">Contracts</h1>
              <div className="small muted">{totalElements} document{totalElements !== 1 ? 's' : ''}</div>
            </div>
            <div className="row" style={{ gap: 8 }}>
              <button className="btn" onClick={() => setShowUpload(!showUpload)}>
                <Upload size={14} /> Upload
              </button>
              <button className="btn" onClick={() => setShowCloudImport(true)}>
                <Cloud size={14} /> Import
              </button>
            </div>
          </div>

          {/* Search */}
          <div style={{ position: 'relative', marginBottom: 12 }}>
            <Search size={14} style={{ position: 'absolute', left: 10, top: '50%', transform: 'translateY(-50%)', color: 'var(--text-3)' }} />
            <input
              className="input"
              placeholder="Search by name, party, or client..."
              value={search}
              onChange={e => handleSearchInput(e.target.value)}
              style={{ paddingLeft: 32, width: '100%' }}
            />
            {search && (
              <button onClick={() => { setSearch(''); setPage(0); }}
                style={{ position: 'absolute', right: 8, top: '50%', transform: 'translateY(-50%)', background: 'none', border: 'none', cursor: 'pointer', color: 'var(--text-3)' }}>
                <X size={14} />
              </button>
            )}
          </div>

          {/* Status tabs */}
          <div style={{ display: 'flex', gap: 4, flexWrap: 'wrap', marginBottom: 10 }}>
            {STATUS_TABS.map(t => (
              <button key={t.value}
                onClick={() => handleStatusTab(t.value)}
                style={{
                  padding: '4px 12px', borderRadius: 'var(--r-sm)', fontSize: 11, fontWeight: 600,
                  border: '1px solid ' + (statusFilter === t.value ? 'var(--brand-400)' : 'var(--line-1)'),
                  background: statusFilter === t.value ? 'var(--brand-400)' : 'var(--bg-2)',
                  color: statusFilter === t.value ? 'white' : 'var(--text-2)',
                  cursor: 'pointer', transition: 'all 0.15s',
                }}>
                {t.label}
              </button>
            ))}
          </div>

          {/* Filter row */}
          <div style={{ display: 'flex', gap: 8, marginBottom: 12, alignItems: 'center' }}>
            <select className="select" value={typeFilter} onChange={e => { setTypeFilter(e.target.value); setPage(0); }}
              style={{ fontSize: 11, padding: '3px 8px' }}>
              <option value="">All types</option>
              {DOC_TYPES.map(t => <option key={t} value={t}>{t}</option>)}
            </select>
            <select className="select" value={matterFilter} onChange={e => { setMatterFilter(e.target.value); setPage(0); }}
              style={{ fontSize: 11, padding: '3px 8px' }}>
              <option value="">All matters</option>
              {matters.map(m => <option key={m.id} value={m.id}>{m.name}</option>)}
            </select>
            <div style={{ marginLeft: 'auto' }}>
              <select className="select" value={sortField} onChange={e => setSortField(e.target.value)}
                style={{ fontSize: 11, padding: '3px 8px' }}>
                {SORT_OPTIONS.map(s => <option key={s.value} value={s.value}>{s.label}</option>)}
              </select>
            </div>
          </div>
        </div>

        {/* Upload form (collapsible) */}
        {showUpload && (
          <div style={{ padding: '0 24px 12px', flexShrink: 0 }}>
            <div className="card" style={{ padding: 14 }}>
              {!selectedFile ? (
                <div {...getRootProps()} style={{
                  border: '2px dashed var(--line-2)', borderRadius: 'var(--r-md)',
                  padding: 24, textAlign: 'center', cursor: 'pointer',
                }}>
                  <input {...getInputProps()} />
                  <Upload size={20} style={{ color: 'var(--text-3)', margin: '0 auto 8px' }} />
                  <div className="small muted">Drop a file or click to browse (PDF, DOCX, HTML)</div>
                </div>
              ) : (
                <div>
                  <div className="small" style={{ fontWeight: 600, marginBottom: 8 }}>{selectedFile.name}</div>
                  <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 8, marginBottom: 8 }}>
                    <select className="select" value={meta.documentType} onChange={e => setMeta({ ...meta, documentType: e.target.value })}
                      style={{ fontSize: 12, borderColor: meta.documentType ? undefined : 'var(--warn-400)' }}>
                      <option value="">Contract type *</option>
                      {DOC_TYPES.map(t => <option key={t} value={t}>{t}</option>)}
                    </select>
                    <select className="select" value={meta.matterId} onChange={e => setMeta({ ...meta, matterId: e.target.value })} style={{ fontSize: 12 }}>
                      <option value="">No matter</option>
                      {matters.map(m => <option key={m.id} value={m.id}>{m.name}</option>)}
                    </select>
                    <input className="input" placeholder="Client name" value={meta.clientName} onChange={e => setMeta({ ...meta, clientName: e.target.value })} style={{ fontSize: 12 }} />
                    <input className="input" placeholder="Jurisdiction" value={meta.jurisdiction} onChange={e => setMeta({ ...meta, jurisdiction: e.target.value })} style={{ fontSize: 12 }} />
                  </div>
                  <div className="row" style={{ gap: 8 }}>
                    <button className="btn sm" onClick={() => { setSelectedFile(null); setShowUpload(false); }}>Cancel</button>
                    <button className="btn sm primary" onClick={handleUpload} disabled={uploading || !meta.documentType}>
                      {uploading ? <Loader size={12} className="animate-spin" /> : <Upload size={12} />}
                      {uploading ? 'Uploading...' : 'Upload'}
                    </button>
                  </div>
                </div>
              )}
            </div>
          </div>
        )}

        {/* Table */}
        <div style={{ flex: 1, overflow: 'auto', padding: '0 24px' }}>
          <table style={{ width: '100%', fontSize: 13, borderCollapse: 'collapse' }}>
            <thead>
              <tr style={{ borderBottom: '1px solid var(--line-1)' }}>
                <th style={{ textAlign: 'left', padding: '8px 0', fontWeight: 600, fontSize: 11, color: 'var(--text-3)', textTransform: 'uppercase' }}>Contract</th>
                {!selectedDocId && <th style={{ textAlign: 'left', padding: '8px 0', fontWeight: 600, fontSize: 11, color: 'var(--text-3)', textTransform: 'uppercase' }}>Matter</th>}
                <th style={{ textAlign: 'left', padding: '8px 0', fontWeight: 600, fontSize: 11, color: 'var(--text-3)', textTransform: 'uppercase' }}>Status</th>
                <th style={{ textAlign: 'left', padding: '8px 0', fontWeight: 600, fontSize: 11, color: 'var(--text-3)', textTransform: 'uppercase' }}>Type</th>
                {!selectedDocId && <th style={{ textAlign: 'left', padding: '8px 0', fontWeight: 600, fontSize: 11, color: 'var(--text-3)', textTransform: 'uppercase' }}>Expiry</th>}
                {!selectedDocId && <th style={{ textAlign: 'left', padding: '8px 0', fontWeight: 600, fontSize: 11, color: 'var(--text-3)', textTransform: 'uppercase', width: 40 }}>Ver</th>}
              </tr>
            </thead>
            <tbody>
              {loading && docs.length === 0 ? (
                <tr><td colSpan={6} style={{ padding: 40, textAlign: 'center', color: 'var(--text-3)' }}>
                  <Loader size={16} className="animate-spin" style={{ margin: '0 auto' }} />
                </td></tr>
              ) : docs.length === 0 ? (
                <tr><td colSpan={6} style={{ padding: 40, textAlign: 'center', color: 'var(--text-3)' }}>
                  {search || statusFilter || typeFilter ? 'No contracts match your filters.' : 'No contracts yet. Upload or generate a draft to get started.'}
                </td></tr>
              ) : docs.map(d => {
                const days = daysUntil(d.expiryDate);
                const isSelected = selectedDocId === d.id;
                return (
                  <tr key={d.id}
                    onClick={() => setSelectedDocId(d.id)}
                    style={{
                      borderBottom: '1px solid var(--line-1)',
                      cursor: 'pointer',
                      background: isSelected ? 'var(--brand-400)08' : undefined,
                      transition: 'background 0.1s',
                    }}
                    onMouseEnter={e => { if (!isSelected) e.currentTarget.style.background = 'var(--bg-2)'; }}
                    onMouseLeave={e => { if (!isSelected) e.currentTarget.style.background = ''; }}>
                    <td style={{ padding: '10px 8px 10px 0' }}>
                      <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                        <FileText size={14} style={{ color: 'var(--text-3)', flexShrink: 0 }} />
                        <div style={{ minWidth: 0 }}>
                          <div style={{ fontWeight: 500, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', maxWidth: selectedDocId ? 200 : 300 }}>
                            {d.fileName}
                          </div>
                          {(d.partyA || d.partyB) && (
                            <div style={{ fontSize: 10, color: 'var(--text-3)', marginTop: 1 }}>
                              {[d.partyA, d.partyB].filter(Boolean).join(' ↔ ')}
                            </div>
                          )}
                        </div>
                      </div>
                    </td>
                    {!selectedDocId && (
                      <td style={{ padding: '10px 8px', color: 'var(--text-2)', fontSize: 12 }}>
                        {d.matter?.name || '—'}
                      </td>
                    )}
                    <td style={{ padding: '10px 8px' }}>
                      {d.contractStatus ? <StatusBadge status={d.contractStatus} size="sm" /> : (
                        <span style={{ fontSize: 10, color: 'var(--text-3)' }}>{d.processingStatus || '—'}</span>
                      )}
                    </td>
                    <td style={{ padding: '10px 8px', color: 'var(--text-2)', fontSize: 12 }}>{d.documentType || '—'}</td>
                    {!selectedDocId && (
                      <td style={{ padding: '10px 8px', fontSize: 12, color: days !== null && days <= 30 ? 'var(--danger-400)' : days !== null && days <= 90 ? 'var(--warn-400)' : 'var(--text-2)' }}>
                        {d.expiryDate || '—'}
                      </td>
                    )}
                    {!selectedDocId && (
                      <td style={{ padding: '10px 8px', fontSize: 11, color: 'var(--text-3)' }}>
                        {d.currentVersion ? `v${d.currentVersion}` : '—'}
                      </td>
                    )}
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>

        {/* Pagination */}
        {totalPages > 1 && (
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 12, padding: '10px 24px', borderTop: '1px solid var(--line-1)', flexShrink: 0 }}>
            <button className="icon-btn" disabled={page === 0} onClick={() => setPage(p => p - 1)}>
              <ChevronLeft size={14} />
            </button>
            <span className="small muted">Page {page + 1} of {totalPages}</span>
            <button className="icon-btn" disabled={page >= totalPages - 1} onClick={() => setPage(p => p + 1)}>
              <ChevronRight size={14} />
            </button>
          </div>
        )}
      </div>

      {/* ── RIGHT: Detail panel ── */}
      {selectedDocId && (
        <ContractDetailPanel
          docId={selectedDocId}
          onClose={() => setSelectedDocId(null)}
          onStatusChanged={fetchDocs}
          onDeleted={() => { setSelectedDocId(null); fetchDocs(); }}
        />
      )}

      {/* Modals */}
      {showCloudImport && (
        <CloudImportModal
          onClose={() => setShowCloudImport(false)}
          onImported={() => { fetchDocs(); setShowCloudImport(false); }}
        />
      )}
    </div>
  );
}
