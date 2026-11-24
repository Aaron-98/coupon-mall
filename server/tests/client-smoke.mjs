import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { setTimeout as sleep } from 'node:timers/promises';

const load = createRequire(import.meta.url);
const storage = new Map();
const app = {};
let navigation;
const apiBase = process.env.COUPON_TEST_API;
load('../../lib/config.js').apiBase = apiBase;

globalThis.getApp = () => app;
globalThis.wx = {
  getStorageSync: key => storage.get(key),
  setStorageSync: (key, value) => storage.set(key, value),
  removeStorageSync: key => storage.delete(key),
  navigateTo: ({ url }) => { navigation = url; },
  switchTab: ({ url }) => { navigation = url; },
  showModal: ({ success }) => success({ confirm: true }),
  showToast: () => {},
  async request({ url, method, data, header, success, fail }) {
    try {
      const headers = { ...header };
      if (data) headers['Content-Type'] = 'application/json';
      const res = await fetch(url, { method, headers, body: data ? JSON.stringify(data) : undefined });
      success({ statusCode: res.status, data: res.status === 204 ? null : await res.json() });
    } catch (error) {
      fail({ errMsg: error.message });
    }
  }
};

function page(name) {
  let definition;
  globalThis.Page = value => { definition = value; };
  load('../../pages/' + name + '/' + name + '.js');
  return {
    ...definition,
    data: structuredClone(definition.data),
    setData(patch) {
      for (const [key, value] of Object.entries(patch)) {
        const parts = key.split('.');
        let target = this.data;
        while (parts.length > 1) target = target[parts.shift()];
        target[parts[0]] = value;
      }
    }
  };
}

async function waitFor(condition) {
  for (let i = 0; i < 35; i++) {
    if (condition()) return;
    await sleep(110);
  }
  assert.fail('Client state did not update');
}

const index = page('index');
await index.onShow();
assert.equal(index.data.offers[0].amount, '¥95.00');
index.input({ detail: { value: '保温杯' } });
await index.resolve();
assert.match(navigation, /radar/);

const radar = page('radar');
radar.onShow();
await waitFor(() => !radar.data.loading);
assert.equal(radar.data.items.length, 1);
assert.match(radar.data.items[0].name, /保温杯/);
radar.open({ detail: 'demo-01' });
assert.match(navigation, /detail.*demo-01/);

const product = page('detail');
product.onLoad({ id: 'demo-01' });
await waitFor(() => !product.data.loading);
assert.equal(product.data.offer.plan.estimatedTotalFen, 9500);
assert.equal(product.data.offer.outboundAvailable, false);
product.compare();
assert.match(navigation, /compare.*demo-01/);

const comparison = page('compare');
comparison.onLoad({ id: 'demo-01', quantity: '1' });
await waitFor(() => !comparison.data.loading);
assert.deepEqual(comparison.data.comparable.map(offer => offer.amount), ['¥95.00', '¥99.00']);
assert.equal(comparison.data.excluded.length, 1);
await product.save();
await product.history();
assert.ok(product.data.history.length >= 1);
product.threshold({ detail: { value: '100' } });
await product.watch();
const favorites = page('favorites');
await favorites.load();
assert.equal(favorites.data.items[0].id, 'demo-01');

const recognition = page('recognize');
recognition.onLoad({});
recognition.onShow();
recognition.mode({ currentTarget: { dataset: { mode: 'text' } } });
recognition.input({ detail: { value: '满100减20元\n有效期：今晚\n适用范围：指定店铺' } });
await recognition.submit();
await waitFor(() => recognition.data.fields.length > 0);
assert.equal(recognition.data.form.discount, '20');
assert.equal(recognition.data.relative, true);
await recognition.save();
assert.equal(recognition.data.task.status, 'COMPLETED');
assert.equal(recognition.data.missingLabel, '截图日期');
recognition.onUnload();

const profile = page('profile');
await profile.load();
assert.equal(profile.data.notifications.length, 1);
assert.equal(profile.data.subscriptions.length, 1);
await profile.unwatch({ currentTarget: { dataset: { id: 'demo-01' } } });
assert.equal(profile.data.subscriptions.length, 0);
assert.equal(profile.data.history[0].id, recognition.id);
await profile.remove({ currentTarget: { dataset: { id: recognition.id } } });
assert.equal(profile.data.history.length, 0);
await profile.deleteData();
await favorites.load();
assert.equal(favorites.data.loggedIn, false);
console.log('Mini program page logic passed against the Java API');
