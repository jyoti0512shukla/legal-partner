import { useState, useEffect } from 'react';
import { Plus, Trash2, Save, RefreshCw, Loader2 } from 'lucide-react';
import api from '../../api/client';

const CHANNELS = ['EMAIL', 'SLACK', 'TEAMS', 'IN_APP'];

export default function DeadlineConfigTab() {
  const [configs, setConfigs] = useState([]);
  const [loading, setLoading] = useState(true);
  const [newDays, setNewDays] = useState('');
  const [newChannel, setNewChannel] = useState('EMAIL');

  const load = () => {
    api.get('/documents/deadlines/config')
      .then(r => setConfigs(r.data || []))
      .catch(() => {})
      .finally(() => setLoading(false));
  };
  useEffect(() => { load(); }, []);

  const addConfig = async () => {
    const days = parseInt(newDays);
    if (!days || days <= 0) return;
    await api.post('/documents/deadlines/config', { alertWindowDays: days, notifyChannel: newChannel });
    setNewDays('');
    load();
  };

  const toggleEnabled = async (cfg) => {
    await api.put(`/documents/deadlines/config/${cfg.id}`, { enabled: !cfg.enabled });
    load();
  };

  const updateChannel = async (cfg, channel) => {
    await api.put(`/documents/deadlines/config/${cfg.id}`, { notifyChannel: channel });
    load();
  };

  const remove = async (id) => {
    await api.delete(`/documents/deadlines/config/${id}`);
    load();
  };

  if (loading) return <div className="small muted">Loading...</div>;

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
      <div>
        <h3 style={{ fontSize: 14, marginBottom: 4 }}>Deadline Alert Windows</h3>
        <p className="small muted">Configure when alerts are sent before contract deadlines (expiry, notice periods, renewals).</p>
      </div>

      <div className="card">
        <table style={{ width: '100%', fontSize: 13 }}>
          <thead>
            <tr style={{ borderBottom: '1px solid var(--line-1)' }}>
              <th style={{ textAlign: 'left', padding: '8px 12px', fontWeight: 600, fontSize: 11, textTransform: 'uppercase', color: 'var(--text-3)' }}>Days Before</th>
              <th style={{ textAlign: 'left', padding: '8px 12px', fontWeight: 600, fontSize: 11, textTransform: 'uppercase', color: 'var(--text-3)' }}>Channel</th>
              <th style={{ textAlign: 'center', padding: '8px 12px', fontWeight: 600, fontSize: 11, textTransform: 'uppercase', color: 'var(--text-3)' }}>Enabled</th>
              <th style={{ width: 40 }}></th>
            </tr>
          </thead>
          <tbody>
            {configs.map(cfg => (
              <tr key={cfg.id} style={{ borderBottom: '1px solid var(--line-1)' }}>
                <td style={{ padding: '8px 12px', fontWeight: 600 }}>{cfg.alertWindowDays} days</td>
                <td style={{ padding: '8px 12px' }}>
                  <select className="select" value={cfg.notifyChannel} onChange={e => updateChannel(cfg, e.target.value)}
                          style={{ fontSize: 12, padding: '2px 6px' }}>
                    {CHANNELS.map(c => <option key={c} value={c}>{c}</option>)}
                  </select>
                </td>
                <td style={{ padding: '8px 12px', textAlign: 'center' }}>
                  <input type="checkbox" checked={cfg.enabled} onChange={() => toggleEnabled(cfg)} />
                </td>
                <td style={{ padding: '8px 12px' }}>
                  <button className="icon-btn" onClick={() => remove(cfg.id)} style={{ width: 24, height: 24 }}>
                    <Trash2 size={12} />
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
        <input className="input" type="number" placeholder="Days" value={newDays} onChange={e => setNewDays(e.target.value)}
               style={{ width: 80 }} />
        <select className="select" value={newChannel} onChange={e => setNewChannel(e.target.value)} style={{ fontSize: 12 }}>
          {CHANNELS.map(c => <option key={c} value={c}>{c}</option>)}
        </select>
        <button className="btn sm" onClick={addConfig}>
          <Plus size={12} /> Add Window
        </button>
      </div>
    </div>
  );
}
