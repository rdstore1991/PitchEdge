package com.example.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.MarketEdgeItem
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.LocalEdgeColors
import java.util.Locale
import kotlin.math.max

@Composable
fun ProbabilityEdgeComparisonChart(
    markets: List<MarketEdgeItem>,
    selectedMarketId: String? = null,
    onSelectMarket: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val edgeColors = LocalEdgeColors.current
    val textMeasurer = rememberTextMeasurer()

    var internalSelectedId by remember(markets) {
        mutableStateOf(selectedMarketId ?: markets.firstOrNull()?.id)
    }
    val activeMarketId = selectedMarketId ?: internalSelectedId
    val activeMarket = markets.find { it.id == activeMarketId } ?: markets.firstOrNull()

    val marketBarColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.42f)
    val gridColor = edgeColors.cardBorder
    val axisTextColor = MaterialTheme.colorScheme.onSurfaceVariant
    val highlightSlotColor = edgeColors.elevatedSurface

    val axisStyle = remember(axisTextColor) {
        TextStyle(
            fontFamily = JetBrainsMonoFontFamily,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            color = axisTextColor
        )
    }

    val animatedProgress by animateFloatAsState(
        targetValue = if (markets.isNotEmpty()) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = 280f),
        label = "probChartReveal"
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("probability_edge_canvas_chart")
    ) {
        // Legend Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ChartLegendDot(color = marketBarColor, label = "Market %")
            ChartLegendDot(color = edgeColors.positiveEdge, label = "Model +EV")
            ChartLegendDot(color = edgeColors.warningAmber, label = "Pre-XI Risk")
            ChartLegendDot(color = edgeColors.negativeEdge, label = "Model -EV")
        }

        Spacer(modifier = Modifier.height(10.dp))

        if (markets.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No markets match the selected filter",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            return
        }

        val maxPct = 66f
        val gridTicks = listOf(0, 20, 40, 60)

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(172.dp)
                .pointerInput(markets) {
                    detectTapGestures { offset ->
                        val leftPad = 34.dp.toPx()
                        val chartWidth = (size.width - leftPad).coerceAtLeast(1f)
                        if (offset.x >= leftPad && markets.isNotEmpty()) {
                            val slotWidth = chartWidth / markets.size
                            val index = ((offset.x - leftPad) / slotWidth)
                                .toInt()
                                .coerceIn(0, markets.lastIndex)
                            val tappedId = markets[index].id
                            internalSelectedId = tappedId
                            onSelectMarket?.invoke(tappedId)
                        }
                    }
                }
        ) {
            val leftPad = 34.dp.toPx()
            val topPad = 24.dp.toPx()
            val bottomPad = 24.dp.toPx()
            val chartWidth = (size.width - leftPad).coerceAtLeast(1f)
            val chartHeight = (size.height - topPad - bottomPad).coerceAtLeast(1f)

            val dashEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()), 0f)

            // Draw Y-axis gridlines and ticks
            gridTicks.forEach { tick ->
                val y = topPad + chartHeight * (1f - (tick / maxPct))
                drawLine(
                    color = gridColor.copy(alpha = if (tick == 0) 0.9f else 0.55f),
                    start = Offset(leftPad, y),
                    end = Offset(size.width, y),
                    strokeWidth = if (tick == 0) 1.2.dp.toPx() else 0.8.dp.toPx(),
                    pathEffect = if (tick == 0) null else dashEffect
                )
                val labelLayout = textMeasurer.measure("$tick%", axisStyle)
                drawText(
                    textLayoutResult = labelLayout,
                    topLeft = Offset(
                        x = (leftPad - labelLayout.size.width - 6.dp.toPx()).coerceAtLeast(0f),
                        y = y - labelLayout.size.height / 2f
                    )
                )
            }

            // Draw paired bars for each market
            val slotWidth = chartWidth / markets.size
            val barWidth = (slotWidth * 0.24f).coerceIn(12.dp.toPx(), 24.dp.toPx())
            val barGap = 4.dp.toPx()
            val corner = CornerRadius(4.dp.toPx(), 4.dp.toPx())

            markets.forEachIndexed { index, item ->
                val slotLeft = leftPad + index * slotWidth
                val slotCenterX = slotLeft + slotWidth / 2f
                val isActive = item.id == activeMarket?.id

                if (isActive) {
                    drawRoundRect(
                        color = highlightSlotColor.copy(alpha = 0.7f),
                        topLeft = Offset(slotLeft + 4.dp.toPx(), topPad - 4.dp.toPx()),
                        size = Size(slotWidth - 8.dp.toPx(), chartHeight + 4.dp.toPx()),
                        cornerRadius = CornerRadius(8.dp.toPx(), 8.dp.toPx())
                    )
                }

                val marketHeight = ((item.marketImpliedPct.toFloat() / maxPct) * chartHeight * animatedProgress)
                    .coerceAtLeast(4.dp.toPx())
                val modelHeight = ((item.modelPct.toFloat() / maxPct) * chartHeight * animatedProgress)
                    .coerceAtLeast(4.dp.toPx())

                val mktBarLeft = slotCenterX - barGap / 2f - barWidth
                val modelBarLeft = slotCenterX + barGap / 2f
                val baselineY = topPad + chartHeight

                val modelColor = when {
                    item.hasRiskFlag -> edgeColors.warningAmber
                    item.edgePct > 0.0 -> edgeColors.positiveEdge
                    else -> edgeColors.negativeEdge
                }

                // Market implied % bar
                drawRoundRect(
                    color = marketBarColor,
                    topLeft = Offset(mktBarLeft, baselineY - marketHeight),
                    size = Size(barWidth, marketHeight),
                    cornerRadius = corner
                )

                // Model % bar
                drawRoundRect(
                    color = modelColor,
                    topLeft = Offset(modelBarLeft, baselineY - modelHeight),
                    size = Size(barWidth, modelHeight),
                    cornerRadius = corner
                )

                // Edge delta label above the taller bar
                val edgeSign = if (item.edgePct > 0.0) "+" else ""
                val deltaLabel = "$edgeSign${String.format(Locale.US, "%.1f", item.edgePct)}%"
                val deltaStyle = TextStyle(
                    fontFamily = JetBrainsMonoFontFamily,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = modelColor
                )
                val deltaLayout = textMeasurer.measure(deltaLabel, deltaStyle)
                val tallerTopY = baselineY - max(marketHeight, modelHeight)
                val pillWidth = deltaLayout.size.width + 8.dp.toPx()
                val pillHeight = deltaLayout.size.height + 4.dp.toPx()
                val pillLeft = (slotCenterX - pillWidth / 2f)
                    .coerceIn(leftPad, size.width - pillWidth)
                val pillTop = (tallerTopY - pillHeight - 4.dp.toPx()).coerceAtLeast(0f)

                drawRoundRect(
                    color = modelColor.copy(alpha = 0.16f),
                    topLeft = Offset(pillLeft, pillTop),
                    size = Size(pillWidth, pillHeight),
                    cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx())
                )
                drawText(
                    textLayoutResult = deltaLayout,
                    topLeft = Offset(
                        pillLeft + 4.dp.toPx(),
                        pillTop + 2.dp.toPx()
                    )
                )

                // Short X-axis label
                val shortName = shortMarketLabel(item.id)
                val xStyle = axisStyle.copy(
                    color = if (isActive) modelColor else axisTextColor,
                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium
                )
                val xLayout = textMeasurer.measure(shortName, xStyle)
                drawText(
                    textLayoutResult = xLayout,
                    topLeft = Offset(
                        x = (slotCenterX - xLayout.size.width / 2f)
                            .coerceIn(leftPad, size.width - xLayout.size.width),
                        y = baselineY + 6.dp.toPx()
                    )
                )
            }
        }

        // Recharts-style interactive inspector tooltip strip
        if (activeMarket != null) {
            Spacer(modifier = Modifier.height(6.dp))
            val isPos = activeMarket.edgePct > 0.0
            val accent = when {
                activeMarket.hasRiskFlag -> edgeColors.warningAmber
                isPos -> edgeColors.positiveEdge
                else -> edgeColors.negativeEdge
            }
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = edgeColors.elevatedSurface,
                border = BorderStroke(1.dp, accent.copy(alpha = 0.45f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = activeMarket.marketName,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "Mkt ${String.format(Locale.US, "%.1f", activeMarket.marketImpliedPct)}% vs Model ${String.format(Locale.US, "%.1f", activeMarket.modelPct)}% • Odds ${String.format(Locale.US, "%.2f", activeMarket.bestOdds)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    val sign = if (isPos) "+" else ""
                    Text(
                        text = "$sign${String.format(Locale.US, "%.1f", activeMarket.edgePct)}%",
                        style = MaterialTheme.typography.labelLarge,
                        color = accent,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun ChartLegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(modifier = Modifier.width(5.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun shortMarketLabel(id: String): String = when (id) {
    "fakhar_10_quicker" -> "Fakhar 10R"
    "holden_top_dc" -> "Holden Bat"
    "dc_match_winner" -> "DC Win"
    "dv_match_winner" -> "DV Win"
    else -> id.take(8)
}
