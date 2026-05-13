import { useState, useEffect, useRef, useCallback } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { Search, Bell, Home, ChevronRight, FileText, Briefcase, Wand2, X, Check } from 'lucide-react';
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

function NotificationBell() {
  const navigate = useNavigate();
  const [open, setOpen] = useState(false);
  const [data, setData] = useState({ notifications: [], unreadCount: 0 });
  const ref = useRef(null);

  const load = () => {
    api.get('/notifications').then(r => setData(r.data || { notifications: [], unreadCount: 0 })).catch(() => {});
  };

  useEffect(() => { load(); const i = setInterval(load, 30000); return () => clearInterval(i); }, []);

  useEffect(() => {
    const handler = (e) => { if (ref.current && !ref.current.contains(e.target)) setOpen(false); };
    document.addEventListener('mousedown', handler);
    return () => document.removeEventListener('mousedown', handler);
  }, []);

  const handleClick = (n) => {
    // Mark read immediately in local state
    setData(prev => ({
      notifications: prev.notifications.filter(x => x.id !== n.id),
      unreadCount: Math.max(0, prev.unreadCount - (n.read ? 0 : 1)),
    }));
    api.post(`/notifications/${n.id}/read`).catch(() => {});
    setOpen(false);
    if (n.link) navigate(n.link);
  };

  const markAllRead = () => {
    api.post('/notifications/read-all').then(load).catch(() => {});
  };

  const TYPE_ICONS = {
    REVIEW_ASSIGNED: { color: 'var(--brand-400)', label: 'Review' },
    DEADLINE_APPROACHING: { color: 'var(--warn-400)', label: 'Deadline' },
    CONTRACT_EXECUTED: { color: 'var(--success-400)', label: 'Executed' },
    CONTRACT_EXPIRING: { color: '#d29922', label: 'Expiring' },
    STATUS_CHANGED: { color: 'var(--info-400)', label: 'Status' },
    NOTE_ADDED: { color: 'var(--text-2)', label: 'Note' },
  };

  return (
    <div ref={ref} style={{ position: 'relative' }}>
      <button className="icon-btn" title="Notifications" onClick={() => setOpen(!open)}>
        <Bell size={16} />
        {data.unreadCount > 0 && (
          <span style={{
            position: 'absolute', top: 2, right: 2, width: 8, height: 8,
            borderRadius: '50%', background: 'var(--danger-400)',
          }} />
        )}
      </button>
      {open && (
        <div style={{
          position: 'absolute', top: '100%', right: 0, marginTop: 8,
          width: 360, maxHeight: 440, overflow: 'auto',
          background: 'var(--bg-1)', border: '1px solid var(--line-1)',
          borderRadius: 'var(--r-md)', boxShadow: '0 8px 24px rgba(0,0,0,0.15)',
          zIndex: 1000,
        }}>
          <div style={{
            display: 'flex', alignItems: 'center', justifyContent: 'space-between',
            padding: '10px 14px', borderBottom: '1px solid var(--line-1)',
          }}>
            <span style={{ fontSize: 13, fontWeight: 600, color: 'var(--text-1)' }}>
              Notifications {data.unreadCount > 0 && <span style={{ color: 'var(--brand-400)' }}>({data.unreadCount})</span>}
            </span>
            {data.unreadCount > 0 && (
              <button onClick={markAllRead}
                style={{ fontSize: 10, color: 'var(--brand-400)', background: 'none', border: 'none', cursor: 'pointer' }}>
                <Check size={10} /> Mark all read
              </button>
            )}
          </div>
          {data.notifications.length === 0 ? (
            <div style={{ padding: 24, textAlign: 'center', color: 'var(--text-3)', fontSize: 12 }}>
              No notifications yet
            </div>
          ) : data.notifications.map(n => {
            const t = TYPE_ICONS[n.type] || { color: 'var(--text-3)', label: n.type };
            return (
              <div key={n.id} onClick={() => handleClick(n)}
                style={{
                  display: 'flex', gap: 10, padding: '10px 14px', cursor: 'pointer',
                  borderBottom: '1px solid var(--line-1)',
                  background: n.read ? 'transparent' : 'var(--brand-400)04',
                }}
                onMouseEnter={e => e.currentTarget.style.background = 'var(--bg-2)'}
                onMouseLeave={e => e.currentTarget.style.background = n.read ? 'transparent' : 'var(--brand-400)04'}>
                <div style={{
                  width: 8, height: 8, borderRadius: '50%', marginTop: 4, flexShrink: 0,
                  background: n.read ? 'var(--line-2)' : t.color,
                }} />
                <div style={{ flex: 1, minWidth: 0 }}>
                  <div style={{ fontSize: 12, fontWeight: n.read ? 400 : 600, color: 'var(--text-1)' }}>
                    {n.title}
                  </div>
                  {n.message && (
                    <div style={{ fontSize: 11, color: 'var(--text-2)', marginTop: 2, lineHeight: 1.4 }}>
                      {n.message.length > 80 ? n.message.slice(0, 80) + '...' : n.message}
                    </div>
                  )}
                  <div style={{ fontSize: 10, color: 'var(--text-3)', marginTop: 3 }}>
                    <span style={{ color: t.color, fontWeight: 600 }}>{t.label}</span>
                    {' · '}{timeAgo(n.createdAt)}
                  </div>
                </div>
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}

function timeAgo(dateStr) {
  const diff = Date.now() - new Date(dateStr).getTime();
  const mins = Math.floor(diff / 60000);
  if (mins < 1) return 'just now';
  if (mins < 60) return mins + 'm ago';
  const hrs = Math.floor(mins / 60);
  if (hrs < 24) return hrs + 'h ago';
  const days = Math.floor(hrs / 24);
  return days + 'd ago';
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
            <NotificationBell />
          </div>
        </div>
        <div className="content-area">
          {children}
        </div>
      </div>
    </div>
  );
}
