/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{js,jsx}'],
  theme: {
    extend: {
      colors: {
        // Theme-aware colors — reference CSS variables so they adapt to light/dark
        bg: 'var(--bg-0)',
        surface: 'var(--bg-1)',
        'surface-el': 'var(--bg-2)',
        'surface-bright': 'var(--bg-3)',
        border: 'var(--line-1)',
        'border-subtle': 'var(--line-2)',
        primary: 'var(--brand-500)',
        'primary-hover': 'var(--brand-400)',
        'primary-container': 'var(--brand-300)',
        secondary: 'var(--brand-400)',
        'secondary-container': 'var(--brand-50)',
        tertiary: 'var(--teal-400)',
        success: 'var(--success-500)',
        warning: 'var(--warn-500)',
        danger: 'var(--danger-500)',
        'text-primary': 'var(--text-1)',
        'text-secondary': 'var(--text-2)',
        'text-muted': 'var(--text-3)',
        'surface-tint': 'var(--brand-500)',
        gold: 'var(--warn-400)',
      },
      fontFamily: {
        sans: ['Inter', '-apple-system', 'BlinkMacSystemFont', 'Segoe UI', 'sans-serif'],
        display: ["'Source Serif 4'", 'Georgia', 'Times New Roman', 'serif'],
        mono: ["'JetBrains Mono'", 'ui-monospace', 'SFMono-Regular', 'Menlo', 'monospace'],
      },
      borderRadius: {
        'xl': '1rem',
        '2xl': '1.5rem',
      },
      boxShadow: {
        'ambient': 'var(--shadow-1)',
        'glow': 'var(--shadow-2)',
        'modal': 'var(--shadow-3)',
      },
    },
  },
  plugins: [],
};
