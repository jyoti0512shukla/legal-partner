import { NavLink } from 'react-router-dom';
import { useAuth } from '../../hooks/useAuth';
import { LayoutDashboard, Briefcase, Workflow, Shield, Settings, LogOut, Scale, FileText, Brain, FileEdit, GitCompare, ClipboardList, Key } from 'lucide-react';

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

        <SidebarSection label="AI Intelligence" />
        <SidebarLink to="/intelligence" icon={Brain} label="Ask AI" />
        <SidebarLink to="/draft" icon={FileEdit} label="Draft" />
        <SidebarLink to="/compare" icon={GitCompare} label="Compare" />
        <SidebarLink to="/review" icon={ClipboardList} label="Review" />
        <SidebarLink to="/extraction" icon={Key} label="Extraction" />

        <SidebarSection label="Manage" />
        <SidebarLink to="/workflows" icon={Workflow} label="Workflows" />
        <SidebarLink to="/playbooks" icon={Shield} label="Playbooks" />
        <SidebarLink to="/settings" icon={Settings} label="Settings" />
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

function SidebarSection({ label }) {
  return (
    <p className="text-[10px] font-semibold text-text-muted uppercase tracking-wider px-3 pt-4 pb-1">{label}</p>
  );
}
