import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { ClickToComponent } from 'click-to-react-component'
import './index.css'
import App from './App.tsx'

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <ClickToComponent />
    <App />
  </StrictMode>,
)
