import { useCallback, useEffect, useState } from 'react'
import { applyPwaUpdate, onNeedRefresh, onOfflineReady } from './registerPwa'

export function usePwaUpdate() {
  const [needRefresh, setNeedRefresh] = useState(false)
  const [offlineReady, setOfflineReady] = useState(false)

  useEffect(() => {
    const unsubRefresh = onNeedRefresh(() => setNeedRefresh(true))
    const unsubReady = onOfflineReady(() => setOfflineReady(true))
    return () => {
      unsubRefresh()
      unsubReady()
    }
  }, [])

  const reload = useCallback(() => {
    void applyPwaUpdate(true)
  }, [])

  const dismiss = useCallback(() => {
    setNeedRefresh(false)
  }, [])

  return { needRefresh, offlineReady, reload, dismiss }
}
