'use strict';

module.exports = [
  {
    files: ['src/pkjs/**/*.js'],
    languageOptions: {
      ecmaVersion: 5,
      sourceType: 'script',
      globals: {
        Pebble: 'readonly',
        localStorage: 'readonly',
        window: 'readonly',
        location: 'readonly',
        clearTimeout: 'readonly',
        setTimeout: 'readonly',
        module: 'readonly',
      },
    },
    rules: { 'no-undef': 'error', 'no-unreachable': 'error', 'no-dupe-keys': 'error' },
  },
  {
    files: ['test/**/*.js', 'eslint.config.js'],
    languageOptions: {
      ecmaVersion: 2022,
      sourceType: 'commonjs',
      globals: {
        __dirname: 'readonly',
        Buffer: 'readonly',
        module: 'readonly',
        process: 'readonly',
        require: 'readonly',
      },
    },
    rules: { 'no-undef': 'error', 'no-unreachable': 'error', 'no-dupe-keys': 'error' },
  },
];
