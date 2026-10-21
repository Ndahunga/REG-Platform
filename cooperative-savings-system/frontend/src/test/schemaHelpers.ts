import { todayInKigaliIso } from '@/shared/utils/rwandaCooperative'

type Validatable = { validate: (value: unknown, options?: object) => Promise<unknown> }

/** The first validation message for a value, or null when it is valid. */
export async function firstMessage(schema: Validatable, value: unknown): Promise<string | null> {
  try {
    await schema.validate(value)
    return null
  } catch (error) {
    return (error as Error).message
  }
}

/** Every validation message for a value (abortEarly off), or an empty list when it is valid. */
export async function allMessages(schema: Validatable, value: unknown): Promise<string[]> {
  try {
    await schema.validate(value, { abortEarly: false })
    return []
  } catch (error) {
    return (error as { errors: string[] }).errors
  }
}

/** Today in Africa/Kigali shifted by whole days, as YYYY-MM-DD. */
export function kigaliDay(offsetDays = 0): string {
  const d = new Date(`${todayInKigaliIso()}T00:00:00Z`)
  d.setUTCDate(d.getUTCDate() + offsetDays)
  return d.toISOString().slice(0, 10)
}
