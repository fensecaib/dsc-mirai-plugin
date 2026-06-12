# dsc-mirai-plugin
由Colter23大佬 [mirai](https://github.com/mamoe/mirai) 插件二次开发而来。

## 功能与声明
接入DS APIKEY后，可在群聊中@bot后可进行对话，文本超出上限后会绘制图片发送。

也可以让DS大老师为你分析dota战绩，找出你的局内战犯。**战报分析**功能由于没有局内录像数据作为支撑，仅通过数据得出战报分析结果。
所以内容仅供娱乐参考。

得分计算公式如下，你也可以自行修改MVP与战犯的判定权重：
```markdown
GPM(25%) + CS(20%) + Tower(20%) + Kills(15%) + Death(15%) + KDA(5%)
```

## 使用

- **前置插件**: [mirai-skia-plugin](https://github.com/cssxsh/mirai-skia-plugin)
- 自定义字体请把 ttf 字体文件放到 `data/top.colter.ds-chat/font` 目录下，插件会自动解析
- 你需要在`config/top.colter.ds-chat/`的配置文件中自行添加APIKEY。
- 包括deepseek的api-key和tavily的api-key（需要web-search增强可选）


## 指令

### 通用对话指令
- `/ds` 或 `@bot` 触发普通的对话请求，允许在config中自定义一段提示词来塑造人设。

### 绑定可用dota指令
- `/dota 绑定 {9位数字ID}` : 绑定玩家的dota-id，用于快速使用dota战报分析和历史查询功能。

- `/dota 历史`  : 查询绑定玩家的历史10场战绩概要，并给出最近10场比赛id。

- `/dota 战报`, `/dota 战报 {比赛id}` : 给出绑定玩家一边阵容的MVP和战犯分析。

### 不绑定可用dota指令
- `/dota 分析`, `/dota 分析 {比赛id}` : **不要求绑定玩家id**，可以直接查询某一场比赛，并给出双方的MVP和战犯分析。

## 附录
DS接口文档：https://api-docs.deepseek.com/zh-cn/