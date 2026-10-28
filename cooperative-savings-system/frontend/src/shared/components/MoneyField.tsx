import { TextField, type TextFieldProps } from '@mui/material'
import { useCallback, useLayoutEffect, useRef, type ChangeEvent, type Ref } from 'react'
import { caretAfterGrouping, displayMoney, groupedMoney, plainMoney } from '@/shared/utils/moneyInput'

type InputElement = HTMLInputElement | HTMLTextAreaElement

/**
 * A money amount input that shows thousand separators while typing ("530,000") and hands the plain number
 * ("530000") to its owner. Works the two ways forms in the app use inputs:
 *
 * <ul>
 *   <li>controlled ({@code value} + {@code onChange}): {@code value} is the plain number, {@code onChange} receives an
 *       event whose {@code target.value} is the plain number;
 *   <li>react-hook-form ({@code {...register('amount')}}): the form reads and writes the plain number; only the
 *       screen shows the separators.
 * </ul>
 */
export function MoneyField(props: TextFieldProps & { ref?: Ref<unknown> }) {
  const { value, onChange, ref: outerRef, inputRef: ownInputRef, slotProps, ...rest } = props
  const controlled = value !== undefined
  const element = useRef<InputElement | null>(null)
  const pendingCaret = useRef<number | null>(null)
  const proxies = useRef(new WeakMap<InputElement, object>())
  // Latest refs, read by a stable callback so the input is attached once rather than on every render.
  const outer = useRef(outerRef)
  const own = useRef(ownInputRef)
  const isControlled = useRef(controlled)
  outer.current = outerRef
  own.current = ownInputRef
  isControlled.current = controlled

  // The form library talks to this stand-in instead of the input: it sees the plain number.
  const formRef = useCallback(
    (el: InputElement | null) => {
      element.current = el
      const ownRef = own.current
      if (typeof ownRef === 'function') ownRef(el)
      else if (ownRef) (ownRef as { current: unknown }).current = el
      const outerRef = outer.current
      if (isControlled.current || !outerRef) return
      let target: object | null = null
      if (el) {
        target = proxies.current.get(el) ?? null
        if (!target) {
          target = {
            get value() {
              return plainMoney(el.value)
            },
            set value(v: string) {
              el.value = displayMoney(v)
            },
            type: 'text',
            get name() {
              return el.name
            },
            get disabled() {
              return el.disabled
            },
            focus: () => el.focus(),
            select: () => el.select(),
            setCustomValidity: (m: string) => el.setCustomValidity(m),
            reportValidity: () => el.reportValidity(),
          }
          proxies.current.set(el, target)
        }
      }
      if (typeof outerRef === 'function') (outerRef as (v: unknown) => void)(target)
      else (outerRef as { current: unknown }).current = target
    },
    [],
  )

  useLayoutEffect(() => {
    if (pendingCaret.current !== null && element.current && document.activeElement === element.current) {
      element.current.setSelectionRange(pendingCaret.current, pendingCaret.current)
    }
    pendingCaret.current = null
  })

  const handleChange = (e: ChangeEvent<InputElement>) => {
    const el = e.target
    const typed = el.value
    const caret = el.selectionStart ?? typed.length
    const grouped = groupedMoney(typed)
    const nextCaret = caretAfterGrouping(typed, caret, grouped)
    if (controlled) {
      pendingCaret.current = nextCaret
      const plain = plainMoney(typed)
      onChange?.({ ...e, target: { ...el, name: el.name, value: plain }, currentTarget: { ...el, name: el.name, value: plain } } as never)
      return
    }
    el.value = grouped
    el.setSelectionRange(nextCaret, nextCaret)
    onChange?.(e)
  }

  // While typing, the field keeps what was typed (grouped); a value set from outside is shown tidily.
  const typed = element.current?.value
  const shown = controlled
    ? typed !== undefined && plainMoney(typed) === plainMoney(value as string | number | null)
      ? groupedMoney(typed)
      : displayMoney(value as string | number | null)
    : undefined
  const htmlInput = (slotProps?.htmlInput ?? {}) as Record<string, unknown>
  return (
    <TextField
      {...rest}
      value={shown}
      onChange={handleChange}
      inputRef={formRef}
      slotProps={{ ...slotProps, htmlInput: { inputMode: 'decimal', autoComplete: 'off', ...htmlInput } }}
    />
  )
}
