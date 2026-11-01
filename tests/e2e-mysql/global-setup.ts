import { generateKeyPairSync } from 'node:crypto'
import { mkdtemp, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { createMockServices } from './mock-services.mjs'

export default async function globalSetup() {
  const { privateKey, publicKey } = generateKeyPairSync('rsa', { modulusLength: 2048 })
  const tempDirectory = await mkdtemp(join(tmpdir(), 'yandu-mysql-e2e-'))
  const privateKeyPath = join(tempDirectory, 'private-key.pem')
  await writeFile(privateKeyPath, privateKey.export({ type: 'pkcs8', format: 'pem' }), { mode: 0o600 })
  process.env.YANDU_E2E_PRIVATE_KEY_FILE = privateKeyPath

  const services = createMockServices(publicKey.export({ format: 'jwk' }))
  await new Promise<void>((resolve, reject) => {
    services.once('error', reject)
    services.listen(4180, '127.0.0.1', resolve)
  })

  return async () => {
    services.closeAllConnections()
    await new Promise<void>((resolve, reject) => services.close((error) => error ? reject(error) : resolve()))
    await rm(tempDirectory, { recursive: true, force: true })
  }
}
