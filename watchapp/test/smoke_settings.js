'use strict';
const assert = require('assert');
const fs = require('fs');
const {JSDOM} = require('jsdom');

module.exports = function saveSettings(url) {
assert(url.startsWith('data:text/html;charset=utf-8,'));
let response;
const dom = new JSDOM(decodeURIComponent(url.slice(url.indexOf(',') + 1)), {
  runScripts: 'dangerously',
  beforeParse(window) {
    window.__pebbleConfigClose = value => { response = value; };
    window.confirm = () => true;
    window.alert = message => { throw new Error(message); };
  },
});
const document = dom.window.document;
assert(document.querySelector('.activity'), 'real packaged settings must contain the supplied profile');
document.querySelector('#generalOpen').click();
const theme = document.querySelector('#theme');
theme.value = theme.value === 'dark' ? 'light' : 'dark';
document.querySelector('#generalDone').click();
document.querySelector('#save').click();
assert(response, 'saving the real settings page must return a configuration');
assert.strictEqual(JSON.parse(response).theme, theme.value);
const result = encodeURIComponent(response);
dom.window.close();
return result;
};

if (require.main === module) process.stdout.write(module.exports(fs.readFileSync(0, 'utf8').trim()));
