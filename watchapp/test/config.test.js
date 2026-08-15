'use strict';
const assert = require('assert');
const { JSDOM } = require('jsdom');
const config = require('../src/pkjs/index.js');

const original = JSON.parse(JSON.stringify(config.defaults));
assert(config.validate(original));
assert.deepStrictEqual(config.parse(config.serialize(original)), original);

const pageUrl = config.page(original, ['Hiking', 'Cycling', 'Running']);
assert(pageUrl.startsWith('data:text/html'));
const html = decodeURIComponent(pageUrl.split(',').slice(1).join(','));
const embeddedScript = html.match(/<script>([\s\S]*)<\/script>/)[1];
assert.doesNotThrow(() => new Function(embeddedScript), 'generated settings script must parse');
assert(!embeddedScript.includes('Array.from('), 'CoreApp webview needs ES5-compatible DOM code');

let closedPayload = null;
const dom = new JSDOM(html, {
  runScripts: 'dangerously',
  beforeParse(window) {
    window.__pebbleConfigClose = payload => { closedPayload = payload; };
  },
});
const document = dom.window.document;
const sections = () => document.querySelectorAll('#profiles section');

assert.strictEqual(sections().length, 3, 'built-in profiles render on initial load');
assert.strictEqual(sections()[0].querySelector('.name').disabled, true, 'built-in name is protected');
assert.strictEqual(sections()[0].querySelectorAll('.metrics input:checked').length, 6);
assert.strictEqual(document.querySelectorAll('#locusNames option').length, 3);

document.getElementById('add').click();
assert.strictEqual(sections().length, 4, 'Add profile updates the page');
sections()[3].querySelector('.name').value = 'Walking';
sections()[3].querySelector('.name').dispatchEvent(new dom.window.Event('change'));
sections()[3].querySelector('.locus').value = 'Hiking';
sections()[3].querySelector('.locus').dispatchEvent(new dom.window.Event('change'));
sections()[3].querySelector('[name=sel]').click();
sections()[3].querySelector('.dup').click();
assert.strictEqual(sections().length, 5, 'Duplicate updates the page');
sections()[4].querySelector('.del').click();
assert.strictEqual(sections().length, 4, 'Delete updates the page');
sections()[3].querySelector('.up').click();
assert.strictEqual(sections()[2].querySelector('.name').value, 'Walking', 'Up reorders profiles');
sections()[2].querySelector('.down').click();
assert.strictEqual(sections()[3].querySelector('.name').value, 'Walking', 'Down reorders profiles');
sections()[3].querySelector('.up').click();
const firstSelectedMetric = sections()[2].querySelector('.metrics input:checked');
firstSelectedMetric.click();
assert.strictEqual(sections()[2].querySelectorAll('.metrics input:checked').length, 1, 'metric controls update');
document.getElementById('theme').value = 'light';
document.getElementById('save').click();

assert(closedPayload, 'Save closes the CoreApp webview with a payload');
const saved = JSON.parse(closedPayload);
assert.strictEqual(saved.theme, 'light');
assert.strictEqual(saved.profiles.length, 4);
assert.strictEqual(saved.selected, 2, 'selection follows the profile while reordering');
assert.strictEqual(saved.profiles[2].name, 'Walking');
assert(config.validate(saved), 'saved payload passes production validation');

assert(!config.remove(original, 0), 'built-ins are protected');
assert(!config.rename(original, 1, 'Road'), 'built-ins cannot be renamed');
assert(config.add(original));
assert(config.rename(original, 3, 'Walk'));
assert(config.move(original, 3, 0));
assert(config.remove(original, 0));
while (original.profiles.length < 8) assert(config.add(original));
assert(!config.add(original), 'only eight profiles are allowed');

const malformed = JSON.parse(JSON.stringify(config.defaults));
malformed.profiles[0].metrics = [1, 1];
assert(!config.validate(malformed));
assert.strictEqual(config.parse('dark|0\ncorrupt'), null);
