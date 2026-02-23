/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{js,jsx}'],
  theme: {
    extend: {
      colors: {
        bg: '#0A0E1A',
        surface: '#111827',
        'surface-el': '#1F2937',
        border: '#374151',
        primary: '#6366F1',
        'primary-hover': '#818CF8',
        success: '#10B981',
        warning: '#F59E0B',
        danger: '#EF4444',
        gold: '#D4A843',
        'text-primary': '#F9FAFB',
        'text-secondary': '#9CA3AF',
        'text-muted': '#6B7280',
      },
      fontFamily: {
        sans: ['Inter', 'system-ui', 'sans-serif'],
        mono: ['JetBrains Mono', 'monospace'],
      },
    },
  },
  plugins: [],
};
