import React, { useId } from 'react'
import { useTheme, type ThemePreference, type ResolvedTheme } from '../context/ThemeContext'

// Inline SVG icon set, ported from MPA. Glyphs are Bootstrap Icons paths
// (https://icons.getbootstrap.com) on a 16x16 viewBox, drawn in `currentColor`
// so they inherit text colour. The `Icon` wrapper sizes to 1em so an icon
// matches the surrounding font size.
export function Icon({ children, className }: { children: React.ReactNode; className?: string }) {
  return (
    <svg xmlns="http://www.w3.org/2000/svg" width="1em" height="1em" viewBox="0 0 16 16"
      fill="currentColor" className={`bi${className ? ' ' + className : ''}`} aria-hidden="true">
      {children}
    </svg>
  )
}

// --- Theme glyphs (sun-fill / moon-stars-fill / eye-fill / square-half / circle-half) ---
export const THEME_ICON: Record<ThemePreference, React.ReactNode> = {
  light: (
    <path d="M8 12a4 4 0 1 0 0-8 4 4 0 0 0 0 8M8 0a.5.5 0 0 1 .5.5v2a.5.5 0 0 1-1 0v-2A.5.5 0 0 1 8 0m0 13a.5.5 0 0 1 .5.5v2a.5.5 0 0 1-1 0v-2A.5.5 0 0 1 8 13m8-5a.5.5 0 0 1-.5.5h-2a.5.5 0 0 1 0-1h2a.5.5 0 0 1 .5.5M3 8a.5.5 0 0 1-.5.5h-2a.5.5 0 0 1 0-1h2A.5.5 0 0 1 3 8m10.657-5.657a.5.5 0 0 1 0 .707l-1.414 1.415a.5.5 0 1 1-.707-.708l1.414-1.414a.5.5 0 0 1 .707 0m-9.193 9.193a.5.5 0 0 1 0 .707L3.05 13.657a.5.5 0 0 1-.707-.707l1.414-1.414a.5.5 0 0 1 .707 0m9.193 2.121a.5.5 0 0 1-.707 0l-1.414-1.414a.5.5 0 0 1 .707-.707l1.414 1.414a.5.5 0 0 1 0 .707M4.464 4.465a.5.5 0 0 1-.707 0L2.343 3.05a.5.5 0 1 1 .707-.707l1.414 1.414a.5.5 0 0 1 0 .708" />
  ),
  dark: (
    <>
      <path d="M6 .278a.77.77 0 0 1 .08.858 7.2 7.2 0 0 0-.878 3.46c0 4.021 3.278 7.277 7.318 7.277q.792-.001 1.533-.16a.79.79 0 0 1 .81.316.73.73 0 0 1-.031.893A8.35 8.35 0 0 1 8.344 16C3.734 16 0 12.286 0 7.71 0 4.266 2.114 1.312 5.124.06A.75.75 0 0 1 6 .278" />
      <path d="M10.794 3.148a.217.217 0 0 1 .412 0l.387 1.162c.173.518.579.924 1.097 1.097l1.162.387a.217.217 0 0 1 0 .412l-1.162.387a1.73 1.73 0 0 0-1.097 1.097l-.387 1.162a.217.217 0 0 1-.412 0l-.387-1.162A1.73 1.73 0 0 0 9.31 6.593l-1.162-.387a.217.217 0 0 1 0-.412l1.162-.387a1.73 1.73 0 0 0 1.097-1.097z" />
    </>
  ),
  colorblind: (
    <>
      <path d="M16 8s-3-5.5-8-5.5S0 8 0 8s3 5.5 8 5.5S16 8 16 8M5 8a3 3 0 1 1 6 0 3 3 0 0 1-6 0" />
      <path d="M8 5.5a2.5 2.5 0 1 0 0 5 2.5 2.5 0 0 0 0-5" />
    </>
  ),
  contrast: (
    <path d="M8 1v14H2.5A1.5 1.5 0 0 1 1 13.5v-11A1.5 1.5 0 0 1 2.5 1zM2.5 0A2.5 2.5 0 0 0 0 2.5v11A2.5 2.5 0 0 0 2.5 16h11a2.5 2.5 0 0 0 2.5-2.5v-11A2.5 2.5 0 0 0 13.5 0z" />
  ),
  system: (
    <path d="M8 15A7 7 0 1 0 8 1zm0 1A8 8 0 1 1 8 0a8 8 0 0 1 0 16" />
  ),
}

export const THEME_LABEL: Record<ThemePreference, string> = {
  light: 'Light', dark: 'Dark', colorblind: 'Color-blind', contrast: 'High contrast', system: 'Auto',
}

// MPA's mango assistant logo (frontend/theme/mango-assistant-logo*.svg) — the
// smiling mango on a rounded tile. Each theme has its own colourway (tile,
// mango/leaf gradients, ink, sparkle); dark adds an ember glow and the
// accessibility variants add a tile border. Gradient IDs are namespaced per
// instance (useId) so multiple logos on a page don't collide. `variant` forces a
// mode; otherwise it follows the resolved theme.
type LogoColors = {
  bg: [string, string]; mango: [string, string, string]; leaf: [string, string]
  vein: string; ink: string; spark: string; hi: number; glow: boolean; stroke: string | null
}
const LOGO: Record<ResolvedTheme, LogoColors> = {
  light:      { bg: ['#FFF9F1', '#FFEEDC'], mango: ['#FFD54A', '#FF9233', '#F2542D'], leaf: ['#8BC34A', '#5C8A35'], vein: '#4A7029', ink: '#7A3B1E', spark: '#FFE9C2', hi: 0.25, glow: false, stroke: null },
  dark:       { bg: ['#34271A', '#16100B'], mango: ['#FFD54A', '#FF9233', '#F2542D'], leaf: ['#9CCC55', '#5C8A35'], vein: '#3A5420', ink: '#3A2210', spark: '#FFE9C2', hi: 0.35, glow: true, stroke: null },
  colorblind: { bg: ['#FFFFFF', '#FFFFFF'], mango: ['#F2B705', '#E69F00', '#D55E00'], leaf: ['#56B4E9', '#0072B2'], vein: '#005A8C', ink: '#15202B', spark: '#FFFFFF', hi: 0.30, glow: false, stroke: '#D7DEE5' },
  contrast:   { bg: ['#000000', '#000000'], mango: ['#FFE100', '#FFB000', '#FFB000'], leaf: ['#FFFFFF', '#CFCFCF'], vein: '#000000', ink: '#000000', spark: '#FFFFFF', hi: 0.45, glow: false, stroke: '#FFFFFF' },
}

export function MangoLogo({ size = '1.5em', variant, className }:
  { size?: string; variant?: ResolvedTheme; className?: string }) {
  const { theme } = useTheme()
  const c = LOGO[variant ?? theme] ?? LOGO.light
  const uid = useId().replace(/[^a-zA-Z0-9]/g, '')
  const bg = `mbg-${uid}`, mango = `mango-${uid}`, leaf = `leaf-${uid}`, glow = `glow-${uid}`

  return (
    <svg xmlns="http://www.w3.org/2000/svg" width={size} height={size} viewBox="0 0 512 512"
      className={className} role="img" aria-label="Mango logo">
      <defs>
        <linearGradient id={bg} x1="0%" y1="0%" x2="100%" y2="100%">
          <stop offset="0%" stopColor={c.bg[0]} />
          <stop offset="100%" stopColor={c.bg[1]} />
        </linearGradient>
        <linearGradient id={mango} x1="20%" y1="0%" x2="85%" y2="100%">
          <stop offset="0%" stopColor={c.mango[0]} />
          <stop offset="50%" stopColor={c.mango[1]} />
          <stop offset="100%" stopColor={c.mango[2]} />
        </linearGradient>
        <linearGradient id={leaf} x1="0%" y1="0%" x2="100%" y2="100%">
          <stop offset="0%" stopColor={c.leaf[0]} />
          <stop offset="100%" stopColor={c.leaf[1]} />
        </linearGradient>
        {c.glow && (
          <filter id={glow} x="-60%" y="-60%" width="220%" height="220%">
            <feGaussianBlur stdDeviation="22" />
          </filter>
        )}
      </defs>

      <rect x={c.stroke ? 4 : 0} y={c.stroke ? 4 : 0} width={c.stroke ? 504 : 512} height={c.stroke ? 504 : 512}
        rx="108" fill={`url(#${bg})`} stroke={c.stroke ?? undefined} strokeWidth={c.stroke ? 6 : undefined} />
      {c.glow && <circle cx="252" cy="290" r="150" fill="#FF7A33" opacity="0.30" filter={`url(#${glow})`} />}

      <g transform="translate(252,290) rotate(-8)">
        <path d="M26,-120 C5,-145 -10,-175 -22,-200 C-2,-185 18,-155 32,-128 Z" fill={`url(#${leaf})`} />
        <path d="M28,-122 C12,-148 0,-172 -16,-196" stroke={c.vein} strokeWidth="3" fill="none" strokeLinecap="round" />
        <path d="M24,-118 C72,-110 106,-56 104,10 C102,78 60,128 -2,130 C-64,132 -104,80 -100,12 C-97,-50 -60,-110 24,-118 Z" fill={`url(#${mango})`} />
        <path d="M-85,-35 Q-75,-85 -10,-110" stroke="#FFFFFF" strokeWidth="10" fill="none" strokeLinecap="round" opacity={c.hi} />
        <circle cx="-32" cy="-10" r="10" fill={c.ink} />
        <circle cx="28" cy="-10" r="10" fill={c.ink} />
        <circle cx="-35" cy="-13" r="3" fill={c.spark} />
        <circle cx="25" cy="-13" r="3" fill={c.spark} />
        <path d="M-32,32 Q-2,62 34,28" stroke={c.ink} strokeWidth="9" fill="none" strokeLinecap="round" />
      </g>
    </svg>
  )
}
