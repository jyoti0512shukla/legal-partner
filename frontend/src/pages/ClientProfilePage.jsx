import { useState, useEffect } from 'react';
import { useParams, useNavigate, Link } from 'react-router-dom';
import { ArrowLeft, FileText, Briefcase, Loader2, TrendingUp } from 'lucide-react';
import api from '../api/client';
import StatusBadge from '../components/contract/StatusBadge';

export default function ClientProfilePage() {
  const { name } = useParams();
  const navigate = useNavigate();
  const [profile, setProfile] = useState(null);
  const [loading, setLoading] = useState(true);
  const [tab, setTab] = useState('contracts');

  useEffect(() => {
    setLoading(true);
    api.get(`/documents/clients/${encodeURIComponent(name)}/profile`)
      .then(r => setProfile(r.data))
      .catch(() => {})
      .finally(() => setLoading(false));
  }, [name]);

  if (loading) return (
    <div className="page" style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', minHeight: '60vh' }}>
      <Loader2 className="animate-spin" size={24} style={{ color: 'var(--text-3)' }} />
    </div>
  );

  if (!profile) return (
    <div className="page">
      <Link to="/clients" style={{ fontSize: 13, color: 'var(--text-3)', display: 'flex', alignItems: 'center', gap: 4, marginBottom: 12 }}>
        <ArrowLeft size={14} /> Back to Clients
      </Link>
      <div className="card" style={{ padding: 40, textAlign: 'center', color: 'var(--text-3)' }}>Client not found.</div>
    </div>
  );

  const lt = profile.latestTerms || {};
  const contracts = profile.contracts || [];
  const termsHistory = profile.keyTermsHistory || {};
  const matters = profile.matters || [];

  return (
    <div className="page">
      <Link to="/clients" style={{ fontSize: 13, color: 'var(--text-3)', display: 'flex', alignItems: 'center', gap: 4, marginBottom: 12 }}>
        <ArrowLeft size={14} /> Back to Clients
      </Link>

      {/* Header */}
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 20 }}>
        <div>
          <h1 className="page-title">{decodeURIComponent(name)}</h1>
          <div className="small muted">{contracts.length} contract{contracts.length !== 1 ? 's' : ''}</div>
        </div>
        <button className="btn primary" onClick={() => navigate(`/documents?search=${encodeURIComponent(name)}`)}>
          View in Portfolio
        </button>
      </div>

      {/* Stats */}
      <div className="grid-4" style={{ marginBottom: 18 }}>
        <div className="stat">
          <div className="label">Total Contracts</div>
          <div className="value">{contracts.length}</div>
        </div>
        <div className="stat">
          <div className="label">Active</div>
          <div className="value" style={{ color: 'var(--success-400)' }}>
            {contracts.filter(c => c.contractStatus === 'ACTIVE' || c.contractStatus === 'EXPIRING').length}
          </div>
        </div>
        <div className="stat">
          <div className="label">Latest Terms</div>
          <div className="small" style={{ marginTop: 4 }}>
            {lt.contractType || '—'}
            {lt.contractValue && <span> · {lt.contractValue}</span>}
          </div>
        </div>
        <div className="stat">
          <div className="label">Matters</div>
          <div className="value">{Array.isArray(matters) ? matters.length : Object.keys(matters).length}</div>
        </div>
      </div>

      {/* Tabs */}
      <div style={{ display: 'flex', gap: 0, borderBottom: '1px solid var(--line-1)', marginBottom: 16 }}>
        {[
          { id: 'contracts', label: 'Contracts' },
          { id: 'terms', label: 'Key Terms History' },
          { id: 'matters', label: 'Matters' },
        ].map(t => (
          <button key={t.id} onClick={() => setTab(t.id)}
            style={{
              padding: '8px 16px', fontSize: 13, fontWeight: 500, cursor: 'pointer',
              background: 'none', border: 'none',
              borderBottom: tab === t.id ? '2px solid var(--brand-400)' : '2px solid transparent',
              color: tab === t.id ? 'var(--brand-400)' : 'var(--text-3)',
            }}>
            {t.label}
          </button>
        ))}
      </div>

      {/* Contracts tab */}
      {tab === 'contracts' && (
        <div className="card" style={{ overflow: 'hidden' }}>
          <table style={{ width: '100%', fontSize: 13, borderCollapse: 'collapse' }}>
            <thead>
              <tr style={{ borderBottom: '1px solid var(--line-1)' }}>
                <th style={{ textAlign: 'left', padding: '8px 12px', fontWeight: 600, fontSize: 11, color: 'var(--text-3)', textTransform: 'uppercase' }}>Contract</th>
                <th style={{ textAlign: 'left', padding: '8px 12px', fontWeight: 600, fontSize: 11, color: 'var(--text-3)', textTransform: 'uppercase' }}>Status</th>
                <th style={{ textAlign: 'left', padding: '8px 12px', fontWeight: 600, fontSize: 11, color: 'var(--text-3)', textTransform: 'uppercase' }}>Type</th>
                <th style={{ textAlign: 'left', padding: '8px 12px', fontWeight: 600, fontSize: 11, color: 'var(--text-3)', textTransform: 'uppercase' }}>Value</th>
                <th style={{ textAlign: 'left', padding: '8px 12px', fontWeight: 600, fontSize: 11, color: 'var(--text-3)', textTransform: 'uppercase' }}>Expiry</th>
              </tr>
            </thead>
            <tbody>
              {contracts.map(c => (
                <tr key={c.id} style={{ borderBottom: '1px solid var(--line-1)', cursor: 'pointer' }}
                  onClick={() => navigate(`/documents?docId=${c.id}`)}
                  onMouseEnter={e => e.currentTarget.style.background = 'var(--bg-2)'}
                  onMouseLeave={e => e.currentTarget.style.background = ''}>
                  <td style={{ padding: '10px 12px' }}>
                    <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                      <FileText size={13} style={{ color: 'var(--text-3)', flexShrink: 0 }} />
                      <div style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', maxWidth: 250 }}>
                        {c.fileName}
                      </div>
                    </div>
                  </td>
                  <td style={{ padding: '10px 12px' }}>
                    {c.contractStatus ? <StatusBadge status={c.contractStatus} size="sm" /> : <span className="muted">—</span>}
                  </td>
                  <td style={{ padding: '10px 12px', color: 'var(--text-2)' }}>{c.documentType || '—'}</td>
                  <td style={{ padding: '10px 12px', color: 'var(--text-2)' }}>{c.contractValue || '—'}</td>
                  <td style={{ padding: '10px 12px', color: 'var(--text-2)' }}>{c.expiryDate || '—'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {/* Key Terms History tab */}
      {tab === 'terms' && (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
          {Object.keys(termsHistory).length === 0 ? (
            <div className="card" style={{ padding: 24, textAlign: 'center', color: 'var(--text-3)' }}>
              No key terms extracted yet. Run extraction on this client's contracts to populate.
            </div>
          ) : Object.entries(termsHistory).map(([field, values]) => (
            <div key={field} className="card" style={{ padding: 14 }}>
              <div style={{ fontSize: 11, fontWeight: 600, color: 'var(--text-3)', textTransform: 'uppercase', marginBottom: 6 }}>
                {field.replace(/([A-Z])/g, ' $1').trim()}
              </div>
              <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
                {values.map((v, i) => (
                  <div key={i} style={{
                    display: 'flex', alignItems: 'center', gap: 8, fontSize: 12,
                    padding: '4px 8px', background: i === 0 ? 'var(--brand-400)08' : 'var(--bg-2)',
                    borderRadius: 'var(--r-sm)',
                  }}>
                    {i === 0 && <TrendingUp size={10} style={{ color: 'var(--brand-400)' }} />}
                    <span style={{ color: i === 0 ? 'var(--text-1)' : 'var(--text-2)', fontWeight: i === 0 ? 500 : 400 }}>{v}</span>
                    {i === 0 && <span style={{ fontSize: 9, color: 'var(--brand-400)', marginLeft: 'auto' }}>Latest</span>}
                  </div>
                ))}
              </div>
            </div>
          ))}
        </div>
      )}

      {/* Matters tab */}
      {tab === 'matters' && (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
          {(Array.isArray(matters) ? matters : []).length === 0 ? (
            <div className="card" style={{ padding: 24, textAlign: 'center', color: 'var(--text-3)' }}>
              No matters linked to this client's contracts.
            </div>
          ) : (Array.isArray(matters) ? matters : []).map(m => (
            <div key={m.id || m.name} className="card" style={{ padding: 14, cursor: 'pointer' }}
              onClick={() => navigate(`/matters/${m.id}`)}>
              <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                <Briefcase size={14} style={{ color: 'var(--text-3)' }} />
                <span style={{ fontWeight: 500, fontSize: 13 }}>{m.name}</span>
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
