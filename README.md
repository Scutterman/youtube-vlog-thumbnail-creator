## Prerequisites
- You must have NodeJS (https://nodejs.org) and the yarn package manager (https://classic.yarnpkg.com) installed
- You must have a google cloud platform (gcp) developer account (console.cloud.google.com)

## GCP Setup
- Log in to gcp at console.cloud.google.com
- Go to the APIs & Services screen https://console.cloud.google.com/apis/dashboard
- If the Youtube API isn't listed in the table, find it in the library and enable it https://console.cloud.google.com/apis/library
- Within the Youtube API dashboard, go to the API credentials page https://console.cloud.google.com/apis/api/youtube.googleapis.com/credentials
- Use the "Create Credentials" button at the top to create an OAuth Client and set the type to "Web Application"
- Go to the Security > Secret Manager product section and enable the Secret Manager API if it's not already enabled
- Add the secret key from the youtube OAuth application to Secret Manager using `youtube-rest-api-secret` as the secret name

## Backend deployment
- In the source code, go to the `gcp` directory
- Copy the `functions/config.sample.json` file to `functions/config.json`
  - The value of the `clientId` field is the Client Id from the youtube OAuth application
  - The value of the `apiBaseUrl` field is the `trigger` of the function once it's deployed. This follows a predictable path if you know your function region, project id, and function name, but if in doubt you can deploy the function once with an empty string for this value so you can view the trigger information using the gcp functions dashboard
- Copy the `.firebaserc.sample` file to `.firebaserc` and add your project id into it
- In a command line, go to the `gcp/functions` directory
- Run the `yarn` command to install all dependencies
- Run `yarn firebase login` to log in to gcp
- Run the `yarn deploy` command to deploy the rest api backend to your gcp project
