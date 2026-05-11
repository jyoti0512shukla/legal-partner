import { useState, useEffect } from 'react';
import { Bell, Loader2 } from 'lucide-react';
import api from '../../api/client';

export default function DeadlineConfigTab() {
  const [config, setConfig] = useState(null);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);

  const load = () => {
    api.get('/documents/deadlines/config')
      .then(r => {
        const configs = r.data || [];
        // Use first enabled config, or first config, or default
        const active = configs.find(c => c.enabled) || configs[0];
        setConfig(active || { alertWindowDays: 180, enabled: false });
      })
      .catch(() => setConfig({ alertWindowDays: 180, enabled: false }))
      .finally(() => setLoading(false));
  };
  useEffect(() => { load(); }, []);

  const handleToggle = async () => {
    if (!config?.id) return;
    setSaving(true);
    try {
      await api.put(`/documents/deadlines/config/${config.id}`, { enabled: !config.enabled });
      load();
    } catch {}
    finally { setSaving(false); }
  };

  const handleDaysChange = async (days) => {
    const parsed = parseInt(days);
    if (!parsed || parsed <= 0 || !config?.id) return;
    setSaving(true);
    try {
      await api.put(`/documents/deadlines/config/${config.id}`, { alertWindowDays: parsed });
      load();
    } catch {}
    finally { setSaving(false); }
  };

  if (loading) return <div className="small muted">Loading...</div>;

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 20, maxWidth: 480 }}>
      <div>
        <h3 className="text-sm font-semibold text-text-primary mb-3">Deadline Alerts</h3>
        <p className="text-xs text-text-muted mb-4">
          Get notified in the dashboard when contracts are approaching expiry, notice deadlines, or renewal dates.
        </p>
      </div>

      <div className="card" style={{ padding: 20 }}>
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 20 }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
            <Bell size={18} style={{ color: config?.enabled ? 'var(--brand-400)' : 'var(--text-3)' }} />
            <div>
              <div style={{ fontWeight: 600, fontSize: 14 }}>Enable deadline alerts</div>
              <div className="small muted">Show upcoming deadlines on the dashboard</div>
            </div>
          </div>
          <label style={{ position: 'relative', display: 'inline-block', width: 44, height: 24, cursor: 'pointer' }}>
            <input type="checkbox" checked={config?.enabled || false} onChange={handleToggle}
              style={{ opacity: 0, width: 0, height: 0 }} />
            <span style={{
              position: 'absolute', inset: 0, borderRadius: 12,
              background: config?.enabled ? 'var(--brand-400)' : 'var(--bg-3)',
              transition: 'background 0.2s',
            }} />
            <span style={{
              position: 'absolute', top: 3, left: config?.enabled ? 23 : 3,
              width: 18, height: 18, borderRadius: '50%', background: 'white',
              transition: 'left 0.2s', boxShadow: '0 1px 3px rgba(0,0,0,0.2)',
            }} />
          </label>
        </div>

        {config?.enabled && (
          <div style={{ borderTop: '1px solid var(--line-1)', paddingTop: 16 }}>
            <label style={{ display: 'block', fontSize: 12, fontWeight: 600, color: 'var(--text-2)', marginBottom: 6 }}>
              Alert me this many days before a deadline
            </label>
            <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
              <input
                className="input"
                type="number"
                min="1"
                max="365"
                value={config?.alertWindowDays || 180}
                onChange={e => setConfig({ ...config, alertWindowDays: parseInt(e.target.value) || 180 })}
                onBlur={e => handleDaysChange(e.target.value)}
                style={{ width: 80, textAlign: 'center' }}
              />
              <span className="small muted">days before expiry / notice / renewal</span>
            </div>
            <div className="small muted" style={{ marginTop: 10, padding: 10, background: 'var(--bg-2)', borderRadius: 'var(--r-md)' }}>
              Example: If a contract expires on Jan 15, 2027 and you set 180 days, you'll see it on your dashboard starting Jul 19, 2026.
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
