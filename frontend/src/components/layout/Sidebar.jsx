import { useState, useEffect } from 'react';
import { NavLink } from 'react-router-dom';
import { useAuth } from '../../hooks/useAuth';
import { useTheme } from '../../hooks/useTheme';
import api from '../../api/client';
import {
  LayoutDashboard, Briefcase, FileText, Shield, Settings, LogOut,
  FileEdit, Wand2, Library, Users, Sparkles, Workflow, Brain,
  GitCompare, ClipboardList, Key, Sun, Moon,
} from 'lucide-react';

const NAV = [
  { section: 'Workspace', items: [
    { to: '/',           label: 'Dashboard',       icon: LayoutDashboard, end: true },
    { to: '/matters',    label: 'Matters',         icon: Briefcase, end: true },
    { to: '/documents',  label: 'Documents',       icon: FileText },
  ]},
  { section: 'CognitaAI', items: [
    { to: '/draft',        label: 'Draft',           icon: Wand2, badge: 'AI' },
    { to: '/review',       label: 'Contract Review', icon: Shield },
    { to: '/intelligence', label: 'Ask Cognita',     icon: Brain },
    { to: '/compare',      label: 'Compare',         icon: GitCompare },
    { to: '/extraction',   label: 'Extraction',      icon: Key },
  ]},
  { section: 'Manage', items: [
    { to: '/playbooks',     label: 'Playbooks',       icon: ClipboardList },
    { to: '/clause-library', label: 'Clause Library', icon: Library },
    { to: '/workflows',      label: 'AI Agents',      icon: Workflow },
    { to: '/settings',       label: 'Settings',        icon: Settings },
  ]},
];

export default function Sidebar() {
  const { user, logout } = useAuth();
  const { isDark, toggle: toggleTheme } = useTheme();
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
        <svg width="32" height="32" viewBox="0 0 64 64" xmlns="http://www.w3.org/2000/svg" style={{ flexShrink: 0 }}>
          <path d="M40 12 L46 12 L52 18 L52 22 M52 22 L52 14 L14 14 L14 50 L52 50 L52 42"
                fill="none" stroke="#3a86d8" strokeWidth="6" strokeLinejoin="miter" strokeLinecap="square"/>
          <path d="M46 12 L52 18 L46 18 Z" fill="#3a86d8" fillOpacity="0.45"/>
          <circle cx="48" cy="32" r="5" fill="#3a86d8"/>
          <circle cx="48" cy="32" r="1.8" fill="var(--bg-1)"/>
          <path d="M43 32 L36 32" stroke="#3a86d8" strokeWidth="2.2" strokeLinecap="round"/>
        </svg>
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
        <div style={{ marginLeft: 'auto', display: 'flex', gap: 4 }}>
          <button
            onClick={toggleTheme}
            className="icon-btn"
            title={isDark ? 'Switch to light mode' : 'Switch to dark mode'}
            style={{ width: 28, height: 28 }}
          >
            {isDark ? <Sun size={14} /> : <Moon size={14} />}
          </button>
          <button
            onClick={logout}
            className="icon-btn"
            title="Sign out"
            style={{ width: 28, height: 28 }}
          >
            <LogOut size={14} />
          </button>
        </div>
      </div>
    </aside>
  );
}
