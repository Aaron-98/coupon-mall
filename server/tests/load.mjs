import { performance } from 'node:perf_hooks';
import { readFile, writeFile } from 'node:fs/promises';
import { setTimeout as sleep } from 'node:timers/promises';

const base = process.env.COUPON_TEST_API || 'http://127.0.0.1:8791/api/v1';
const seconds = Number(process.env.COUPON_LOAD_SECONDS || 900);
const users = Number(process.env.COUPON_LOAD_USERS || 50);
const output = process.env.COUPON_LOAD_REPORT || 'target/load-report.json';
const image = process.env.COUPON_LOAD_IMAGE ? (await readFile(process.env.COUPON_LOAD_IMAGE)).toString('base64') : null;
const latencies = [], tasks = [], errors = [];
async function call(path, method = 'GET', data, token) {
  const start = performance.now();
  const res = await fetch(base + path, { method, headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: 'Bearer ' + token } : {}) }, body: data ? JSON.stringify(data) : undefined });
  const body = await res.json();
  if (!res.ok) throw new Error(path + ': ' + body.code);
  if (path.startsWith('/products')) latencies.push(performance.now() - start);
  return body.data;
}
const tokens = [];
for (let i = 0; i < users; i++) {
  const guest = await call('/sessions/guest', 'POST');
  tokens.push((await call('/sessions/login', 'POST', {}, guest.token)).token);
}
const start = performance.now(), end = start + seconds * 1000;
async function recognition(token, data, accepted, type) {
  const task = await call('/recognitions', 'POST', data, token);
  let result;
  do {
    await sleep(100);
    result = await call('/recognitions/' + task.id, 'GET', undefined, token);
  } while (['CREATED', 'PROCESSING'].includes(result.status) && performance.now() - accepted < 25000);
  tasks.push({ type, status: result.status, ms: performance.now() - accepted });
}
await Promise.all(tokens.map(async (token, user) => {
  let nextTask = start + user * 100;
  let nextImage = start + user * 5000;
  while (performance.now() < end) {
    const tick = performance.now();
    try {
      await call('/products?limit=12');
      if (tick >= nextTask) {
        const accepted = performance.now();
        await recognition(token, { inputType: 'text', text: '满100减20元\n有效期：今晚\n适用范围：指定店铺' }, accepted, 'text');
        nextTask += 61000;
      }
      if (image && tick >= nextImage) {
        const accepted = performance.now();
        const upload = await call('/uploads', 'POST', { imageBase64: image }, token);
        await recognition(token, { inputType: 'image', uploadId: upload.uploadId }, accepted, 'image');
        nextImage += 300000;
      }
    } catch (error) { errors.push(String(error)); }
    await sleep(Math.max(0, 1000 - (performance.now() - tick)));
  }
}));
function percentile(values, p) { return [...values].sort((a,b) => a-b)[Math.ceil(values.length * p) - 1] ?? null; }
const success = tasks.filter(task => ['NEED_CONFIRMATION', 'COMPLETED'].includes(task.status)).length;
const images = tasks.filter(task=>task.type==='image');
const report = { users, durationSeconds: (performance.now()-start)/1000, mode: image ? 'synthetic demo; 50 active list users; one text task/user/61s; one real OCR image/user/300s staggered by 5s; end-to-end includes upload' : 'synthetic demo; text tasks; local Windows OCR benchmark reported separately', listRequests: latencies.length, listP95Ms: percentile(latencies, .95), acceptedTasks: tasks.length, taskSuccessRate: tasks.length ? success/tasks.length : null, taskP95Ms: percentile(tasks.map(task=>task.ms), .95), imageTasks: images.length, imageP95Ms: percentile(images.map(task=>task.ms), .95), errorCount: errors.length, errors: errors.slice(0,20) };
report.pass = report.listP95Ms <= 800 && report.taskSuccessRate >= .95 && report.taskP95Ms <= 15000 && !errors.length;
if (image) report.pass &&= images.length > 0 && report.imageP95Ms <= 15000;
await writeFile(output, JSON.stringify(report, null, 2));
console.log(JSON.stringify(report));
if (!report.pass) process.exitCode = 1;
