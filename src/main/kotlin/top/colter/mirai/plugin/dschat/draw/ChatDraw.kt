package top.colter.mirai.plugin.dschat.draw

import org.jetbrains.skia.Color
import org.jetbrains.skia.Image
import org.jetbrains.skia.paragraph.TextStyle
import top.colter.mirai.plugin.dschat.deepseek.DeepSeekConfig
import top.colter.skiko.*
import top.colter.skiko.data.Gradient
import top.colter.skiko.data.RichParagraphBuilder
import top.colter.skiko.data.Shadow
import top.colter.skiko.layout.*

suspend fun chatDraw(content: String): Image? {
    val colorConfig = DeepSeekConfig.image.defaultColor
    val colors = colorConfig.split(";", "；").filter { it.isNotBlank() }
    Dp.factor = DeepSeekConfig.image.factor

    val gradient = if (colors.size >= 2) {
        Gradient(10, listOf(Color.makeRGB(colors[0].trim()), Color.makeRGB(colors[1].trim())))
    } else null

    val displayText = if (content.length > 8000) {
        content.take(8000) + "\n\n---\n(内容过长，已截断前8000字)"
    } else {
        content
    }

    val style = TextStyle()
        .setColor(Color.BLACK)
        .setFontSize(30.px)
        .setFontFamily(FontUtils.defaultFont!!.familyName)

    val paragraph = RichParagraphBuilder(style)
    paragraph.addText(displayText)

    return View(
        modifier = Modifier()
            .width(800.dp)
            .padding(30.dp)
            .background(
                color = Color.makeRGB(colors.first().trim()),
                gradient = gradient
            )
    ) {
        Column(
            modifier = Modifier()
                .fillMaxWidth()
                .padding(25.dp)
                .background(Color.WHITE.withAlpha(0.85f))
                .border(3.dp, 15.dp)
                .shadows(Shadow.ELEVATION_2)
        ) {
            RichText(
                paragraph = paragraph.build(),
                modifier = Modifier().margin(20.dp).fillMaxWidth()
            )
        }
    }
}
