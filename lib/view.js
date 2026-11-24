
function money(fen) {
  return Number.isFinite(fen) ? '¥' + (fen / 100).toFixed(2) : '待确认';
}

function time(value) {
  if (!value) return '';
  const instant = Date.parse(value);
  if (!Number.isFinite(instant)) return value;
  const shanghai = new Date(instant + 8 * 3600000).toISOString();
  return shanghai.slice(5, 10) + ' ' + shanghai.slice(11, 16);
}

function card(offer) {
  const plan = offer.plan || {};
  const priced = Number.isFinite(plan.estimatedTotalFen);
  return Object.assign({}, offer, {
    amount: money(priced ? plan.estimatedTotalFen : plan.merchandiseFen),
    amountLabel: offer.demo ? (priced ? '样例预估价' : '样例参考价') : (priced ? '预估到手价' : '参考价'),
    timeLabel: time(offer.observedAt),
    stateLabel: offer.available === false || offer.fresh === false ? '已失效' : (offer.stale ? '待刷新' : ''),
    missingLabel: (plan.missingConditions || []).join('、'),
    categoryMark: ({ 数码: '♫', 日用: '◒', 家居: '✧' })[offer.category] || '◇',
    categoryClass: ({ 数码: 'digital', 日用: 'daily', 家居: 'home' })[offer.category] || '',
  });
}

function toast(title) { wx.showToast({ title, icon: 'none' }); }
function detail(id) { wx.navigateTo({ url: '/pages/detail/detail?id=' + encodeURIComponent(id) }); }
function recognition(id) { wx.navigateTo({ url: '/pages/recognize/recognize' + (id ? '?id=' + encodeURIComponent(id) : '') }); }
function confirm(title, content) {
  return new Promise(resolve => wx.showModal({
    title, content, confirmText: '确认', cancelText: '取消', success: res => resolve(res.confirm), fail: () => resolve(false)
  }));
}

module.exports = { money, time, card, toast, detail, recognition, confirm };
