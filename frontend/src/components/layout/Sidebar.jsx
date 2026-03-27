import { NavLink } from 'react-router-dom';
import { useAuth } from '../../hooks/useAuth';
import { useState } from 'react';
import { useLocation } from 'react-router-dom';
import { LayoutDashboard, Briefcase, Workflow, Shield, Settings, LogOut, Scale, FileText, Brain, ChevronDown, FileEdit, GitCompare, ClipboardList, Key } from 'lucide-react';

export default function Sidebar() {
  const { user, logout } = useAuth();

  return (
    <aside className="glass w-60 min-h-screen flex flex-col px-4 py-6">
      <div className="flex items-center gap-2.5 mb-8 px-2">
        <Scale className="w-7 h-7 text-primary" />
        <span className="text-lg font-bold font-display text-text-primary">Legal Partner</span>
      </div>

      <nav className="flex-1 space-y-1">
        <SidebarLink to="/" icon={LayoutDashboard} label="Dashboard" />
        <SidebarLink to="/matters" icon={Briefcase} label="Matters" />
        <SidebarLink to="/documents" icon={FileText} label="Documents" />
        <SidebarGroup icon={Brain} label="Intelligence" items={[
          { to: '/intelligence', icon: Brain, label: 'Ask AI' },
          { to: '/draft', icon: FileEdit, label: 'Draft' },
          { to: '/compare', icon: GitCompare, label: 'Compare' },
          { to: '/review', icon: ClipboardList, label: 'Review' },
          { to: '/extraction', icon: Key, label: 'Extraction' },
        ]} />
        <SidebarLink to="/workflows" icon={Workflow} label="Workflows" />
        <SidebarLink to="/playbooks" icon={Shield} label="Playbooks" />

        <div className="pt-4 mt-4 border-t border-border/50">
          <SidebarLink to="/settings" icon={Settings} label="Settings" />
        </div>
      </nav>

      <div className="border-t border-border/50 pt-4 mt-4">
        <div className="px-3 mb-3">
          <p className="text-sm font-medium text-text-primary">{user?.displayName || user?.email}</p>
          <p className="text-xs text-text-muted">{user?.role?.replace('ROLE_', '')}</p>
        </div>
        <button onClick={logout}
          className="flex items-center gap-2 px-3 py-2 text-sm text-text-muted hover:text-danger transition-colors w-full rounded-lg hover:bg-surface-el">
          <LogOut className="w-4 h-4" />
          Sign Out
        </button>
      </div>
    </aside>
  );
}

function SidebarLink({ to, icon: Icon, label }) {
  return (
    <NavLink to={to} end={to === '/matters'}
      className={({ isActive }) =>
        `flex items-center gap-3 px-3 py-2.5 rounded-lg text-sm font-medium transition-colors ${
          isActive
            ? 'bg-primary/10 text-primary'
            : 'text-text-secondary hover:text-text-primary hover:bg-surface-el'
        }`
      }>
      <Icon className="w-5 h-5" />
      {label}
    </NavLink>
  );
}

function SidebarGroup({ icon: Icon, label, items }) {
  const [open, setOpen] = useState(false);
  const loc = useLocation();
  const isChildActive = items.some(item => loc.pathname === item.to);

  return (
    <div>
      <button onClick={() => setOpen(!open)}
        className={`w-full flex items-center justify-between px-3 py-2.5 rounded-lg text-sm font-medium transition-colors ${
          isChildActive ? 'bg-primary/10 text-primary' : 'text-text-secondary hover:text-text-primary hover:bg-surface-el'
        }`}>
        <div className="flex items-center gap-3">
          <Icon className="w-5 h-5" />
          {label}
        </div>
        <ChevronDown className={`w-4 h-4 transition-transform ${open || isChildActive ? 'rotate-180' : ''}`} />
      </button>
      {(open || isChildActive) && (
        <div className="ml-4 mt-0.5 space-y-0.5">
          {items.map(item => (
            <NavLink key={item.to} to={item.to}
              className={({ isActive }) =>
                `flex items-center gap-2.5 px-3 py-2 rounded-lg text-xs font-medium transition-colors ${
                  isActive ? 'text-primary bg-primary/5' : 'text-text-muted hover:text-text-primary hover:bg-surface-el'
                }`
              }>
              <item.icon className="w-4 h-4" />
              {item.label}
            </NavLink>
          ))}
        </div>
      )}
    </div>
  );
}
