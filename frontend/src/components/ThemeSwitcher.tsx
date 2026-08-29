import { useEffect, useRef, useState } from 'react'
import { useTheme, type ThemePreference } from '../context/ThemeContext'
import { Icon, THEME_ICON, THEME_LABEL } from './icons'

// Horizontal segmented control, ported from MPA. Collapsed: shows the active
// option as a single pill button. Click to expand all options inline; click one
// to select and collapse.
const ORDER: ThemePreference[] = ['light', 'dark', 'colorblind', 'contrast', 'system']

export default function ThemeSwitcher() {
  const { preference, setPreference } = useTheme()
  const [expanded, setExpanded] = useState(false)
  const ref = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (!expanded) return
    function handleClickOutside(e: MouseEvent) {
      if (ref.current && !ref.current.contains(e.target as Node)) setExpanded(false)
    }
    document.addEventListener('mousedown', handleClickOutside)
    return () => document.removeEventListener('mousedown', handleClickOutside)
  }, [expanded])

  function select(p: ThemePreference) {
    setPreference(p)
    setExpanded(false)
  }

  return (
    <div ref={ref} className="mpa-theme-select" role="group" aria-label="Theme">
      {expanded ? (
        ORDER.map(p => (
          <button key={p} type="button"
            className={`mpa-theme-opt${preference === p ? ' active' : ''}`}
            aria-pressed={preference === p}
            onClick={() => select(p)}>
            <Icon>{THEME_ICON[p]}</Icon>
            {THEME_LABEL[p]}
          </button>
        ))
      ) : (
        <button type="button" className="mpa-theme-opt active"
          onClick={() => setExpanded(true)}
          aria-label="Switch theme" title="Switch theme">
          <Icon>{THEME_ICON[preference]}</Icon>
          {THEME_LABEL[preference]}
        </button>
      )}
    </div>
  )
}
