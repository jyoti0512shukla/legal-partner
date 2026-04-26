import { useState, useEffect, useRef, useCallback } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { Search, Sparkles, Bell, Home, ChevronRight, FileText, Briefcase, Wand2, X } from 'lucide-react';
import api from '../../api/client';
import Sidebar from './Sidebar';

const PAGE_TITLES = {
  '/': 'Dashboard',
  '/draft': 'Draft Contract',
  '/review': 'Contract Review',
  '/risk': 'Contract Review',
  '/matters': 'Matters',
  '/documents': 'Documents',
  '/intelligence': 'Ask Cognita',
  '/compare': 'Compare',
  '/extraction': 'Extraction',
  '/clause-library': 'Clause Library',
  '/workflows': 'AI Agents',
  '/settings': 'Settings',
  '/playbooks': 'Playbooks',
  '/audit': 'Audit Log',
};

function GlobalSearch() {
  const navigate = useNavigate();
  const [query, setQuery] = useState('');
  const [results, setResults] = useState(null);
  const [loading, setLoading] = useState(false);
  const [open, setOpen] = useState(false);
  const inputRef = useRef(null);
  const debounceRef = useRef(null);

  // Cmd+K shortcut
  useEffect(() => {
    const handler = (e) => {
      if ((e.metaKey || e.ctrlKey) && e.key === 'k') {
        e.preventDefault();
        inputRef.current?.focus();
        setOpen(true);
      }
      if (e.key === 'Escape') { setOpen(false); setQuery(''); setResults(null); }
    };
    window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, []);

  const search = useCallback((q) => {
    if (!q || q.length < 2) { setResults(null); return; }
    setLoading(true);
    Promise.all([
      api.get(`/documents?search=${encodeURIComponent(q)}&size=5`).catch(() => ({ data: { content: [] } })),
      api.get('/matters').catch(() => ({ data: [] })),
      api.get('/ai/drafts').catch(() => ({ data: [] })),
    ]).then(([docsRes, mattersRes, draftsRes]) => {
      const lq = q.toLowerCase();
      const docs = (docsRes.data?.content || []).slice(0, 5);
      const matters = (Array.isArray(mattersRes.data) ? mattersRes.data : [])
        .filter(m => m.name?.toLowerCase().includes(lq) || m.clientName?.toLowerCase().includes(lq))
        .slice(0, 5);
      const drafts = (Array.isArray(draftsRes.data) ? draftsRes.data : [])
        .filter(d => d.fileName?.toLowerCase().includes(lq))
        .slice(0, 5);
      setResults({ docs, matters, drafts });
    }).finally(() => setLoading(false));
  }, []);

  const handleChange = (e) => {
    const v = e.target.value;
    setQuery(v);
    setOpen(true);
    clearTimeout(debounceRef.current);
    debounceRef.current = setTimeout(() => search(v), 300);
  };

  const go = (path) => { setOpen(false); setQuery(''); setResults(null); navigate(path); };
  const hasResults = results && (results.docs.length + results.matters.length + results.drafts.length > 0);

  return (
    <div style={{ position: 'relative' }}>
      <div className="search-bar">
        <Search size={16} className="search-icon" />
        <input ref={inputRef} value={query} onChange={handleChange}
               onFocus={() => query.length >= 2 && setOpen(true)}
               placeholder="Search matters, documents, drafts..." />
        {query ? <X size={14} style={{ cursor: 'pointer', color: 'var(--text-3)' }} onClick={() => { setQuery(''); setResults(null); setOpen(false); }} />
               : <kbd>&#x2318;K</kbd>}
      </div>
      {open && query.length >= 2 && (
        <div style={{
          position: 'absolute', top: '100%', left: 0, right: 0, marginTop: 6,
          background: 'var(--bg-2)', border: '1px solid var(--line-2)', borderRadius: 'var(--r-lg)',
          boxShadow: '0 12px 40px rgba(0,0,0,0.5)', zIndex: 100, maxHeight: 400, overflow: 'auto',
        }}>
          {loading && <div style={{ padding: 16, textAlign: 'center', color: 'var(--text-3)', fontSize: 12 }}>Searching...</div>}
          {!loading && !hasResults && <div style={{ padding: 16, textAlign: 'center', color: 'var(--text-3)', fontSize: 12 }}>No results for "{query}"</div>}
          {!loading && hasResults && (
            <>
              {results.matters.length > 0 && (
                <div>
                  <div style={{ padding: '8px 14px', fontSize: 10, fontWeight: 600, textTransform: 'uppercase', letterSpacing: '0.08em', color: 'var(--text-4)' }}>Matters</div>
                  {results.matters.map(m => (
                    <div key={m.id} onClick={() => go(`/matters/${m.id}`)}
                         style={{ padding: '8px 14px', cursor: 'pointer', display: 'flex', alignItems: 'center', gap: 10, fontSize: 13 }}
                         onMouseEnter={e => e.currentTarget.style.background = 'var(--bg-3)'}
                         onMouseLeave={e => e.currentTarget.style.background = 'transparent'}>
                      <Briefcase size={14} style={{ color: 'var(--teal-400)', flexShrink: 0 }} />
                      <span style={{ fontWeight: 500 }}>{m.name}</span>
                      {m.clientName && <span style={{ color: 'var(--text-3)', fontSize: 11 }}>· {m.clientName}</span>}
                    </div>
                  ))}
                </div>
              )}
              {results.docs.length > 0 && (
                <div>
                  <div style={{ padding: '8px 14px', fontSize: 10, fontWeight: 600, textTransform: 'uppercase', letterSpacing: '0.08em', color: 'var(--text-4)', borderTop: '1px solid var(--line-1)' }}>Documents</div>
                  {results.docs.map(d => (
                    <div key={d.id} onClick={() => go(`/review?doc=${d.id}`)}
                         style={{ padding: '8px 14px', cursor: 'pointer', display: 'flex', alignItems: 'center', gap: 10, fontSize: 13 }}
                         onMouseEnter={e => e.currentTarget.style.background = 'var(--bg-3)'}
                         onMouseLeave={e => e.currentTarget.style.background = 'transparent'}>
                      <FileText size={14} style={{ color: 'var(--brand-400)', flexShrink: 0 }} />
                      <span style={{ fontWeight: 500, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{d.originalFileName || d.fileName || 'Untitled'}</span>
                    </div>
                  ))}
                </div>
              )}
              {results.drafts.length > 0 && (
                <div>
                  <div style={{ padding: '8px 14px', fontSize: 10, fontWeight: 600, textTransform: 'uppercase', letterSpacing: '0.08em', color: 'var(--text-4)', borderTop: '1px solid var(--line-1)' }}>Drafts</div>
                  {results.drafts.map(d => (
                    <div key={d.id} onClick={() => go('/draft')}
                         style={{ padding: '8px 14px', cursor: 'pointer', display: 'flex', alignItems: 'center', gap: 10, fontSize: 13 }}
                         onMouseEnter={e => e.currentTarget.style.background = 'var(--bg-3)'}
                         onMouseLeave={e => e.currentTarget.style.background = 'transparent'}>
                      <Wand2 size={14} style={{ color: 'var(--success-400)', flexShrink: 0 }} />
                      <span style={{ fontWeight: 500, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{d.fileName || 'Untitled Draft'}</span>
                      <span style={{ color: 'var(--text-4)', fontSize: 11, marginLeft: 'auto' }}>{d.status}</span>
                    </div>
                  ))}
                </div>
              )}
            </>
          )}
        </div>
      )}
    </div>
  );
}

export default function AppLayout({ children }) {
  const location = useLocation();
  const pageTitle = PAGE_TITLES[location.pathname] || '';

  return (
    <div className="app-shell">
      <Sidebar />
      <div className="main-panel">
        <div className="topbar">
          <div className="row small" style={{ color: 'var(--text-3)' }}>
            <Home size={14} />
            <ChevronRight size={12} />
            <span style={{ color: 'var(--text-1)', fontWeight: 500 }}>{pageTitle}</span>
          </div>
          <GlobalSearch />
          <div className="topbar-right">
            <button className="icon-btn" title="What's new">
              <Sparkles size={16} />
            </button>
            <button className="icon-btn" title="Notifications">
              <Bell size={16} />
              <span className="dot"></span>
            </button>
          </div>
        </div>
        <div className="content-area">
          {children}
        </div>
      </div>
    </div>
  );
}
