import { createServer } from 'node:http'

export function createMockServices(jwk) {
  const publicJwk = { ...jwk, kid: 'yandu-mysql-e2e', use: 'sig', alg: 'RS256' }
  return createServer(async (request, response) => {
    if (request.url === '/health') {
      response.writeHead(200, { 'content-type': 'text/plain' })
      response.end('ok')
      return
    }
    if (request.url === '/jwks') {
      response.writeHead(200, { 'content-type': 'application/json' })
      response.end(JSON.stringify({ keys: [publicJwk] }))
      return
    }
    if (request.url === '/translate' && request.method === 'POST') {
      let body = ''
      for await (const chunk of request) body += chunk
      const { q } = JSON.parse(body)
      response.writeHead(200, { 'content-type': 'application/json' })
      response.end(JSON.stringify({ translatedText: `MT: ${q}` }))
      return
    }
    response.writeHead(404)
    response.end()
  })
}
