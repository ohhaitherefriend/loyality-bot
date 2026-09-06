import { describe, expect, it } from 'vitest'
import {
  BATCH_STATUS_LABELS,
  ROW_STATUS_LABELS,
  batchStatusVariant,
  isRunningBatchStatus,
  rowStatusVariant,
} from './statusLabels'
import type { ImportBatchStatus, ImportRowStatus } from '@/api/types'

describe('statusLabels', () => {
  it('provides a Russian label for every ImportRowStatus value', () => {
    const allStatuses: ImportRowStatus[] = [
      'PENDING',
      'EXACT_MATCH',
      'LEARNED_MATCH',
      'AI_MATCH',
      'AUTO_APPROVED',
      'NEEDS_REVIEW',
      'NEW_PRODUCT',
      'IGNORED',
      'INVALID',
      'APPROVED',
      'APPLIED',
    ]
    for (const status of allStatuses) {
      expect(ROW_STATUS_LABELS[status]).toBeTruthy()
    }
  })

  it('provides a Russian label for every ImportBatchStatus value', () => {
    const allStatuses: ImportBatchStatus[] = [
      'RECEIVED',
      'STORED',
      'PARSING',
      'NORMALIZING',
      'MATCHING',
      'VALIDATING',
      'AUTO_APPROVED',
      'NEEDS_ATTENTION',
      'APPROVED',
      'APPLYING',
      'APPLIED',
      'QUARANTINED',
      'FAILED',
    ]
    for (const status of allStatuses) {
      expect(BATCH_STATUS_LABELS[status]).toBeTruthy()
    }
  })

  it('marks exception statuses with warning/destructive variants', () => {
    expect(rowStatusVariant('NEEDS_REVIEW')).toBe('warning')
    expect(rowStatusVariant('INVALID')).toBe('destructive')
    expect(rowStatusVariant('APPLIED')).toBe('success')
  })

  it('marks failed/quarantined batches as destructive and applied as success', () => {
    expect(batchStatusVariant('FAILED')).toBe('destructive')
    expect(batchStatusVariant('QUARANTINED')).toBe('destructive')
    expect(batchStatusVariant('APPLIED')).toBe('success')
    expect(batchStatusVariant('NEEDS_ATTENTION')).toBe('warning')
  })

  it('classifies pipeline-in-progress statuses as running, terminal ones as not running', () => {
    expect(isRunningBatchStatus('MATCHING')).toBe(true)
    expect(isRunningBatchStatus('APPLIED')).toBe(false)
    expect(isRunningBatchStatus('FAILED')).toBe(false)
    expect(isRunningBatchStatus('QUARANTINED')).toBe(false)
  })
})
