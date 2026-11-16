'use strict';
const fs = require('fs');
const vm = require('vm');
const readline = require('readline');
const console = require('node:console');
const {setTimeout, clearTimeout, setInterval, clearInterval} = require('node:timers');
const saveSettings = require('./smoke_settings.js');
const listeners = {};
const pending = new Map();
const storage = new Map();
let sequence = 0;
const emit = value => process.stdout.write(JSON.stringify(value) + '\n');
const context = {
  console: {log: (...values) => console.error(...values)},
  setTimeout, clearTimeout, setInterval, clearInterval,
  localStorage: {
    getItem: key => storage.has(key) ? storage.get(key) : null,
    setItem: (key, value) => storage.set(key, String(value)),
    removeItem: key => storage.delete(key),
  },
  Pebble: {
    addEventListener: (name, handler) => { listeners[name] = handler; },
    getActiveWatchInfo: () => ({language: 'en_US', platform: process.argv[3]}),
    sendAppMessage(frame, success, failure) {
      const id = ++sequence;
      pending.set(id, {success, failure});
      emit({event: 'send', id, frame});
    },
    openURL: url => emit({event: 'settings', url}),
  },
};
vm.runInNewContext(fs.readFileSync(process.argv[2], 'utf8'), context, {filename: 'packaged-pkjs.js'});
readline.createInterface({input: process.stdin}).on('line', line => {
  try {
    const message = JSON.parse(line);
    switch (message.op) {
      case 'ready':
        listeners.ready();
        emit({event: 'ready'});
        break;
      case 'message':
        listeners.appmessage({payload: message.payload});
        break;
      case 'sent': {
        const callbacks = pending.get(message.id);
        pending.delete(message.id);
        const callback = message.success ? callbacks.success : callbacks.failure;
        if (callback) callback({});
        break;
      }
      case 'configure':
        listeners.showConfiguration();
        break;
      case 'save':
        listeners.webviewclosed({response: saveSettings(message.url)});
        emit({event: 'saved'});
        break;
      default:
        throw new Error('Unknown phone operation');
    }
  } catch (error) {
    console.error(error.stack);
    process.exit(1);
  }
});
