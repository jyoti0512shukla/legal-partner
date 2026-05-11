import { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { Users, Search, X, Loader2, FileText, Briefcase } from 'lucide-react';
import api from '../api/client';

export default function ClientsPage() {
  const navigate = useNavigate();
  const [clients, setClients] = useState([]);
  const [loading, setLoading] = useState(true);
  const [search, setSearch] = useState('');

  useEffect(() => {
    api.get('/documents/clients')
      .then(r => setClients(r.data || []))
      .catch(() => {})
      .finally(() => setLoading(false));
  }, []);

  const filtered = search
    ? clients.filter(c => c.clientName.toLowerCase().includes(search.toLowerCase()))
    : clients;

  return (
    <div className="page">
      <div className="page-header">
        <div>
          <h1 className="page-title">Clients</h1>
          <div className="small muted">{clients.length} client{clients.length !== 1 ? 's' : ''} across your contract portfolio</div>
        </div>
      </div>

      {/* Search */}
      <div style={{ position: 'relative', marginBottom: 16, maxWidth: 400 }}>
        <Search size={14} style={{ position: 'absolute', left: 10, top: '50%', transform: 'translateY(-50%)', color: 'var(--text-3)' }} />
        <input className="input" placeholder="Search clients..." value={search} onChange={e => setSearch(e.target.value)}
          style={{ paddingLeft: 32, width: '100%' }} />
        {search && (
          <button onClick={() => setSearch('')}
            style={{ position: 'absolute', right: 8, top: '50%', transform: 'translateY(-50%)', background: 'none', border: 'none', cursor: 'pointer', color: 'var(--text-3)' }}>
            <X size={14} />
          </button>
        )}
      </div>

      {loading ? (
        <div style={{ padding: 40, textAlign: 'center' }}><Loader2 size={20} className="animate-spin" style={{ color: 'var(--text-3)' }} /></div>
      ) : filtered.length === 0 ? (
        <div className="card" style={{ padding: 40, textAlign: 'center', color: 'var(--text-3)' }}>
          {search ? 'No clients match your search.' : 'No clients found. Upload contracts with client names to populate this view.'}
        </div>
      ) : (
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(320px, 1fr))', gap: 12 }}>
          {filtered.map(c => (
            <div key={c.clientName} className="card" style={{ padding: 16, cursor: 'pointer', transition: 'border-color 0.15s' }}
              onClick={() => navigate(`/clients/${encodeURIComponent(c.clientName)}`)}
              onMouseEnter={e => e.currentTarget.style.borderColor = 'var(--brand-400)'}
              onMouseLeave={e => e.currentTarget.style.borderColor = ''}>
              <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 10 }}>
                <div style={{
                  width: 36, height: 36, borderRadius: 8, background: 'var(--brand-400)12',
                  color: 'var(--brand-400)', display: 'grid', placeItems: 'center', flexShrink: 0,
                }}>
                  <Users size={16} />
                </div>
                <div style={{ minWidth: 0 }}>
                  <div style={{ fontWeight: 600, fontSize: 14, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                    {c.clientName}
                  </div>
                  <div style={{ fontSize: 11, color: 'var(--text-3)' }}>
                    Last activity: {c.latestActivity ? new Date(c.latestActivity).toLocaleDateString() : '—'}
                  </div>
                </div>
              </div>

              <div style={{ display: 'flex', gap: 16, fontSize: 12 }}>
                <div>
                  <div style={{ color: 'var(--text-3)', fontSize: 10, textTransform: 'uppercase', fontWeight: 600 }}>Contracts</div>
                  <div style={{ fontWeight: 600, fontSize: 16, color: 'var(--text-1)' }}>{c.contractCount}</div>
                </div>
                <div>
                  <div style={{ color: 'var(--text-3)', fontSize: 10, textTransform: 'uppercase', fontWeight: 600 }}>Active</div>
                  <div style={{ fontWeight: 600, fontSize: 16, color: c.activeCount > 0 ? 'var(--success-400)' : 'var(--text-3)' }}>{c.activeCount}</div>
                </div>
                <div style={{ flex: 1 }}>
                  <div style={{ color: 'var(--text-3)', fontSize: 10, textTransform: 'uppercase', fontWeight: 600 }}>Types</div>
                  <div style={{ fontSize: 11, color: 'var(--text-2)', marginTop: 2 }}>
                    {[...(c.contractTypes || [])].join(', ') || '—'}
                  </div>
                </div>
              </div>

              {c.matters?.size > 0 || (Array.isArray(c.matters) && c.matters.length > 0) ? (
                <div style={{ marginTop: 8, display: 'flex', alignItems: 'center', gap: 4, fontSize: 10, color: 'var(--text-3)' }}>
                  <Briefcase size={10} />
                  {[...(c.matters || [])].join(', ')}
                </div>
              ) : null}
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
