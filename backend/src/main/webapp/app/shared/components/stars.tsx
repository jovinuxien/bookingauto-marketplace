import React from 'react';

/**
 * A rating, as stars and a number.
 *
 * Nothing when there is no rating: a new salon shows no stars rather than
 * zero of them, because zero reads as "terrible" and absent reads as "new".
 */
export const Stars = ({ average, count, small }: { average: number | null; count: number; small?: boolean }) => {
  if (average === null || count === 0) {
    return null;
  }
  const rounded = Math.round(average * 10) / 10;
  return (
    <span className={small ? 'small' : ''} aria-label={`Betyg ${rounded.toLocaleString('sv-SE')} av 5, ${count} omdömen`}>
      <span aria-hidden="true">★</span> {rounded.toLocaleString('sv-SE')}
      <span className="text-muted"> ({count})</span>
    </span>
  );
};
