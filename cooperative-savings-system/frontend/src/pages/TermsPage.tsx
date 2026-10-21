import { LegalDocument } from '@/features/legal/LegalDocument'

/** Public Terms & Conditions (short form). The copy lives in the locale files. */
export function TermsPage() {
  return <LegalDocument docKey="terms" />
}
