import type { CapacitorConfig } from "@capacitor/cli"

const config: CapacitorConfig = {
  appId: "ai.opencode.mobile",
  appName: "AndrOpencode",
  // webDir points to the SOURCE web assets (built output of packages/app).
  // Capacitor's `cap sync` copies this into android/app/src/main/assets/public.
  webDir: "../app/dist",

  android: {
    webContentsDebuggingEnabled: false,
    allowMixedContent: false,
  },

  // Phase 2: ship as plain http://127.0.0.1:4096 directly. The WebView talks
  // to the in-process reverse proxy without HTTPS overhead. Network security
  // config (`network_security_config.xml`) limits cleartext to 127.0.0.1 /
  // localhost so this does not open cleartext traffic to the wider internet.
  // Phase 3 will revisit this once the LocalReverseProxy handles WebSocket
  // and we can switch back to https://opencode.local.
  server: {
    androidScheme: "http",
    hostname: "127.0.0.1",
    cleartext: true,
  },
}

export default config