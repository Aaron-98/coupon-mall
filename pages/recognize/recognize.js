
const api = require('../../lib/api');
const view = require('../../lib/view');
const pending = ['CREATED', 'PROCESSING'];
const labels = { CREATED: '等待处理', PROCESSING: '正在识别', NEED_CONFIRMATION: '请核对识别字段', COMPLETED: '已保存私有记录', FAILED: '识别失败', TIMEOUT: '识别超时' };

Page({
  data: { mode: 'image', form: {}, text: '', path: '', error: '', fields: [], busy: false },
  onLoad(options) {
    this.id = options.id || '';
  },
  onShow() { this.active = true; if (this.id) this.refresh(); },
  onHide() { this.active = false; clearTimeout(this.timer); },
  onUnload() { this.onHide(); },
  mode(e) { this.setData({ mode: e.currentTarget.dataset.mode, error: '' }); },
  input(e) { this.setData({ text: e.detail.value }); },
  choose() {
    wx.chooseMedia({
      count: 1, mediaType: ['image'], sourceType: ['album', 'camera'], sizeType: ['original'],
      success: res => {
        const file = res.tempFiles[0];
        if (file.size > 10 * 1024 * 1024) return this.setData({ error: '请选择不超过 10 MB 的图片' });
        this.setData({ path: file.tempFilePath, error: '' });
      }
    });
  },
  async submit() {
    if (this.data.busy) return;
    const image = this.data.mode === 'image';
    const input = image ? this.data.path : this.data.text.trim();
    if (!input) return this.setData({ error: image ? '请先选择图片' : '请先填写优惠文字' });
    if (image && this.allowedImage !== input) {
      const agreed = await view.confirm('上传前确认', '图片会发送至服务端用于提取优惠字段。请先遮盖个人信息。原图在任务结束后 24 小时内清理。');
      if (!agreed) return;
      this.allowedImage = input;
    }
    this.setData({ busy: true, error: '' });
    try {
      if (!this.submission || this.submission.input !== input || this.submission.image !== image)
        this.submission = { input, image, key: Date.now() + '_' + Math.random().toString(36).slice(2) };
      const submission = this.submission;
      if (image && !submission.uploadId) {
        const imageBase64 = await new Promise((resolve, reject) => {
          wx.getFileSystemManager().readFile({ filePath: input, encoding: 'base64', success: res => resolve(res.data), fail: () => reject(new Error('无法读取这张图片')) });
        });
        const uploaded = await api.privateRequest('/uploads', { method: 'POST', data: { imageBase64 } });
        submission.uploadId = uploaded.uploadId;
      }
      const task = await api.privateRequest('/recognitions', {
        method: 'POST', headers: { 'Idempotency-Key': submission.key },
        data: image ? { inputType: 'image', uploadId: submission.uploadId } : { inputType: 'text', text: input }
      });
      this.id = task.id;
      api.track('input_submitted', { taskId: task.id, eventId: 'submitted_' + task.id });
      if (image) this.setData({ path: '' });
      await this.refresh();
    } catch (error) { this.setData({ error: error.message }); }
    finally { this.setData({ busy: false }); }
  },
  async refresh() {
    clearTimeout(this.timer);
    try {
      const task = await api.privateRequest('/recognitions/' + this.id);
      if (!this.active) return;
      const patch = { task, statusLabel: labels[task.status], missingLabel: (task.missingConditions || []).join('、'), error: '' };
      if (task.fields && this.formTask !== task.id) {
        this.formTask = task.id;
        const form = Object.assign({}, task.confirmedFields);
        patch.fields = task.fields.map(field => {
          if (!(field.name in form)) form[field.name] = field.value || '';
          return Object.assign({}, field, { stateLabel: { UNKNOWN: '未知', CONFLICT: '存在冲突', REVIEW: '请核对' }[field.state] });
        });
        patch.form = form;
        patch.relative = /今天|今晚|今日|明天|明日|后天|当日|^[今明]$/.test(form.period || '');
      }
      this.setData(patch);
      if (!pending.includes(task.status)) api.track('recognition_finished', {
        taskId: task.id, eventId: 'finished_' + task.id + '_' + task.attempt
      });
      if (pending.includes(task.status)) this.timer = setTimeout(() => this.refresh(), 750);
      else if (task.hasImage && !this.data.evidence && !this.loadingPicture) {
        this.loadingPicture = true;
        try {
          const path = await api.picture('/recognitions/' + task.id + '/image');
          const info = await new Promise((resolve, reject) => wx.getImageInfo({ src: path, success: resolve, fail: reject }));
          if (this.active && this.id === task.id) this.setData({ evidence: path, ratio: info.height / info.width });
        } catch (error) { if (this.active) this.setData({ imageError: '原图暂时无法加载' }); }
        finally { this.loadingPicture = false; }
      }
    } catch (error) { if (this.active) this.setData({ error: error.message }); }
  },
  field(e) {
    const name = e.currentTarget.dataset.name;
    const value = e.detail.value;
    const patch = { ['form.' + name]: value };
    if (name === 'period') patch.relative = /今天|今晚|今日|明天|明日|后天|当日|^[今明]$/.test(value);
    this.setData(patch);
  },
  date(e) { this.setData({ 'form.captureDate': e.detail.value }); },
  highlight(e) {
    const field = this.data.fields.find(field => field.name === e.currentTarget.dataset.name);
    const box = field && field.bbox;
    if (box) this.setData({ boxStyle: 'left:' + box.x * 100 + '%;top:' + box.y * 100 + '%;width:' + box.width * 100 + '%;height:' + box.height * 100 + '%' });
  },
  async update(path, method, data) {
    if (this.data.busy) return;
    this.setData({ busy: true, error: '' });
    try {
      const task = await api.privateRequest('/recognitions/' + this.id + path, { method, data });
      this.setData({ task, statusLabel: labels[task.status], missingLabel: (task.missingConditions || []).join('、') });
      if (path === '/retry') await this.refresh();
      else {
        api.track('recognition_confirmed', { taskId: task.id, eventId: 'confirmed_' + task.id + '_' + task.version });
        view.toast('已保存');
      }
    } catch (error) {
      this.setData({ error: error.message });
      if (error.code === 'VERSION_CONFLICT') {
        this.formTask = '';
        await this.refresh();
        this.setData({ error: error.message });
      }
    } finally { this.setData({ busy: false }); }
  },
  save() { return this.update('/confirmation', 'PATCH', { version: this.data.task.version, confirmedFields: this.data.form }); },
  retry() { return this.update('/retry', 'POST'); },
  restart() {
    this.id = '';
    this.submission = null;
    this.formTask = '';
    clearTimeout(this.timer);
    this.setData({ task: null, path: '', text: '', error: '', evidence: '', boxStyle: '', imageError: '' });
  },
  search() {
    getApp().radarKeyword = (this.data.form.product || '').slice(0, 100);
    wx.switchTab({ url: '/pages/radar/radar' });
  }
});
