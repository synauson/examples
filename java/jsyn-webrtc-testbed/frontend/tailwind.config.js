/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  theme: {
    extend: {
      // Design tokens straight out of the handoff's
      // design_handoff_synauson/README.md "Color" section.
      colors: {
        bg: {
          0: '#0a0b0d', 1: '#101216', 2: '#14171c', 3: '#1a1e24',
        },
        line: {
          DEFAULT: 'rgba(255, 255, 255, 0.06)',
          2: 'rgba(255, 255, 255, 0.10)',
        },
        ink: {
          1: '#e7e9ec', 2: '#9aa0a6', 3: '#60656d', 4: '#3f444b',
        },
        accent: {
          DEFAULT: '#3DDC97',
          dim: 'rgba(61, 220, 151, 0.12)',
        },
        danger: '#ff5e5e',
        p: {
          self: '#5BD3F5',
          remote: '#B58CFF',
        },
        prob: {
          high: '#3DDC97',
          mid:  '#FFB547',
          low:  '#FF6B8A',
        },
      },
      fontFamily: {
        sans: ['Geist', 'system-ui', '-apple-system', 'sans-serif'],
        mono: ['Geist Mono', 'ui-monospace', 'SF Mono', 'Menlo', 'monospace'],
      },
      // 1.6s ease-out pulse from the handoff's <style> block, ported.
      keyframes: {
        pulse: {
          '0%':   { boxShadow: '0 0 0 0 currentColor', opacity: '1' },
          '70%':  { boxShadow: '0 0 0 6px transparent', opacity: '0.85' },
          '100%': { boxShadow: '0 0 0 0 transparent', opacity: '1' },
        },
      },
      animation: {
        'live-pulse': 'pulse 1.6s ease-out infinite',
      },
    },
  },
  plugins: [],
};
