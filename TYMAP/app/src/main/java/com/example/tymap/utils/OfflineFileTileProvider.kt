package com.example.tymap.utils

import android.graphics.drawable.Drawable
import org.osmdroid.tileprovider.modules.MapTileModuleProviderBase
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.util.MapTileIndex
import java.io.File

class OfflineFileTileProvider(
    tileSource: ITileSource,
    private val offlineDir: File
) : MapTileModuleProviderBase(2, 40) {

    private var mTileSource: ITileSource = tileSource

    override fun getName(): String = "Offline Tile Provider"
    override fun getThreadGroupName(): String = "offline-tile-thread"
    override fun getUsesDataConnection(): Boolean = false
    override fun getTileLoader(): TileLoader = TileLoader()

    override fun getMinimumZoomLevel(): Int = mTileSource.minimumZoomLevel
    override fun getMaximumZoomLevel(): Int = mTileSource.maximumZoomLevel

    override fun setTileSource(pTileSource: ITileSource) {
        mTileSource = pTileSource
    }

    inner class TileLoader : MapTileModuleProviderBase.TileLoader() {
        override fun loadTile(pMapTileIndex: Long): Drawable? {
            val zoom = MapTileIndex.getZoom(pMapTileIndex)
            val x = MapTileIndex.getX(pMapTileIndex)
            val y = MapTileIndex.getY(pMapTileIndex)

            val tileFile = findOfflineTileFile(zoom, x, y)
            if (tileFile != null && tileFile.exists()) {
                try {
                    synchronized(mTileSource) {
                        return mTileSource.getDrawable(tileFile.absolutePath)
                    }
                } catch (e: Exception) {
                    android.util.Log.e("OfflineTileProvider", "Error reading offline tile file: ${tileFile.absolutePath}", e)
                }
            }
            return null
        }
    }

    private fun findOfflineTileFile(zoom: Int, x: Int, y: Int): File? {
        if (!offlineDir.exists()) return null
        val regions = offlineDir.listFiles { f -> f.isDirectory } ?: return null
        for (region in regions) {
            val tileFile = File(region, "$zoom/$x/$y.png")
            if (tileFile.exists() && tileFile.length() > 0) {
                return tileFile
            }
        }
        return null
    }
}
