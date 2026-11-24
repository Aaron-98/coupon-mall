
const api = require('../../lib/api');
const view = require('../../lib/view');

Page({
  data: { quantity: '1', region: '', regionOptions: [], regions: ['默认配送地区'], regionIndex: 0, eligibility: 'STANDARD', eligibilityIndex: 0, eligibilityOptions: ['普通购买', '会员（主动选择）', '新客（主动选择）'], error: '', loading: true, feedback: '' },
  onLoad(options) { this.id = options.id || ''; this.load(); },
  context() { return { quantity: Number(this.data.quantity), region: this.data.region, eligibility: this.data.eligibility }; },
  quantity(e) { this.setData({ quantity: e.detail.value, dirty: true }); },
  async load() {
    const version = this.generation = (this.generation || 0) + 1;
    const context = this.context();
    if (!Number.isInteger(context.quantity) || context.quantity < 1 || context.quantity > 99)
      return this.setData({ error: '数量须为 1 至 99 的整数', loading: false });
    this.setData({ loading: true, error: '', dirty: false });
    try {
      const result = await api.request('/products/' + encodeURIComponent(this.id) + '/offers?' + api.query(context));
      if (version === this.generation) this.show(result);
    } catch (error) { if (version === this.generation) this.setData({ error: error.message, offer: null }); }
    finally { if (version === this.generation) this.setData({ loading: false }); }
  },
  show(result) {
    if (!result.variants && this.data.offer && result.id === this.data.offer.id)
      result = Object.assign({}, result, { variants: this.data.offer.variants });
    const offer = view.card(result);
    const plan = offer.plan;
    const breakdown = [
      { label: '商品小计', value: view.money(plan.itemSubtotalFen) },
      { label: '商品优惠', value: '− ' + view.money(plan.itemDiscountFen) },
      { label: '优惠券抵扣', value: '− ' + view.money(plan.couponFen) },
      { label: '运费', value: view.money(plan.freightFen) },
      { label: '必选费用', value: view.money(plan.feeFen) }
    ];
    const coupons = plan.couponOptions.map(coupon => Object.assign({}, coupon, {
      requirement: '满 ' + view.money(coupon.thresholdFen) + ' 减 ' + (
        Number.isFinite(coupon.discountBps) ? coupon.discountBps / 100 + '%' : view.money(coupon.benefitFen)
      ),
      expiryLabel: coupon.useEnd ? '使用截止 ' + view.time(coupon.useEnd) : '使用期待确认'
    }));
    this.setData({ offer, breakdown, coupons, expires: view.time(offer.expiresAt) });
    if (this.viewedPlan !== offer.planId) {
      this.viewedPlan = offer.planId;
      api.track('offer_viewed', { offerId: offer.id, planId: offer.planId });
    }
  },
  variant(e) { this.id = e.currentTarget.dataset.id; this.load(); },
  async history() {
    const id = this.id;
    try {
      const result = await api.request('/products/' + encodeURIComponent(id) + '/history');
      if (id === this.id) this.setData({ history: result.points.map(point => ({
        date: view.time(point.observedAt), amount: view.money(point.estimatedTotalFen)
      })), historyContext: result.context, historyId: id });
    } catch (error) { view.toast(error.message); }
  },
  threshold(e) { this.setData({ threshold: e.detail.value }); },
  async watch() {
    if (!/^\d{1,7}(\.\d{1,2})?$/.test(this.data.threshold || '') || Number(this.data.threshold) <= 0)
      return view.toast('请填写大于零的目标价格');
    if (!(await view.confirm('开启站内价格提醒', '按单件、默认地区、普通购买条件检查价格。每件商品每天最多提醒一次。在「我的」查看和退订；不会向微信发送消息。'))) return;
    try {
      if (!api.session().loggedIn) await api.login();
      await api.privateRequest('/subscriptions/' + encodeURIComponent(this.id), { method: 'PUT', data: {
        thresholdFen: Math.round(Number(this.data.threshold) * 100), consent: true
      } });
      view.toast('已开启，可在「我的」退订');
    } catch (error) { view.toast(error.message); }
  },
  region(e) {
    const values = e.detail.value.slice(0, 2);
    this.setData({ region: values.join('/'), regionOptions: values });
    this.load();
  },
  clearRegion() { this.setData({ region: '', regionOptions: [] }); this.load(); },
  eligibility(e) {
    const index = Number(e.detail.value);
    this.setData({ eligibility: ['STANDARD', 'MEMBER', 'NEW'][index], eligibilityIndex: index });
    this.load();
  },
  compare() {
    if (this.data.dirty || this.data.loading) return view.toast('请先更新价格');
    wx.navigateTo({ url: '/pages/compare/compare?' + api.query(Object.assign({ id: this.id }, this.context())) });
  },
  async save() {
    if (!api.session().loggedIn) {
      if (!(await view.confirm('登录后收藏', '登录后可以同步收藏。'))) return;
      try { await api.login(); } catch (error) { return view.toast(error.message); }
    }
    try {
      await api.privateRequest('/favorites/' + encodeURIComponent(this.id), { method: 'PUT' });
      api.track('favorite_changed', { offerId: this.id });
      view.toast('已收藏');
    } catch (error) { view.toast(error.message); }
  },
  toggleFeedback() { this.setData({ showFeedback: !this.data.showFeedback }); },
  feedback(e) { this.setData({ feedback: e.detail.value }); },
  async sendFeedback() {
    if (!this.data.feedback.trim()) return view.toast('请说明需要核对的问题');
    try {
      await api.privateRequest('/feedback', { method: 'POST', data: { objectId: this.id, reason: '报价问题', description: this.data.feedback } });
      api.track('price_feedback_created', { offerId: this.id, planId: this.data.offer.planId });
      this.setData({ feedback: '', showFeedback: false });
      view.toast('已提交反馈');
    } catch (error) { view.toast(error.message); }
  },
  async outbound() {
    if (this.data.outboundBusy || this.data.loading || this.data.dirty || !this.data.offer.outboundAvailable) return;
    const id = this.id, generation = this.generation, planId = this.data.offer.planId;
    this.setData({ outboundBusy: true });
    try {
      const target = await api.request('/outbound-targets', {
        method: 'POST', data: { offerId: this.id, planVersion: this.data.offer.planVersion, context: this.context() }
      });
      if (id !== this.id || generation !== this.generation || this.data.dirty) return;
      api.track('outbound_started', { offerId: id, planId });
      wx.navigateToMiniProgram({ appId: target.appId, path: target.path, fail: () => {
        api.track('outbound_failed', { offerId: id, planId });
        view.toast('跳转失败，请稍后重试或在原平台搜索商品');
      } });
    } catch (error) {
      if (id === this.id && generation === this.generation) {
        if (error.code === 'PRICE_CHANGED' && error.data) this.show(error.data);
        this.setData({ error: error.message });
      }
    } finally { this.setData({ outboundBusy: false }); }
  }
});
