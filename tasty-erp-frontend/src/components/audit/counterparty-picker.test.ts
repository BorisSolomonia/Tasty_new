/** BOR-92: a picked suggestion attaches the TIN; free text never guesses one. */
import { describe, expect, it } from 'vitest'
import { matchOption, optionLabel, type CounterpartyOption } from './counterparty-picker'

const options: CounterpartyOption[] = [
  { tin: '200000001', name: 'ერთგული ვაჟა პაპა', kind: 'supplier' },
  { tin: '300000002', name: 'Customer Two', kind: 'customer' },
  { tin: null, name: 'Nameless Ltd', kind: 'supplier' },
]

describe('counterparty picker', () => {
  it('labels an option with name · TIN, or the bare name when there is no TIN', () => {
    expect(optionLabel(options[0])).toBe('ერთგული ვაჟა პაპა · 200000001')
    expect(optionLabel(options[2])).toBe('Nameless Ltd')
  })

  it('matches by label, exact name, or exact TIN — never by substring', () => {
    expect(matchOption('ერთგული ვაჟა პაპა · 200000001', options)).toBe(options[0])
    expect(matchOption('Customer Two', options)).toBe(options[1])
    expect(matchOption('300000002', options)).toBe(options[1])
    expect(matchOption('Customer', options)).toBeNull()
    expect(matchOption('   ', options)).toBeNull()
  })
})
