
const api = require('../../lib/api');
const view = require('../../lib/view');
Page({
  data: {
    q: '', platform: '', category: '', platforms: ['全部平台', '淘宝', '抖音'], categories: ['全部品类', '数码', '日用', '家居'],
    platformIndex: 0, categoryIndex: 0, min: '', max: '', items: [], sources: [], cursor: '', loading: false, error: '', filtersOpen: false
  },
  onShow() {
    const app = getApp();
    if (typeof app.radarKeyword === 'string') {
      this.setData({ q: app.radarKeyword });
      delete app.radarKeyword;
    }
    this.load();
  },
  onPullDownRefresh() { this.load().finally(() => wx.stopPullDownRefresh()); },
  onReachBottom() { if (this.data.cursor && !this.data.loading) this.load(true); },
  field(e) { this.setData({ [e.currentTarget.dataset.key]: e.detail.value }); },
  filter(e) {
    const key = e.currentTarget.dataset.key;
    const values = key === 'platform' ? this.data.platforms : this.data.categories;
    const index = Number(e.detail.value);
    this.setData({ [key]: index ? values[index] : '', [key + 'Index']: index });
    this.load();
  },
  filters() { this.setData({ filtersOpen: !this.data.filtersOpen }); },
  search() { return this.load(); },
  more() { if (this.data.cursor && !this.data.loading) return this.load(true); },
  async load(append = false) {
    const generation = this.generation = (this.generation || 0) + 1;
    const { q, platform, category, min, max, cursor } = this.data;
    this.setData({ loading: true, error: '', ...(append ? {} : { items: [], cursor: '' }) });
    try {
      for (const price of [min, max]) {
        if (price && !/^\d{1,7}(\.\d{1,2})?$/.test(price)) throw new Error('价格请填写最多两位小数的有效金额');
      }
      const query = api.query({
        q: q.trim(), platform, category, minFen: min ? Math.round(Number(min) * 100) : '',
        maxFen: max ? Math.round(Number(max) * 100) : '', cursor: append ? cursor : '', limit: 12
      });
      const result = await api.request('/products?' + query);
      if (generation !== this.generation) return;
      const items = result.items.map(view.card);
      const demo = result.sourceStates.some(source => source.state === 'DEMO');
      const active = result.sourceStates.some(source => ['DEMO', 'AVAILABLE'].includes(source.state));
      this.setData({ items: append ? this.data.items.concat(items) : items, cursor: result.nextCursor || '', demo, active });
    } catch (error) { if (generation === this.generation) this.setData({ error: error.message }); }
    finally { if (generation === this.generation) this.setData({ loading: false }); }
  },
  open(e) { view.detail(e.detail); },
  recognize() { view.recognition(); }
});
