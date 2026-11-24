
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { setTimeout as sleep } from 'node:timers/promises';

const base = process.env.COUPON_TEST_API || 'http://127.0.0.1:8787/api/v1';
const secret = process.env.COUPON_ADMIN_KEY;
const imagePath = process.argv[2];

async function call(path, { method = 'GET', data, token, key, admin, status = 200 } = {}) {
  const headers = {};
  if (data) headers['Content-Type'] = 'application/json';
  if (token) headers.Authorization = 'Bearer ' + token;
  if (key) headers['Idempotency-Key'] = key;
  if (admin) headers['X-Admin-Key'] = secret;
  if (method === 'PATCH') {
    method = 'POST';
    headers['X-HTTP-Method-Override'] = 'PATCH';
  }
  const res = await fetch(base + path, { method, headers, body: data ? JSON.stringify(data) : undefined });
  const body = res.headers.get('Content-Type')?.startsWith('application/json') ? await res.json() : null;
  assert.equal(res.status, status, path + ': ' + JSON.stringify(body));
  if (body) assert.ok(body.requestId);
  return body?.data;
}

async function finished(id, token) {
  for (let i = 0; i < 35; i++) {
    const task = await call('/recognitions/' + id, { token });
    if (!['CREATED', 'PROCESSING'].includes(task.status)) return task;
    await sleep(450);
  }
  assert.fail('Recognition task did not finish in time');
}
const health = await call('/health');
assert.ok(health.demo);
const a = (await call('/sessions/guest', { method: 'POST' })).token;
const b = (await call('/sessions/guest', { method: 'POST' })).token;
const started = await Promise.all([1, 2].map(() => call('/recognitions', {
  method: 'POST', token: a, key: 'same-request', status: 202,
  data: { inputType: 'text', text: '满100减20元\n有效期：今晚\n适用范围：指定店铺\n商品：声屿 Air 2' }
})));
assert.equal(started[0].id, started[1].id);
const id = started[0].id;
await call('/recognitions/' + id, { token: b, status: 404 });
const done = await finished(id, a);
assert.equal(done.status, 'NEED_CONFIRMATION');
assert.equal(done.fields.find(field => field.name === 'discount').value, '20');
const confirmation = { version: done.version, confirmedFields: { threshold: '100', discount: '20', period: '今晚', scope: '指定店铺' } };
const saved = await call('/recognitions/' + id + '/confirmation', { method: 'PATCH', token: a, data: confirmation });
assert.equal(saved.status, 'COMPLETED');
assert.deepEqual(saved.missingConditions, ['截图日期']);
await call('/recognitions/' + id + '/confirmation', { method: 'PATCH', token: a, data: confirmation, status: 409 });
await call('/uploads', { method: 'POST', token: a, data: { imageBase64: Buffer.from('fake image file').toString('base64') }, status: 400 });
if (imagePath) {
  const uploaded = await call('/uploads', { method: 'POST', token: a, data: { imageBase64: (await readFile(imagePath)).toString('base64') } });
  await call('/recognitions', { method: 'POST', data: { inputType: 'image', uploadId: uploaded.uploadId }, token: b, status: 404 });
  const image = await call('/recognitions', { method: 'POST', data: { inputType: 'image', uploadId: uploaded.uploadId }, token: a, status: 202 });
  const result = await finished(image.id, a);
  assert.equal(result.status, 'NEED_CONFIRMATION');
  const field = result.fields.find(field => field.name === 'discount');
  assert.equal(field.value, '20', JSON.stringify(result.fields));
  assert.ok(field.bbox.width > 0 && field.bbox.width <= 1);
  await call('/recognitions/' + image.id + '/image', { token: a });
  await call('/recognitions/' + image.id + '/image', { token: b, status: 404 });
  await call('/me/recognitions/' + image.id, { method: 'DELETE', token: a, status: 204 });
  await call('/recognitions/' + image.id + '/image', { token: a, status: 404 });
}

const first = await call('/products?limit=2&category=' + encodeURIComponent('数码'));
assert.deepEqual((await call('/products?maxFen=10000')).items.map(row=>row.id), ['demo-01','demo-02','demo-04']);
assert.equal(first.items.length, 2);
const next = await call('/products?limit=2&cursor=' + first.nextCursor);
assert.ok(!next.items.some(row => first.items.some(initial => initial.id === row.id)));
const offer = await call('/products/demo-01/offers?quantity=1');
assert.equal(offer.plan.estimatedTotalFen, 9500);
assert.equal(offer.plan.couponFen, 2000);
assert.equal((await call('/products/demo-05/offers')).plan.estimatedTotalFen, null);
assert.equal((await call('/products/demo-01/offers?quantity=2')).plan.quantity, 2);
await call('/products/demo-01/offers?quantity=0', { status: 400 });
const comparison = await call('/comparisons', { method: 'POST', data: { offerId: 'demo-01' } });
assert.deepEqual(comparison.comparable.map(row => row.plan.estimatedTotalFen), [9500, 9900]);
assert.equal(comparison.excluded[0].id, 'demo-03');
assert.equal((await call('/resolve', { method: 'POST', data: { text: 'https://item.taobao.com/item.htm?id=900000000001' } })).offerId, 'demo-01');
await call('/resolve', { method: 'POST', data: { text: 'http://127.0.0.1:1234/test' }, status: 400 });
await call('/outbound-targets', { method: 'POST', data: { offerId: 'demo-01', planVersion: offer.planVersion }, status: 403 });

await call('/favorites', { token: a, status: 401 });
const logged = (await call('/sessions/login', { method: 'POST', token: a })).token;
assert.equal((await call('/recognitions/' + id, { token: logged })).id, id);
await call('/recognitions/' + id, { token: a, status: 401 });
for (let i = 0; i < 2; i++) await call('/favorites/demo-01', { method: 'PUT', token: logged });
assert.equal((await call('/favorites', { token: logged })).length, 1);
await call('/subscriptions/demo-01', { method: 'PUT', token: logged, data: { thresholdFen: 10000, consent: false }, status: 400 });
await call('/subscriptions/demo-01', { method: 'PUT', token: logged, data: { thresholdFen: 10000, consent: true } });
assert.equal((await call('/subscriptions', { token: logged })).length, 1);
assert.equal((await call('/me/notifications', { token: logged })).length, 1);
assert.equal((await call('/me/notifications', { token: logged })).length, 1); // frequency cap
await call('/subscriptions', { token: b, status: 401 });
await call('/subscriptions/demo-01', { method: 'DELETE', token: logged, status: 204 });
assert.equal((await call('/subscriptions', { token: logged })).length, 0);
await call('/admin/dashboard', { status: 403 });
if (secret) {
  const dashboard = await call('/admin/dashboard', { admin: true });
  const source = dashboard.source;
  const complaint = await call('/feedback', { method: 'POST', token: logged, data: { reason: '价格', description: '测试反馈' } });
  const handled = await call('/admin/feedback/' + complaint.feedbackId, { method: 'PATCH', admin: true, data: { version: 1, reason: '测试已核对' } });
  assert.equal(handled.status, 'RESOLVED');
  const stop = await call('/admin/sources/' + source.id, { method: 'PATCH', admin: true, data: { version: source.version, reason: '测试停用', enabled: false } });
  try {
    assert.equal((await call('/products')).items.length, 0);
    assert.equal((await call('/favorites', { token: logged }))[0].available, false);
  } finally {
    await call('/admin/sources/' + source.id, { method: 'PATCH', admin: true, data: { version: stop.version, reason: '测试恢复', enabled: true } });
  }
  const paused = await call('/admin/rules', { method: 'PATCH', admin: true, data: { version: dashboard.rule.version, reason: '测试发布', couponsEnabled: false } });
  try { assert.equal((await call('/products/demo-01/offers')).plan.estimatedTotalFen, 11500); }
  finally {
    await call('/admin/rules', { method: 'PATCH', admin: true, data: { version: paused.version, reason: '测试回滚', rollbackTo: dashboard.rule.version } });
  }
  const snapshot = await call('/admin/dashboard', { admin: true });
  assert.ok(snapshot.audit.some(row => row.action === 'RULE_ROLLED_BACK'));
}
await call('/favorites/demo-01', { method: 'DELETE', token: logged, status: 204 });
await call('/me', { method: 'DELETE', token: logged, status: 204 });
await call('/recognitions/' + id, { token: logged, status: 401 });
await call('/me', { method: 'DELETE', token: b, status: 204 });
console.log('API smoke passed' + (imagePath ? ' including real Chinese OCR' : '') + (secret ? ', including admin' : ''));
