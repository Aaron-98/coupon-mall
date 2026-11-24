
const api = require('../../lib/api');
const view = require('../../lib/view');
Page({
  data: { loading: true, error: '' },
  onLoad(options) {
    this.offerId = options.id;
    this.context = { quantity: Number(options.quantity || 1), region: options.region || '', eligibility: options.eligibility || 'STANDARD' };
    this.load();
  },
  async load() {
    this.setData({ loading: true, error: '' });
    try {
      const result = await api.request('/comparisons', { method: 'POST', data: { offerId: this.offerId, context: this.context } });
      const results = {};
      for (const key of ['comparable', 'referenceOnly', 'excluded']) results[key] = result[key].map(view.card);
      this.setData(Object.assign({}, result, results, { eligibilityLabel: { STANDARD: '普通购买', MEMBER: '会员', NEW: '新客' }[result.eligibility] }));
      api.track('comparison_finished', { offerId: this.offerId });
    } catch (error) { this.setData({ error: error.message }); }
    finally { this.setData({ loading: false }); }
  },
  open(e) { view.detail(e.detail); }
});
