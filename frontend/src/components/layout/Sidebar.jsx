import { NavLink } from 'react-router-dom';
import { useAuth } from '../../hooks/useAuth';
import { LayoutDashboard, Brain, FileText, GitCompare, ShieldAlert, ScrollText, LogOut, Scale } from 'lucide-react';

const NAV_ITEMS = [
  { to: '/', icon: LayoutDashboard, label: 'Dashboard' },
  { to: '/intelligence', icon: Brain, label: 'Intelligence' },
  { to: '/documents', icon: FileText, label: 'Documents' },
  { to: '/compare', icon: GitCompare, label: 'Compare' },
  { to: '/risk', icon: ShieldAlert, label: 'Risk' },
];

export default function Sidebar() {
  const { user, logout } = useAuth();
  const isPartnerOrAdmin = user?.role === 'ROLE_PARTNER' || user?.role === 'ROLE_ADMIN';

  return (
    <aside className="glass w-64 min-h-screen flex flex-col px-4 py-6">
      <div className="flex items-center gap-2.5 mb-10 px-2">
        <Scale className="w-7 h-7 text-gold" />
        <span className="text-lg font-bold text-text-primary">Legal Partner</span>
      </div>

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
          <p className="text-sm font-medium text-text-primary capitalize">{user?.username}</p>
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
