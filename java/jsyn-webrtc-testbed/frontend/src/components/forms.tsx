import { type ReactNode } from 'react';
import { Icon } from './primitives';

function hexA(hex: string, a: number): string {
  const h = hex.replace('#', '');
  const r = parseInt(h.slice(0, 2), 16);
  const g = parseInt(h.slice(2, 4), 16);
  const b = parseInt(h.slice(4, 6), 16);
  return `rgba(${r},${g},${b},${a})`;
}

export function PillBtn({ icon, label, active = false, danger = false, onClick }: {
  icon: string; label: string; active?: boolean; danger?: boolean; onClick?: () => void;
}) {
  const accent = '#3DDC97';
  const dangerColor = '#ff5e5e';
  return (
    <button onClick={onClick} style={{
      height: 34, padding: '0 14px', display: 'inline-flex', alignItems: 'center', gap: 8,
      background: active ? hexA(accent, 0.12) : '#101216',
      border: `1px solid ${active ? hexA(accent, 0.35) : 'rgba(255,255,255,0.10)'}`,
      color: active ? accent : danger ? dangerColor : '#e7e9ec',
      borderRadius: 999, fontSize: 13, fontWeight: 500, cursor: 'pointer',
    }} className="font-sans">
      <Icon name={icon} size={14} />
      <span>{label}</span>
    </button>
  );
}

/** Call-screen action button. `fullWidth` spans its row container. */
export function ControlBtn({ icon, label, danger = false, fullWidth = false, onClick }: {
  icon: string; label: string; danger?: boolean; fullWidth?: boolean; onClick?: () => void;
}) {
  const dangerColor = '#ff5e5e';
  return (
    <button onClick={onClick} style={{
      height: 40, padding: '0 16px 0 14px',
      display: 'inline-flex', alignItems: 'center', gap: 10,
      background: danger ? hexA(dangerColor, 0.1) : '#101216',
      border: `1px solid ${danger ? hexA(dangerColor, 0.35) : 'rgba(255,255,255,0.10)'}`,
      color: danger ? dangerColor : '#e7e9ec',
      borderRadius: 10, fontSize: 13, fontWeight: 500, cursor: 'pointer',
      width: fullWidth ? '100%' : undefined,
      justifyContent: fullWidth ? 'center' : undefined,
    }} className="font-sans">
      <Icon name={icon} size={15} />
      <span>{label}</span>
    </button>
  );
}

/**
 * DeviceChip — compact device label with caret, wraps a hidden <select>.
 *
 * The hidden select is positioned over the entire chip so clicking anywhere
 * opens the native device picker. This avoids building a custom popover while
 * meeting the spec's interactivity requirement.
 *
 * `disabled` is true in browsers that don't support setSinkId (output pickers
 * in Safari/Firefox). When disabled, a tooltip explains why.
 */
export function DeviceChip({ devices, value, onChange, disabled = false, disabledTooltip }: {
  devices: MediaDeviceInfo[];
  value?: string;
  onChange?: (deviceId: string) => void;
  disabled?: boolean;
  disabledTooltip?: string;
}) {
  const label = devices.find(d => d.deviceId === value)?.label ||
    (value ? `Device …${value.slice(-4)}` : 'Select device');

  return (
    <div
      title={disabled ? disabledTooltip : undefined}
      style={{
        position: 'relative',
        height: 40, flex: 1,
        display: 'inline-flex', alignItems: 'center',
        gap: 8, padding: '0 10px 0 12px',
        background: '#101216',
        border: '1px solid rgba(255,255,255,0.10)',
        borderRadius: 10, fontSize: 12,
        color: disabled ? '#3f444b' : '#9aa0a6',
        cursor: disabled ? 'not-allowed' : 'pointer',
        overflow: 'hidden',
      }}
      className="font-sans"
    >
      <span style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', flex: 1 }}>
        {label}
      </span>
      <svg width={9} height={9} viewBox="0 0 9 9" fill="none" stroke="currentColor" strokeWidth="1.3" strokeLinecap="round" style={{ opacity: 0.55, flexShrink: 0 }}>
        <path d="M1.5 3.5L4.5 6 7.5 3.5" />
      </svg>
      {!disabled && (
        <select
          value={value ?? ''}
          onChange={e => onChange?.(e.target.value)}
          style={{ position: 'absolute', inset: 0, opacity: 0, cursor: 'pointer', width: '100%' }}
          aria-label="Select device"
        >
          {devices.map(d => (
            <option key={d.deviceId} value={d.deviceId}>
              {d.label || `Device …${d.deviceId.slice(-4)}`}
            </option>
          ))}
        </select>
      )}
    </div>
  );
}

export function Field({ label, meta, children }: { label: string; meta?: ReactNode; children: ReactNode }) {
  return (
    <div>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 8 }}>
        <label style={{ fontSize: 11, color: '#60656d', letterSpacing: 0.5 }} className="font-mono">{label.toUpperCase()}</label>
        {meta}
      </div>
      {children}
    </div>
  );
}

export function DeviceSelect({ devices = [], value, onChange }: {
  devices?: MediaDeviceInfo[]; value?: string; onChange?: (deviceId: string) => void;
}) {
  if (devices.length === 0) {
    return (
      <div style={{ height: 42, padding: '0 14px', background: '#101216', border: '1px solid rgba(255,255,255,0.10)', borderRadius: 10, display: 'flex', alignItems: 'center', justifyContent: 'space-between', color: '#e7e9ec', fontSize: 14 }} className="font-sans">
        <span style={{ color: '#60656d' }}>loading devices…</span>
        <svg width={9} height={9} viewBox="0 0 9 9" fill="none" stroke="currentColor" strokeWidth="1.3" strokeLinecap="round" style={{ opacity: 0.55 }}>
          <path d="M1.5 3.5L4.5 6 7.5 3.5" />
        </svg>
      </div>
    );
  }
  return (
    <select value={value} onChange={e => onChange?.(e.target.value)} style={{
      width: '100%', height: 42, padding: '0 14px',
      background: '#101216', border: '1px solid rgba(255,255,255,0.10)',
      borderRadius: 10, color: '#e7e9ec', fontSize: 14,
      outline: 'none', appearance: 'none', cursor: 'pointer',
    }} className="font-sans">
      {devices.map(d => (
        <option key={d.deviceId} value={d.deviceId}>
          {d.label || `Device …${d.deviceId.slice(-4)}`}
        </option>
      ))}
    </select>
  );
}

/** Toolbar filter chip used in EventStream header. Mono micro-caps, 26px height. */
export function FilterChip({ label, active, color, muted = false, onClick }: {
  label: string; active?: boolean; color?: string; muted?: boolean; onClick?: () => void;
}) {
  const accent = '#3DDC97';
  const fg = active ? (color ?? accent) : muted ? '#3f444b' : '#60656d';
  const bg = active ? (color ? hexA(color, 0.1) : hexA(accent, 0.1)) : 'transparent';
  const bd = active ? (color ? hexA(color, 0.3) : hexA(accent, 0.3)) : 'rgba(255,255,255,0.08)';
  return (
    <button onClick={onClick} style={{
      height: 26, padding: '0 10px', borderRadius: 6, cursor: 'pointer',
      background: bg, border: `1px solid ${bd}`, color: fg,
      fontSize: 10.5, letterSpacing: 0.5, display: 'inline-flex', alignItems: 'center', gap: 5,
    }} className="font-mono">
      {color && !active && <span style={{ width: 5, height: 5, borderRadius: '50%', background: color, flexShrink: 0 }} />}
      {label}
    </button>
  );
}
