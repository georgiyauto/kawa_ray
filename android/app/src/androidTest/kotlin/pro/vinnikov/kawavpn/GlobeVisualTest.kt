package pro.vinnikov.kawavpn

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import java.io.File
import pro.vinnikov.kawavpn.ui.Globe
import pro.vinnikov.kawavpn.vpn.TunnelStatus
import pro.vinnikov.kawavpn.domain.ConnectionState

/** Deterministic render fixture only; never mutates the VPN service or claims a live route. */
class GlobeVisualTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private fun shot(name:String):Bitmap {
        val bitmap=compose.onRoot().captureToImage().asAndroidBitmap()
        File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),name+".png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
        return bitmap
    }
    private fun bright(bitmap:Bitmap):Int {
        var n=0
        for(y in 0 until bitmap.height) for(x in 0 until bitmap.width) {
            val c=bitmap.getPixel(x,y)
            if(android.graphics.Color.red(c)>210 && android.graphics.Color.green(c)>210 && android.graphics.Color.blue(c)>210) n++
        }
        return n
    }
    @Test fun routeRevealsAndGlobeCanBeRotatedAndReset() {
        compose.mainClock.autoAdvance=false
        compose.runOnUiThread {
            compose.activity.setContent {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    Globe(TunnelStatus(state=ConnectionState.CONNECTED,originCountry="RU",exitCountry="DE"),true,Modifier.fillMaxWidth().height(300.dp))
                }
            }
        }
        compose.mainClock.advanceTimeBy(32)
        val before=shot("fixture-route-start")
        compose.mainClock.advanceTimeBy(1700)
        val after=shot("fixture-route-complete")
        assertTrue("The route must visibly reveal, not only show an endpoint",bright(after)>bright(before)+30)
        compose.onNodeWithContentDescription("Трёхмерный глобус",substring=true).performTouchInput { swipeLeft() }
        compose.mainClock.advanceTimeBy(32)
        val rotated=shot("fixture-route-rotated")
        assertFalse("Dragging must change the projection",after.sameAs(rotated))
        compose.onNodeWithContentDescription("Вернуть маршрут в центр").performClick()
        compose.mainClock.advanceTimeBy(32)
        val reset=shot("fixture-route-reset")
        assertTrue("Reset must restore the visible route",bright(reset)>30)
        compose.mainClock.autoAdvance=true
    }
}
