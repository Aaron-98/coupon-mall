
const api = require('../../lib/api');
const view = require('../../lib/view');
const stateLabels = { CREATED: '等待处理', PROCESSING: '正在识别', NEED_CONFIRMATION: '待核对', COMPLETED: '已保存', FAILED: '失败', TIMEOUT: '超时' };
Page({
  data: { history: [], loading: true, feedback: '', error: '' },
  onShow() { this.load(); },
  onPullDownRefresh() { this.load().finally(() => wx.stopPullDownRefresh()); },
  async load() {
    this.setData({ loading: true, error: '' });
    try {
      const history = await api.privateRequest('/recognitions');
      const session = api.session();
      this.setData({ history: history.map(task => Object.assign({}, task, {
        statusLabel: stateLabels[task.status], date: view.time(task.createdAt)
      })), loggedIn: session.loggedIn, demo: session.demo });
      if (session.loggedIn) {
        const [notifications, subscriptions] = await Promise.all([
          api.privateRequest('/me/notifications'), api.privateRequest('/subscriptions')
        ]);
        this.setData({ notifications: notifications.map(row => Object.assign({}, row, {
          amount: view.money(row.priceFen), date: view.time(row.observedAt)
        })), subscriptions: subscriptions.map(row => Object.assign({}, row, { amount: view.money(row.thresholdFen) })) });
      } else this.setData({ notifications: [], subscriptions: [] });
    } catch (error) { this.setData({ error: error.message }); }
    finally { this.setData({ loading: false }); }
  },
  async login() {
    this.setData({ signingIn: true });
    try { await api.login(); await this.load(); }
    catch (error) { view.toast(error.message); }
    finally { this.setData({ signingIn: false }); }
  },
  async logout() {
    if (!(await view.confirm('退出当前会话', '确认退出登录？下次可用微信重新登录。'))) return;
    api.clear();
    await this.load();
  },
  open(e) { view.recognition(e.currentTarget.dataset.id); },
  newTask() { view.recognition(); },
  async remove(e) {
    const id = e.currentTarget.dataset.id;
    if (!(await view.confirm('删除识别记录', '将同时删除这条记录及其图片。'))) return;
    try { await api.privateRequest('/me/recognitions/' + id, { method: 'DELETE' }); await this.load(); }
    catch (error) { view.toast(error.message); }
  },
  async deleteData() {
    if (!(await view.confirm('删除我的数据', '确认删除当前会话或账号的收藏、识别记录及图片，并退出会话？'))) return;
    try {
      await api.privateRequest('/me', { method: 'DELETE' });
      api.clear();
      this.setData({ history: [], loggedIn: false });
      await this.load();
      view.toast('已删除');
    } catch (error) { view.toast(error.message); }
  },
  privacy() {
    wx.showModal({ title: '识别与隐私', content: '截图用于提取优惠字段，图片在任务结束后 24 小时内清理。结构化历史保留 30 天。可随时在此删除。请勿上传含身份、地址或付款信息的图片。', showCancel: false, confirmText: '知道了' });
  },
  feedback(e) { this.setData({ feedback: e.detail.value }); },
  async send() {
    if (!this.data.feedback.trim()) return view.toast('请填写反馈内容');
    try {
      await api.privateRequest('/feedback', { method: 'POST', data: { reason: '使用反馈', description: this.data.feedback } });
      this.setData({ feedback: '' });
      view.toast('已提交反馈');
    } catch (error) { view.toast(error.message); }
  },
  admin() { wx.navigateTo({ url: '/pages/admin/admin' }); },
  offer(e) { view.detail(e.currentTarget.dataset.id); },
  async unwatch(e) {
    if (!(await view.confirm('退订站内提醒', '确认停止关注该商品的目标价格？'))) return;
    try {
      await api.privateRequest('/subscriptions/' + encodeURIComponent(e.currentTarget.dataset.id), { method: 'DELETE' });
      await this.load();
    } catch (error) { view.toast(error.message); }
  }
});
