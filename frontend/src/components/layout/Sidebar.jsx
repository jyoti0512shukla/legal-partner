import { useState, useEffect } from 'react';
import { NavLink } from 'react-router-dom';
import { useAuth } from '../../hooks/useAuth';
import api from '../../api/client';
import {
  LayoutDashboard, Briefcase, FileText, Shield, Settings, LogOut,
  FileEdit, Wand2, Library, Users, Sparkles, Workflow, Brain,
  GitCompare, ClipboardList, Key,
} from 'lucide-react';

const NAV = [
  { section: 'Workspace', items: [
    { to: '/',           label: 'Dashboard',       icon: LayoutDashboard, end: true },
    { to: '/matters',    label: 'Matters',         icon: Briefcase, end: true },
    { to: '/documents',  label: 'Documents',       icon: FileText },
  ]},
  { section: 'AI Intelligence', items: [
    { to: '/draft',        label: 'Draft',           icon: Wand2, badge: 'AI' },
    { to: '/review',       label: 'Contract Review', icon: Shield },
    { to: '/intelligence', label: 'Ask AI',          icon: Brain },
    { to: '/compare',      label: 'Compare',         icon: GitCompare },
    { to: '/extraction',   label: 'Extraction',      icon: Key },
  ]},
  { section: 'Manage', items: [
    { to: '/clause-library', label: 'Clause Library', icon: Library },
    { to: '/workflows',      label: 'AI Agents',      icon: Workflow },
    { to: '/settings',       label: 'Settings',        icon: Settings },
  ]},
];

export default function Sidebar() {
  const { user, logout } = useAuth();
  const [orgName, setOrgName] = useState('');
  useEffect(() => {
    api.get('/ai/legal-system').then(r => {
      if (r.data?.organizationName) setOrgName(r.data.organizationName);
    }).catch(() => {});
  }, []);

  const initials = user?.displayName
    ? user.displayName.split(' ').map(w => w[0]).join('').slice(0, 2).toUpperCase()
    : (user?.email?.[0] || 'U').toUpperCase();

  return (
    <aside className="sidebar">
      <NavLink to="/" className="brand" style={{ textDecoration: 'none', color: 'inherit' }}>
        <div className="brand-logo">C</div>
        <div>
          <div className="brand-name">ContractIQ</div>
          <div className="brand-tag">{orgName || 'AI-Powered Contract Intelligence'}</div>
        </div>
      </NavLink>

      {NAV.map((group) => (
        <div key={group.section}>
          <div className="nav-section">{group.section}</div>
          {group.items.map(it => {
            const Icon = it.icon;
            return (
              <NavLink
                key={it.to}
                to={it.to}
                end={it.end}
                className={({ isActive }) => `nav-item ${isActive ? 'active' : ''}`}
              >
                <Icon size={16} />
                <span>{it.label}</span>
                {it.badge && (
                  <span className="badge info" style={{ marginLeft: 'auto', height: 18, fontSize: 10 }}>
                    {it.badge}
                  </span>
                )}
                {it.count && <span className="count">{it.count}</span>}
              </NavLink>
            );
          })}
        </div>
      ))}

      <div className="user-chip">
        <div className="avatar">{initials}</div>
        <div className="meta">
          <div className="name">{user?.displayName || user?.email}</div>
          <div className="role">{user?.role?.replace('ROLE_', '')}</div>
        </div>
        <button
          onClick={logout}
          className="icon-btn"
          title="Sign out"
          style={{ marginLeft: 'auto', width: 28, height: 28 }}
        >
          <LogOut size={14} />
        </button>
      </div>
    </aside>
  );
}
