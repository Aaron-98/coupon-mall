
const api = require('../../lib/api');
const view = require('../../lib/view');
Page({
  data: { items: [], loading: false, loggedIn: false, error: '' },
  onShow() { this.load(); },
  onPullDownRefresh() { this.load().finally(() => wx.stopPullDownRefresh()); },
  async load() {
    const loggedIn = Boolean(api.session().loggedIn);
    this.setData({ loggedIn, items: [], error: '', loading: loggedIn });
    if (!loggedIn) return;
    try {
      const items = await api.privateRequest('/favorites');
      this.setData({ items: items.map(view.card) });
    } catch (error) { this.setData({ error: error.message, loggedIn: Boolean(api.session().loggedIn) }); }
    finally { this.setData({ loading: false }); }
  },
  async login() {
    this.setData({ signingIn: true });
    try { await api.login(); await this.load(); }
    catch (error) { this.setData({ error: error.message }); }
    finally { this.setData({ signingIn: false }); }
  },
  open(e) {
    const item = this.data.items.find(row => row.id === e.detail);
    if (item && item.available) view.detail(item.id);
    else view.toast('这条报价已失效');
  },
  async remove(e) {
    const id = e.currentTarget.dataset.id;
    if (!(await view.confirm('取消收藏', '从收藏中移除这项商品？'))) return;
    try {
      await api.privateRequest('/favorites/' + encodeURIComponent(id), { method: 'DELETE' });
      api.track('favorite_changed', { offerId: id });
      await this.load();
    }
    catch (error) { view.toast(error.message); }
  },
  radar() { wx.switchTab({ url: '/pages/radar/radar' }); }
});
