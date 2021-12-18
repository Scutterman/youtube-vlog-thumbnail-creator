module.exports = {
  root: true,
  env: {
    es6: true,
    node: true
  },
  extends: [
    'eslint:recommended',
    'plugin:import/errors',
    'plugin:import/warnings',
    'plugin:import/typescript',
    'google',
    'plugin:@typescript-eslint/recommended'
  ],
  parser: '@typescript-eslint/parser',
  overrides: [
    {
      files: ['*.ts', '*.tsx'], // Your TypeScript files extension
      parserOptions: {
        project: ['gcp/functions/tsconfig.json'] // Specify it only for TypeScript files
      }
    }
  ],
  parserOptions: {
    sourceType: 'module'
  },
  ignorePatterns: [
    '/lib/**/*' // Ignore built files.
  ],
  plugins: [
    '@typescript-eslint',
    'import'
  ],
  rules: {
    'comma-dangle': ['error', 'never'],
    'eol-last': ['error', 'always'],
    'import/no-unresolved': 0,
    'max-len': ['error', {
      'code': 150,
      'tabWidth': 2
    }],
    'object-curly-spacing': ['error', 'always'],
    'quotes': ['error', 'single'],
    'require-jsdoc': 'off',
    'semi': ['error', 'never']
  }
}
