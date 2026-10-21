export type LegalDocumentKey = 'terms'

/**
 * Section ids in reading order. The copy for each lives in the locale files under public.<doc>.sections.<id>.
 * Privacy disclosures are the `privacy` section of the Terms; there is no standalone privacy page.
 */
export const LEGAL_SECTIONS: Record<LegalDocumentKey, readonly string[]> = {
  terms: [
    'acceptance',
    'service',
    'accounts',
    'data',
    'privacy',
    'subscription',
    'use',
    'liability',
    'suspension',
    'changes',
    'contact',
  ],
}
