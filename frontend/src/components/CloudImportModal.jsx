import { useState, useEffect } from 'react';
import { X, Folder, FileText, ChevronRight, Loader, Cloud, Unplug } from 'lucide-react';
import api from '../api/client';

const PROVIDER_ICONS = {
  GOOGLE_DRIVE: '📁',
  ONEDRIVE: '☁️',
  DROPBOX: '📦',
};

const DOC_TYPES = ['NDA', 'MSA', 'SOW', 'EMPLOYMENT', 'LEASE', 'MOU', 'NOTICE', 'PETITION', 'VENDOR', 'LOAN', 'OTHER'];
const PRACTICE_AREAS = ['CORPORATE', 'LITIGATION', 'IP', 'TAX', 'REAL_ESTATE', 'LABOR', 'BANKING', 'REGULATORY', 'OTHER'];
const JURISDICTIONS = ['Maharashtra', 'Delhi', 'Karnataka', 'Tamil Nadu', 'Gujarat', 'Rajasthan', 'Supreme Court'];

const SUPPORTED_EXT = ['.pdf', '.docx', '.html', '.htm'];

export default function CloudImportModal({ onClose, onImported }) {
  const [connections, setConnections] = useState([]);
  const [loading, setLoading] = useState(true);
  const [connecting, setConnecting] = useState(null);
  const [provider, setProvider] = useState(null);
  const [files, setFiles] = useState([]);
  const [folderStack, setFolderStack] = useState([{ id: 'root', name: 'Root' }]);
  const [loadingFiles, setLoadingFiles] = useState(false);
  const [selectedFile, setSelectedFile] = useState(null);
  const [importing, setImporting] = useState(false);
  const [meta, setMeta] = useState({
    jurisdiction: '', year: '', confidential: false,
    documentType: 'OTHER', practiceArea: 'OTHER', clientName: '', matterId: '',
  });

  const fetchConnections = () => {
    api.get('/cloud-storage/connections')
      .then(r => setConnections(r.data || []))
      .catch(() => setConnections([]))
      .finally(() => setLoading(false));
  };

  useEffect(() => { fetchConnections(); }, []);

  const handleConnect = async (p) => {
    setConnecting(p.provider);
    try {
      const { data } = await api.get(`/cloud-storage/auth-url?provider=${p.provider}`);
      window.location.href = data.url;
    } catch (e) {
      const msg = e.response?.data?.message || e.message || 'Failed to get auth URL';
      alert(msg);
    } finally {
      setConnecting(null);
    }
  };

  const handleBrowse = (p) => {
    setProvider(p);
    setFolderStack([{ id: 'root', name: 'Root' }]);
    setFiles([]);
    setSelectedFile(null);
    loadFiles(p.provider, 'root');
  };

  const loadFiles = (prov, folderId) => {
    setLoadingFiles(true);
    const params = new URLSearchParams({ provider: prov });
    if (folderId && folderId !== 'root') params.append('folderId', folderId);
    api.get(`/cloud-storage/files?${params}`)
      .then(r => setFiles(r.data || []))
      .catch(() => setFiles([]))
      .finally(() => setLoadingFiles(false));
  };

  const handleFolderClick = (item) => {
    if (!item.folder) return;
    setFolderStack(prev => [...prev, { id: item.id, name: item.name }]);
    loadFiles(provider.provider, item.id);
  };

  const handleBreadcrumb = (idx) => {
    const target = folderStack[idx];
    setFolderStack(prev => prev.slice(0, idx + 1));
    loadFiles(provider.provider, target.id);
    setSelectedFile(null);
  };

  const handleFileSelect = (item) => {
    if (item.folder) return;
    const ext = item.name ? item.name.toLowerCase().slice(item.name.lastIndexOf('.')) : '';
    if (!SUPPORTED_EXT.includes(ext)) {
      alert('Supported formats: PDF, DOCX, HTML');
      return;
    }
    setSelectedFile(item);
  };

  const handleImport = async () => {
    if (!selectedFile || !provider) return;
    setImporting(true);
    try {
      await api.post('/cloud-storage/import', {
        provider: provider.provider,
        fileId: selectedFile.id,
        fileName: selectedFile.name,
        ...meta,
      });
      onImported?.();
      setSelectedFile(null);
      onClose?.();
    } catch (e) {
      const msg = e.response?.data?.message || e.response?.data?.error || e.message || 'Import failed';
      alert(msg);
    } finally {
      setImporting(false);
    }
  };

  const handleDisconnect = async (p) => {
    if (!confirm(`Disconnect ${p.displayName}?`)) return;
    try {
      await api.delete(`/cloud-storage/disconnect?provider=${p.provider}`);
      fetchConnections();
      if (provider?.provider === p.provider) {
        setProvider(null);
        setFiles([]);
        setSelectedFile(null);
      }
    } catch (e) {
      alert(e.response?.data?.message || e.message || 'Failed to disconnect');
    }
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 backdrop-blur-sm" onClick={onClose}>
      <div className="bg-surface border border-border rounded-xl shadow-2xl w-full max-w-2xl max-h-[90vh] overflow-hidden flex flex-col" onClick={e => e.stopPropagation()}>
        <div className="flex items-center justify-between p-4 border-b border-border">
          <h2 className="text-lg font-semibold flex items-center gap-2">
            <Cloud className="w-5 h-5" />
            Import from Cloud Storage
          </h2>
          <button onClick={onClose} className="p-2 hover:bg-surface-el rounded-lg transition-colors">
            <X className="w-5 h-5" />
          </button>
        </div>

        <div className="p-4 overflow-y-auto flex-1">
          {loading ? (
            <div className="flex justify-center py-12"><Loader className="w-8 h-8 animate-spin text-text-muted" /></div>
          ) : connections.length === 0 ? (
            <p className="text-text-muted text-center py-8">No cloud storage providers configured. Ask your admin to enable Google Drive, OneDrive, or Dropbox.</p>
          ) : !provider ? (
            <div className="space-y-3">
              {connections.map(c => (
                <div key={c.provider} className="flex items-center justify-between p-4 rounded-lg border border-border bg-surface-el/50">
                  <div className="flex items-center gap-3">
                    <span className="text-2xl">{PROVIDER_ICONS[c.provider] || '☁️'}</span>
                    <div>
                      <p className="font-medium">{c.displayName}</p>
                      <p className="text-sm text-text-muted">{c.connected ? 'Connected' : 'Not connected'}</p>
                    </div>
                  </div>
                  <div className="flex gap-2">
                    {c.connected ? (
                      <>
                        <button onClick={() => handleBrowse(c)} className="btn-primary text-sm py-2">
                          Browse & Import
                        </button>
                        <button onClick={() => handleDisconnect(c)} className="btn-secondary text-sm py-2 flex items-center gap-1" title="Disconnect">
                          <Unplug className="w-4 h-4" />
                        </button>
                      </>
                    ) : (
                      <button
                        onClick={() => handleConnect(c)}
                        disabled={connecting === c.provider}
                        className="btn-primary text-sm py-2 flex items-center gap-2"
                      >
                        {connecting === c.provider && <Loader className="w-4 h-4 animate-spin" />}
                        Connect
                      </button>
                    )}
                  </div>
                </div>
              ))}
            </div>
          ) : (
            <div className="space-y-4">
              <div className="flex items-center gap-2 text-sm text-text-muted">
                <button onClick={() => setProvider(null)} className="hover:text-text-primary">← Back</button>
                <span>•</span>
                <span>{provider.displayName}</span>
              </div>

              <div className="flex gap-2 items-center flex-wrap">
                {folderStack.map((f, i) => (
                  <span key={f.id} className="flex items-center gap-2">
                    {i > 0 && <ChevronRight className="w-4 h-4 text-text-muted" />}
                    <button onClick={() => handleBreadcrumb(i)} className="text-sm hover:text-primary">
                      {f.name}
                    </button>
                  </span>
                ))}
              </div>

              <div className="border border-border rounded-lg max-h-48 overflow-y-auto">
                {loadingFiles ? (
                  <div className="flex justify-center py-8"><Loader className="w-6 h-6 animate-spin text-text-muted" /></div>
                ) : (
                  <div className="divide-y divide-border">
                    {files.filter(f => f.folder).map(item => (
                      <button key={item.id} onClick={() => handleFolderClick(item)} className="w-full flex items-center gap-3 px-4 py-3 hover:bg-surface-el/50 text-left">
                        <Folder className="w-5 h-5 text-text-muted" />
                        <span>{item.name}</span>
                      </button>
                    ))}
                    {files.filter(f => !f.folder).map(item => (
                      <button
                        key={item.id}
                        onClick={() => handleFileSelect(item)}
                        className={`w-full flex items-center gap-3 px-4 py-3 hover:bg-surface-el/50 text-left ${selectedFile?.id === item.id ? 'bg-primary/10 border-l-2 border-primary' : ''}`}
                      >
                        <FileText className="w-5 h-5 text-text-muted" />
                        <span>{item.name}</span>
                        {item.size != null && <span className="text-xs text-text-muted ml-auto">{(item.size / 1024).toFixed(1)} KB</span>}
                      </button>
                    ))}
                    {files.length === 0 && !loadingFiles && <p className="py-6 text-center text-text-muted text-sm">No files here</p>}
                  </div>
                )}
              </div>

              {selectedFile && (
                <div className="card">
                  <h4 className="font-medium mb-3">Import: {selectedFile.name}</h4>
                  <div className="grid grid-cols-2 gap-3">
                    <select value={meta.documentType} onChange={e => setMeta({ ...meta, documentType: e.target.value })} className="input-field text-sm">
                      {DOC_TYPES.map(t => <option key={t} value={t}>{t}</option>)}
                    </select>
                    <select value={meta.practiceArea} onChange={e => setMeta({ ...meta, practiceArea: e.target.value })} className="input-field text-sm">
                      {PRACTICE_AREAS.map(p => <option key={p} value={p}>{p}</option>)}
                    </select>
                    <input placeholder="Client Name" value={meta.clientName} onChange={e => setMeta({ ...meta, clientName: e.target.value })} className="input-field text-sm" />
                    <input placeholder="Matter ID" value={meta.matterId} onChange={e => setMeta({ ...meta, matterId: e.target.value })} className="input-field text-sm" />
                    <select value={meta.jurisdiction} onChange={e => setMeta({ ...meta, jurisdiction: e.target.value })} className="input-field text-sm">
                      <option value="">Jurisdiction</option>
                      {JURISDICTIONS.map(j => <option key={j} value={j}>{j}</option>)}
                    </select>
                    <input type="number" placeholder="Year" value={meta.year} onChange={e => setMeta({ ...meta, year: e.target.value })} className="input-field text-sm" />
                    <label className="flex items-center gap-2 text-sm col-span-2">
                      <input type="checkbox" checked={meta.confidential} onChange={e => setMeta({ ...meta, confidential: e.target.checked })} className="rounded" />
                      Confidential
                    </label>
                  </div>
                  <div className="flex justify-end gap-2 mt-4">
                    <button onClick={() => setSelectedFile(null)} className="btn-secondary text-sm">Cancel</button>
                    <button onClick={handleImport} disabled={importing} className="btn-primary text-sm flex items-center gap-2">
                      {importing && <Loader className="w-4 h-4 animate-spin" />}
                      {importing ? 'Importing...' : 'Import & Index'}
                    </button>
                  </div>
                </div>
              )}
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
