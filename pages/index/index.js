
const api = require('../../lib/api');
const view = require('../../lib/view');

Page({
  data: { text: '', offers: [], sources: [], loading: true, error: '' },
  async onShow() { await this.load(); },
  async load() {
    this.setData({ loading: true, error: '' });
    try {
      const data = await api.request('/products?limit=3');
      this.setData({ offers: data.items.map(view.card), sources: data.sourceStates });
    } catch (error) { this.setData({ error: error.message, offers: [] }); }
    finally { this.setData({ loading: false }); }
  },
  input(e) { this.setData({ text: e.detail.value }); },
  paste() {
    wx.getClipboardData({ success: res => this.setData({ text: (res.data || '').slice(0, 3000) }), fail: () => view.toast('剪贴板读取失败') });
  },
  recognize() { view.recognition(); },
  search() {
    getApp().radarKeyword = this.data.text.trim().slice(0, 100);
    wx.switchTab({ url: '/pages/radar/radar' });
  },
  async resolve() {
    const text = this.data.text.trim();
    if (!text) return view.toast('请先填写商品链接或关键词');
    if (!/https?:\/\//i.test(text)) return this.search();
    this.setData({ resolving: true, linkError: '' });
    try {
      const result = await api.request('/resolve', { method: 'POST', data: { text } });
      view.detail(result.offerId);
    } catch (error) { this.setData({ linkError: error.message }); }
    finally { this.setData({ resolving: false }); }
  },
  radar() { wx.switchTab({ url: '/pages/radar/radar' }); },
  open(e) { view.detail(e.detail); }
});
