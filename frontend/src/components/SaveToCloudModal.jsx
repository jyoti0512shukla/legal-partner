import { useState, useEffect } from 'react';
import { X, Folder, ChevronRight, Loader, Cloud } from 'lucide-react';
import api from '../api/client';

const PROVIDER_ICONS = { GOOGLE_DRIVE: '📁', ONEDRIVE: '☁️', DROPBOX: '📦' };

export default function SaveToCloudModal({ content, defaultFileName, onClose, onSaved }) {
  const [connections, setConnections] = useState([]);
  const [loading, setLoading] = useState(true);
  const [provider, setProvider] = useState(null);
  const [files, setFiles] = useState([]);
  const [folderStack, setFolderStack] = useState([{ id: 'root', name: 'Root' }]);
  const [loadingFiles, setLoadingFiles] = useState(false);
  const [selectedFolderId, setSelectedFolderId] = useState('root');
  const [fileName, setFileName] = useState(defaultFileName || 'draft.html');
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');

  const connectedOnly = (list) => (list || []).filter(c => c.connected);

  useEffect(() => {
    api.get('/cloud-storage/connections')
      .then(r => setConnections(connectedOnly(r.data)))
      .catch(() => setConnections([]))
      .finally(() => setLoading(false));
  }, []);

  const loadFiles = (prov, folderId) => {
    setLoadingFiles(true);
    const params = new URLSearchParams({ provider: prov });
    if (folderId && folderId !== 'root') params.append('folderId', folderId);
    api.get(`/cloud-storage/files?${params}`)
      .then(r => setFiles(r.data || []))
      .catch(() => setFiles([]))
      .finally(() => setLoadingFiles(false));
  };

  const handleSelectProvider = (p) => {
    setProvider(p);
    setFolderStack([{ id: 'root', name: 'Root' }]);
    setSelectedFolderId('root');
    loadFiles(p.provider, 'root');
  };

  const handleFolderClick = (item) => {
    if (!item.folder) return;
    setFolderStack(prev => [...prev, { id: item.id, name: item.name }]);
    setSelectedFolderId(item.id);
    loadFiles(provider.provider, item.id);
  };

  const handleBreadcrumb = (idx) => {
    const target = folderStack[idx];
    setFolderStack(prev => prev.slice(0, idx + 1));
    setSelectedFolderId(target.id);
    loadFiles(provider.provider, target.id);
  };

  const handleSave = async () => {
    if (!provider || !content) return;
    setSaving(true);
    setError('');
    try {
      const base64 = btoa(unescape(encodeURIComponent(content)));
      await api.post('/cloud-storage/save', {
        provider: provider.provider,
        folderId: selectedFolderId === 'root' ? '' : selectedFolderId,
        fileName,
        content: base64,
        mimeType: 'text/html',
      });
      onSaved?.();
      onClose?.();
    } catch (e) {
      setError(e.response?.data?.message || e.message || 'Save failed');
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 backdrop-blur-sm" onClick={onClose}>
      <div className="bg-surface border border-border rounded-xl shadow-2xl w-full max-w-md overflow-hidden" onClick={e => e.stopPropagation()}>
        <div className="flex items-center justify-between p-4 border-b border-border">
          <h2 className="text-lg font-semibold flex items-center gap-2">
            <Cloud className="w-5 h-5" />
            Save to Cloud
          </h2>
          <button onClick={onClose} className="p-2 hover:bg-surface-el rounded-lg transition-colors">
            <X className="w-5 h-5" />
          </button>
        </div>

        <div className="p-4 space-y-4">
          {loading ? (
            <div className="flex justify-center py-8"><Loader className="w-6 h-6 animate-spin text-text-muted" /></div>
          ) : connections.length === 0 ? (
            <p className="text-text-muted text-sm">No cloud storage connected. Connect Google Drive, OneDrive, or Dropbox from Documents → Import from Cloud.</p>
          ) : (
            <>
              <div>
                <label className="text-xs text-text-muted mb-1 block">Provider</label>
                <div className="flex gap-2 flex-wrap">
                  {connections.map(c => (
                    <button
                      key={c.provider}
                      onClick={() => handleSelectProvider(c)}
                      className={`px-3 py-2 rounded-lg border text-sm transition-colors ${provider?.provider === c.provider ? 'border-primary bg-primary/10' : 'border-border hover:border-text-muted'}`}
                    >
                      {PROVIDER_ICONS[c.provider] || '☁️'} {c.displayName}
                    </button>
                  ))}
                </div>
              </div>

              {provider && (
                <>
                  <div>
                    <label className="text-xs text-text-muted mb-1 block">Folder</label>
                    <div className="flex gap-2 items-center flex-wrap mb-2">
                      {folderStack.map((f, i) => (
                        <span key={f.id} className="flex items-center gap-1">
                          {i > 0 && <ChevronRight className="w-4 h-4 text-text-muted" />}
                          <button onClick={() => handleBreadcrumb(i)} className="text-sm hover:text-primary">
                            {f.name}
                          </button>
                        </span>
                      ))}
                    </div>
                    <div className="border border-border rounded-lg max-h-32 overflow-y-auto">
                      {loadingFiles ? (
                        <div className="flex justify-center py-4"><Loader className="w-5 h-5 animate-spin text-text-muted" /></div>
                      ) : (
                        files.filter(f => f.folder).map(item => (
                          <button
                            key={item.id}
                            onClick={() => handleFolderClick(item)}
                            className={`w-full flex items-center gap-2 px-3 py-2 hover:bg-surface-el/50 text-left text-sm ${selectedFolderId === item.id ? 'bg-primary/10' : ''}`}
                          >
                            <Folder className="w-4 h-4 text-text-muted" />
                            {item.name}
                          </button>
                        ))
                      )}
                    </div>
                  </div>

                  <div>
                    <label className="text-xs text-text-muted mb-1 block">File name</label>
                    <input
                      value={fileName}
                      onChange={e => setFileName(e.target.value)}
                      className="input-field w-full text-sm"
                      placeholder="draft.html"
                    />
                  </div>

                  {error && <p className="text-danger text-sm">{error}</p>}

                  <div className="flex justify-end gap-2 pt-2">
                    <button onClick={onClose} className="btn-secondary text-sm">Cancel</button>
                    <button onClick={handleSave} disabled={saving || !fileName} className="btn-primary text-sm flex items-center gap-2">
                      {saving && <Loader className="w-4 h-4 animate-spin" />}
                      {saving ? 'Saving...' : 'Save'}
                    </button>
                  </div>
                </>
              )}
            </>
          )}
        </div>
      </div>
    </div>
  );
}
