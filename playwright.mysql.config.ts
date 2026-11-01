import { defineConfig, devices } from '@playwright/test'
import { existsSync } from 'node:fs'

const dbUrl = process.env.YANDU_E2E_DB_URL ||
  'jdbc:mysql://127.0.0.1:3306/yandu_browser_e2e?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC'
const executablePath = process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH ||
  (existsSync('/usr/bin/chromium') ? '/usr/bin/chromium' : undefined)

export default defineConfig({
  globalSetup: './tests/e2e-mysql/global-setup.ts',
  testDir: './tests/e2e-mysql',
  fullyParallel: false,
  workers: 1,
  timeout: 90_000,
  reporter: 'list',
  use: {
    baseURL: 'http://127.0.0.1:5175',
    trace: 'retain-on-failure',
    launchOptions: { executablePath },
  },
  projects: [{ name: 'mysql-chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: [
    {
      command: 'mvn -B -q -pl backend -am -Pmachine-translation-libretranslate -DskipTests package && java -jar backend/target/yandu-backend-0.1.0-SNAPSHOT.jar',
      url: 'http://127.0.0.1:4174/readyz',
      reuseExistingServer: false,
      timeout: 180_000,
      env: {
        SERVER_PORT: '4174',
        YANDU_DB_URL: dbUrl,
        YANDU_DB_USER: process.env.YANDU_E2E_DB_USER || 'root',
        YANDU_DB_PASSWORD: process.env.YANDU_E2E_DB_PASSWORD || 'yandu-ci-root',
        YANDU_OIDC_ISSUER_URI: 'http://127.0.0.1:4180',
        YANDU_OIDC_AUDIENCE: 'yandu-api',
        SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWK_SET_URI: 'http://127.0.0.1:4180/jwks',
        YANDU_MT_PROVIDER: 'libretranslate',
        YANDU_MT_BASE_URL: 'http://127.0.0.1:4180',
        YANDU_MT_POLL_MS: '300',
        YANDU_CORS_ALLOWED_ORIGINS: 'http://127.0.0.1:5175',
      },
    },
    {
      command: 'VITE_YANDU_API_URL=http://127.0.0.1:4174 VITE_OIDC_AUTHORITY=http://127.0.0.1:4180 VITE_OIDC_CLIENT_ID=yandu-mysql-e2e npm run dev -- --host 127.0.0.1 --port 5175',
      url: 'http://127.0.0.1:5175',
      reuseExistingServer: false,
      timeout: 30_000,
    },
  ],
})
