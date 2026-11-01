import { defineConfig, devices } from '@playwright/test'
import { existsSync } from 'node:fs'

const executablePath =
  process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH ||
  (existsSync('/usr/bin/chromium') ? '/usr/bin/chromium' : undefined)

export default defineConfig({
  testDir: './tests/e2e-api',
  fullyParallel: true,
  workers: 1,
  reporter: 'list',
  use: {
    baseURL: 'http://127.0.0.1:5174',
    trace: 'retain-on-failure',
    launchOptions: { executablePath },
  },
  projects: [{ name: 'api-chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: {
    command: 'VITE_YANDU_API_URL=http://127.0.0.1:4174 VITE_OIDC_AUTHORITY=https://issuer.example.test VITE_OIDC_CLIENT_ID=yandu-e2e npm run dev -- --host 127.0.0.1 --port 5174',
    url: 'http://127.0.0.1:5174',
    reuseExistingServer: !process.env.CI,
  },
})
