import { NavLink } from 'react-router-dom';
import { useEffect, useState } from 'react';
import { useAuth } from '../../hooks/useAuth';
import { LayoutDashboard, Brain, FileText, FileEdit, GitCompare, ClipboardList, ScrollText, LogOut, Scale, Settings, Briefcase, Key, Workflow, BookOpen, DatabaseZap } from 'lucide-react';

const NAV_ITEMS = [
  { to: '/', icon: LayoutDashboard, label: 'Dashboard' },
  { to: '/matters', icon: Briefcase, label: 'Matters' },
  { to: '/intelligence', icon: Brain, label: 'Intelligence' },
  { to: '/documents', icon: FileText, label: 'Documents' },
  { to: '/draft', icon: FileEdit, label: 'Draft' },
  { to: '/clause-library', icon: BookOpen, label: 'Clause Library' },
  { to: '/compare', icon: GitCompare, label: 'Compare' },
  { to: '/review', icon: ClipboardList, label: 'Contract Review' },
  { to: '/extraction', icon: Key, label: 'Extraction' },
  { to: '/workflows', icon: Workflow, label: 'Workflows' },
  { to: '/settings', icon: Settings, label: 'Settings' },
];

export default function Sidebar() {
  const { user, logout } = useAuth();
  const isPartnerOrAdmin = user?.role === 'ROLE_PARTNER' || user?.role === 'ROLE_ADMIN';
  const [legalSystem, setLegalSystem] = useState(null);

  useEffect(() => {
    fetch('/api/v1/ai/legal-system')
      .then(r => r.ok ? r.json() : null)
      .then(data => { if (data) setLegalSystem(data); })
      .catch(() => {});
  }, []);

  return (
    <aside className="glass w-64 min-h-screen flex flex-col px-4 py-6">
      <div className="flex items-center gap-2.5 mb-2 px-2">
        <Scale className="w-7 h-7 text-gold" />
        <span className="text-lg font-bold text-text-primary">Legal Partner</span>
      </div>
      {legalSystem && (
        <div className="px-2 mb-8">
          <span className="inline-flex items-center gap-1.5 px-2 py-0.5 rounded text-xs font-medium bg-surface-el text-text-muted border border-border">
            {legalSystem.system === 'USA' ? '🇺🇸' : '🇮🇳'}
            {legalSystem.system === 'USA' ? 'US Law' : 'Indian Law'}
          </span>
        </div>
      )}

      <nav className="flex-1 space-y-1">
        {NAV_ITEMS.map(({ to, icon: Icon, label }) => (
          <NavLink
            key={to}
            to={to}
            end={to === '/'}
            className={({ isActive }) =>
              `flex items-center gap-3 px-3 py-2.5 rounded-lg text-sm font-medium transition-colors ${
                isActive
                  ? 'bg-primary/10 text-primary border-l-[3px] border-primary -ml-[3px]'
                  : 'text-text-secondary hover:text-text-primary hover:bg-surface-el'
              }`
            }
          >
            <Icon className="w-5 h-5" />
            {label}
          </NavLink>
        ))}
        {isPartnerOrAdmin && (
          <NavLink
            to="/edgar-import"
            className={({ isActive }) =>
              `flex items-center gap-3 px-3 py-2.5 rounded-lg text-sm font-medium transition-colors ${
                isActive
                  ? 'bg-primary/10 text-primary border-l-[3px] border-primary -ml-[3px]'
                  : 'text-text-secondary hover:text-text-primary hover:bg-surface-el'
              }`
            }
          >
            <DatabaseZap className="w-5 h-5" />
            Seed Corpus
          </NavLink>
        )}
        {isPartnerOrAdmin && (
          <NavLink
            to="/audit"
            className={({ isActive }) =>
              `flex items-center gap-3 px-3 py-2.5 rounded-lg text-sm font-medium transition-colors ${
                isActive
                  ? 'bg-primary/10 text-primary border-l-[3px] border-primary -ml-[3px]'
                  : 'text-text-secondary hover:text-text-primary hover:bg-surface-el'
              }`
            }
          >
            <ScrollText className="w-5 h-5" />
            Audit Log
          </NavLink>
        )}
      </nav>

      <div className="border-t border-border pt-4 mt-4">
        <div className="px-3 mb-3">
          <p className="text-sm font-medium text-text-primary">{user?.displayName || user?.email}</p>
          <p className="text-xs text-text-muted">{user?.role?.replace('ROLE_', '')}</p>
        </div>
        <button onClick={logout} className="flex items-center gap-2 px-3 py-2 text-sm text-text-muted hover:text-danger transition-colors w-full rounded-lg hover:bg-surface-el">
          <LogOut className="w-4 h-4" />
          Sign Out
        </button>
      </div>
    </aside>
  );
}
