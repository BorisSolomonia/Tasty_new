/** Display labels for product categories (shared by the audit-control page and its charts). */
export const CATEGORY_LABELS: Record<string, string> = {
  BEEF: '🐄 Beef',
  PORK: '🐷 Pork',
  SHEEP: '🐑 Sheep',
  CHICKEN: '🐔 Chicken',
  FAT: 'Fat',
  OTHER_FOOD: 'Other food',
  SUPPLIES: '🔧 Supplies',
  OTHER: 'Other',
}

/** Label without the emoji, for chart axes where the glyph would collide with marks. */
export function plainCategoryLabel(code: string): string {
  return (CATEGORY_LABELS[code] ?? code).replace(/^[^\p{L}\p{N}]+/u, '').trim()
}
