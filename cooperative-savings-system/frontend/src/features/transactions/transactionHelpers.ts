import type {
  IncomeExpenseApprovalStatus,
  IncomeExpenseCategory,
} from '@/shared/types/incomeExpense'
import {
  EXPENSE_CATEGORIES,
  INCOME_CATEGORIES,
} from '@/shared/types/incomeExpense'

export type ChipColor =
  | 'default'
  | 'primary'
  | 'secondary'
  | 'error'
  | 'info'
  | 'success'
  | 'warning'

export type TransactionBucket = 'all' | 'income' | 'expenses'

export function transactionStatusColor(status: string): ChipColor {
  switch (status) {
    case 'PENDING':
      return 'info'
    case 'APPROVED':
      return 'success'
    case 'REJECTED':
      return 'error'
    default:
      return 'default'
  }
}

export function transactionCategoryColor(category: string): ChipColor {
  switch (category) {
    case 'OTHER_INCOME':
      return 'success'
    case 'GENERAL_EXPENSE':
      return 'warning'
    case 'INTEREST_EXPENSE':
      return 'error'
    case 'ADJUSTMENT':
      return 'secondary'
    default:
      return 'default'
  }
}

export function canApproveTransaction(status: string, isAdmin: boolean): boolean {
  return isAdmin && status === 'PENDING'
}

export function canRejectTransaction(status: string, isAdmin: boolean): boolean {
  return isAdmin && status === 'PENDING'
}

export function isApprovedTransaction(status: string): boolean {
  return status === 'APPROVED'
}

export function filterTransactionActions(
  status: IncomeExpenseApprovalStatus | string,
  isAdmin: boolean,
): Array<'approve' | 'reject'> {
  const actions: Array<'approve' | 'reject'> = []
  if (canApproveTransaction(status, isAdmin)) actions.push('approve')
  if (canRejectTransaction(status, isAdmin)) actions.push('reject')
  return actions
}

export function categoriesForBucket(bucket: TransactionBucket): string[] | undefined {
  if (bucket === 'income') return [...INCOME_CATEGORIES]
  if (bucket === 'expenses') return [...EXPENSE_CATEGORIES]
  return undefined
}

export function matchesTransactionBucket(
  category: IncomeExpenseCategory | string,
  bucket: TransactionBucket,
): boolean {
  if (bucket === 'all') return true
  if (bucket === 'income') {
    return category === 'OTHER_INCOME' || category === 'ADJUSTMENT'
  }
  return category === 'GENERAL_EXPENSE' || category === 'INTEREST_EXPENSE'
}
