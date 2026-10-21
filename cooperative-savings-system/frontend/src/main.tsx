import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import '@/i18n'
import '@/theme/tokens.css'
import './index.css'
import App from './App.tsx'
import { registerPwa } from '@/pwa/registerPwa'

registerPwa()

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
)
