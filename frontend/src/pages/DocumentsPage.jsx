import { useState, useEffect, useCallback } from 'react';
import { useSearchParams } from 'react-router-dom';
import { useDropzone } from 'react-dropzone';
import { Upload, FileText, Trash2, CheckCircle, Loader, XCircle, Cloud } from 'lucide-react';
import api from '../api/client';
import CloudImportModal from '../components/CloudImportModal';

const STATUS_ICONS = {
  INDEXED: <CheckCircle className="w-4 h-4 text-success" />,
  PROCESSING: <Loader className="w-4 h-4 text-warning animate-spin" />,
  PENDING: <Loader className="w-4 h-4 text-text-muted animate-spin" />,
  FAILED: <XCircle className="w-4 h-4 text-danger" />,
};

export default function DocumentsPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const [docs, setDocs] = useState([]);
  const [matters, setMatters] = useState([]);
  const [uploading, setUploading] = useState(false);
  const [showForm, setShowForm] = useState(false);
  const [showCloudImport, setShowCloudImport] = useState(false);
  const [selectedFile, setSelectedFile] = useState(null);
  const [meta, setMeta] = useState({ jurisdiction: '', year: '', confidential: false, documentType: 'OTHER', practiceArea: 'OTHER', clientName: '', matterId: '', industry: '' });

  const fetchDocs = () => api.get('/documents?size=50&sort=uploadDate,desc').then(r => setDocs(r.data.content || [])).catch(() => {});
  useEffect(() => {
    fetchDocs();
    api.get('/matters').then(r => setMatters(r.data)).catch(() => {});
  }, []);

  useEffect(() => {
    if (searchParams.get('cloud') === 'connected') {
      setSearchParams({}, { replace: true });
      setShowCloudImport(true);
    }
  }, [searchParams, setSearchParams]);

  const onDrop = useCallback((files) => {
    if (files.length > 0) { setSelectedFile(files[0]); setShowForm(true); }
  }, []);
  const { getRootProps, getInputProps, isDragActive } = useDropzone({ onDrop, accept: { 'application/pdf': ['.pdf'], 'text/html': ['.html'], 'application/vnd.openxmlformats-officedocument.wordprocessingml.document': ['.docx'] }, maxSize: 50 * 1024 * 1024 });

  const handleUpload = async () => {
    if (!selectedFile) return;
    setUploading(true);
    const fd = new FormData();
    fd.append('file', selectedFile);
    Object.entries(meta).forEach(([k, v]) => { if (v !== '' && v !== false) fd.append(k, v); });
    if (meta.confidential) fd.append('confidential', 'true');
    try {
      await api.post('/documents/upload', fd, { headers: { 'Content-Type': undefined } });
      setShowForm(false); setSelectedFile(null);
      setMeta({ jurisdiction: '', year: '', confidential: false, documentType: 'OTHER', practiceArea: 'OTHER', clientName: '', matterId: '', industry: '' });
      fetchDocs();
    } catch (e) {
      const msg = e.response?.data?.message || e.response?.data?.error || e.message || 'Upload failed';
      const detail = e.response?.status === 401 ? ' (Check login)' : e.response?.status === 0 ? ' (Is Docker/backend running?)' : '';
      alert(msg + detail);
    }
    finally { setUploading(false); }
  };

  return (
    <div>
      <h1 className="text-2xl font-bold mb-6">Documents</h1>

      <div className="flex gap-3 mb-6">
        <div {...getRootProps()} className={`flex-1 card border-2 border-dashed text-center py-10 cursor-pointer transition-colors ${isDragActive ? 'border-primary bg-primary/5' : 'border-border hover:border-text-muted'}`}>
        <input {...getInputProps()} />
        <Upload className="w-8 h-8 text-text-muted mx-auto mb-3" />
        <p className="text-text-secondary">{isDragActive ? 'Drop your file here' : 'Drag & drop a document, or click to browse'}</p>
        <p className="text-xs text-text-muted mt-1">PDF, HTML, DOCX — Max 50MB</p>
        </div>
        <button onClick={() => setShowCloudImport(true)} className="card border-2 border-border hover:border-primary/50 flex flex-col items-center justify-center gap-2 px-8 transition-colors min-w-[140px]">
          <Cloud className="w-8 h-8 text-text-muted" />
          <span className="text-sm font-medium">Import from Cloud</span>
          <span className="text-xs text-text-muted">Drive, OneDrive, Dropbox</span>
        </button>
      </div>

      {showCloudImport && (
        <CloudImportModal
          onClose={() => setShowCloudImport(false)}
          onImported={() => { fetchDocs(); setShowCloudImport(false); }}
        />
      )}

      {showForm && selectedFile && (
        <div className="card mb-6">
          <h3 className="font-semibold mb-4">Document Metadata — {selectedFile.name}</h3>
          <div className="grid grid-cols-2 gap-4">
            <select value={meta.documentType} onChange={e => setMeta({ ...meta, documentType: e.target.value })} className="input-field text-sm">
              {['NDA', 'MSA', 'SOW', 'EMPLOYMENT', 'LEASE', 'MOU', 'NOTICE', 'PETITION', 'VENDOR', 'LOAN', 'OTHER'].map(t => <option key={t} value={t}>{t}</option>)}
            </select>
            <select value={meta.practiceArea} onChange={e => setMeta({ ...meta, practiceArea: e.target.value })} className="input-field text-sm">
              {['CORPORATE', 'LITIGATION', 'IP', 'TAX', 'REAL_ESTATE', 'LABOR', 'BANKING', 'REGULATORY', 'OTHER'].map(p => <option key={p} value={p}>{p}</option>)}
            </select>
            <input placeholder="Client Name" value={meta.clientName} onChange={e => setMeta({ ...meta, clientName: e.target.value })} className="input-field text-sm" />
            <select value={meta.matterId} onChange={e => setMeta({ ...meta, matterId: e.target.value })} className="input-field text-sm">
              <option value="">No matter (unlinked)</option>
              {matters.map(m => <option key={m.id} value={m.id}>{m.name} — {m.clientName}</option>)}
            </select>
            <select value={meta.jurisdiction} onChange={e => setMeta({ ...meta, jurisdiction: e.target.value })} className="input-field text-sm">
              <option value="">Jurisdiction</option>
              {['California', 'New York', 'Delaware', 'Texas', 'Illinois', 'Florida', 'Massachusetts'].map(j => <option key={j} value={j}>{j}</option>)}
            </select>
            <select value={meta.industry} onChange={e => setMeta({ ...meta, industry: e.target.value })} className="input-field text-sm">
              <option value="">Industry (optional)</option>
              {['GENERAL', 'IT_SERVICES', 'FINTECH', 'PHARMA', 'MANUFACTURING'].map(i => <option key={i} value={i}>{i.replace('_', ' ')}</option>)}
            </select>
            <input type="number" placeholder="Year" value={meta.year} onChange={e => setMeta({ ...meta, year: e.target.value })} className="input-field text-sm" />
            <label className="flex items-center gap-2 text-sm text-text-secondary col-span-2">
              <input type="checkbox" checked={meta.confidential} onChange={e => setMeta({ ...meta, confidential: e.target.checked })} className="rounded" />
              Confidential (hidden from Associates)
            </label>
          </div>
          <div className="flex justify-end gap-3 mt-4">
            <button onClick={() => { setShowForm(false); setSelectedFile(null); }} className="btn-secondary text-sm">Cancel</button>
            <button onClick={handleUpload} disabled={uploading} className="btn-primary text-sm flex items-center gap-2">
              {uploading && <Loader className="w-4 h-4 animate-spin" />}
              {uploading ? 'Uploading...' : 'Upload & Index'}
            </button>
          </div>
        </div>
      )}

      <div className="card overflow-hidden">
        <table className="w-full text-sm">
          <thead>
            <tr className="border-b border-border text-left text-text-muted">
              <th className="pb-3 font-medium">Name</th>
              <th className="pb-3 font-medium">Type</th>
              <th className="pb-3 font-medium">Client</th>
              <th className="pb-3 font-medium">Jurisdiction</th>
              <th className="pb-3 font-medium">Year</th>
              <th className="pb-3 font-medium">Segments</th>
              <th className="pb-3 font-medium">Status</th>
              <th className="pb-3 font-medium"></th>
            </tr>
          </thead>
          <tbody>
            {docs.length === 0 ? (
              <tr><td colSpan={8} className="py-12 text-center text-text-muted">No documents yet. Upload one to get started.</td></tr>
            ) : docs.map(d => (
              <tr key={d.id} className="border-b border-border/50 hover:bg-surface-el/50 transition-colors">
                <td className="py-3 flex items-center gap-2"><FileText className="w-4 h-4 text-text-muted" />{d.fileName}</td>
                <td className="py-3 text-text-secondary">{d.documentType}</td>
                <td className="py-3 text-text-secondary">{d.clientName || '—'}</td>
                <td className="py-3 text-text-secondary">{d.jurisdiction || '—'}</td>
                <td className="py-3 text-text-secondary">{d.year || '—'}</td>
                <td className="py-3">{d.segmentCount}</td>
                <td className="py-3">{STATUS_ICONS[d.processingStatus]}</td>
                <td className="py-3">
                  <button onClick={() => api.delete(`/documents/${d.id}`).then(fetchDocs).catch(() => {})}
                    className="text-text-muted hover:text-danger transition-colors"><Trash2 className="w-4 h-4" /></button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}
