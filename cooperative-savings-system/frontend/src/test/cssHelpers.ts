/**
 * jsdom ignores media and container queries when computing styles, so layout tests read the CSS rules emotion emitted
 * for an element's classes instead. Returns every rule (including nested at-rules) that mentions one of its classes.
 */
export function cssFor(el: Element): string {
  const classes = [...el.classList].filter((c) => c.startsWith('css-'))
  const rules: string[] = []
  const walk = (list: CSSRuleList) => {
    for (const rule of list) {
      const nested = (rule as CSSGroupingRule).cssRules
      if (nested && rule.cssText.startsWith('@')) {
        if (classes.some((c) => rule.cssText.includes('.' + c))) rules.push(rule.cssText)
        continue
      }
      if (classes.some((c) => rule.cssText.includes('.' + c))) rules.push(rule.cssText)
    }
  }
  for (const sheet of document.styleSheets) walk(sheet.cssRules)
  return rules.join(' ')
}
