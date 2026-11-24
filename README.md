# Coupon Mall

Coupon Mall 是一个原生微信小程序。用户可以整理截图里的优惠条件，也可以查询已接入渠道的报价，按同一规格、数量、地区和购买资格比价。

## 本地运行

开发环境需要 Windows、Java 17、Python 3 和 Pillow。首次解析 Java 依赖需要 Maven。图片识别依赖 Windows 已安装的简体中文 OCR 语言包。回归测试另需 Node.js 22。

在仓库根目录启动 Java 接口。

```powershell
$env:COUPON_ADMIN_KEY = 'coupon-mall-local-test'
powershell.exe -NoProfile -ExecutionPolicy Bypass -File server/run.ps1 -Demo
```

如果依赖已经下载在另一处 Maven 缓存，可先将 `COUPON_MAVEN_REPO` 设为该缓存目录。默认从当前 Windows 用户的 `.m2/repository` 解析依赖。

用微信开发者工具导入仓库根目录并编译，保留 `project.config.json` 中的小程序 AppID。模拟器的默认接口地址为 `http://127.0.0.1:8787/api/v1`，配置文件为 `lib/config.js`。如果模拟器提示「接口域名未通过小程序校验」，可使用已备案的 HTTPS 接口，或仅在本地联调时通过开发者工具的项目本地设置关闭合法域名校验。真机需要配置可访问的 HTTPS 接口，以及微信后台的请求和下载合法域名。

### 联调操作

1. 首页打开「AI 找优惠」，上传截图或选择文字输入。可以用三行文字测试，依次填入「满100减20元」「有效期：今晚」「适用范围：指定店铺」。识别完成后核对金额和截图日期，保存至「我的」。
2. 雷达打开月岩白色的「声屿 Air 2 无线耳机」。青柠数码店样例按直降 10 元、券减 20 元、运费 5 元计算为 95 元。比价页可看到同规格样例报价 95 元和 99 元，黑色规格单列。
3. 通过联调登录试用收藏。「我的」底部可进联调运营后台，使用启动时的访问凭据。填写理由后可下架报价、停用渠道、处理反馈、发布计价规则和回滚。
4. 运行回归测试，覆盖计价边界、真实中文 OCR、API 和小程序主要页面逻辑。脚本会为测试启动独立监听端口和数据目录。

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File server/test.ps1
```

## 渠道与数据

- 启动时省去 `-Demo` 即进入截图整理模式。渠道默认关闭，`-Demo` 的报价、购买资格和商品均为合成数据。
- 微信登录需要在服务端环境中配置 `COUPON_WX_APP_ID` 和 `COUPON_WX_APP_SECRET`。生产后台 API 需要单独配置访问凭据和角色鉴权。
- 第一渠道提供规范化报价快照接口。可将 `COUPON_AUTHORIZED_FEED` 指向经授权的 JSON 文件，或配置 `COUPON_FEED_URL` 与服务端凭证。快照需要有效授权时间和已核验能力登记，再通过后台开启渠道。远程定时读取，购买前刷新；权限撤销时停止推荐。完整配置见[接入约定](docs/source-contract.md)。这不是已接通淘宝官方 API 的声明。
- 快照中的 `verifiedStacks` 是按抵扣顺序排列的券 ID 数组列表。每张券需要明确的叠加组，同组至多抵扣一张。百分比优惠使用 `discountBps`，目前支持有渠道依据的向下舍入规则。缺运费和必选费用的结果只提供商品价格参考。
- 外跳要求服务端配置已批准的 `COUPON_APPROVED_TARGET_APPID`，且快照报价有对应的 `targetAppId`、`targetPath` 和有效价格。演示报价没有购买目标。分享链接只与受控商品库匹配，无任意 URL 请求。
- 本地部署以文件持久化会话、任务、审计与业务记录，图片存于同一工作目录的私有子路径，API 按会话鉴权。未登录的历史随当前会话保存，微信登录后可同步。图片服务端保留至任务结束后 24 小时，识别历史最多 30 天，可主动删除。

## 生产验收

本地已经补充价格方案回放、后台三种角色、事件去重、任务预算、价格记录和站内到价提醒。站内提醒在打开「我的」时检查，有明确同意、每日频控与退订，不发送微信消息。

已执行 200 张合成标注截图验收和 50 用户、15 分钟本地负载验证。结果、流量模型、全部需求对照和未完成项见[实现与验收记录](docs/implementation-and-verification.md)，不能据此认定真实渠道或真机已通过。

```powershell
# 基础回归、生产模式合成契约和真实本地 HTTPS 故障测试
powershell.exe -NoProfile -ExecutionPolicy Bypass -File server/verify.ps1

# 再执行 200 张 OCR 与 50 用户、15 分钟图片混合负载
powershell.exe -NoProfile -ExecutionPolicy Bypass -File server/verify.ps1 -Extended
```

真实渠道权限、真实微信登录、核准目标的真机外跳、微信订阅消息、生产数据库和私有对象存储、备份与隐私审核仍需完成。目前仍是单实例本地工程，未部署，也未修改 aron.asia 的解析。
