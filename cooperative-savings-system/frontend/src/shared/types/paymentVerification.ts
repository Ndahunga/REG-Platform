import type { PaymentMethod, PaymentStatus, VerificationMethod } from './memberPayment'

// ---------------------------------------------------------------- receipt reading (advisory, never proof)

export type ExtractionStatus = 'COMPLETED' | 'FAILED' | 'UNAVAILABLE' | 'UNSUPPORTED'
export type ConfidenceLevel = 'HIGH' | 'MEDIUM' | 'LOW' | 'UNKNOWN'

export interface ExtractedField {
  name: 'reference' | 'amount' | 'currency' | 'paidAt' | 'sender' | 'recipient' | 'provider' | 'status'
  value: string
  confidence: string | number | null
  level: ConfidenceLevel
}

export interface ReceiptExtraction {
  id: string
  paymentId: string
  status: ExtractionStatus
  failureCode: string | null
  engine: string | null
  attempts: number
  meanConfidence: string | number | null
  fields: ExtractedField[]
  rawText: string | null
  duplicateReceipt: boolean
  duplicateOfPaymentId: string | null
  /** Differences from the claim, e.g. AMOUNT_MISMATCH. They only ever add doubt. */
  discrepancies: string[]
  /** Always false: an extraction is a claim made by an image, never evidence of receipt. */
  proofOfPayment: boolean
  reviewedBy: string | null
  reviewedAt: string | null
  reviewNote: string | null
  createdAt: string
}

// ---------------------------------------------------------------- verification outcomes (not lifecycle statuses)

export type VerificationOutcome = 'CONFIRMED' | 'PENDING' | 'FAILED' | 'UNMATCHED' | 'CONFLICTED'
export type VerificationSource =
  | 'MANUAL_CASH_CONFIRMATION'
  | 'BANK_STATEMENT_IMPORT'
  | 'MOBILE_MONEY_STATEMENT_IMPORT'
  | 'PROVIDER_CALLBACK'
  | 'RECEIPT_OCR'
  | 'REFERENCE_MATCH'

export interface VerificationCheck {
  id: string
  paymentId: string
  outcome: VerificationOutcome
  method: VerificationMethod
  source: VerificationSource
  authoritative: boolean
  trustedTransactionId: string | null
  reasons: string[]
  detail: string | null
  checkedBy: string | null
  createdAt: string
}

export interface VerificationOverview {
  requiresTrustedConfirmation: boolean
  /** UNAVAILABLE, or CALLBACK_CONFIGURED when a signed callback secret exists (polling a provider is not implemented). */
  automaticProviderConfirmation: 'UNAVAILABLE' | 'CALLBACK_CONFIGURED'
  current: VerificationCheck | null
  history: VerificationCheck[]
}

export interface AttentionItem {
  paymentId: string
  memberUserId: string
  amount: string | number
  currency: string
  paymentMethod: PaymentMethod
  transactionReference: string | null
  paymentStatus: PaymentStatus
  outcome: VerificationOutcome
  reasons: string[]
  checkedAt: string
}

// ---------------------------------------------------------------- trusted transactions

export type TrustedSource = 'BANK_STATEMENT_IMPORT' | 'MOBILE_MONEY_STATEMENT_IMPORT' | 'PROVIDER_CALLBACK'
export type ProviderStatus = 'SUCCESS' | 'FAILED' | 'PENDING'
export type TrustedChannel = Exclude<PaymentMethod, 'CASH'>

export interface TrustedTransaction {
  id: string
  source: TrustedSource
  channel: TrustedChannel
  reference: string
  amount: string | number
  currency: string
  providerStatus: ProviderStatus
  transactedAt: string | null
  sender: string | null
  recipient: string | null
  consumed: boolean
  consumedByPaymentId: string | null
  conflicted: boolean
  conflictDetail: string | null
  recordedAt: string
}

export interface TrustedTransactionLine {
  source: Exclude<TrustedSource, 'PROVIDER_CALLBACK'>
  channel: TrustedChannel
  reference: string
  amount: number
  currency: string
  status: ProviderStatus
  transactedAt?: string
  sender?: string
  recipient?: string
}

export type ImportResult = 'CREATED' | 'UPDATED' | 'DUPLICATE' | 'CONFLICT' | 'INVALID'

export interface ImportResponse {
  batchId: string
  results: { reference: string; result: ImportResult; reason: string | null; transactionId: string | null }[]
}

// ---------------------------------------------------------------- legacy rotating reconciliation

export type ReconciliationStatus = 'REQUESTED' | 'APPROVED' | 'DECLINED' | 'WITHDRAWN'

export interface ReconciliationCandidate {
  contributionId: string
  memberUserId: string
  memberName: string
  year: number
  month: number
  rotationPeriodId: string
  originalRecipientUserId: string
  originalRecipientName: string
  paidAmount: string | number
  verifiedAmount: string | number
  unreconciledAmount: string | number
  unreconciledRotating: string | number
  openRequestId: string | null
}

export interface ReconciliationRequest {
  id: string
  contributionId: string
  memberUserId: string
  periodYear: number
  periodMonth: number
  rotationPeriodId: string
  originalRecipientUserId: string
  amount: string | number
  status: ReconciliationStatus
  method: VerificationMethod
  basis: string
  reference: string | null
  requestedBy: string
  requestedAt: string
  decidedBy: string | null
  decidedAt: string | null
  decisionNote: string | null
  withdrawnAt: string | null
}

export interface ReconciliationRequestBody {
  contributionId: string
  amount: number
  method: VerificationMethod
  basis: string
  reference?: string
}

export interface ReconciliationEvent {
  id: string
  actorUserId: string
  actorRole: string
  fromStatus: ReconciliationStatus | null
  toStatus: ReconciliationStatus
  comment: string | null
  createdAt: string
}

// ---------------------------------------------------------------- what this deployment can read and confirm

export interface VerificationCapabilities {
  ocrAvailable: boolean
  ocrEngine: string | null
  supportedReceiptTypes: string[]
  /** Always false: PDF receipts are recorded as unsupported, never read. */
  pdfSupported: boolean
  maxReceiptBytes: number
  ocrTimeoutSeconds: number
  /** True when a payment needs authoritative confirmation before it can be verified or posted. */
  strictVerification: boolean
  /** True when any signed provider callback is configured (never the secret itself). */
  providerCallbackConfigured: boolean
}
