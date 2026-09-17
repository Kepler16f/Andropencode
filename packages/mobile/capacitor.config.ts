import type { CapacitorConfig } from "@capacitor/cli"

const config: CapacitorConfig = {
  appId: "ai.opencode.mobile",
  appName: "OpenCode Mobile",
  // webDir points to the SOURCE web assets (built output of packages/app).
  // Capacitor's `cap sync` copies this into android/app/src/main/assets/public.
  webDir: "../app/dist",

  android: {
    webContentsDebuggingEnabled: false,
    allowMixedContent: false,
  },

  server: {
    androidScheme: "https",
    hostname: "opencode.local",
    cleartext: false,
  },
}

export default config