
const api = require('../../lib/api');
const view = require('../../lib/view');
const auditLabels = { SOURCE_CHANGED: '调整渠道', OFFER_CHANGED: '调整报价', RULE_PUBLISHED: '发布规则', RULE_ROLLED_BACK: '回滚规则', FEEDBACK_RESOLVED: '处理反馈', RECOGNITION_DELETED: '用户删除识别记录', RECOGNITION_EXPIRED: '清理过期识别记录' };
Page({
  data: { dashboard: null, error: '', busy: false, reason: '', rollback: '' },
  field(e) {
    const key = e.currentTarget.dataset.key;
    if (key === 'key') this.key = e.detail.value;
    else this.setData({ [key]: e.detail.value });
  },
  async load() {
    this.setData({ busy: true, error: '' });
    try {
      const dashboard = await api.request('/admin/dashboard', { headers: { 'X-Admin-Key': this.key || '' } });
      dashboard.audit = dashboard.audit.map(row => Object.assign({}, row, { label: auditLabels[row.action], date: view.time(row.createdAt) }));
      this.setData({ dashboard });
    } catch (error) { this.setData({ error: error.message, dashboard: null }); }
    finally { this.setData({ busy: false }); }
  },
  async mutate(e) {
    const { kind, id, version, enabled } = e.currentTarget.dataset;
    const reason = this.data.reason.trim();
    if (!reason) return view.toast('请填写操作理由');
    const data = { version, reason };
    let path = '/admin/' + kind;
    if (kind !== 'rules') path += '/' + id;
    if (kind === 'rules') {
      if (id === 'rollback') {
        const rollbackTo = Number(this.data.rollback);
        if (!Number.isInteger(rollbackTo) || rollbackTo < 1) return view.toast('请输入有效的历史版本号');
        data.rollbackTo = rollbackTo;
      } else data.couponsEnabled = !enabled;
    } else if (kind !== 'feedback') data.enabled = !enabled;
    if (!(await view.confirm('确认运营操作', '操作会即时生效，并写入审计记录。'))) return;
    this.setData({ busy: true, error: '' });
    try {
      await api.request(path, { method: 'PATCH', data, headers: { 'X-Admin-Key': this.key } });
      this.setData({ reason: '', rollback: '' });
      await this.load();
      view.toast('操作完成');
    } catch (error) { this.setData({ error: error.message }); }
    finally { this.setData({ busy: false }); }
  }
});
