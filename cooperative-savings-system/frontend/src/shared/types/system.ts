export interface SystemInfo {
  name?: string
  version?: string
  profiles?: string[]
  timestamp?: string
  javaVersion?: string
  dbReachable?: boolean
  flywayVersion?: string
  [key: string]: unknown
}
