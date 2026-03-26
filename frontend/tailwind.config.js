/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{js,jsx}'],
  theme: {
    extend: {
      colors: {
        // Sovereign Counsel — Midnight Slate palette
        bg: '#060e20',
        surface: '#171f33',
        'surface-el': '#222a3d',
        'surface-bright': '#31394d',
        border: 'rgba(218, 226, 253, 0.08)',
        'border-subtle': 'rgba(218, 226, 253, 0.04)',
        primary: '#8083ff',
        'primary-hover': '#9b9eff',
        'primary-container': '#c0c1ff',
        secondary: '#a0b4fc',
        'secondary-container': '#1e2a4a',
        tertiary: '#ffb783',
        success: '#34d399',
        warning: '#fbbf24',
        danger: '#f87171',
        gold: '#D4A843',
        'text-primary': '#dae2fd',
        'text-secondary': '#8b95b0',
        'text-muted': '#5b6580',
        'surface-tint': '#8083ff',
      },
      fontFamily: {
        sans: ['Inter', 'system-ui', 'sans-serif'],
        display: ['Manrope', 'Inter', 'system-ui', 'sans-serif'],
        mono: ['JetBrains Mono', 'monospace'],
      },
      borderRadius: {
        'xl': '1rem',
        '2xl': '1.5rem',
      },
      boxShadow: {
        'ambient': '0 8px 40px rgba(218, 226, 253, 0.04)',
        'glow': '0 0 20px rgba(128, 131, 255, 0.15)',
        'modal': '0 16px 48px rgba(6, 14, 32, 0.8)',
      },
      backgroundImage: {
        'gradient-primary': 'linear-gradient(135deg, #8083ff, #c0c1ff)',
        'gradient-surface': 'linear-gradient(180deg, #171f33, #131b2e)',
      },
    },
  },
  plugins: [],
};
