// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class FutoAssetMaterializationTest {
    @get:Rule val folder = TemporaryFolder()
    private val asset = "futo-swipe/honorable_sturgeon/metadata.json"

    private fun prepare(targetTime: Long): Triple<Context, File, ByteArray> {
        val app = ApplicationProvider.getApplicationContext<App>()
        val apk = folder.newFile("package.apk").apply { setLastModified(2000) }
        val context = object : ContextWrapper(app) {
            override fun getFilesDir() = folder.root
            override fun getApplicationInfo() = ApplicationInfo(app.applicationInfo).apply {
                sourceDir = apk.path
            }
        }
        val bytes = app.assets.open(asset).use { it.readBytes() }
        val target = File(folder.root, "futo/$asset")
        target.parentFile!!.mkdirs()
        target.writeBytes(bytes)
        target.setLastModified(targetTime)
        val cache = FutoSuggestions::class.java.getDeclaredField("materialized")
            .apply { isAccessible = true }.get(FutoSuggestions) as MutableMap<*, *>
        cache.clear()
        return Triple(context, target, bytes)
    }

    private fun materialize(context: Context): File =
        FutoSuggestions::class.java.getDeclaredMethod("materialize", Context::class.java, String::class.java)
            .apply { isAccessible = true }.invoke(FutoSuggestions, context, asset) as File

    @Test fun sameSizeAssetFromThePreviousPackageIsReplaced() {
        val (context, target, bytes) = prepare(1000)
        target.writeBytes(ByteArray(bytes.size) { 'x'.code.toByte() })
        target.setLastModified(1000)
        assertContentEquals(bytes, materialize(context).readBytes())
    }

    @Test fun currentPackageAssetIsReused() {
        val (context, target, bytes) = prepare(2000)
        assertContentEquals(bytes, materialize(context).readBytes())
        assertEquals(2000, target.lastModified())
    }
}
