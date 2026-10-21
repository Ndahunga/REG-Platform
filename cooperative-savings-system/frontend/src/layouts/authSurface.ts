import { createContext, useContext } from 'react'

/** Auth canvas surface: light gradient in Light Mode, dark gradient in Dark Mode. */
export const AuthSurfaceContext = createContext<{ onDark: boolean }>({ onDark: false })

export function useAuthSurface() {
  return useContext(AuthSurfaceContext)
}
