# weibo-dynamic
由微博链接解析 [mirai](https://github.com/mamoe/mirai) 插件二次开发而来。

## 功能
接入DS APIKEY后，可在群聊中@bot后可进行对话，文本超出上限后会绘制图片发送。

## 使用

- **前置插件**: [mirai-skia-plugin](https://github.com/cssxsh/mirai-skia-plugin)

- 自定义字体请把 ttf 字体文件放到 `data/top.colter.ds-chat/font` 目录下，插件会自动解析

- 你需要在`config/top.colter.ds-chat/`的配置文件中自行添加APIKEY。

## 附录
DS接口文档：https://api-docs.deepseek.com/zh-cn/