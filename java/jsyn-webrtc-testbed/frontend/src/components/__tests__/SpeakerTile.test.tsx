import { render, screen } from '@testing-library/react';
import { SpeakerTile } from '../SpeakerTile';
import type { Participant } from '../SpeakerTile';

const p: Participant = { id: 'alice', name: 'Alice B', handle: 'a.b', color: '#5BD3F5' };

it('renders name and handle', () => {
  render(<SpeakerTile participant={p} status="listening" />);
  expect(screen.getByText('Alice B')).toBeInTheDocument();
  expect(screen.getByText('a.b')).toBeInTheDocument();
});

it('shows SPEAKING status', () => {
  render(<SpeakerTile participant={p} status="speaking" />);
  expect(screen.getByText('SPEAKING')).toBeInTheDocument();
});

it('shows MUTED status', () => {
  render(<SpeakerTile participant={p} status="muted" />);
  expect(screen.getByText('MUTED')).toBeInTheDocument();
});

it('does not accept a big prop (single-size tile)', () => {
  // The component should render the same regardless — no big variant
  const { container } = render(<SpeakerTile participant={p} status="listening" />);
  // Tile should NOT have min-height 320px (that was the old big-tile style)
  const tile = container.firstElementChild as HTMLElement;
  expect(tile.style.minHeight).not.toBe('320px');
});
