import type { CapacitorConfig } from "@capacitor/cli"

const config: CapacitorConfig = {
  appId: "ai.opencode.mobile",
  appName: "OpenCode Mobile",
  webDir: "android/app/src/main/assets/public",

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