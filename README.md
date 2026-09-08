# 好奇旅行 · HaoQiTravel

单机版 AI 旅行规划 App：填好时间与城市，AI 自动生成完整行程（景点 / 酒店 / 饭店 / 城际交通），一键导入后可在高德地图上查看与导航、按出发时间自动提醒。

## 技术栈

- Kotlin + Jetpack Compose (Material 3)
- 高德地图（3D 地图 + 地理编码）
- Room（本地数据库，纯离线，数据只存本机）
- DeepSeek API（AI 行程生成，可选联网搜索真实车次/航班）

## 功能

- **AI 规划**：填出发地 + 每天城市，AI 结合口味/预算/节奏偏好生成行程，可反复对话修改到满意；
- **联网搜索**：可选，让 AI 联网查真实高铁/航班班次（带车次号、发车/到达时间）；
- **行程页**：按天 + 时段（上午/午餐/下午/晚餐/晚上）排布，可拖动调整、手动加地点；
- **车票**：录入真实车票后按发车时间提前提醒（高铁/火车 45 分钟、航班 2 小时、大巴 30 分钟）；
- **地图**：地点落点、点标记导航；
- **预约提醒**：需预约/购票的景点自动标记，到预约日提醒。

## 安装

到 [Releases](../../releases) 下载最新 `app-release.apk`，直接安装（需允许"安装未知来源应用"）。

## 自己构建

1. 申请 [高德开放平台](https://lbs.amap.com/) Key（Android 平台，填应用包名 `com.haoqi.travel` 和你本机的调试 SHA1）；
2. 在项目根目录 `local.properties` 里加一行（该文件不进 git）：
   ```
   amap.key=你的高德Key
   ```
3. 申请 [DeepSeek](https://platform.deepseek.com) 的 API Key，装好后在 App「更多 → AI 生成设置」里填（Key 只存在本机）；
4. `./gradlew :app:assembleRelease` 构建。

## 隐私

- 所有行程数据**只存本机**（Room 数据库），不上传任何服务器；
- DeepSeek 只在你点「AI 规划」时，把行程需求发给官方 API 用于生成，不存你的旅行数据；
- API Key 只保存在本机 SharedPreferences。

## 目录

- `app/src/main/java/com/haoqi/travel/data/` — 数据层（Room、网络、AI 客户端、解析器）
- `app/src/main/java/com/haoqi/travel/ui/` — Compose 界面
- `app/src/test/` — 单元测试（解析器 / AI 清洗 / 提示词）
