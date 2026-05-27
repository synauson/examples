/**
 * Maps a Smart-Turn probability to a visual bucket. Mirrors the design
 * spec's thresholds and colours.
 *
 *   >= 0.85 → "done"     (mint)
 *   >= 0.50 → "likely"   (amber)
 *   <  0.50 → "not done" (rose)
 */
export type Bucket = {
  color: string;
  tone: 'high' | 'medium' | 'low';
  label: 'done' | 'likely' | 'not done';
};

export function probBucket(p: number): Bucket {
  if (p >= 0.85) return { color: '#3DDC97', tone: 'high',   label: 'done' };
  if (p >= 0.50) return { color: '#FFB547', tone: 'medium', label: 'likely' };
  return                { color: '#FF6B8A', tone: 'low',    label: 'not done' };
}
