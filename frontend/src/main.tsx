import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
// No static bootstrap import: ThemeContext injects the Bootswatch stylesheet
// (Flatly or Darkly) as the first <head> child, and style.css overrides it.
import './style.css'
import { ThemeProvider } from './context/ThemeContext'
import App from './App'

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <ThemeProvider>
      <App />
    </ThemeProvider>
  </StrictMode>,
)
