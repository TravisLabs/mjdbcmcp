import React, { createContext, useCallback, useContext, useEffect, useState } from 'react'
// Bootswatch sibling themes: Flatly (light) / Darkly (dark). Resolved to bundled
// asset URLs so we can swap the stylesheet at runtime without importing both
// (each is a full Bootstrap bundle — statically importing both would conflict).
import flatlyUrl from 'bootswatch/dist/flatly/bootstrap.min.css?url'
import darklyUrl from 'bootswatch/dist/darkly/bootstrap.min.css?url'

export type ThemePreference = 'system' | 'light' | 'dark' | 'colorblind' | 'contrast'
export type ResolvedTheme = 'light' | 'dark' | 'colorblind' | 'contrast'

interface ThemeCtx {
  preference: ThemePreference
  /** The resolved theme actually in effect (system collapsed to light/dark). */
  theme: ResolvedTheme
  setPreference: (p: ThemePreference) => void
}

const Ctx = createContext<ThemeCtx>(null!)
export const useTheme = () => useContext(Ctx)

function effectiveTheme(pref: ThemePreference): ResolvedTheme {
  if (pref !== 'system') return pref
  return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'
}

// Bootstrap's component theming only understands light|dark. Colorblind builds
// on the light (Flatly) base, contrast on the dark (Darkly) base; the palette
// itself is carried separately via data-mpa-theme.
function baseTheme(t: ResolvedTheme): 'light' | 'dark' {
  return t === 'dark' || t === 'contrast' ? 'dark' : 'light'
}

const THEME_LINK_ID = 'bootswatch-theme'
// Same key as MPA on purpose: the two apps share the Mango theme, so a user who
// picks high-contrast in one gets it in the other when served from one origin.
const STORAGE_KEY = 'mpa-theme'

function applyStylesheet(base: 'light' | 'dark') {
  let link = document.getElementById(THEME_LINK_ID) as HTMLLinkElement | null
  if (!link) {
    link = document.createElement('link')
    link.id = THEME_LINK_ID
    link.rel = 'stylesheet'
    // Insert first so app overrides (style.css) cascade after and win.
    document.head.insertBefore(link, document.head.firstChild)
  }
  link.href = base === 'dark' ? darklyUrl : flatlyUrl
}

function apply(pref: ThemePreference) {
  const theme = effectiveTheme(pref)
  const base = baseTheme(theme)
  document.documentElement.setAttribute('data-bs-theme', base)
  document.documentElement.setAttribute('data-mpa-theme', theme)
  applyStylesheet(base)
}

// Apply the stored preference synchronously at module load so the correct
// stylesheet is in the <head> before first paint (avoids an unstyled flash).
apply((localStorage.getItem(STORAGE_KEY) as ThemePreference | null) ?? 'system')

export function ThemeProvider({ children }: { children: React.ReactNode }) {
  const [preference, setPreferenceState] = useState<ThemePreference>(
    () => (localStorage.getItem(STORAGE_KEY) as ThemePreference | null) ?? 'system'
  )
  const [theme, setTheme] = useState<ResolvedTheme>(() => effectiveTheme(preference))

  const setPreference = useCallback((p: ThemePreference) => {
    localStorage.setItem(STORAGE_KEY, p)
    setPreferenceState(p)
    apply(p)
  }, [])

  // Apply on mount and listen for OS changes when preference is 'system'
  useEffect(() => {
    apply(preference)
    setTheme(effectiveTheme(preference))
    const mq = window.matchMedia('(prefers-color-scheme: dark)')
    const handler = () => { if (preference === 'system') { apply('system'); setTheme(effectiveTheme('system')) } }
    mq.addEventListener('change', handler)
    return () => mq.removeEventListener('change', handler)
  }, [preference])

  return <Ctx.Provider value={{ preference, theme, setPreference }}>{children}</Ctx.Provider>
}
