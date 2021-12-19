import { auth } from '@googleapis/oauth2'
import { SecretManagerServiceClient } from '@google-cloud/secret-manager'
import * as functions from 'firebase-functions'
import * as config from './config.json'

interface AccessTokenResponse {
  // eslint-disable-next-line camelcase
  access_token: string
  // eslint-disable-next-line camelcase
  expires_in: number
  // eslint-disable-next-line camelcase
  token_type: 'Bearer'
  scope: string // space-delimited set of strings
  // eslint-disable-next-line camelcase
  refresh_token: string
}

let _secretManager: SecretManagerServiceClient | undefined

function secretManager(): SecretManagerServiceClient {
  if (_secretManager == null) {
    _secretManager = new SecretManagerServiceClient()
  }

  return _secretManager
}

async function getSecret(secretName: string, version: string): Promise<string | null> {
  const name = `projects/${ process.env.GCLOUD_PROJECT }/secrets/${ secretName }/versions/${ version }`
  const [secretVersion] = await secretManager().accessSecretVersion({ name })
  return secretVersion.payload?.data?.toString() ?? null
}

async function getApiKey(): Promise<string> {
  const secret = await getSecret('youtube-rest-api-secret', config.apiSecretVersion)
  if (!secret) {
    throw new Error('Youtube API key not available, add it to secret manager.')
  }

  return secret
}

export const youtubeRestApi = functions.https.onRequest(async (request, response) => {
  if (request.path === '/generateAuthUrl') {
    const oauth2Client = new auth.OAuth2(
        config.clientId,
        await getApiKey(),
        config.apiBaseUrl + '/tokenResponse'
    )

    const scopes = [
      'https://www.googleapis.com/youtube/readonly',
      'https://www.googleapis.com/youtube/upload'
    ]

    const url = oauth2Client.generateAuthUrl({
      // 'online' (default) or 'offline' (gets refresh_token)
      access_type: 'offline',
      scope: scopes
    })

    response.send({
      url
    })
  } else if (request.path === '/tokenResponse') {
    const accessToken = request.body as AccessTokenResponse
    response.send(`<html>
      <body>
        <script type="text/javascript">
          window.addEventListener('DOMContentLoaded', (event) => {
            host.postMessage(\`${ JSON.stringify(accessToken) }\`)
          })
        </script>
      </body>
    </html>`)
  } else {
    response.sendStatus(404)
  }
})
