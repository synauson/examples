import { describe, expect, it } from 'vitest';
import { probBucket } from '../probBucket';

// Thresholds follow the Smart Turn v3 calibration documented in probBucket.ts.
describe('probBucket', () => {
  it('returns done (mint) for >= 0.65', () => {
    expect(probBucket(0.65)).toMatchObject({ tone: 'high', label: 'done' });
    expect(probBucket(1.0).tone).toBe('high');
  });
  it('returns likely (amber) for [0.20, 0.65)', () => {
    expect(probBucket(0.20)).toMatchObject({ tone: 'medium', label: 'likely' });
    expect(probBucket(0.5).tone).toBe('medium');
    expect(probBucket(0.649).tone).toBe('medium');
  });
  it('returns not done (rose) for < 0.20', () => {
    expect(probBucket(0)).toMatchObject({ tone: 'low', label: 'not done' });
    expect(probBucket(0.199).tone).toBe('low');
  });
});
