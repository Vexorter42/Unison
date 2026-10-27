package com.musichub.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Собственные иконки Unison: мягкие скруглённые формы, контурный вариант для неактивного
 * состояния и залитый — для активного. Цвет задаёт tint у Icon().
 */
object UnisonIcons {

    private class Spec(val d: String, val fill: Boolean = false, val stroke: Float = 0f, val evenOdd: Boolean = false)

    private fun icon(name: String, vararg paths: Spec): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            paths.forEach { p ->
                addPath(
                    pathData = addPathNodes(p.d),
                    pathFillType = if (p.evenOdd) PathFillType.EvenOdd else PathFillType.NonZero,
                    fill = if (p.fill) SolidColor(Color.Black) else null,
                    stroke = if (p.stroke > 0f) SolidColor(Color.Black) else null,
                    strokeLineWidth = p.stroke,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
        }.build()

    private const val HOUSE = "M4 10.2L12 3.8L20 10.2V18.6C20 19.6 19.2 20.4 18.2 20.4H5.8C4.8 20.4 4 19.6 4 18.6Z"
    private const val DOOR = "M9.6 20.4V15.6C9.6 14.3 10.7 13.2 12 13.2C13.3 13.2 14.4 14.3 14.4 15.6V20.4"

    val Home = icon("home", Spec(HOUSE, stroke = 1.8f), Spec(DOOR, stroke = 1.8f))
    val HomeActive = icon("home_active", Spec("$HOUSE M9.6 20.4V15.6C9.6 14.3 10.7 13.2 12 13.2C13.3 13.2 14.4 14.3 14.4 15.6V20.4Z", fill = true, evenOdd = true))

    private const val LENS = "M16.6 10.4A6.2 6.2 0 1 1 4.2 10.4A6.2 6.2 0 1 1 16.6 10.4Z"
    val Search = icon("search", Spec(LENS, stroke = 1.8f), Spec("M15.1 15.1L20 20", stroke = 2.2f), Spec("M7.6 9.4A3 3 0 0 1 9.6 7.4", stroke = 1.5f))
    val SearchActive = icon("search_active", Spec(LENS, fill = true), Spec("M15.1 15.1L20 20", stroke = 2.6f))

    // Конверт пластинки + выглядывающий диск
    private const val SLEEVE = "M5 5H12A2 2 0 0 1 14 7V17A2 2 0 0 1 12 19H5A2 2 0 0 1 3 17V7A2 2 0 0 1 5 5Z"
    private const val LABEL = "M10.5 12A2 2 0 1 1 6.5 12A2 2 0 1 1 10.5 12Z"
    private const val DISC = "M14 6.2A5.8 5.8 0 0 1 14 17.8"
    val Library = icon("library", Spec(SLEEVE, stroke = 1.8f), Spec(LABEL, stroke = 1.6f), Spec(DISC, stroke = 1.8f), Spec("M17.2 9.6A2.8 2.8 0 0 1 17.2 14.4", stroke = 1.4f))
    val LibraryActive = icon("library_active", Spec("$SLEEVE $LABEL", fill = true, evenOdd = true), Spec(DISC, stroke = 2.4f))

    // Три столбика-эквалайзера
    val Stats = icon("stats", Spec("M6 19V13", stroke = 2.2f), Spec("M12 19V8", stroke = 2.2f), Spec("M18 19V4.6", stroke = 2.2f))
    val StatsActive = icon("stats_active", Spec("M6 19V12.5", stroke = 3.4f), Spec("M12 19V7.5", stroke = 3.4f), Spec("M18 19V4.2", stroke = 3.4f))

    // Два ползунка вместо привычной шестерёнки
    private const val KNOB_A = "M11.4 8A2.4 2.4 0 1 1 6.6 8A2.4 2.4 0 1 1 11.4 8Z"
    private const val KNOB_B = "M17.4 16A2.4 2.4 0 1 1 12.6 16A2.4 2.4 0 1 1 17.4 16Z"
    val Settings = icon(
        "settings",
        Spec("M4 8H6.6M11.4 8H20", stroke = 1.8f), Spec(KNOB_A, stroke = 1.8f),
        Spec("M4 16H12.6M17.4 16H20", stroke = 1.8f), Spec(KNOB_B, stroke = 1.8f),
    )
    val SettingsActive = icon(
        "settings_active",
        Spec("M4 8H20", stroke = 2.2f), Spec(KNOB_A, fill = true, stroke = 1.2f),
        Spec("M4 16H20", stroke = 2.2f), Spec(KNOB_B, fill = true, stroke = 1.2f),
    )

    // Капля — значок темы Liquid Glass
    val Drop = icon("drop", Spec("M12 3.5C12 3.5 5.5 10.4 5.5 14.6A6.5 6.5 0 0 0 18.5 14.6C18.5 10.4 12 3.5 12 3.5Z", stroke = 1.8f), Spec("M9.2 15A2.8 2.8 0 0 0 11.6 17.6", stroke = 1.5f))
    val DropActive = icon("drop_active", Spec("M12 3.5C12 3.5 5.5 10.4 5.5 14.6A6.5 6.5 0 0 0 18.5 14.6C18.5 10.4 12 3.5 12 3.5Z", fill = true))
}
