import { describe, expect, it } from 'vitest';
import { probBucket } from '../probBucket';

describe('probBucket', () => {
  it('returns mint for >= 0.85', () => {
    expect(probBucket(0.85).tone).toBe('high');
    expect(probBucket(1.0).tone).toBe('high');
  });
  it('returns amber for [0.50, 0.85)', () => {
    expect(probBucket(0.50).tone).toBe('medium');
    expect(probBucket(0.7).tone).toBe('medium');
    expect(probBucket(0.849).tone).toBe('medium');
  });
  it('returns rose for < 0.50', () => {
    expect(probBucket(0).tone).toBe('low');
    expect(probBucket(0.499).tone).toBe('low');
  });
});
