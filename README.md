## Prerequisites
- You must have NodeJS (https://nodejs.org) and the yarn package manager (https://classic.yarnpkg.com) installed
- You must have a google cloud platform (gcp) developer account (console.cloud.google.com)

## GCP Setup
- Log in to gcp at console.cloud.google.com
- Go to the Cloud Firestore screen and enable the API implementation https://console.developers.google.com/apis/api/firestore.googleapis.com/overview
- Go to https://console.cloud.google.com/firestore/welcome and enable a Native Mode firestore database
- Go to the APIs & Services screen https://console.cloud.google.com/apis/dashboard
- If the Youtube API isn't listed in the table, find it in the library and enable it https://console.cloud.google.com/apis/library
- Within the Youtube API dashboard, go to the API credentials page https://console.cloud.google.com/apis/api/youtube.googleapis.com/credentials
- Use the "Create Credentials" button at the top to create an OAuth Client and set the type to "Web Application"
- Go to the Security > Secret Manager product section and enable the Secret Manager API if it's not already enabled
- Add the secret key from the youtube OAuth application to Secret Manager using `youtube-rest-api-secret` as the secret name
- Ensure the functions service account can access the secret by selecting the checkbox beside the secret you just created and then clicking `Add Principal`in the permissions box to the right so you can add the `Secret Manager Secret Accessor` role to the service account (if you don't know the service account, it is available in the function `Details` tab after you've deployed the function)
- When you know the url of the api (see the `Backend deployment` step), add it as an `authroized redirect uri` to the credential with the path `/tokenResponse` appended to the end

## Backend deployment
- In the source code, go to the `gcp` directory
- Copy the `functions/src/config.sample.json` file to `functions/src/config.json`
  - The value of the `clientId` field is the Client Id from the youtube OAuth application
  - The value of the `apiBaseUrl` field is the `trigger` of the function once it's deployed. This follows a predictable path if you know your function region, project id, and function name, but if in doubt you can deploy the function once with an empty string for this value so you can view the trigger information using the gcp functions dashboard.
  - The value of the `apiSecretVersion` field is version of the secret that contains the youtube api secret key. If you added the secret and never changed it then this version will be "1" or "latest", otherwise the different version numbers can be seen when viewing the secret in google cloud console.
- Copy the `.firebaserc.sample` file to `.firebaserc` and add your project id into it
- In a command line, go to the `gcp/functions` directory
- Run the `yarn` command to install all dependencies
- Run `yarn firebase login` to log in to gcp
- Run the `yarn deploy` command to deploy the rest api backend to your gcp project
- In the android app, create a new `values` xml file in the `res/values` directory and add a string with the name `auth_api_url` and the same value as `apiBaseUrl` above

## Auth Expiration
While the youtube oauth app is still in the "testing" state, any users will need to provide consent to the app every 7 days.
You can move your app into a different state by going to the `OAuth Consent Screen` section of the gcp `APIs and Services` (https://console.cloud.google.com/apis/credentials/consent) product and clicking the `Publish` button.
Anecdotal evidence suggests that even moving the app status to `Needs verification` will be enough to ensure the app does not need to re-acquire consent every 7 days, if you only plan on using it for a personal project (https://stackoverflow.com/a/65936387/989477).
