/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{js,jsx}'],
  theme: {
    extend: {
      colors: {
        // Dark slate palette — matching CSS variables
        bg: '#0b0f14',
        surface: '#11161d',
        'surface-el': '#161c25',
        'surface-bright': '#1d242f',
        border: '#222b37',
        'border-subtle': '#2c3644',
        primary: '#3a86d8',
        'primary-hover': '#5aa0ea',
        'primary-container': '#8dbff3',
        secondary: '#5aa0ea',
        'secondary-container': '#0f2033',
        tertiary: '#49c4be',
        success: '#3fa96b',
        warning: '#d89a3a',
        danger: '#d9534e',
        'text-primary': '#e7ecf3',
        'text-secondary': '#b3bdcc',
        'text-muted': '#7b879a',
        'surface-tint': '#3a86d8',
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
        'ambient': '0 1px 0 rgba(255,255,255,0.02) inset, 0 1px 2px rgba(0,0,0,0.35)',
        'glow': '0 4px 14px rgba(0,0,0,0.35), 0 1px 0 rgba(255,255,255,0.02) inset',
        'modal': '0 12px 32px rgba(0,0,0,0.45), 0 1px 0 rgba(255,255,255,0.03) inset',
      },
    },
  },
  plugins: [],
};
