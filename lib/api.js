
const { apiBase } = require('./config');
const key = 'coupon-session-v1';
let pendingGuest;

function session() {
  return wx.getStorageSync(key) || {};
}

function save(value) {
  wx.setStorageSync(key, value);
  return value;
}

function request(path, { method = 'GET', data, headers, token } = {}) {
  return new Promise((resolve, reject) => {
    const header = Object.assign({}, headers);
    if (token) header.Authorization = 'Bearer ' + token;
    if (method === 'PATCH') header['X-HTTP-Method-Override'] = 'PATCH';
    wx.request({
      url: apiBase + path,
      method: method === 'PATCH' ? 'POST' : method,
      data,
      header,
      timeout: 18000,
      success(res) {
        if (res.statusCode >= 200 && res.statusCode < 300) return resolve(res.statusCode === 204 ? null : res.data.data);
        const payload = res.data || {};
        const error = new Error(payload.message || '服务暂时无法处理请求');
        error.code = payload.code;
        error.data = payload.data;
        reject(error);
      },
      fail(res) {
        const reason = (res && res.errMsg) || '';
        if (reason.includes('url not in domain list') || reason.includes('合法域名')) {
          reject(new Error('接口域名未通过小程序校验'));
        } else {
          console.warn('Coupon Mall API connection failed', reason);
          reject(new Error('无法连接服务，请检查 API 地址或稍后重试'));
        }
      }
    });
  });
}

async function guest() {
  const current = session();
  if (current.token) return current;
  if (!pendingGuest) {
    pendingGuest = request('/sessions/guest', { method: 'POST' }).then(save)
      .finally(() => { pendingGuest = null; });
  }
  return pendingGuest;
}

async function privateRequest(path, options) {
  const current = await guest();
  try {
    return await request(path, Object.assign({}, options, { token: current.token }));
  } catch (error) {
    if (error.code !== 'LOGIN_REQUIRED' || !error.message.includes('会话已失效')) throw error;
    if (session().token === current.token) clear();
    const renewed = await guest();
    return request(path, Object.assign({}, options, { token: renewed.token }));
  }
}

async function login() {
  const current = await guest();
  const code = current.demo ? '' : await new Promise((resolve, reject) => {
    wx.login({ success: res => resolve(res.code), fail: () => reject(new Error('微信登录暂不可用')) });
  });
  return save(await request('/sessions/login', { method: 'POST', token: current.token, data: { code } }));
}

function clear() { wx.removeStorageSync(key); }

function track(event, identifiers = {}) {
  const eventId = Date.now() + '_' + Math.random().toString(36).slice(2);
  return privateRequest('/events', { method: 'POST', data: Object.assign({ event, eventId }, identifiers) }).catch(() => null);
}

async function picture(path) {
  const current = await guest();
  return new Promise((resolve, reject) => {
    wx.downloadFile({
      url: apiBase + path,
      header: { Authorization: 'Bearer ' + current.token },
      success: res => res.statusCode === 200 ? resolve(res.tempFilePath) : reject(new Error('原图已清理或暂不可用')),
      fail: () => reject(new Error('原图加载失败'))
    });
  });
}

function query(values) {
  return Object.keys(values).filter(key => values[key] !== '' && values[key] !== undefined)
    .map(key => encodeURIComponent(key) + '=' + encodeURIComponent(values[key])).join('&');
}

module.exports = { request, privateRequest, guest, login, session, clear, picture, query, track };
