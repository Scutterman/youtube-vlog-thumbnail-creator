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
  try {
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
          <div id="hideUntilReady" style="display: none">
            <p>
              <strong>Credentials</strong> <button id="copyCredentials">Copy</button><br />
              Copy the credentials into the app to finish authentication.
            </p>
            <p>
              <textview id="credentials"></textview>
            </p>
          <script type="text/javascript">
            window.addEventListener('DOMContentLoaded', (event) => {
              document.querySelector('#credentials').value = \`${ JSON.stringify(accessToken) }\`
              document.querySelector('#copyCredentials').addEventListener('click', e => {
                e.preventDefault()
                navigator.clipboard.writeText(document.querySelector('#credentials').value)
                alert("Credentials have been copied to the clipboard. Switch back to the app to complete the authentication.")
              })
              
              document.querySelector('#hideUntilReady').style.display = 'block'
            })
          </script>
        </body>
      </html>`)
    } else {
      response.sendStatus(404)
    }
  } catch (e) {
    console.error('Something went wrong with the rest api', e)
    response.sendStatus(500)
  }
})
