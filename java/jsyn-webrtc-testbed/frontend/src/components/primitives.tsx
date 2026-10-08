/**
 * Visual primitives: Wordmark, Dot, Icon, Avatar, MicMeter.
 *
 * These are the lowest-level building blocks used across both lobby and
 * call screens. Dynamic colors (hex values with opacity) use inline styles;
 * structural layout uses Tailwind tokens where possible.
 */

/** Converts a hex color + alpha to rgba(). Used for dynamic opacity tinting. */
function hexA(hex: string, a: number): string {
  const h = hex.replace('#', '');
  const r = parseInt(h.slice(0, 2), 16);
  const g = parseInt(h.slice(2, 4), 16);
  const b = parseInt(h.slice(4, 6), 16);
  return `rgba(${r},${g},${b},${a})`;
}

/**
 * Wordmark — mint circle with angled slash + "synauson" text.
 * Matches the handoff's inline SVG construction exactly.
 */
export function Wordmark({ size = 14, dim = false }: { size?: number; dim?: boolean }) {
  const accent = '#3DDC97';
  return (
    <div
      style={{
        display: 'flex',
        alignItems: 'center',
        gap: size * 0.5,
        color: dim ? '#9aa0a6' : '#e7e9ec',
        fontSize: size,
        fontWeight: 600,
        letterSpacing: -0.2,
      }}
      className="font-sans"
    >
      <span style={{ display: 'inline-block', width: size * 0.95, height: size * 0.95, position: 'relative' }}>
        <span
          style={{
            position: 'absolute',
            inset: 0,
            borderRadius: '50%',
            border: `1.5px solid ${accent}`,
          }}
        />
        <span
          style={{
            position: 'absolute',
            top: '50%',
            left: '15%',
            right: '15%',
            height: 1.5,
            background: accent,
            transform: 'translateY(-50%) rotate(-22deg)',
          }}
        />
      </span>
      synauson
    </div>
  );
}

/**
 * Dot — colored circle, optionally pulsing with the `animate-live-pulse`
 * Tailwind animation defined in tailwind.config.js.
 */
export function Dot({
  color,
  size = 6,
  pulse = false,
}: {
  color: string;
  size?: number;
  pulse?: boolean;
}) {
  return (
    <span
      style={{
        display: 'inline-block',
        width: size,
        height: size,
        borderRadius: size / 2,
        background: color,
        flexShrink: 0,
      }}
      className={pulse ? 'animate-live-pulse' : ''}
    />
  );
}

/**
 * Icon — line-art SVG icons for mic, mic-off, phone-down, signal, check,
 * wave, and headphones. Paths copied verbatim from the handoff JSX.
 */
export function Icon({ name, size = 16 }: { name: string; size?: number }) {
  const s = size;
  const stroke = {
    fill: 'none' as const,
    stroke: 'currentColor',
    strokeWidth: 1.5,
    strokeLinecap: 'round' as const,
    strokeLinejoin: 'round' as const,
  };
  switch (name) {
    case 'mic':
      return (
        <svg width={s} height={s} viewBox="0 0 16 16" {...stroke}>
          <rect x="6" y="2" width="4" height="8" rx="2" />
          <path d="M4 8a4 4 0 0 0 8 0" />
          <path d="M8 12v2M5.5 14h5" />
        </svg>
      );
    case 'mic-off':
      return (
        <svg width={s} height={s} viewBox="0 0 16 16" {...stroke}>
          <rect x="6" y="2" width="4" height="8" rx="2" />
          <path d="M4 8a4 4 0 0 0 8 0" />
          <path d="M8 12v2M5.5 14h5" />
          <path d="M2 2l12 12" />
        </svg>
      );
    case 'phone-down':
      return (
        <svg width={s} height={s} viewBox="0 0 16 16" {...stroke}>
          <path
            d="M2 9a9 9 0 0 1 12 0l-1.5 1.5a1 1 0 0 1-1.3.1L10 9.5a1 1 0 0 0-1.1-.1l-1.8.9-1.8-.9A1 1 0 0 0 4.2 9.5l-1.2 1.1a1 1 0 0 1-1.3-.1z"
            transform="rotate(135 8 8)"
          />
        </svg>
      );
    case 'signal':
      return (
        <svg width={s} height={s} viewBox="0 0 16 16" fill="currentColor">
          <rect x="2" y="9" width="2" height="5" rx="0.5" />
          <rect x="6" y="6" width="2" height="8" rx="0.5" />
          <rect x="10" y="3" width="2" height="11" rx="0.5" />
        </svg>
      );
    case 'check':
      return (
        <svg width={s} height={s} viewBox="0 0 16 16" {...stroke}>
          <path d="M3 8.5l3 3 7-7" />
        </svg>
      );
    case 'wave':
      return (
        <svg width={s} height={s} viewBox="0 0 16 16" {...stroke}>
          <path d="M1 8h2l1-3 2 6 2-9 2 9 2-6 1 3h2" />
        </svg>
      );
    case 'headphones':
      return (
        <svg width={s} height={s} viewBox="0 0 16 16" {...stroke}>
          <path d="M2 11V8a6 6 0 0 1 12 0v3" />
          <rect x="2" y="10" width="3" height="5" rx="1" />
          <rect x="11" y="10" width="3" height="5" rx="1" />
        </svg>
      );
    default:
      return null;
  }
}

/**
 * Avatar — initials in a tinted disc.
 *
 * Background = color @ 22%, border = color @ 50%, text = full color.
 * Initials = first character of each word in name, max 2 chars.
 */
export function Avatar({
  name,
  color,
  size = 28,
}: {
  name: string;
  color: string;
  size?: number;
}) {
  const initials = name
    .split(' ')
    .map((w) => w[0])
    .slice(0, 2)
    .join('');

  return (
    <div
      style={{
        width: size,
        height: size,
        borderRadius: '50%',
        flexShrink: 0,
        background: hexA(color, 0.22),
        border: `1px solid ${hexA(color, 0.5)}`,
        color,
        fontSize: Math.max(11, size * 0.34),
        fontWeight: 600,
        letterSpacing: 0.3,
        position: 'relative',
        zIndex: 1,
      }}
      className="font-sans flex items-center justify-center"
    >
      {initials}
    </div>
  );
}

/**
 * MicMeter — 9-bar inline level indicator used in lobby form fields.
 * Shows a quick "is sound coming in" signal at a glance.
 */
export function MicMeter({
  levels = [0.2, 0.4, 0.7, 0.55, 0.3, 0.6, 0.85, 0.5, 0.3],
  color = '#3DDC97',
  active = true,
}: {
  levels?: number[];
  color?: string;
  active?: boolean;
}) {
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 2, height: 14 }}>
      {levels.map((v, i) => (
        <span
          key={i}
          style={{
            width: 2,
            height: Math.max(2, v * 14),
            borderRadius: 1,
            background: active ? color : '#3f444b',
            opacity: active ? 0.85 : 0.5,
          }}
        />
      ))}
    </div>
  );
}
