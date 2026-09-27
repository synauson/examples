/**
 * Maps a Smart-Turn probability to a visual bucket.
 *
 * Thresholds calibrated against observed Smart Turn v3 output distribution:
 *   - "not done" cluster:  0.006 – 0.18  (model confident more speech follows)
 *   - "uncertain" gap:     0.18  – 0.65  (model unsure; turn may be ending)
 *   - "done" cluster:      0.65  – 1.0   (model confident turn is complete)
 *
 *   >= 0.65 → "done"     (mint)
 *   >= 0.20 → "likely"   (amber)
 *   <  0.20 → "not done" (rose)
 */
export type Bucket = {
  color: string;
  tone: 'high' | 'medium' | 'low';
  label: 'done' | 'likely' | 'not done';
};

export function probBucket(p: number): Bucket {
  if (p >= 0.65) return { color: '#3DDC97', tone: 'high',   label: 'done' };
  if (p >= 0.20) return { color: '#FFB547', tone: 'medium', label: 'likely' };
  return                { color: '#FF6B8A', tone: 'low',    label: 'not done' };
}
