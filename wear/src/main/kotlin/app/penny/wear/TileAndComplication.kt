package app.penny.wear

import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.material.Text
import androidx.wear.protolayout.material.Typography
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/** WG-02 tile: safe to spend from the last phone summary; tap opens the watch app. */
class SafeTileService : TileService() {
    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        val s = WearCache.load(this)
        val label = getString(R.string.safe_to_spend)
        val value = if (s == null) "—" else if (s.hidden) "••••" else s.safe
        val click = ModifiersBuilders.Clickable.Builder().setId("open")
            .setOnClick(ActionBuilders.LaunchAction.Builder().setAndroidActivity(
                ActionBuilders.AndroidActivity.Builder().setPackageName(packageName).setClassName(WearActivity::class.java.name).build(),
            ).build()).build()
        val column = LayoutElementBuilders.Column.Builder()
            .setWidth(expand()).setHeight(expand())
            .setModifiers(ModifiersBuilders.Modifiers.Builder().setClickable(click).build())
            .addContent(Text.Builder(this, label).setTypography(Typography.TYPOGRAPHY_CAPTION1).setColor(argb(0xFFB5F2D3.toInt())).build())
            .addContent(Text.Builder(this, value).setTypography(Typography.TYPOGRAPHY_DISPLAY3).setColor(argb(0xFFFFFFFF.toInt())).build())
            .build()
        val layout = LayoutElementBuilders.Layout.Builder().setRoot(
            LayoutElementBuilders.Box.Builder().setWidth(expand()).setHeight(expand()).addContent(column).build(),
        ).build()
        return Futures.immediateFuture(
            TileBuilders.Tile.Builder().setResourcesVersion("1").setFreshnessIntervalMillis(30 * 60_000L)
                .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(layout.root!!)).build(),
        )
    }

    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> =
        Futures.immediateFuture(ResourceBuilders.Resources.Builder().setVersion("1").build())
}

/** WG-02 complication: short text with safe to spend. */
class SafeComplicationService : SuspendingComplicationDataSourceService() {
    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        if (type == ComplicationType.SHORT_TEXT) text("1,234") else null

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        if (request.complicationType != ComplicationType.SHORT_TEXT) return null
        val s = WearCache.load(this)
        return text(if (s == null || s.hidden) "—" else s.safe)
    }

    private fun text(v: String) = ShortTextComplicationData.Builder(PlainComplicationText.Builder(v).build(), PlainComplicationText.Builder(getString(R.string.safe_to_spend)).build()).build()
}
