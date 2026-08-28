package com.example.tymap.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.switchmaterial.SwitchMaterial
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.*
import com.example.tymap.R
import com.example.tymap.databinding.FragmentMapBinding
import com.example.tymap.repository.NavigationRepository
import com.example.tymap.service.NavigationService
import com.example.tymap.service.RoutingEngine
import com.example.tymap.utils.NasConnectionManager
import com.example.tymap.utils.PrefsHelper
import com.google.android.material.bottomsheet.BottomSheetBehavior
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.compass.CompassOverlay
import org.osmdroid.views.overlay.compass.InternalCompassOrientationProvider
import org.osmdroid.views.overlay.gestures.RotationGestureOverlay
import org.osmdroid.views.overlay.compass.IOrientationConsumer
import org.osmdroid.views.overlay.compass.IOrientationProvider
import android.graphics.DashPathEffect
import android.graphics.RectF
import android.widget.CheckBox
import android.widget.TextView
import android.content.DialogInterface
import com.example.tymap.utils.OfflineFileTileProvider
import com.example.tymap.service.OfflineDownloadService
import org.osmdroid.util.BoundingBox
import org.osmdroid.tileprovider.modules.MapTileModuleProviderBase
import org.osmdroid.tileprovider.MapTileProviderArray
import org.osmdroid.tileprovider.util.SimpleRegisterReceiver
import java.io.File
import java.util.UUID
import kotlin.math.min
import kotlin.math.max
import kotlin.math.abs
import android.os.Build
import org.osmdroid.views.MapView
import org.osmdroid.views.Projection

class MapFragment : Fragment(), IOrientationConsumer {
    private var _binding: FragmentMapBinding? = null
    private val binding get() = _binding!!

    private lateinit var bottomSheetBehavior: BottomSheetBehavior<View>
    private lateinit var suggestionAdapter: SuggestionAdapter
    private lateinit var routeAlternativeAdapter: RouteAlternativeAdapter
    private lateinit var routeStepsAdapter: RouteStepsAdapter
    private lateinit var savedPlaceDbHelper: com.example.tymap.repository.SavedPlaceDbHelper
    private val httpClient = OkHttpClient()
    private lateinit var routingEngine: RoutingEngine

    private var userMarker: Marker? = null
    private var destinationMarker: Marker? = null
    private var routePolylines = mutableListOf<Polyline>()
    private var drawRoutesJob: Job? = null
    private var compassOverlay: CompassOverlay? = null
    private var orientationProvider: InternalCompassOrientationProvider? = null
    
    private var selectionOverlay: SelectionOverlay? = null
    private var offlineSelectionBinding: com.example.tymap.databinding.LayoutOfflineSelectionBinding? = null

    private var isFollowing = true
    private var isFirstLocation = true
    private var lastHeading: Float = 0f

    // GPS Smoothing and Dynamic Updates
    private var filteredLat = 0.0
    private var filteredLon = 0.0
    private var lastLocationTime = 0L

    // Route Simulation
    private var simulationJob: Job? = null
    private var isSimulating: Boolean = false

    private val screenCaptureLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK && result.data != null) {
            val serviceIntent = Intent(requireContext(), com.example.tymap.service.NavigationService::class.java).apply {
                action = "ACTION_START_CAPTURE"
                putExtra("PROJECTION_INTENT", result.data)
            }
            requireContext().startForegroundService(serviceIntent)
            PrefsHelper.putInt(requireContext(), "map_capture_mode", 1)
            Toast.makeText(requireContext(), "Đã bật chụp Google Maps", Toast.LENGTH_SHORT).show()
        } else {
            PrefsHelper.putInt(requireContext(), "map_capture_mode", 0)
            Toast.makeText(requireContext(), "Quyền chụp màn hình bị từ chối", Toast.LENGTH_SHORT).show()
        }
    }

    // Map Sources
    private val mapCnDark = object : XYTileSource("CartoDB Dark Matter", 1, 20, 256, ".png",
        arrayOf("https://a.basemaps.cartocdn.com/dark_all/", "https://b.basemaps.cartocdn.com/dark_all/", "https://c.basemaps.cartocdn.com/dark_all/"),
        "© OpenStreetMap contributors, © CARTO") {
        override fun getTileURLString(pMapTileIndex: Long): String {
            val base = super.getTileURLString(pMapTileIndex)
            val key = try { PrefsHelper.getSecureString(requireContext(), "api_key_carto", "") } catch (e: Exception) { "" }
            return if (key.isNotEmpty()) "$base?api_key=$key" else base
        }
    }

    private val mapCnPositron = object : XYTileSource("CartoDB Positron", 1, 20, 256, ".png",
        arrayOf("https://a.basemaps.cartocdn.com/light_all/", "https://b.basemaps.cartocdn.com/light_all/", "https://c.basemaps.cartocdn.com/light_all/"),
        "© OpenStreetMap contributors, © CARTO") {
        override fun getTileURLString(pMapTileIndex: Long): String {
            val base = super.getTileURLString(pMapTileIndex)
            val key = try { PrefsHelper.getSecureString(requireContext(), "api_key_carto", "") } catch (e: Exception) { "" }
            return if (key.isNotEmpty()) "$base?api_key=$key" else base
        }
    }

    private val mapCnVoyager = object : XYTileSource("CartoDB Voyager", 1, 20, 256, ".png",
        arrayOf("https://a.basemaps.cartocdn.com/rastertiles/voyager/", "https://b.basemaps.cartocdn.com/rastertiles/voyager/", "https://c.basemaps.cartocdn.com/rastertiles/voyager/"),
        "© OpenStreetMap contributors, © CARTO") {
        override fun getTileURLString(pMapTileIndex: Long): String {
            val base = super.getTileURLString(pMapTileIndex)
            val key = try { PrefsHelper.getSecureString(requireContext(), "api_key_carto", "") } catch (e: Exception) { "" }
            return if (key.isNotEmpty()) "$base?api_key=$key" else base
        }
    }

    private val googleMapsDark = object : XYTileSource("Google Maps Dark", 1, 20, 256, "",
        arrayOf("https://mt1.google.com/vt/lyrs=m"),
        "© Google") {
        override fun getTileURLString(pMapTileIndex: Long): String {
            val z = org.osmdroid.util.MapTileIndex.getZoom(pMapTileIndex)
            val x = org.osmdroid.util.MapTileIndex.getX(pMapTileIndex)
            val y = org.osmdroid.util.MapTileIndex.getY(pMapTileIndex)
            val style = "s.t:1|s.e:g|p.c:#ff242f3e,s.t:1|s.e:l.t.f|p.c:#ff746855,s.t:1|s.e:l.t.s|p.c:#ff242f3e,s.t:3|s.e:g.f|p.c:#ff242f3e,s.t:3|s.e:l.t.f|p.c:#ff746855,s.t:4|s.e:g.f|p.c:#ff212a37,s.t:5|s.e:g.f|p.c:#ff38414e,s.t:5|s.e:g.s|p.c:#ff212a37,s.t:5|s.e:l.t.f|p.c:#ff9ca5b3,s.t:6|s.e:g.f|p.c:#ff746855,s.t:6|s.e:g.s|p.c:#ff242f3e,s.t:6|s.e:l.t.f|p.c:#ffd59563,s.t:81|s.e:g.f|p.c:#ff17263c,s.t:82|s.e:g.f|p.c:#ff1f2835,s.t:82|s.e:l.t.f|p.c:#ff515c6d,s.t:82|s.e:l.t.s|p.c:#ff1f2835"
            val encodedStyle = android.net.Uri.encode(style)
            return "https://mt1.google.com/vt/lyrs=m&x=$x&y=$y&z=$z&apistyle=$encodedStyle"
        }
    }

    private val googleMaps = object : XYTileSource("Google Maps", 1, 20, 256, "",
        arrayOf("https://mt1.google.com/vt/lyrs=m"),
        "© Google") {
        override fun getTileURLString(pMapTileIndex: Long): String {
            val z = org.osmdroid.util.MapTileIndex.getZoom(pMapTileIndex)
            val x = org.osmdroid.util.MapTileIndex.getX(pMapTileIndex)
            val y = org.osmdroid.util.MapTileIndex.getY(pMapTileIndex)
            return "https://mt1.google.com/vt/lyrs=m&x=$x&y=$y&z=$z"
        }
    }

    private val googleMapsSatellite = object : XYTileSource("Google Satellite", 1, 20, 256, "",
        arrayOf("https://mt1.google.com/vt/lyrs=s"),
        "© Google") {
        override fun getTileURLString(pMapTileIndex: Long): String {
            val z = org.osmdroid.util.MapTileIndex.getZoom(pMapTileIndex)
            val x = org.osmdroid.util.MapTileIndex.getX(pMapTileIndex)
            val y = org.osmdroid.util.MapTileIndex.getY(pMapTileIndex)
            return "https://mt1.google.com/vt/lyrs=s&x=$x&y=$y&z=$z"
        }
    }

    private val googleMapsHybrid = object : XYTileSource("Google Hybrid", 1, 20, 256, "",
        arrayOf("https://mt1.google.com/vt/lyrs=y"),
        "© Google") {
        override fun getTileURLString(pMapTileIndex: Long): String {
            val z = org.osmdroid.util.MapTileIndex.getZoom(pMapTileIndex)
            val x = org.osmdroid.util.MapTileIndex.getX(pMapTileIndex)
            val y = org.osmdroid.util.MapTileIndex.getY(pMapTileIndex)
            return "https://mt1.google.com/vt/lyrs=y&x=$x&y=$y&z=$z"
        }
    }

    private val osmStandard = XYTileSource("OpenStreetMap", 1, 19, 256, ".png",
        arrayOf("https://tile.openstreetmap.org/"),
        "© OpenStreetMap contributors")

    private val osmHot = XYTileSource("OSM HOT", 1, 19, 256, ".png",
        arrayOf("https://a.tile.openstreetmap.fr/hot/", "https://b.tile.openstreetmap.fr/hot/"),
        "© OpenStreetMap contributors, HOT")

    private fun getTileSources(): List<ITileSource> {
        val list = mutableListOf<ITileSource>()
        list.add(mapCnDark)           // 0: CartoDB Dark Matter
        list.add(mapCnPositron)       // 1: CartoDB Positron
        list.add(mapCnVoyager)        // 2: CartoDB Voyager
        list.add(googleMaps)          // 3: Google Maps (MT)
        list.add(googleMapsDark)      // 4: Google Maps Dark (MT)
        list.add(googleMaps)          // 5: Google Maps Đảo Màu (MT Invert)
        list.add(googleMapsSatellite) // 6: Google Maps Satellite (MT)
        list.add(googleMapsHybrid)    // 7: Google Maps Hybrid (MT)
        list.add(osmStandard)         // 8: OpenStreetMap Chuẩn
        list.add(osmHot)              // 9: OpenStreetMap HOT

        val customUrl = PrefsHelper.getString(requireContext(), "custom_tile_url", "")
        if (customUrl.isNotEmpty() && customUrl.contains("{z}")) {
            try {
                val baseUrl = customUrl.substringBefore("{z}")
                val ext = "." + customUrl.substringAfterLast(".")
                list.add(XYTileSource("Tùy chỉnh", 1, 20, 256, ext, arrayOf(baseUrl), "Custom"))
            } catch (e: Exception) {
                list.add(osmStandard)
            }
        } else {
            list.add(XYTileSource("Tùy chỉnh (Chưa cấu hình)", 1, 20, 256, ".png", arrayOf("https://tile.openstreetmap.org/"), "Custom"))
        }
        return list
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        Configuration.getInstance().load(requireContext(), requireContext().getSharedPreferences("tymap_osmdroid", Context.MODE_PRIVATE))
        Configuration.getInstance().userAgentValue = "Mozilla/5.0 (Android; Mobile; TYMAP/1.0)"
        // Rule APP-21: Bật cache tối thiểu 50MB
        Configuration.getInstance().cacheMapTileCount = 50 
        Configuration.getInstance().tileDownloadThreads = 2
        Configuration.getInstance().tileDownloadMaxQueueSize = 40
        _binding = FragmentMapBinding.inflate(inflater, container, false)
        routingEngine = RoutingEngine(httpClient)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        savedPlaceDbHelper = com.example.tymap.repository.SavedPlaceDbHelper(requireContext())
        setupWindowInsets()
        setupMap()
        setupBottomSheet()
        setupSearch()
        setupButtons()
        observeNavigationData()
        observeRemoteCommands()
        handleSharedLocation()

        // Đảm bảo dịch vụ GPS đang chạy khi xem bản đồ
        ensureGpsRunning()
    }

    private fun setupWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            val density = resources.displayMetrics.density

            // 1. Top Inset for Search Card (tránh camera nốt ruồi / status bar)
            val searchParams = binding.searchCard.layoutParams as ViewGroup.MarginLayoutParams
            searchParams.topMargin = (16 * density).toInt() + insets.top
            binding.searchCard.layoutParams = searchParams

            // 2. Suggestions drop below the search card, so it must follow the
            //    same status-bar offset or it will overlap the search bar on
            //    edge-to-edge (Android 15 / targetSdk 35) devices.
            val suggestionsParams = binding.suggestionsCard.layoutParams as ViewGroup.MarginLayoutParams
            suggestionsParams.topMargin = (72 * density).toInt() + insets.top
            binding.suggestionsCard.layoutParams = suggestionsParams

            // Note: the bottom-sheet floating card uses a fixed 16dp gap defined
            // in bottom_sheet_navigation.xml. MainActivity already insets the
            // fragment above the Liquid nav via the ViewPager bottom padding, so
            // no nav height / system bar bottom inset is re-applied here.

            windowInsets
        }
    }

    private fun ensureGpsRunning() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            val intent = Intent(requireContext(), NavigationService::class.java)
            requireContext().startForegroundService(intent)
        }
    }

    private fun setupMap() {
        val context = requireContext()
        val sources = getTileSources()
        val savedIndex = PrefsHelper.getInt(context, "tile_source", 0)
        val index = if (savedIndex >= sources.size) 0 else savedIndex

        binding.mapView.apply {
            overlays.clear()
            setTileSource(sources[index])
            
            if (index == 5) {
                val colorMatrix = android.graphics.ColorMatrix(floatArrayOf(
                    -1.0f, 0.0f, 0.0f, 0.0f, 255f,
                    0.0f, -1.0f, 0.0f, 0.0f, 255f,
                    0.0f, 0.0f, -1.0f, 0.0f, 255f,
                    0.0f, 0.0f, 0.0f, 1.0f, 0.0f
                ))
                overlayManager.tilesOverlay.setColorFilter(android.graphics.ColorMatrixColorFilter(colorMatrix))
            } else {
                overlayManager.tilesOverlay.setColorFilter(null)
            }
            
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            isTilesScaledToDpi = true 
            
            // 1. Map Events
            val eventsReceiver = object : MapEventsReceiver {
                override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                    binding.suggestionsCard.visibility = View.GONE
                    binding.etSearch.clearFocus()
                    if (!NavigationRepository.navigationState.value) {
                        clearDestination()
                    }
                    return true
                }
                override fun longPressHelper(p: GeoPoint?): Boolean {
                    if (p != null) {
                        reverseGeocode(p.latitude, p.longitude)
                        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                        vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
                    }
                    return true
                }
            }
            overlays.add(MapEventsOverlay(eventsReceiver))

            // 2. User Position Marker (The Blue Dot + Fan)
            userMarker = Marker(this).apply {
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                icon = createUserIcon(context)
                // FLAT marker: icon nằm trên mặt bản đồ và TỰ XOAY THEO bản đồ khi người dùng
                // xoay map. marker.rotation = -heading => nón xanh luôn chỉ đúng hướng thực tế
                // trên bản đồ đã xoay (osmdroid: total = mapOrientation - marker.rotation).
                setFlat(true)
                infoWindow = null
                setOnMarkerClickListener { _, _ -> true }
            }
            overlays.add(userMarker)

            // 3. Compass sensor provider
            orientationProvider = InternalCompassOrientationProvider(context)
            compassOverlay = CompassOverlay(context, orientationProvider!!, this).apply {
                enableCompass()
                setCompassCenter(0f, 0f) 
            }
            overlays.add(compassOverlay)
            orientationProvider?.startOrientationProvider(this@MapFragment)

            // 4. Rotation UI
            overlays.add(RotationGestureOverlay(this))

            // Setting "Hướng bản đồ" (map_orientation): 0 = Hướng Bắc, 1 = Hướng đi
            NavigationRepository.setTrackUpMode(
                PrefsHelper.getInt(context, "map_orientation", 1) == 1
            )

            // Map Listeners
            addMapListener(object : MapListener {
                override fun onScroll(scrollEvent: ScrollEvent?): Boolean {
                    updateCompassVisibility()
                    return false
                }
                override fun onZoom(zoomEvent: ZoomEvent?): Boolean {
                    updateZoomButtonsState()
                    val currentZoom = binding.mapView.zoomLevelDouble.toFloat()
                    PrefsHelper.putFloat(context, "last_map_zoom", currentZoom)
                    NavigationRepository.lastMapZoom = currentZoom.toDouble()
                    triggerDrawRoutes(NavigationRepository.routes.value)
                    return false
                }
            })

            // Restore Camera
            val restoredZoom = PrefsHelper.getFloat(context, "last_map_zoom", 15f).toDouble()
            NavigationRepository.lastMapZoom = restoredZoom
            controller.setZoom(restoredZoom)
            val lat = PrefsHelper.getFloat(context, "last_map_lat", 10.762622f).toDouble()
            val lon = PrefsHelper.getFloat(context, "last_map_lon", 106.660172f).toDouble()
            controller.setCenter(GeoPoint(lat, lon))

            // Detect manual pan
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_MOVE) {
                    if (isFollowing) {
                        isFollowing = false
                        updateLocationButtonState()
                        binding.btnRecenter.show()
                        android.util.Log.d("MapFragment", "Manual pan detected, button shown")
                    }
                }
                false
            }
            
            updateLocationButtonState()
        }

        val tileCallbackHandler = object : android.os.Handler(android.os.Looper.getMainLooper()) {
            override fun handleMessage(msg: android.os.Message) {
                if (msg.what == org.osmdroid.tileprovider.MapTileProviderBase.MAPTILE_FAIL_ID) {
                    val currentSource = binding.mapView.tileProvider.tileSource
                    if (currentSource != null && currentSource.name() == "Satellite (ESRI)") {
                        binding.mapView.setTileSource(googleMapsDark)
                        PrefsHelper.putInt(requireContext(), "tile_source", 0)
                        android.widget.Toast.makeText(context, "Lỗi tải ảnh vệ tinh, chuyển về Google Maps Dark", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        binding.mapView.tileProvider.tileRequestCompleteHandlers.add(tileCallbackHandler)

        injectOfflineProvider()
        updateZoomButtonsState()
    }

    private fun injectOfflineProvider() {
        val context = requireContext()
        val isOfflinePriority = PrefsHelper.getBoolean(context, "offline_priority", true)
        if (!isOfflinePriority) return

        val offlineDir = File(context.getExternalFilesDir(null), "offline_maps")
        try {
            val provider = binding.mapView.tileProvider
            val field = org.osmdroid.tileprovider.MapTileProviderArray::class.java.getDeclaredField("mTileProviderList")
            field.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val list = field.get(provider) as MutableList<org.osmdroid.tileprovider.modules.MapTileModuleProviderBase>

            val alreadyExists = list.any { it is OfflineFileTileProvider }
            if (!alreadyExists) {
                val offlineProvider = OfflineFileTileProvider(provider.tileSource, offlineDir)
                list.add(0, offlineProvider)
                android.util.Log.d("MapFragment", "Successfully injected OfflineFileTileProvider into MapView")
            }
        } catch (e: Exception) {
            android.util.Log.e("MapFragment", "Error injecting offline provider: ${e.message}", e)
        }
    }

    private fun createUserIcon(context: Context): android.graphics.drawable.Drawable {
        // Reduced size for better precision and stability
        val density = context.resources.displayMetrics.density
        val size = (120 * density).toInt()
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val center = size / 2f
        
        // 1. Draw Orientation Fan (Stronger blue, slightly smaller radius)
        val fanRadius = size * 0.45f
        val gradient = RadialGradient(center, center, fanRadius,
            intArrayOf(Color.parseColor("#B01A73E8"), Color.TRANSPARENT), // More opaque: B0 instead of 90
            null, Shader.TileMode.CLAMP)
        paint.shader = gradient
        // UP is 270. Sweep 60 degrees from 240 to 300.
        canvas.drawArc(RectF(center - fanRadius, center - fanRadius, center + fanRadius, center + fanRadius), 240f, 60f, true, paint)
        
        // 2. Draw the Blue Dot (Sharp and centered)
        paint.shader = null
        val dotSize = 22 * density
        // Outer white border
        paint.color = Color.WHITE
        canvas.drawCircle(center, center, dotSize / 2f, paint)
        // Inner blue circle
        paint.color = ContextCompat.getColor(context, R.color.blue_primary)
        canvas.drawCircle(center, center, dotSize / 2.8f, paint)
        
        // Add a small drop shadow to the dot for better contrast on any background
        paint.color = Color.parseColor("#40000000")
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1 * density
        canvas.drawCircle(center, center, dotSize / 2f, paint)
        
        return android.graphics.drawable.BitmapDrawable(context.resources, bitmap)
    }

    private fun updateCompassVisibility() {
        val rotation = binding.mapView.mapOrientation
        if (Math.abs(rotation) > 5) {
            binding.btnCompass.visibility = View.VISIBLE
            binding.btnCompass.rotation = -rotation
        } else {
            binding.btnCompass.visibility = View.GONE
        }
    }

    private fun updateZoomButtonsState() {
        val zoom = binding.mapView.zoomLevelDouble
        val maxZoom = binding.mapView.maxZoomLevel
        val minZoom = binding.mapView.minZoomLevel
        binding.fabZoomIn.alpha = if (zoom < maxZoom) 1.0f else 0.5f
        binding.fabZoomOut.alpha = if (zoom > minZoom) 1.0f else 0.5f
    }

    private fun reverseGeocode(lat: Double, lon: Double) {
        binding.searchProgress.visibility = View.VISIBLE
        lifecycleScope.launch(Dispatchers.IO) {
            val context = requireContext()
            var resolvedName: String? = null

            // 1. Thử Goong Reverse Geocoding nếu có API key
            val goongKey = PrefsHelper.getSecureString(context, "api_key_goong", "").trim()
            if (goongKey.isNotEmpty()) {
                try {
                    val goongUrl = "https://rsapi.goong.io/Geocode?latlng=$lat,$lon&api_key=$goongKey"
                    val request = Request.Builder().url(goongUrl).header("User-Agent", "TYMAP-Android/1.0").build()
                    NasConnectionManager.publicHttpClient.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            val json = JSONObject(response.body.string())
                            val results = json.optJSONArray("results")
                            if (results != null && results.length() > 0) {
                                val name = results.getJSONObject(0).optString("formatted_address", "")
                                if (name.isNotEmpty()) {
                                    resolvedName = name
                                }
                            }
                        }
                    }
                } catch (e: Exception) {}
            }

            // 2. Thử NAS Nominatim nếu NAS đang online
            if (resolvedName == null && NasConnectionManager.isNasPotentiallyAvailable(context)) {
                val nominatimBaseUrl = NasConnectionManager.getNominatimBaseUrl(context)
                val nasUrl = "$nominatimBaseUrl/reverse?lat=$lat&lon=$lon&format=json"
                try {
                    val request = Request.Builder()
                        .url(nasUrl)
                        .header("User-Agent", "TYMAP-Android/1.0 (contact@tymap.local)")
                        .build()
                    NasConnectionManager.nasHttpClient.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            val json = JSONObject(response.body.string())
                            val name = json.optString("display_name", "")
                            if (name.isNotEmpty()) {
                                resolvedName = name
                                NasConnectionManager.markNasSuccess()
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (NasConnectionManager.isConnectionFailure(e)) {
                        NasConnectionManager.markNasFailed(e.message)
                    }
                }
            }

            // 3. Fallback sang OpenStreetMap Nominatim công cộng
            if (resolvedName == null) {
                try {
                    val osmUrl = "https://nominatim.openstreetmap.org/reverse?lat=$lat&lon=$lon&format=json"
                    val request = Request.Builder()
                        .url(osmUrl)
                        .header("User-Agent", "TYMAP-Android/1.0 (contact@tymap.local)")
                        .build()
                    NasConnectionManager.publicHttpClient.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            val json = JSONObject(response.body.string())
                            val name = json.optString("display_name", "")
                            if (name.isNotEmpty()) {
                                resolvedName = name
                            }
                        }
                    }
                } catch (e: Exception) {}
            }

            withContext(Dispatchers.Main) {
                onPlaceSelected(lat, lon, resolvedName ?: "Vị trí đã thả ghim")
                binding.searchProgress.visibility = View.GONE
            }
        }
    }

    private fun clearDestination() {
        destinationMarker?.let {
            binding.mapView.overlays.remove(it)
            destinationMarker = null
        }
        val toRemove = binding.mapView.overlays.filterIsInstance<Polyline>()
        binding.mapView.overlays.removeAll(toRemove)
        routePolylines.clear()
        NavigationRepository.updateRoutes(emptyList())
        bottomSheetBehavior.state = BottomSheetBehavior.STATE_HIDDEN
        binding.bottomSheet.layoutRoutePreview.visibility = View.GONE
        binding.bottomSheet.layoutPlaceInfo.visibility = View.GONE
        binding.bottomSheet.layoutNavigation.visibility = View.GONE
        binding.layoutRouteSteps.visibility = View.GONE
        
        // Reset floating UI components translationY back down to initial position (0f)
        binding.zoomControls.translationY = 0f
        binding.btnRecenter.translationY = 0f
        binding.cardGpsSpeedometer.translationY = 0f
        binding.cardSpeedLimitSign.translationY = 0f
        
        binding.mapView.invalidate()
    }

    private fun setupBottomSheet() {
        bottomSheetBehavior = BottomSheetBehavior.from(binding.bottomSheet.bottomSheetContainer)
        bottomSheetBehavior.state = BottomSheetBehavior.STATE_HIDDEN

        bottomSheetBehavior.addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
            override fun onStateChanged(bottomSheet: View, newState: Int) {
                if (newState == BottomSheetBehavior.STATE_HIDDEN) {
                    binding.bottomSheet.layoutPlaceInfo.visibility = View.GONE
                    binding.bottomSheet.layoutRoutePreview.visibility = View.GONE
                    binding.bottomSheet.layoutNavigation.visibility = View.GONE
                    binding.layoutRouteSteps.visibility = View.GONE
                }
            }
            override fun onSlide(bottomSheet: View, slideOffset: Float) {
                // Only bottom-anchored controls (which the expanding sheet would
                // otherwise cover) follow the sheet. fabLocation lives in the
                // top-right stack, so it must NOT move here.
                val shift = if (slideOffset > 0f) -slideOffset * (bottomSheet.height - bottomSheetBehavior.peekHeight) else 0f
                binding.zoomControls.translationY = shift
                binding.btnRecenter.translationY = shift
                binding.cardGpsSpeedometer.translationY = shift
                binding.cardSpeedLimitSign.translationY = shift
            }
        })

        binding.cardGpsSpeedometer.setOnClickListener {
            showSpeedLimitSettingsDialog()
        }
        binding.cardSpeedLimitSign.setOnClickListener {
            showSpeedLimitSettingsDialog()
        }

        routeAlternativeAdapter = RouteAlternativeAdapter { selectRoute(it) }
        binding.bottomSheet.rvAlternatives.layoutManager = LinearLayoutManager(requireContext())
        binding.bottomSheet.rvAlternatives.adapter = routeAlternativeAdapter

        routeStepsAdapter = RouteStepsAdapter().apply {
            onStepClickListener = { step ->
                if (step.location.first != 0.0 && step.location.second != 0.0) {
                    binding.mapView.controller.animateTo(GeoPoint(step.location.first, step.location.second))
                    binding.mapView.controller.setZoom(17.5)
                    Toast.makeText(requireContext(), "📍 ${step.instruction}", Toast.LENGTH_SHORT).show()
                }
            }
        }
        binding.rvRouteSteps.layoutManager = LinearLayoutManager(requireContext())
        binding.rvRouteSteps.adapter = routeStepsAdapter

        binding.btnCloseSteps.setOnClickListener {
            binding.layoutRouteSteps.visibility = View.GONE
        }

        binding.bottomSheet.btnClosePreview.setOnClickListener {
            clearDestination()
        }

        binding.bottomSheet.btnOptions.setOnClickListener {
            startActivity(android.content.Intent(requireContext(), com.example.tymap.ui.ThemeBuilderActivity::class.java))
        }

        binding.bottomSheet.btnStartFromPreview.setOnClickListener {
            val destPos = destinationMarker?.position
            if (destPos != null) {
                startNavigation(destPos.latitude, destPos.longitude)
            } else {
                val activeRoute = NavigationRepository.routes.value.firstOrNull { it.isSelected } ?: NavigationRepository.routes.value.firstOrNull()
                if (activeRoute != null && activeRoute.polyline.isNotEmpty()) {
                    val lastPt = activeRoute.polyline.last()
                    startNavigation(lastPt.first, lastPt.second)
                }
            }
        }



        binding.bottomSheet.btnShowStepsPreview.setOnClickListener {
            val activeRoute = NavigationRepository.routes.value.firstOrNull { it.isSelected }
                ?: NavigationRepository.routes.value.firstOrNull()
            if (activeRoute != null && activeRoute.steps.isNotEmpty()) {
                binding.layoutRouteSteps.visibility = View.VISIBLE
                routeStepsAdapter.submitList(activeRoute.steps)
                val durationMin = Math.round(activeRoute.duration / 60.0)
                val distKm = String.format(java.util.Locale.US, "%.1f km", activeRoute.distance / 1000.0)
                binding.tvStepsDuration.text = "$durationMin phút"
                binding.tvStepsSummary.text = "$distKm • ${activeRoute.engineName}"
            } else {
                Toast.makeText(requireContext(), "Chưa có danh sách bước rẽ", Toast.LENGTH_SHORT).show()
            }
        }

        binding.bottomSheet.btnRouteInfo.setOnClickListener {
            if (binding.layoutRouteSteps.visibility == View.VISIBLE) {
                binding.layoutRouteSteps.visibility = View.GONE
            } else {
                binding.layoutRouteSteps.visibility = View.VISIBLE
                val activeRoute = NavigationRepository.routes.value.firstOrNull { it.isSelected }
                    ?: NavigationRepository.routes.value.firstOrNull()
                activeRoute?.let { route ->
                    routeStepsAdapter.submitList(route.steps)
                }
                NavigationRepository.hudPreviewData.value?.let { hud ->
                    binding.tvStepsDuration.text = hud.duration
                    binding.tvStepsSummary.text = if (hud.eta.isNotEmpty()) "${hud.distance} • ${hud.eta}" else hud.distance
                }
            }
        }

        binding.btnCloseSteps.setOnClickListener {
            binding.layoutRouteSteps.visibility = View.GONE
        }

        binding.bottomSheet.btnEndNav.setOnClickListener {
            stopSimulation()
            NavigationRepository.setNavigationRunning(false)
            NavigationRepository.updateRoutes(emptyList()) // Xóa polyline
            binding.layoutRouteSteps.visibility = View.GONE
            clearDestination()
        }
    }

    private fun setupSearch() {
        suggestionAdapter = SuggestionAdapter { item ->
            if (item.optBoolean("is_pin_action")) {
                binding.suggestionsCard.visibility = View.GONE
                binding.etSearch.clearFocus()
                Toast.makeText(requireContext(), "Chạm trực tiếp vào bản đồ để ghim vị trí điểm đến", Toast.LENGTH_LONG).show()
                return@SuggestionAdapter
            }

            if (item.optBoolean("is_placeholder")) {
                val cat = item.optString("category")
                binding.suggestionsCard.visibility = View.GONE
                binding.etSearch.clearFocus()
                showConfigureCategoryDialog(cat)
                return@SuggestionAdapter
            }

            val lat = item.optDouble("lat")
            val lon = item.optDouble("lon")
            val name = item.optString("title").ifEmpty { item.optString("display_name") }
            if (item.optBoolean("is_current_location")) {
                NavigationRepository.gpsLocation.value?.let {
                    binding.mapView.controller.animateTo(GeoPoint(it.latitude, it.longitude))
                }
            } else {
                onPlaceSelected(lat, lon, name)
            }
            binding.suggestionsCard.visibility = View.GONE
            binding.etSearch.clearFocus()
        }
        binding.rvSuggestions.layoutManager = LinearLayoutManager(requireContext())
        binding.rvSuggestions.adapter = suggestionAdapter

        binding.etSearch.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) performSearch(binding.etSearch.text.toString())
            else binding.suggestionsCard.visibility = View.GONE
        }

        binding.etSearch.setOnEditorActionListener { _, actionId, event ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH ||
                actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE ||
                (event != null && event.keyCode == android.view.KeyEvent.KEYCODE_ENTER && event.action == android.view.KeyEvent.ACTION_DOWN)) {
                performEnterSearch(binding.etSearch.text.toString())
                true
            } else {
                false
            }
        }

        binding.etSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (binding.etSearch.hasFocus()) performSearch(s.toString())
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
    }

    private fun calculateDistanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return r * c
    }

    private fun performSearch(query: String) {
        val trimmed = query.trim()
        val currentLoc = NavigationRepository.gpsLocation.value

        if (trimmed.isEmpty() || trimmed.length < 2) {
            // Hiển thị danh sách địa điểm cá nhân đã lưu trong SQLite (truy xuất tức thì < 1ms)
            lifecycleScope.launch(Dispatchers.IO) {
                val mergedResults = mutableListOf<JSONObject>()
                val allSaved = savedPlaceDbHelper.getAllPlaces()
                for (place in allSaved) {
                    val dist = if (currentLoc != null) calculateDistanceMeters(currentLoc.latitude, currentLoc.longitude, place.lat, place.lon) else 0.0
                    mergedResults.add(JSONObject().apply {
                        put("is_saved_place", true)
                        put("category", place.category)
                        put("title", place.title)
                        put("address", place.address)
                        put("display_name", "${place.title}, ${place.address}")
                        put("lat", place.lat)
                        put("lon", place.lon)
                        put("dist_meters", dist)
                    })
                }

                // Nếu chưa lưu Nhà riêng hoặc Công ty, hiển thị gợi ý thiết lập
                val hasHome = allSaved.any { it.category == com.example.tymap.repository.SavedPlace.CATEGORY_HOME }
                val hasWork = allSaved.any { it.category == com.example.tymap.repository.SavedPlace.CATEGORY_WORK }
                if (!hasHome) {
                    mergedResults.add(JSONObject().apply {
                        put("is_saved_place", true)
                        put("category", "HOME")
                        put("title", "Nhà riêng")
                        put("address", "Chạm để ghim hoặc lưu địa chỉ nhà")
                        put("display_name", "Nhà riêng (Chưa thiết lập)")
                        put("is_placeholder", true)
                    })
                }
                if (!hasWork) {
                    mergedResults.add(JSONObject().apply {
                        put("is_saved_place", true)
                        put("category", "WORK")
                        put("title", "Công ty")
                        put("address", "Chạm để ghim hoặc lưu địa chỉ công ty")
                        put("display_name", "Công ty (Chưa thiết lập)")
                        put("is_placeholder", true)
                    })
                }

                // Vị trí GPS hiện tại
                currentLoc?.let {
                    mergedResults.add(JSONObject().apply {
                        put("title", getString(R.string.current_location))
                        put("address", "Vị trí GPS hiện tại của bạn")
                        put("display_name", getString(R.string.current_location))
                        put("lat", it.latitude); put("lon", it.longitude); put("is_current_location", true)
                        put("dist_meters", 0.0)
                    })
                }

                // Nút Ghim vị trí
                mergedResults.add(JSONObject().apply {
                    put("is_pin_action", true)
                    put("title", "Ghim vị trí trực tiếp trên bản đồ")
                    put("address", "Chạm vào bản đồ để chọn tọa độ đích đến")
                    put("display_name", "Ghim vị trí trực tiếp trên bản đồ")
                })

                withContext(Dispatchers.Main) {
                    if (binding.etSearch.hasFocus()) {
                        suggestionAdapter.submitList(mergedResults)
                        binding.suggestionsCard.visibility = View.VISIBLE
                        binding.searchProgress.visibility = View.GONE
                    }
                }
            }
            return
        }

        binding.searchProgress.visibility = View.VISIBLE
        
        lifecycleScope.launch(Dispatchers.IO) {
            val mergedResults = mutableListOf<JSONObject>()

            // 1. Ưu tiên tìm kiếm từ Cơ sở dữ liệu Cá nhân (SQLite)
            try {
                val savedPlaces = savedPlaceDbHelper.searchPlaces(trimmed)
                for (place in savedPlaces) {
                    val dist = if (currentLoc != null) calculateDistanceMeters(currentLoc.latitude, currentLoc.longitude, place.lat, place.lon) else 0.0
                    mergedResults.add(JSONObject().apply {
                        put("is_saved_place", true)
                        put("category", place.category)
                        put("title", place.title)
                        put("address", place.address)
                        put("display_name", "${place.title}, ${place.address}")
                        put("lat", place.lat)
                        put("lon", place.lon)
                        put("dist_meters", dist)
                    })
                }
            } catch (e: Exception) {
                Log.e("MapFragment", "Lỗi tìm kiếm SQLite: ${e.message}")
            }

            if (getString(R.string.current_location).contains(trimmed, true)) {
                currentLoc?.let {
                    mergedResults.add(JSONObject().apply {
                        put("title", getString(R.string.current_location))
                        put("address", "Vị trí GPS hiện tại của bạn")
                        put("display_name", getString(R.string.current_location))
                        put("lat", it.latitude); put("lon", it.longitude); put("is_current_location", true)
                        put("dist_meters", 0.0)
                    })
                }
            }

            // 2. Gợi ý tìm kiếm trực tuyến (Goong / NAS Photon / Photon Public)
            val photonDeferred = async {
                val list = mutableListOf<JSONObject>()
                val context = requireContext()
                val goongKey = PrefsHelper.getSecureString(context, "api_key_goong", "").trim()

                // A. Ưu tiên Goong AutoComplete nếu có API Key (Cực nhanh và chuẩn xác tại Việt Nam)
                if (goongKey.isNotEmpty()) {
                    try {
                        val encodedQuery = java.net.URLEncoder.encode(trimmed, "UTF-8")
                        val goongUrl = "https://rsapi.goong.io/Place/AutoComplete?input=$encodedQuery&api_key=$goongKey" + (currentLoc?.let { "&location=${it.latitude},${it.longitude}" } ?: "")
                        val request = Request.Builder().url(goongUrl).header("User-Agent", "TYMAP-Android/1.0").build()
                        NasConnectionManager.publicHttpClient.newCall(request).execute().use { response ->
                            if (response.isSuccessful) {
                                val json = JSONObject(response.body.string())
                                val predictions = json.optJSONArray("predictions")
                                if (predictions != null && predictions.length() > 0) {
                                    for (i in 0 until predictions.length()) {
                                        val pred = predictions.getJSONObject(i)
                                        val desc = pred.optString("description", "")
                                        val structured = pred.optJSONObject("structured_formatting")
                                        val mainText = structured?.optString("main_text", desc) ?: desc
                                        val secondaryText = structured?.optString("secondary_text", "") ?: ""
                                        val placeId = pred.optString("place_id", "")

                                        // Note: Detail resolution occurs when selected or lat/lon if provided
                                        list.add(JSONObject().apply {
                                            put("title", mainText)
                                            put("address", secondaryText.ifEmpty { desc })
                                            put("display_name", desc)
                                            put("place_id", placeId)
                                            put("is_goong", true)
                                            put("lat", currentLoc?.latitude ?: 0.0)
                                            put("lon", currentLoc?.longitude ?: 0.0)
                                            put("dist_meters", 0.0)
                                        })
                                    }
                                    if (list.isNotEmpty()) return@async list
                                }
                            }
                        }
                    } catch (e: Exception) {}
                }

                // B. Thử NAS Photon nếu NAS đang online
                if (list.isEmpty() && NasConnectionManager.isNasPotentiallyAvailable(context)) {
                    val photonBaseUrl = NasConnectionManager.getPhotonBaseUrl(context)
                    val nasPhotonUrl = "$photonBaseUrl/api?q=$trimmed&limit=5" + (currentLoc?.let { "&lat=${it.latitude}&lon=${it.longitude}" } ?: "")
                    try {
                        val request = Request.Builder().url(nasPhotonUrl).header("User-Agent", "TYMAP-Android/1.0 (contact@tymap.local)").build()
                        NasConnectionManager.fastSearchHttpClient.newCall(request).execute().use { response ->
                            if (response.isSuccessful) {
                                val json = JSONObject(response.body.string())
                                val features = json.optJSONArray("features")
                                if (features != null && features.length() > 0) {
                                    parsePhotonFeatures(features, list, currentLoc)
                                    if (list.isNotEmpty()) {
                                        NasConnectionManager.markNasSuccess()
                                        return@async list
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        if (NasConnectionManager.isConnectionFailure(e)) {
                            NasConnectionManager.markNasFailed("NAS Photon failed: ${e.message}")
                        }
                    }
                }

                // C. Fallback sang Photon Komoot Công cộng (Miễn phí & Cực nhanh)
                if (list.isEmpty()) {
                    val publicPhotonUrl = "https://photon.komoot.io/api?q=$trimmed&limit=5" + (currentLoc?.let { "&lat=${it.latitude}&lon=${it.longitude}" } ?: "")
                    try {
                        val request = Request.Builder().url(publicPhotonUrl).header("User-Agent", "TYMAP-Android/1.0 (contact@tymap.local)").build()
                        NasConnectionManager.fastSearchHttpClient.newCall(request).execute().use { response ->
                            if (response.isSuccessful) {
                                val json = JSONObject(response.body.string())
                                val features = json.optJSONArray("features")
                                if (features != null && features.length() > 0) {
                                    parsePhotonFeatures(features, list, currentLoc)
                                }
                            }
                        }
                    } catch (e: Exception) {}
                }

                list
            }

            val photonFetched = photonDeferred.await()
            // Sắp xếp kết quả Photon theo khoảng cách
            val sortedPhoton = photonFetched.sortedBy { it.optDouble("dist_meters", Double.MAX_VALUE) }
            mergedResults.addAll(sortedPhoton)

            // 3. Nếu không có kết quả -> Hiển thị nút Ghim vị trí trực tiếp
            if (mergedResults.isEmpty()) {
                mergedResults.add(JSONObject().apply {
                    put("is_pin_action", true)
                    put("title", "Ghim vị trí trực tiếp trên bản đồ")
                    put("address", "Chạm vào bản đồ để chọn tọa độ đích đến")
                    put("display_name", "Ghim vị trí trực tiếp trên bản đồ")
                })
            }

            withContext(Dispatchers.Main) {
                suggestionAdapter.submitList(mergedResults)
                binding.suggestionsCard.visibility = if (mergedResults.isNotEmpty()) View.VISIBLE else View.GONE
                binding.searchProgress.visibility = View.GONE
            }
        }
    }

    private fun parsePhotonFeatures(features: JSONArray, list: MutableList<JSONObject>, currentLoc: android.location.Location?) {
        for (i in 0 until features.length()) {
            val feat = features.getJSONObject(i)
            val prop = feat.getJSONObject("properties")
            val geom = feat.getJSONObject("geometry").getJSONArray("coordinates")
            val name = prop.optString("name", "")
            val details = listOfNotNull(
                prop.optString("street").ifEmpty { null },
                prop.optString("district").ifEmpty { null },
                prop.optString("city").ifEmpty { null },
                prop.optString("country").ifEmpty { null }
            ).filter { it.isNotBlank() }.joinToString(", ")

            val displayName = if (name.isNotEmpty() && details.isNotEmpty()) "$name, $details" else (name.ifEmpty { details })
            val lat = geom.getDouble(1)
            val lon = geom.getDouble(0)
            val dist = if (currentLoc != null) calculateDistanceMeters(currentLoc.latitude, currentLoc.longitude, lat, lon) else 0.0
            if (displayName.isNotEmpty()) {
                list.add(JSONObject().apply {
                    put("title", name.ifEmpty { details })
                    put("address", details)
                    put("display_name", displayName)
                    put("lat", lat); put("lon", lon)
                    put("dist_meters", dist)
                })
            }
        }
    }

    private fun performEnterSearch(query: String) {
        if (query.length < 2) return
        binding.searchProgress.visibility = View.VISIBLE
        val currentLoc = NavigationRepository.gpsLocation.value

        lifecycleScope.launch(Dispatchers.IO) {
            val context = requireContext()

            // 1. Kiểm tra nhanh SQLite trước (0ms)
            try {
                val savedPlaces = savedPlaceDbHelper.searchPlaces(query)
                if (savedPlaces.isNotEmpty()) {
                    val first = savedPlaces.first()
                    withContext(Dispatchers.Main) {
                        binding.searchProgress.visibility = View.GONE
                        onPlaceSelected(first.lat, first.lon, "${first.title}, ${first.address}")
                        binding.suggestionsCard.visibility = View.GONE
                        binding.etSearch.clearFocus()
                    }
                    return@launch
                }
            } catch (e: Exception) {}

            val results = mutableListOf<JSONObject>()
            val goongKey = PrefsHelper.getSecureString(context, "api_key_goong", "").trim()

            // 2. Thử Goong Geocoding nếu có key
            if (goongKey.isNotEmpty()) {
                try {
                    val encoded = java.net.URLEncoder.encode(query, "UTF-8")
                    val goongUrl = "https://rsapi.goong.io/geocode?address=$encoded&api_key=$goongKey"
                    val request = Request.Builder().url(goongUrl).header("User-Agent", "TYMAP-Android/1.0").build()
                    NasConnectionManager.publicHttpClient.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            val json = JSONObject(response.body.string())
                            val resArray = json.optJSONArray("results")
                            if (resArray != null) {
                                for (i in 0 until resArray.length()) {
                                    val item = resArray.getJSONObject(i)
                                    val formatted = item.optString("formatted_address", "")
                                    val geom = item.optJSONObject("geometry")?.optJSONObject("location")
                                    if (geom != null) {
                                        val lat = geom.optDouble("lat", 0.0)
                                        val lon = geom.optDouble("lng", 0.0)
                                        val dist = if (currentLoc != null) calculateDistanceMeters(currentLoc.latitude, currentLoc.longitude, lat, lon) else 0.0
                                        results.add(JSONObject().apply {
                                            put("title", formatted.substringBefore(","))
                                            put("address", formatted.substringAfter(",", ""))
                                            put("display_name", formatted)
                                            put("lat", lat); put("lon", lon)
                                            put("dist_meters", dist)
                                        })
                                    }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {}
            }

            // 3. Thử NAS Nominatim nếu NAS đang online
            if (results.isEmpty() && NasConnectionManager.isNasPotentiallyAvailable(context)) {
                val nominatimBaseUrl = NasConnectionManager.getNominatimBaseUrl(context)
                val nasUrl = "$nominatimBaseUrl/search?q=${java.net.URLEncoder.encode(query, "UTF-8")}&format=json&limit=5"
                try {
                    val request = Request.Builder().url(nasUrl).header("User-Agent", "TYMAP-Android/1.0 (contact@tymap.local)").build()
                    NasConnectionManager.nasHttpClient.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            val array = JSONArray(response.body.string())
                            for (i in 0 until array.length()) {
                                val item = array.getJSONObject(i)
                                val lat = item.getDouble("lat")
                                val lon = item.getDouble("lon")
                                val displayName = item.optString("display_name", "")
                                val dist = if (currentLoc != null) calculateDistanceMeters(currentLoc.latitude, currentLoc.longitude, lat, lon) else 0.0
                                if (displayName.isNotEmpty()) {
                                    results.add(JSONObject().apply {
                                        put("title", displayName.substringBefore(","))
                                        put("address", displayName.substringAfter(",", ""))
                                        put("display_name", displayName)
                                        put("lat", lat); put("lon", lon)
                                        put("dist_meters", dist)
                                    })
                                }
                            }
                            if (results.isNotEmpty()) {
                                NasConnectionManager.markNasSuccess()
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (NasConnectionManager.isConnectionFailure(e)) {
                        NasConnectionManager.markNasFailed(e.message)
                    }
                }
            }

            // 4. Fallback sang OpenStreetMap Nominatim công cộng
            if (results.isEmpty()) {
                val osmUrl = "https://nominatim.openstreetmap.org/search?q=${java.net.URLEncoder.encode(query, "UTF-8")}&format=json&limit=5"
                try {
                    val request = Request.Builder().url(osmUrl).header("User-Agent", "TYMAP-Android/1.0 (contact@tymap.local)").build()
                    NasConnectionManager.publicHttpClient.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            val array = JSONArray(response.body.string())
                            for (i in 0 until array.length()) {
                                val item = array.getJSONObject(i)
                                val lat = item.getDouble("lat")
                                val lon = item.getDouble("lon")
                                val displayName = item.optString("display_name", "")
                                val dist = if (currentLoc != null) calculateDistanceMeters(currentLoc.latitude, currentLoc.longitude, lat, lon) else 0.0
                                if (displayName.isNotEmpty()) {
                                    results.add(JSONObject().apply {
                                        put("title", displayName.substringBefore(","))
                                        put("address", displayName.substringAfter(",", ""))
                                        put("display_name", displayName)
                                        put("lat", lat); put("lon", lon)
                                        put("dist_meters", dist)
                                    })
                                }
                            }
                        }
                    }
                } catch (e: Exception) {}
            }

            // 5. Fallback cuối: Photon Komoot Công cộng
            if (results.isEmpty()) {
                val photonUrl = "https://photon.komoot.io/api?q=${java.net.URLEncoder.encode(query, "UTF-8")}&limit=5"
                try {
                    val request = Request.Builder().url(photonUrl).header("User-Agent", "TYMAP-Android/1.0 (contact@tymap.local)").build()
                    NasConnectionManager.fastSearchHttpClient.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            val json = JSONObject(response.body.string())
                            val features = json.optJSONArray("features")
                            if (features != null) {
                                parsePhotonFeatures(features, results, currentLoc)
                            }
                        }
                    }
                } catch (e: Exception) {}
            }

            val sortedResults = results.sortedBy { it.optDouble("dist_meters", Double.MAX_VALUE) }

            withContext(Dispatchers.Main) {
                binding.searchProgress.visibility = View.GONE
                if (sortedResults.isNotEmpty()) {
                    val first = sortedResults.first()
                    onPlaceSelected(first.getDouble("lat"), first.getDouble("lon"), first.getString("display_name"))
                    binding.suggestionsCard.visibility = View.GONE
                    binding.etSearch.clearFocus()
                } else {
                    android.widget.Toast.makeText(context, "Không tìm thấy địa điểm", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun onPlaceSelected(lat: Double, lon: Double, name: String) {
        // Disable following when selecting a place to avoid camera jumping back to user
        isFollowing = false
        updateLocationButtonState()
        binding.btnRecenter.show()

        val point = GeoPoint(lat, lon)
        destinationMarker?.let { binding.mapView.overlays.remove(it) }
        destinationMarker = Marker(binding.mapView).apply {
            position = point
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            title = name
            icon = ContextCompat.getDrawable(requireContext(), R.drawable.ic_red_pin)
            showInfoWindow()
        }
        binding.mapView.overlays.add(destinationMarker)
        binding.mapView.invalidate()
        binding.mapView.controller.animateTo(point)

        binding.bottomSheet.tvPlaceName.text = name.split(",")[0]
        binding.bottomSheet.tvPlaceAddress.text = name
        binding.bottomSheet.layoutPlaceInfo.visibility = View.VISIBLE
        binding.bottomSheet.layoutRoutePreview.visibility = View.GONE
        binding.bottomSheet.layoutNavigation.visibility = View.GONE
        bottomSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
        
        binding.bottomSheet.btnStart.setOnClickListener { startNavigation(lat, lon) }
        binding.bottomSheet.btnDirections.setOnClickListener { showRoutePreview(lat, lon) }
        binding.bottomSheet.btnSavePlace?.setOnClickListener {
            showSavePlaceDialog(lat, lon, name)
        }
    }

    private fun showSavePlaceDialog(lat: Double, lon: Double, name: String) {
        val options = arrayOf("🏠 Lưu làm Nhà riêng", "🏢 Lưu làm Công ty", "⭐ Lưu vào Yêu thích")
        AlertDialog.Builder(requireContext())
            .setTitle("Lưu vào Địa điểm cá nhân")
            .setItems(options) { _, which ->
                val titlePart = name.split(",")[0].trim()
                val addressPart = name.trim()
                when (which) {
                    0 -> {
                        savedPlaceDbHelper.saveOrUpdateCategory(com.example.tymap.repository.SavedPlace.CATEGORY_HOME, "Nhà riêng", addressPart, lat, lon)
                        Toast.makeText(requireContext(), " Đã lưu Nhà riêng thành công!", Toast.LENGTH_SHORT).show()
                    }
                    1 -> {
                        savedPlaceDbHelper.saveOrUpdateCategory(com.example.tymap.repository.SavedPlace.CATEGORY_WORK, "Công ty", addressPart, lat, lon)
                        Toast.makeText(requireContext(), " Đã lưu Công ty thành công!", Toast.LENGTH_SHORT).show()
                    }
                    2 -> {
                        val input = EditText(requireContext()).apply {
                            setText(titlePart)
                            hint = "Nhập tên địa điểm"
                        }
                        AlertDialog.Builder(requireContext())
                            .setTitle("Tên địa điểm yêu thích")
                            .setView(input)
                            .setPositiveButton("Lưu") { _, _ ->
                                val customTitle = input.text.toString().trim().ifEmpty { titlePart }
                                savedPlaceDbHelper.insertPlace(
                                    com.example.tymap.repository.SavedPlace(
                                        title = customTitle,
                                        address = addressPart,
                                        lat = lat,
                                        lon = lon,
                                        category = com.example.tymap.repository.SavedPlace.CATEGORY_FAVORITE
                                    )
                                )
                                Toast.makeText(requireContext(), " Đã thêm '$customTitle' vào Yêu thích!", Toast.LENGTH_SHORT).show()
                            }
                            .setNegativeButton("Hủy", null)
                            .show()
                    }
                }
            }
            .setNegativeButton("Đóng", null)
            .show()
    }

    private fun showConfigureCategoryDialog(category: String) {
        val catName = if (category == "HOME") "Nhà riêng" else "Công ty"
        val currentLoc = NavigationRepository.gpsLocation.value
        val options = mutableListOf<String>()
        if (currentLoc != null) options.add("📍 Lưu vị trí GPS hiện tại làm $catName")
        options.add("📌 Chạm bản đồ để ghim $catName")

        AlertDialog.Builder(requireContext())
            .setTitle("Thiết lập $catName")
            .setItems(options.toTypedArray()) { _, which ->
                if (which == 0 && currentLoc != null) {
                    savedPlaceDbHelper.saveOrUpdateCategory(category, catName, "Vị trí đã lưu", currentLoc.latitude, currentLoc.longitude)
                    Toast.makeText(requireContext(), " Đã thiết lập $catName thành công!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), "Chạm vào bản đồ để chọn vị trí $catName", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    private fun setupButtons() {
        binding.fabLocation.setOnClickListener {
            if (!isFollowing) {
                // Nếu đang ở chế độ xem tự do -> Bật chế độ đi theo (North Up mặc định)
                isFollowing = true
                NavigationRepository.setTrackUpMode(false)
                binding.btnRecenter.hide()
            } else {
                // Nếu đang ở chế độ đi theo -> Chuyển đổi giữa North Up và Track Up
                val currentMode = NavigationRepository.isTrackUpMode.value
                NavigationRepository.setTrackUpMode(!currentMode)
            }
            
            updateLocationButtonState()
            
            NavigationRepository.gpsLocation.value?.let {
                val point = GeoPoint(it.latitude, it.longitude)
                val targetZoom = binding.mapView.zoomLevelDouble
                val isTrackUp = NavigationRepository.isTrackUpMode.value
                val targetRotation = if (isTrackUp) -it.bearing else 0f
                
                binding.mapView.controller.animateTo(point, targetZoom, 200L)
                animateMapRotation(binding.mapView.mapOrientation, targetRotation)
            }
        }

        binding.fabLayers.setOnClickListener {
            val sources = getTileSources()
            val sourceNames = sources.map { it.name() }
            val currentIdx = PrefsHelper.getInt(requireContext(), "tile_source", 0)
            val safeIdx = if (currentIdx >= sources.size) 0 else currentIdx
            
            AlertDialog.Builder(requireContext())
                .setTitle("Nguồn bản đồ")
                .setSingleChoiceItems(sourceNames.toTypedArray(), safeIdx) { dialog, which ->
                    PrefsHelper.putInt(requireContext(), "tile_source", which)
                    binding.mapView.setTileSource(sources[which])
                    
                    if (which == 5) {
                        val colorMatrix = android.graphics.ColorMatrix(floatArrayOf(
                            -1.0f, 0.0f, 0.0f, 0.0f, 255f,
                            0.0f, -1.0f, 0.0f, 0.0f, 255f,
                            0.0f, 0.0f, -1.0f, 0.0f, 255f,
                            0.0f, 0.0f, 0.0f, 1.0f, 0.0f
                        ))
                        binding.mapView.overlayManager.tilesOverlay.setColorFilter(android.graphics.ColorMatrixColorFilter(colorMatrix))
                    } else {
                        binding.mapView.overlayManager.tilesOverlay.setColorFilter(null)
                    }

                    Toast.makeText(requireContext(), sources[which].name(), Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                }
                .setNegativeButton("Hủy", null)
                .show()
        }

        binding.fabDisplayMode.setOnClickListener {
            val modes = arrayOf("Chế độ Bản đồ (MAP)", "Chế độ Dẫn đường (HUD)", "Thời gian & Trạng thái (STATUS)")
            val currentMode = when {
                NavigationRepository.mapModeState.value -> 0
                else -> 1 // Default show HUD when not in map
            }

            AlertDialog.Builder(requireContext())
                .setTitle("Chế độ hiển thị ESP32")
                .setSingleChoiceItems(modes, currentMode) { dialog, which ->
                    val activeService = com.example.tymap.service.NavigationService.activeInstance
                    if (activeService != null && activeService.bleManager.isConnected) {
                        when (which) {
                            0 -> {
                                activeService.bleManager.sendRemoteCommand(0x11.toByte())
                                NavigationRepository.setMapModeActive(true)
                                Toast.makeText(requireContext(), "Đã chuyển sang Bản đồ", Toast.LENGTH_SHORT).show()
                            }
                            1 -> {
                                activeService.bleManager.sendRemoteCommand(0x10.toByte())
                                NavigationRepository.setMapModeActive(false)
                                Toast.makeText(requireContext(), "Đã chuyển sang Dẫn đường HUD", Toast.LENGTH_SHORT).show()
                            }
                            2 -> {
                                activeService.bleManager.sendRemoteCommand(0x12.toByte())
                                NavigationRepository.setMapModeActive(false)
                                Toast.makeText(requireContext(), "Đã chuyển sang Trạng thái", Toast.LENGTH_SHORT).show()
                            }
                        }
                    } else {
                        Toast.makeText(requireContext(), "BLE chưa kết nối!", Toast.LENGTH_SHORT).show()
                    }
                    dialog.dismiss()
                }
                .setNegativeButton("Hủy", null)
                .show()
        }



        binding.fabZoomIn.setOnClickListener { binding.mapView.controller.zoomIn() }
        binding.fabZoomOut.setOnClickListener { binding.mapView.controller.zoomOut() }
        
        binding.btnCompass.setOnClickListener {
            binding.mapView.mapOrientation = 0f
            binding.btnCompass.visibility = View.GONE
        }

        binding.btnRecenter.setOnClickListener {
            isFollowing = true
            binding.btnRecenter.hide()
            updateLocationButtonState()
            val context = requireContext()
            NavigationRepository.gpsLocation.value?.let {
                val point = GeoPoint(it.latitude, it.longitude)
                binding.mapView.controller.animateTo(point, PrefsHelper.getFloat(context, "default_zoom", 15f).toDouble(), 200L)
                if (NavigationRepository.isTrackUpMode.value) binding.mapView.mapOrientation = -it.bearing
            }
            bottomSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
        }
    }

    private var trafficMarkers = mutableListOf<Marker>()

    private fun scanTrafficWarningsAroundCenter() {
        val center = binding.mapView.mapCenter as GeoPoint
        Toast.makeText(requireContext(), "📡 Đang quét Camera phạt nguội & Biển báo quanh đây...", Toast.LENGTH_SHORT).show()
        val context = requireContext()
        
        lifecycleScope.launch(Dispatchers.IO) {
            val query = "[out:json][timeout:10];(node[\"highway\"=\"speed_camera\"](around:8000,${center.latitude},${center.longitude});node[\"maxspeed\"](around:8000,${center.latitude},${center.longitude}););out body;"
            val endpoints = mutableListOf<String>()

            if (NasConnectionManager.isNasPotentiallyAvailable(context)) {
                val fusionBaseUrl = NasConnectionManager.getFusionEngineBaseUrl(context)
                endpoints.add("$fusionBaseUrl/api/interpreter")
            }

            endpoints.add("https://overpass-api.de/api/interpreter")
            endpoints.add("https://overpass.kumi.systems/api/interpreter")
            endpoints.add("https://maps.mail.ru/osm/tools/overpass/api/interpreter")
            
            var success = false
            for (ep in endpoints) {
                try {
                    val isNas = NasConnectionManager.isNasEndpoint(ep)
                    val callClient = if (isNas) NasConnectionManager.nasHttpClient else NasConnectionManager.publicHttpClient
                    val formBody = okhttp3.FormBody.Builder().add("data", query).build()
                    val request = Request.Builder().url(ep).post(formBody).build()
                    callClient.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            val body = response.body?.string() ?: return@use
                            val json = JSONObject(body)
                            val elements = json.optJSONArray("elements") ?: JSONArray()
                            
                            withContext(Dispatchers.Main) {
                                trafficMarkers.forEach { binding.mapView.overlays.remove(it) }
                                trafficMarkers.clear()
                                
                                var camCount = 0
                                var speedCount = 0
                                
                                for (i in 0 until elements.length()) {
                                    val node = elements.getJSONObject(i)
                                    val lat = node.getDouble("lat")
                                    val lon = node.getDouble("lon")
                                    val tags = node.optJSONObject("tags") ?: JSONObject()
                                    val isCam = tags.optString("highway") == "speed_camera" || tags.optString("traffic_signals") == "camera"
                                    val maxspeed = tags.optString("maxspeed")
                                    
                                    val marker = Marker(binding.mapView).apply {
                                        position = GeoPoint(lat, lon)
                                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                                        if (isCam) {
                                            camCount++
                                            title = "📷 Camera Phạt Nguội"
                                            snippet = "Tọa độ: $lat, $lon"
                                            icon = ContextCompat.getDrawable(requireContext(), R.drawable.ic_speedometer)
                                        } else if (maxspeed.isNotEmpty()) {
                                            speedCount++
                                            title = "🛑 Biển Tốc Độ: $maxspeed km/h"
                                            snippet = "Tọa độ: $lat, $lon"
                                            icon = ContextCompat.getDrawable(requireContext(), R.drawable.ic_speedometer)
                                        }
                                    }
                                    binding.mapView.overlays.add(marker)
                                    trafficMarkers.add(marker)
                                }
                                binding.mapView.invalidate()
                                Toast.makeText(requireContext(), "📡 Tìm thấy ${elements.length()} điểm cảnh báo (📷 $camCount Cam, 🛑 $speedCount Biển)!", Toast.LENGTH_LONG).show()
                            }
                            if (isNas) {
                                NasConnectionManager.markNasSuccess()
                            }
                            success = true
                            return@launch
                        }
                    }
                } catch (e: Exception) {
                    if (NasConnectionManager.isNasEndpoint(ep) && NasConnectionManager.isConnectionFailure(e)) {
                        NasConnectionManager.markNasFailed(e.message)
                    }
                }
            }
            if (!success) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "Không thể kết nối máy chủ Overpass cảnh báo", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }



    private fun startGoogleMapsCapture() {
        val mpm = requireContext().getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenCaptureLauncher.launch(mpm.createScreenCaptureIntent())
    }

    private fun updateLocationButtonState() {
        val context = requireContext()
        val isTrackUp = NavigationRepository.isTrackUpMode.value
        if (!isFollowing) {
            binding.fabLocation.imageTintList = android.content.res.ColorStateList.valueOf(Color.BLACK)
            binding.fabLocation.setImageResource(R.drawable.ic_my_location)
        } else {
            binding.fabLocation.imageTintList = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(context, R.color.blue_primary))
            if (NavigationRepository.isTrackUpMode.value) {
                // Chế độ Track Up: Icon mũi tên định hướng
                binding.fabLocation.setImageResource(R.drawable.ic_navigation_arrow) 
            } else {
                // Chế độ North Up: Icon vị trí tiêu chuẩn
                binding.fabLocation.setImageResource(R.drawable.ic_my_location)
            }
        }
    }

    private var mapRotationAnimator: android.animation.ValueAnimator? = null
    private var markerPositionAnimator: android.animation.ValueAnimator? = null
    
    private fun animateMapRotation(currentRotation: Float, targetRotation: Float) {
        mapRotationAnimator?.cancel()
        
        // Normalize rotation diff to shortest path
        var start = currentRotation
        var end = targetRotation
        var diff = end - start
        while (diff < -180f) diff += 360f
        while (diff > 180f) diff -= 360f
        end = start + diff

        mapRotationAnimator = android.animation.ValueAnimator.ofFloat(start, end).apply {
            duration = 200
            addUpdateListener { animator ->
                binding.mapView.mapOrientation = animator.animatedValue as Float
            }
            start()
        }
    }

    private fun observeNavigationData() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                NavigationRepository.gpsLocation.collect { location ->
                    if (location == null) return@collect
                    
                    val now = System.currentTimeMillis()
                    var animDuration = 1000L
                    if (lastLocationTime > 0) {
                        val diff = now - lastLocationTime
                        if (diff in 200..3000) {
                            animDuration = diff
                        }
                    }
                    lastLocationTime = now

                    // Low-pass filter (Exponential Moving Average) to eliminate GPS noise/jitter
                    val alpha = 0.45 
                    val rawLat = location.latitude
                    val rawLon = location.longitude
                    
                    if (filteredLat == 0.0 || filteredLon == 0.0 || isFirstLocation) {
                        filteredLat = rawLat
                        filteredLon = rawLon
                    } else {
                        filteredLat = filteredLat + alpha * (rawLat - filteredLat)
                        filteredLon = filteredLon + alpha * (rawLon - filteredLon)
                    }
                    
                    val point = GeoPoint(filteredLat, filteredLon)
                    
                    // 1. Cập nhật vị trí dấu chấm xanh (và camera bản đồ) bằng animation mượt tự động co giãn thời gian
                    animateMarkerPosition(point, animDuration)
                    
                    // 2. Xử lý xoay bản đồ và Marker theo chế độ
                    val speed = location.speed
                    val isMoving = speed > 1.5f && location.hasBearing()
                    
                    if (isFollowing) {
                        val isTrackUp = NavigationRepository.isTrackUpMode.value
                        if (isTrackUp) {
                            // Chế độ Track Up: Bản đồ xoay ngược trackUpHeading => hướng đi lên 12h.
                            // Flat marker rotation = -trackUpHeading => nón chỉ thẳng đứng (12h).
                            val trackUpHeading = if (isMoving) location.bearing else lastHeading
                            animateMapRotation(binding.mapView.mapOrientation, -trackUpHeading)
                            animateMarkerRotation((-trackUpHeading + 360f) % 360f)
                        } else {
                            // Chế độ North Up: Bản đồ hướng Bắc (0f), Marker xoay theo northUpHeading.
                            // (osmdroid vẽ flat marker: total = mapOrientation - marker.rotation
                            //  nên set marker.rotation = -heading để nón chỉ đúng hướng thực tế)
                            val northUpHeading = (-(if (isMoving) location.bearing else lastHeading) + 360f) % 360f
                            animateMapRotation(binding.mapView.mapOrientation, 0f)
                            animateMarkerRotation(northUpHeading)
                        }
                    } else {
                        // Không theo dõi (đã xoay/pan tay): flat marker tự xoay theo bản đồ
                        val northUpHeading = (-(if (isMoving) location.bearing else lastHeading) + 360f) % 360f
                        animateMarkerRotation(northUpHeading)
                    }
                    
                    if (isFirstLocation) {
                        val zoom = PrefsHelper.getFloat(requireContext(), "default_zoom", 15f).toDouble()
                        binding.mapView.controller.setZoom(zoom)
                        isFirstLocation = false
                    }
                    
                    val speedKmh = (location.speed * 3.6f).toInt().coerceAtLeast(0)
                    binding.tvGpsSpeedValue.text = "$speedKmh"

                    val isSpeedWarningEnabled = PrefsHelper.getBoolean(requireContext(), "speed_warning", true)
                    val speedLimit = PrefsHelper.getInt(requireContext(), "speed_threshold", 60)
                    
                    if (isSpeedWarningEnabled && speedLimit > 0) {
                        binding.cardSpeedLimitSign.visibility = View.VISIBLE
                        binding.tvSpeedLimitSignValue.text = "$speedLimit"

                        when {
                            speedKmh > speedLimit -> { // Trạng thái CẢNH BÁO QUÁ TỐC ĐỘ (Đỏ Rực)
                                binding.cardSpeedLimitSign.setStrokeColor(android.graphics.Color.parseColor("#DC2626"))
                                binding.cardSpeedLimitSign.setCardBackgroundColor(android.graphics.Color.parseColor("#FEF2F2"))
                                binding.ivSpeedWarning.visibility = View.VISIBLE
                            }
                            speedKmh >= speedLimit - 5 -> { // Trạng thái CHÚ Ý GẦN GIỚI HẠN (Vàng Cam)
                                binding.cardSpeedLimitSign.setStrokeColor(android.graphics.Color.parseColor("#F59E0B"))
                                binding.cardSpeedLimitSign.setCardBackgroundColor(android.graphics.Color.parseColor("#FFFFFF"))
                                binding.ivSpeedWarning.visibility = View.GONE
                            }
                            else -> { // Trạng thái AN TOÀN (Viền Đỏ Nền Trắng Chuẩn)
                                binding.cardSpeedLimitSign.setStrokeColor(android.graphics.Color.parseColor("#EF4444"))
                                binding.cardSpeedLimitSign.setCardBackgroundColor(android.graphics.Color.parseColor("#FFFFFF"))
                                binding.ivSpeedWarning.visibility = View.GONE
                            }
                        }
                    } else {
                        binding.cardSpeedLimitSign.visibility = View.GONE
                        binding.ivSpeedWarning.visibility = View.GONE
                    }
                    
                    // Thực hiện vẽ lại tức thì để đồng bộ hoá mượt mà
                    binding.mapView.invalidate()
                }
            }
        }

        // Quan sát biến tốc độ GPS để cập nhật đồng hồ tốc độ trên Tab Map
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                NavigationRepository.currentSpeedKmh.collect { speedKmh ->
                    binding.tvGpsSpeedValue.text = "$speedKmh"
                }
            }
        }

        // Quan sát giới hạn tốc độ từ Overpass API / Settings
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                NavigationRepository.currentSpeedLimit.collect { speedLimit ->
                    val isSpeedWarningEnabled = PrefsHelper.getBoolean(requireContext(), "speed_warning", true)
                    if (isSpeedWarningEnabled && speedLimit > 0) {
                        binding.cardSpeedLimitSign.visibility = View.VISIBLE
                        binding.tvSpeedLimitSignValue.text = "$speedLimit"
                    }
                }
            }
        }

        // Quan sát các điểm cảnh báo (Camera phạt nguội, Biển tốc độ) từ Overpass API để vẽ lên bản đồ
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                NavigationRepository.trafficWarningPoints.collect { points ->
                    trafficMarkers.forEach { binding.mapView.overlays.remove(it) }
                    trafficMarkers.clear()

                    for (point in points) {
                        val isCam = point.type == com.example.tymap.service.WarningType.CAMERA
                        val marker = Marker(binding.mapView).apply {
                            position = GeoPoint(point.lat, point.lon)
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                            if (isCam) {
                                title = "📷 Camera Phạt Nguội"
                                snippet = "Tọa độ: ${point.lat}, ${point.lon}"
                                icon = ContextCompat.getDrawable(requireContext(), R.drawable.ic_speedometer)
                            } else {
                                title = "🛑 Biển Giới Hạn: ${point.speedLimit} km/h"
                                snippet = "Tọa độ: ${point.lat}, ${point.lon}"
                                icon = ContextCompat.getDrawable(requireContext(), R.drawable.ic_speedometer)
                            }
                        }
                        binding.mapView.overlays.add(marker)
                        trafficMarkers.add(marker)
                    }
                    if (points.isNotEmpty()) {
                        binding.mapView.invalidate()
                    }
                }
            }
        }

        // Quan sát cảnh báo giao thông trực tiếp khi đến gần (< 200m)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                NavigationRepository.activeTrafficAlert.collect { alert ->
                    if (alert != null) {
                        Toast.makeText(requireContext(), "🚨 ${alert.message}", Toast.LENGTH_SHORT).show()
                        binding.ivSpeedWarning.visibility = View.VISIBLE
                    }
                }
            }
        }

        // Quan sát danh sách lộ trình để tự động vẽ lại Polyline và cập nhật giao diện chọn tuyến đường
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                NavigationRepository.routes.collect { routes ->
                    triggerDrawRoutes(routes)
                    routeAlternativeAdapter.submitList(routes)
                    val activeRoute = routes.firstOrNull { it.isSelected } ?: routes.firstOrNull()
                    if (activeRoute != null) {
                        routeStepsAdapter.submitList(activeRoute.steps)
                    } else {
                        routeStepsAdapter.submitList(emptyList())
                    }
                }
            }
        }

        lifecycleScope.launch {
            NavigationRepository.navigationState.collect { running ->
                if (running) {
                    isFollowing = true
                    NavigationRepository.setTrackUpMode(true)
                    binding.btnRecenter.hide()
                    updateLocationButtonState()
                    binding.bottomSheet.layoutPlaceInfo.visibility = View.GONE
                    binding.bottomSheet.layoutRoutePreview.visibility = View.GONE
                    binding.bottomSheet.layoutNavigation.visibility = View.VISIBLE
                    bottomSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
                    NavigationRepository.gpsLocation.value?.let {
                        binding.mapView.controller.setZoom(PrefsHelper.getFloat(requireContext(), "default_zoom", 15f).toDouble())
                        binding.mapView.controller.animateTo(GeoPoint(it.latitude, it.longitude))
                        animateMapRotation(binding.mapView.mapOrientation, -it.bearing)
                        animateMarkerRotation((-it.bearing + 360f) % 360f)
                    }
                } else {
                    binding.bottomSheet.layoutNavigation.visibility = View.GONE
                    bottomSheetBehavior.state = BottomSheetBehavior.STATE_HIDDEN
                    binding.mapView.mapOrientation = 0f 
                    updateLocationButtonState()
                }
            }
        }

        lifecycleScope.launch {
            NavigationRepository.hudPreviewData.collectLatest { hud ->
                if (hud != null) {
                    binding.bottomSheet.tvNavInstruction.text = if (hud.directions.isNotEmpty()) "${hud.title} - ${hud.directions}" else hud.title
                    binding.bottomSheet.tvNavDistance.text = hud.distance
                    binding.bottomSheet.tvNavDistance.setTypeface(null, Typeface.BOLD)
                    binding.bottomSheet.tvNavEta.text = getString(R.string.eta_format, hud.eta, hud.duration)
                    if (hud.bitmapIcon != null) {
                        binding.bottomSheet.ivNavIcon.setImageBitmap(hud.bitmapIcon)
                    } else {
                        binding.bottomSheet.ivNavIcon.setImageResource(getManeuverIconRes(hud.iconIndex))
                    }

                    // Đồng bộ thông tin Header của danh sách ngã rẽ chi tiết
                    if (binding.layoutRouteSteps.visibility == View.VISIBLE) {
                        binding.tvStepsDuration.text = hud.duration
                        binding.tvStepsSummary.text = if (hud.eta.isNotEmpty()) "${hud.distance} • ${hud.eta}" else hud.distance
                    }
                } else {
                    binding.layoutRouteSteps.visibility = View.GONE
                }
            }
        }

        // Quan sát cờ chọn vùng bản đồ offline
        lifecycleScope.launch {
            NavigationRepository.isOfflineSelectionMode.collect { selectionMode ->
                handleOfflineSelectionMode(selectionMode)
            }
        }
    }

    private fun observeRemoteCommands() {
        lifecycleScope.launch {
            NavigationRepository.remoteZoomCommand.collect { zoomIn ->
                if (zoomIn) binding.mapView.controller.zoomIn() else binding.mapView.controller.zoomOut()
            }
        }
    }

    private var routeJob: Job? = null

    private fun showRoutePreview(lat: Double, lon: Double) {
        // Disable following to focus on route preview
        isFollowing = false
        updateLocationButtonState()
        binding.btnRecenter.show()

        val startLoc = NavigationRepository.gpsLocation.value ?: return
        val context = requireContext()
        
        routeJob?.cancel()
        routeJob = lifecycleScope.launch(Dispatchers.IO) {
            // Progressive Fast Loading: Khi GraphHopper trả về kết quả trong 15-30ms, vẽ UI ngay lập tức!
            val fetchedRoutes = routingEngine.fetchOsrmAndValhalla(context, startLoc.latitude, startLoc.longitude, lat, lon) { progressiveRoutes ->
                if (progressiveRoutes.isNotEmpty()) {
                    lifecycleScope.launch(Dispatchers.Main) {
                        renderRoutesToUi(progressiveRoutes, lat, lon, context)
                    }
                }
            }
            withContext(Dispatchers.Main) {
                if (!fetchedRoutes.isNullOrEmpty()) {
                    renderRoutesToUi(fetchedRoutes, lat, lon, context)
                } else if (NavigationRepository.routes.value.isEmpty()) {
                    Toast.makeText(requireContext(), "Không tìm thấy tuyến đường", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun renderRoutesToUi(routes: List<com.example.tymap.repository.RouteInfo>, lat: Double, lon: Double, context: Context) {
        if (routes.isEmpty()) return
        
        NavigationRepository.updateRoutes(routes)
        routeAlternativeAdapter.submitList(routes)

        val selectedRoute = routes.firstOrNull { it.isSelected } ?: routes.first()
        val durationMin = Math.round(selectedRoute.duration / 60.0)
        val distKm = String.format(java.util.Locale.US, "%.1f", selectedRoute.distance / 1000.0)
        
        binding.bottomSheet.tvMainDurationDistance.text = "$durationMin phút ($distKm km)"
        binding.bottomSheet.tvTabScooterText.text = "$durationMin phút"
        binding.bottomSheet.tvTabCarText.text = "${Math.round(durationMin * 1.1)} phút"
        binding.bottomSheet.tvRouteDescription.text = "Tuyến đường tối ưu (${selectedRoute.engineName})"

        // Xử lý chuyển đổi chế độ xem Xe máy / Ô tô trực tiếp từ Tab bar Google Maps
        val currentVehicle = PrefsHelper.getInt(context, "vehicle_type", 1)
        updateVehicleTabSelection(currentVehicle)

        binding.bottomSheet.tabScooter.setOnClickListener {
            PrefsHelper.putInt(context, "vehicle_type", 1) // 1: Xe máy (scooter)
            updateVehicleTabSelection(1)
            showRoutePreview(lat, lon)
        }

        binding.bottomSheet.tabCar.setOnClickListener {
            PrefsHelper.putInt(context, "vehicle_type", 0) // 0: Ô tô (car)
            updateVehicleTabSelection(0)
            showRoutePreview(lat, lon)
        }

        binding.bottomSheet.tabRoutes.setOnClickListener {
            val isVisible = binding.bottomSheet.rvAlternatives.visibility == View.VISIBLE
            binding.bottomSheet.rvAlternatives.visibility = if (isVisible) View.GONE else View.VISIBLE
            val cyan = ContextCompat.getColor(requireContext(), R.color.colorAccentCyan)
            val gray = Color.parseColor("#94A3B8")
            binding.bottomSheet.tabRoutes.setBackgroundResource(if (!isVisible) R.drawable.bg_tab_selected else R.drawable.bg_tab_unselected)
            binding.bottomSheet.tvTabRoutesText.setTextColor(if (!isVisible) cyan else gray)
            binding.bottomSheet.ivTabRoutesIcon.imageTintList = android.content.res.ColorStateList.valueOf(if (!isVisible) cyan else gray)
        }

        // Cấu hình Checkbox Né trạm thu phí / Né phà
        val avoidTolls = PrefsHelper.getBoolean(context, "avoid_tolls", false)
        val avoidFerries = PrefsHelper.getBoolean(context, "avoid_ferries", false)
        binding.bottomSheet.cbAvoidTolls.setOnCheckedChangeListener(null)
        binding.bottomSheet.cbAvoidFerries.setOnCheckedChangeListener(null)
        binding.bottomSheet.cbAvoidTolls.isChecked = avoidTolls
        binding.bottomSheet.cbAvoidFerries.isChecked = avoidFerries
        binding.bottomSheet.cbAvoidTolls.setOnCheckedChangeListener { _, isChecked ->
            PrefsHelper.putBoolean(context, "avoid_tolls", isChecked)
            showRoutePreview(lat, lon)
        }
        binding.bottomSheet.cbAvoidFerries.setOnCheckedChangeListener { _, isChecked ->
            PrefsHelper.putBoolean(context, "avoid_ferries", isChecked)
            showRoutePreview(lat, lon)
        }

        // Tải dự báo thời tiết lúc đến nơi (ETA Weather)
        if (selectedRoute.polyline.isNotEmpty()) {
            val endPoint = selectedRoute.polyline.last()
            fetchWeatherEtaForRoute(endPoint.first, endPoint.second, selectedRoute.duration)
        }

        if (selectedRoute.polyline.isNotEmpty()) {
            val boundingBox = org.osmdroid.util.BoundingBox.fromGeoPoints(selectedRoute.polyline.map { GeoPoint(it.first, it.second) })
            binding.mapView.zoomToBoundingBox(boundingBox, true, 150)
        }
        binding.bottomSheet.layoutPlaceInfo.visibility = View.GONE
        binding.bottomSheet.layoutRoutePreview.visibility = View.VISIBLE
        binding.bottomSheet.btnStartFromPreview.setOnClickListener { startNavigation(lat, lon) }
        binding.bottomSheet.btnClosePreview.setOnClickListener {
            clearDestination()
        }
        bottomSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
    }

    /**
     * Giả lập chạy thử tuyến đường (Route Simulation):
     * Tự động phát tọa độ GPS ảo di chuyển dọc polyline để test Turn-by-Turn và BLE HUD trên ESP32
     */
    private fun startSimulation(destLat: Double, destLon: Double) {
        val activeRoute = NavigationRepository.routes.value.firstOrNull { it.isSelected }
            ?: NavigationRepository.routes.value.firstOrNull()
        if (activeRoute == null || activeRoute.polyline.size < 2) {
            Toast.makeText(requireContext(), "Chưa có dữ liệu tuyến đường để chạy thử", Toast.LENGTH_SHORT).show()
            return
        }

        // Bắt đầu chế độ dẫn đường
        startNavigation(destLat, destLon)
        isSimulating = true
        Toast.makeText(requireContext(), "🚗 Bắt đầu giả lập chạy thử tuyến đường!", Toast.LENGTH_SHORT).show()

        simulationJob?.cancel()
        simulationJob = lifecycleScope.launch(Dispatchers.Default) {
            val polyline = activeRoute.polyline
            var currentIdx = 0

            while (isActive && isSimulating && currentIdx < polyline.size) {
                val pt = polyline[currentIdx]
                val nextPt = if (currentIdx < polyline.size - 1) polyline[currentIdx + 1] else pt
                
                // Tính góc hướng di chuyển (Bearing)
                val dLon = Math.toRadians(nextPt.second - pt.second)
                val lat1 = Math.toRadians(pt.first)
                val lat2 = Math.toRadians(nextPt.first)
                val y = Math.sin(dLon) * Math.cos(lat2)
                val x = Math.cos(lat1) * Math.sin(lat2) - Math.sin(lat1) * Math.cos(lat2) * Math.cos(dLon)
                val bearingDeg = ((Math.toDegrees(Math.atan2(y, x)) + 360.0) % 360.0).toFloat()

                val mockLocation = android.location.Location("SimulationGps").apply {
                    latitude = pt.first
                    longitude = pt.second
                    bearing = bearingDeg
                    speed = 12.5f // ~45 km/h
                    accuracy = 2.0f
                    time = System.currentTimeMillis()
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.JELLY_BEAN_MR1) {
                        elapsedRealtimeNanos = android.os.SystemClock.elapsedRealtimeNanos()
                    }
                }

                withContext(Dispatchers.Main) {
                    NavigationRepository.updateLocation(mockLocation)
                }

                currentIdx++
                kotlinx.coroutines.delay(400) // 400ms mỗi bước nhảy GPS
            }

            withContext(Dispatchers.Main) {
                isSimulating = false
                Toast.makeText(requireContext(), "🎉 Đã hoàn thành chạy thử tuyến đường!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun stopSimulation() {
        if (isSimulating) {
            isSimulating = false
            simulationJob?.cancel()
            simulationJob = null
        }
    }

    private fun updateVehicleTabSelection(vehicleType: Int) {
        val isScooter = vehicleType == 1
        binding.bottomSheet.tabScooter.setBackgroundResource(if (isScooter) R.drawable.bg_tab_selected else R.drawable.bg_tab_unselected)
        binding.bottomSheet.tvTabScooterText.setTextColor(if (isScooter) ContextCompat.getColor(requireContext(), R.color.colorAccentCyan) else Color.parseColor("#94A3B8"))
        binding.bottomSheet.ivTabScooterIcon.imageTintList = android.content.res.ColorStateList.valueOf(if (isScooter) ContextCompat.getColor(requireContext(), R.color.colorAccentCyan) else Color.parseColor("#94A3B8"))

        val isCar = vehicleType == 0
        binding.bottomSheet.tabCar.setBackgroundResource(if (isCar) R.drawable.bg_tab_selected else R.drawable.bg_tab_unselected)
        binding.bottomSheet.tvTabCarText.setTextColor(if (isCar) ContextCompat.getColor(requireContext(), R.color.colorAccentCyan) else Color.parseColor("#94A3B8"))
        binding.bottomSheet.ivTabCarIcon.imageTintList = android.content.res.ColorStateList.valueOf(if (isCar) ContextCompat.getColor(requireContext(), R.color.colorAccentCyan) else Color.parseColor("#94A3B8"))
    }

    private fun getManeuverIconRes(iconIndex: Int): Int = when (iconIndex) {
        1 -> R.drawable.ic_nav_slight_right
        2 -> R.drawable.ic_nav_turn_right
        3 -> R.drawable.ic_nav_sharp_right
        4 -> R.drawable.ic_nav_slight_left
        5 -> R.drawable.ic_nav_turn_left
        6 -> R.drawable.ic_nav_sharp_left
        7, 8 -> R.drawable.ic_nav_uturn
        11, 12, 13 -> R.drawable.ic_nav_roundabout
        14 -> R.drawable.ic_nav_arrive
        else -> R.drawable.ic_nav_straight
    }

    private fun getEngineName(index: Int) = when(index) { 1 -> "OpenRouteService"; 2 -> "GraphHopper"; 3 -> "Valhalla"; 4 -> "Tùy chỉnh (Self-Hosted OSRM)"; else -> "OSRM" }

    private fun selectRoute(index: Int) {
        val currentRoutes = NavigationRepository.routes.value
        val updatedRoutes = currentRoutes.mapIndexed { i, route -> route.copy(isSelected = i == index) }
        NavigationRepository.updateRoutes(updatedRoutes)

        val selectedRoute = updatedRoutes.getOrNull(index) ?: return
        val durationMin = Math.round(selectedRoute.duration / 60.0)
        val distKm = String.format(java.util.Locale.US, "%.1f", selectedRoute.distance / 1000.0)

        binding.bottomSheet.tvMainDurationDistance.text = "$durationMin phút ($distKm km)"
        binding.bottomSheet.tvRouteDescription.text = if (selectedRoute.engineName.contains("NAS") || selectedRoute.engineName.contains("GraphHopper")) {
            "Tuyến đường tốt nhất từ Server NAS nhà (192.168.1.114:8989)"
        } else {
            "Tuyến đường dự phòng từ ${selectedRoute.engineName}"
        }

        if (selectedRoute.polyline.isNotEmpty()) {
            val endPoint = selectedRoute.polyline.last()
            fetchWeatherEtaForRoute(endPoint.first, endPoint.second, selectedRoute.duration)
        }
    }

    private fun fetchWeatherEtaForRoute(destLat: Double, destLng: Double, durationSeconds: Double) {
        val startLoc = NavigationRepository.gpsLocation.value
        val startLat = startLoc?.latitude ?: destLat
        val startLng = startLoc?.longitude ?: destLng

        val selectedRoute = NavigationRepository.routes.value.firstOrNull { it.isSelected }
            ?: NavigationRepository.routes.value.firstOrNull()
        val firstStepText = selectedRoute?.steps?.firstOrNull()?.instruction ?: "Tiếp tục"

        binding.bottomSheet.tvWeatherFirstStep.text = "📍 Bước 1: $firstStepText"

        lifecycleScope.launch(Dispatchers.IO) {
            val weather = com.example.tymap.service.WeatherEtaService.checkFullRouteWeather(startLat, startLng, destLat, destLng, durationSeconds)
            withContext(Dispatchers.Main) {
                if (weather != null) {
                    binding.bottomSheet.cardWeatherEta.visibility = View.VISIBLE
                    binding.bottomSheet.tvWeatherEtaBadge.text = "ETA: ${weather.etaTimeStr} (+${weather.travelMinutes}m)"

                    // Mốc 1: Xuất phát
                    val sw = weather.startPointWeather
                    binding.bottomSheet.tvWeatherStartLabel.text = "🚩 Hiện tại xuất phát (${sw.timeLabel}): ${sw.icon} ${sw.tempC}°C"
                    binding.bottomSheet.tvWeatherStartStatus.text = sw.status
                    binding.bottomSheet.tvWeatherStartStatus.backgroundTintList = android.content.res.ColorStateList.valueOf(
                        if (sw.isRainAlert) Color.parseColor("#991B1B") else Color.parseColor("#064E3B")
                    )
                    binding.bottomSheet.tvWeatherStartStatus.setTextColor(
                        if (sw.isRainAlert) Color.parseColor("#FCA5A5") else Color.parseColor("#34D399")
                    )

                    // Mốc 2: Đến nơi (ETA)
                    val ew = weather.etaPointWeather
                    binding.bottomSheet.tvWeatherEtaLabel.text = "🏁 Khi đến nơi (${ew.timeLabel}): ${ew.icon} ${ew.tempC}°C"
                    binding.bottomSheet.tvWeatherEtaStatus.text = ew.status
                    binding.bottomSheet.tvWeatherEtaStatus.backgroundTintList = android.content.res.ColorStateList.valueOf(
                        if (ew.isRainAlert) Color.parseColor("#991B1B") else Color.parseColor("#064E3B")
                    )
                    binding.bottomSheet.tvWeatherEtaStatus.setTextColor(
                        if (ew.isRainAlert) Color.parseColor("#FCA5A5") else Color.parseColor("#34D399")
                    )

                    // Mốc 3: 1 Giờ sau khi đến nơi
                    val e1w = weather.etaPlus1hPointWeather
                    binding.bottomSheet.tvWeatherEtaPlus1Label.text = "⏳ 1 Giờ sau đó (${e1w.timeLabel}): ${e1w.icon} ${e1w.tempC}°C"
                    binding.bottomSheet.tvWeatherEtaPlus1Status.text = e1w.status
                    binding.bottomSheet.tvWeatherEtaPlus1Status.backgroundTintList = android.content.res.ColorStateList.valueOf(
                        if (e1w.isRainAlert) Color.parseColor("#991B1B") else Color.parseColor("#064E3B")
                    )
                    binding.bottomSheet.tvWeatherEtaPlus1Status.setTextColor(
                        if (e1w.isRainAlert) Color.parseColor("#FCA5A5") else Color.parseColor("#34D399")
                    )
                } else {
                    binding.bottomSheet.cardWeatherEta.visibility = View.GONE
                }
            }
        }
    }

    private fun triggerDrawRoutes(routes: List<com.example.tymap.repository.RouteInfo>) {
        drawRoutesJob?.cancel()
        // Clear old polylines immediately on the main thread so they disappear instantly and don't linger
        val toRemove = binding.mapView.overlays.filterIsInstance<Polyline>()
        binding.mapView.overlays.removeAll(toRemove)
        routePolylines.clear()
        binding.mapView.postInvalidateDelayed(50)

        drawRoutesJob = lifecycleScope.launch {
            drawRoutes(routes)
        }
    }

    private suspend fun drawRoutes(routes: List<com.example.tymap.repository.RouteInfo>) {
        val context = context ?: return
        val zoom = binding.mapView.zoomLevelDouble
        val tolerance = 3.0 * (360.0 / (256.0 * Math.pow(2.0, zoom)))

        // Simplify polylines on Dispatchers.Default
        val simplifiedRoutes = withContext(Dispatchers.Default) {
            routes.map { route ->
                val simplifiedPoints = com.example.tymap.utils.PolylineDecoder.simplify(route.polyline, tolerance)
                route to simplifiedPoints
            }
        }

        withContext(Dispatchers.Main) {
            // Remove existing polylines
            val toRemove = binding.mapView.overlays.filterIsInstance<Polyline>()
            binding.mapView.overlays.removeAll(toRemove)
            routePolylines.clear()

            // Separate selected and unselected to control Z-index
            val unselected = simplifiedRoutes.filter { !it.first.isSelected }
            val selected = simplifiedRoutes.filter { it.first.isSelected }

            // Add unselected first (bottom layer)
            (unselected + selected).forEach { (route, points) ->
                if (points.size >= 2) {
                    val polyline = Polyline(binding.mapView).apply {
                        outlinePaint.isAntiAlias = true
                        // Selected: Blue, Unselected: Grey/Semi-transparent
                        outlinePaint.color = if (route.isSelected) Color.parseColor("#007AFF") else Color.parseColor("#8E8E93")
                        outlinePaint.strokeWidth = if (route.isSelected) 18f else 12f
                        outlinePaint.alpha = if (route.isSelected) 255 else 180
                        outlinePaint.strokeCap = Paint.Cap.ROUND
                        outlinePaint.strokeJoin = Paint.Join.ROUND
                        setPoints(points.map { GeoPoint(it.first, it.second) })
                        setOnClickListener { _, _, _ -> selectRoute(routes.indexOf(route)); true }
                    }
                    routePolylines.add(polyline)
                    // Insert polylines at index 1 (above Events but below markers/controls)
                    if (binding.mapView.overlays.size > 1) {
                        binding.mapView.overlays.add(1, polyline)
                    } else {
                        binding.mapView.overlays.add(polyline)
                    }
                }
            }

            // Ensure markers and UI controls are always on top of polylines
            userMarker?.let { 
                binding.mapView.overlays.remove(it)
                binding.mapView.overlays.add(it)
            }
            destinationMarker?.let {
                binding.mapView.overlays.remove(it)
                binding.mapView.overlays.add(it)
            }
            compassOverlay?.let {
                binding.mapView.overlays.remove(it)
                binding.mapView.overlays.add(it)
            }
            binding.mapView.overlays.filterIsInstance<RotationGestureOverlay>().firstOrNull()?.let {
                binding.mapView.overlays.remove(it)
                binding.mapView.overlays.add(it)
            }

            binding.mapView.postInvalidateDelayed(50)
        }
    }

    private var markerRotationAnimator: android.animation.ValueAnimator? = null

    private fun animateMarkerRotation(targetRotation: Float) {
        val marker = userMarker ?: return
        markerRotationAnimator?.cancel()
        val startRotation = marker.rotation
        var diff = targetRotation - startRotation
        while (diff < -180f) diff += 360f
        while (diff > 180f) diff -= 360f
        val finalTargetRotation = startRotation + diff

        markerRotationAnimator = android.animation.ValueAnimator.ofFloat(startRotation, finalTargetRotation).apply {
            duration = 300
            addUpdateListener { animator ->
                val value = animator.animatedValue as Float
                marker.rotation = value
                binding.mapView.invalidate()
            }
            start()
        }
    }

    private fun animateMarkerPosition(targetPoint: GeoPoint, durationMs: Long) {
        val marker = userMarker ?: return
        markerPositionAnimator?.cancel()

        val startPoint = marker.position
        if (startPoint == null || isFirstLocation) {
            marker.position = targetPoint
            if (isFollowing) {
                binding.mapView.controller.setCenter(targetPoint)
            }
            binding.mapView.postInvalidateDelayed(50)
            return
        }

        val startLat = startPoint.latitude
        val startLon = startPoint.longitude
        val targetLat = targetPoint.latitude
        val targetLon = targetPoint.longitude

        val distance = startPoint.distanceToAsDouble(targetPoint)
        if (distance > 500.0) { // Set instantly if distance is too large (avoid panning long distance)
            marker.position = targetPoint
            if (isFollowing) {
                binding.mapView.controller.setCenter(targetPoint)
            }
            binding.mapView.postInvalidateDelayed(50)
            return
        }

        markerPositionAnimator = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            // Thêm 200ms vào thời gian hiệu ứng để đảm bảo chấm xanh trượt liên tục, không bị khựng lại trước khi GPS tiếp theo tới
            duration = durationMs + 200L
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener { animator ->
                val fraction = animator.animatedValue as Float
                val lat = startLat + fraction * (targetLat - startLat)
                val lon = startLon + fraction * (targetLon - startLon)
                val interpolatedPoint = GeoPoint(lat, lon)

                marker.position = interpolatedPoint
                if (isFollowing) {
                    binding.mapView.controller.setCenter(interpolatedPoint)
                } else {
                    binding.mapView.invalidate()
                }
            }
            start()
        }
    }

    private fun startNavigation(lat: Double, lon: Double) {
        val intent = Intent(requireContext(), NavigationService::class.java).apply {
            putExtra("DEST_LAT", lat); putExtra("DEST_LON", lon)
        }
        requireContext().startForegroundService(intent)
    }

    private fun handleSharedLocation() {
        val intent = activity?.intent ?: return
        if (!intent.hasExtra("SHARE_TYPE")) return

        val type = intent.getStringExtra("SHARE_TYPE")
        val label = intent.getStringExtra("LABEL") ?: "Vị trí đã chọn"
        val destLat = intent.getDoubleExtra("DEST_LAT", 0.0)
        val destLon = intent.getDoubleExtra("DEST_LON", 0.0)
        
        // Automatically sync vehicle travel mode (motorcycle/car) shared from Google Maps
        if (intent.hasExtra("VEHICLE_TYPE")) {
            val vehicleType = intent.getIntExtra("VEHICLE_TYPE", -1)
            if (vehicleType != -1) {
                PrefsHelper.putInt(requireContext(), "vehicle_type", vehicleType)
                android.util.Log.d("MapFragment", "Shared vehicle type synced: $vehicleType")
            }
        }

        // Clear intent extras to avoid re-triggering on rotation
        intent.removeExtra("SHARE_TYPE")

        lifecycleScope.launch(Dispatchers.Main) {
            if (type == "ROUTE") {
                val originLat = intent.getDoubleExtra("ORIGIN_LAT", 0.0)
                val originLon = intent.getDoubleExtra("ORIGIN_LON", 0.0)
                
                // Trực tiếp ghim đích và tính toán lộ trình từ điểm xuất phát được share
                onPlaceSelected(destLat, destLon, "Đích: $label")
                // Gọi RoutingEngine với tọa độ xuất phát cố định
                fetchCustomRoute(originLat, originLon, destLat, destLon)
            } else if (type == "POI") {
                // Ghim điểm trên bản đồ và mở bảng xem trước lộ trình
                onPlaceSelected(destLat, destLon, label)
            }
        }
    }

    private fun fetchCustomRoute(startLat: Double, startLon: Double, destLat: Double, destLon: Double) {
        val context = requireContext()
        lifecycleScope.launch(Dispatchers.IO) {
            val preferredEngine = getEngineName(PrefsHelper.getInt(context, "routing_engine", 0))
            val priorityList = mutableListOf(preferredEngine, "Valhalla", "GraphHopper", "OSRM")
            val routes = routingEngine.fetchRouteWithFallback(context, startLat, startLon, destLat, destLon, priorityList)
            withContext(Dispatchers.Main) {
                if (!routes.isNullOrEmpty()) {
                    NavigationRepository.updateRoutes(routes)
                    routeAlternativeAdapter.submitList(routes)
                    bottomSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
                    binding.bottomSheet.layoutPlaceInfo.visibility = View.GONE
                    binding.bottomSheet.layoutRoutePreview.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun handleOfflineSelectionMode(enabled: Boolean) {
        val context = requireContext()
        if (enabled) {
            isFollowing = false
            NavigationRepository.setTrackUpMode(false)
            binding.mapView.mapOrientation = 0f
            
            // Ẩn UI thông thường
            binding.searchCard.visibility = View.GONE
            binding.suggestionsCard.visibility = View.GONE
            binding.fabLocation.hide()
            binding.fabLayers.hide()
            binding.btnRecenter.hide()
            bottomSheetBehavior.state = BottomSheetBehavior.STATE_HIDDEN
            
            // Thêm Selection Overlay
            if (selectionOverlay == null) {
                selectionOverlay = SelectionOverlay(context)
            }
            if (!binding.mapView.overlays.contains(selectionOverlay)) {
                binding.mapView.overlays.add(selectionOverlay)
            }
            
            // Inflate Top/Bottom Panels
            if (offlineSelectionBinding == null) {
                val view = layoutInflater.inflate(R.layout.layout_offline_selection, binding.root, false)
                binding.root.addView(view)
                offlineSelectionBinding = com.example.tymap.databinding.LayoutOfflineSelectionBinding.bind(view)
                
                offlineSelectionBinding?.btnCancel?.setOnClickListener {
                    NavigationRepository.setOfflineSelectionMode(false)
                }
                
                offlineSelectionBinding?.btnDownload?.setOnClickListener {
                    showRegionNameDialog()
                }
                
                val listener = { _: android.widget.CompoundButton, _: Boolean ->
                    updateOfflineEstimation()
                }
                offlineSelectionBinding?.cbZoom14?.setOnCheckedChangeListener(listener)
                offlineSelectionBinding?.cbZoom15?.setOnCheckedChangeListener(listener)
                offlineSelectionBinding?.cbZoom16?.setOnCheckedChangeListener(listener)
            }
            
            // Đăng ký MapListener tạm thời để cập nhật dung lượng dự kiến khi di chuyển
            binding.mapView.addMapListener(offlineMapListener)
            updateOfflineEstimation()
        } else {
            // Xóa Selection Overlay
            selectionOverlay?.let { binding.mapView.overlays.remove(it) }
            
            // Gỡ bỏ Panels
            offlineSelectionBinding?.let {
                binding.root.removeView(it.root)
                offlineSelectionBinding = null
            }
            
            binding.mapView.removeMapListener(offlineMapListener)
            
            // Hiện lại UI thông thường
            binding.searchCard.visibility = View.VISIBLE
            binding.fabLocation.show()
            binding.fabLayers.show()
            updateLocationButtonState()
            isFollowing = true
        }
        binding.mapView.invalidate()
    }

    private val offlineMapListener = object : MapListener {
        override fun onScroll(scrollEvent: ScrollEvent?): Boolean {
            updateOfflineEstimation()
            return false
        }
        override fun onZoom(zoomEvent: ZoomEvent?): Boolean {
            updateOfflineEstimation()
            return false
        }
    }

    private fun updateOfflineEstimation() {
        val w = binding.mapView.width
        val h = binding.mapView.height
        if (w == 0 || h == 0) return
        
        val fillRegionBinding = offlineSelectionBinding ?: return
        val rectSize = Math.min(w, h) * 0.7f
        val left = (w - rectSize) / 2
        val top = (h - rectSize) / 2
        val right = left + rectSize
        val bottom = top + rectSize
        
        val pTopLeft = binding.mapView.projection.fromPixels(left.toInt(), top.toInt()) as? GeoPoint ?: return
        val pBottomRight = binding.mapView.projection.fromPixels(right.toInt(), bottom.toInt()) as? GeoPoint ?: return
        
        val minLat = min(pTopLeft.latitude, pBottomRight.latitude)
        val maxLat = max(pTopLeft.latitude, pBottomRight.latitude)
        val minLon = min(pTopLeft.longitude, pBottomRight.longitude)
        val maxLon = max(pTopLeft.longitude, pBottomRight.longitude)
        
        var totalTilesCount = 0
        val zooms = mutableListOf<Int>()
        if (fillRegionBinding.cbZoom14.isChecked) zooms.add(14)
        if (fillRegionBinding.cbZoom15.isChecked) zooms.add(15)
        if (fillRegionBinding.cbZoom16.isChecked) zooms.add(16)
        
        for (z in zooms) {
            val xMin = OfflineDownloadService.getTileX(minLon, z)
            val xMax = OfflineDownloadService.getTileX(maxLon, z)
            val yMin = OfflineDownloadService.getTileY(maxLat, z)
            val yMax = OfflineDownloadService.getTileY(minLat, z)
            
            val dx = abs(xMax - xMin) + 1
            val dy = abs(yMax - yMin) + 1
            totalTilesCount += dx * dy
        }
        
        fillRegionBinding.tvEstimationTiles.text = "Tổng số tiles: $totalTilesCount"
        val sizeMb = totalTilesCount * 0.015f // 15 KB/tile
        fillRegionBinding.tvEstimationSize.text = "Dung lượng dự kiến: ${String.format("%.1f", sizeMb)} MB"
    }

    private fun showRegionNameDialog() {
        val context = requireContext()
        val input = EditText(context).apply {
            setText("Bản đồ " + java.text.SimpleDateFormat("dd_MM_HH_mm", java.util.Locale.getDefault()).format(java.util.Date()))
            selectAll()
        }
        
        AlertDialog.Builder(context)
            .setTitle("Tên vùng bản đồ")
            .setMessage("Nhập tên cho vùng bản đồ ngoại tuyến tải về:")
            .setView(input)
            .setPositiveButton("Tải về") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    startOfflineDownload(name)
                } else {
                    Toast.makeText(context, "Tên không được để trống", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Hủy", null)
            .show()
       }

       private fun startOfflineDownload(regionName: String) {
           val context = requireContext()
           val w = binding.mapView.width
           val h = binding.mapView.height
           val rectSize = Math.min(w, h) * 0.7f
           val left = (w - rectSize) / 2
           val top = (h - rectSize) / 2
           val right = left + rectSize
           val bottom = top + rectSize
           
           val pTopLeft = binding.mapView.projection.fromPixels(left.toInt(), top.toInt()) as? GeoPoint ?: return
           val pBottomRight = binding.mapView.projection.fromPixels(right.toInt(), bottom.toInt()) as? GeoPoint ?: return
           
           val minLat = min(pTopLeft.latitude, pBottomRight.latitude)
           val maxLat = max(pTopLeft.latitude, pBottomRight.latitude)
           val minLon = min(pTopLeft.longitude, pBottomRight.longitude)
           val maxLon = max(pTopLeft.longitude, pBottomRight.longitude)
           
           val zooms = mutableListOf<Int>()
           if (offlineSelectionBinding?.cbZoom14?.isChecked == true) zooms.add(14)
           if (offlineSelectionBinding?.cbZoom15?.isChecked == true) zooms.add(15)
           if (offlineSelectionBinding?.cbZoom16?.isChecked == true) zooms.add(16)
           
           if (zooms.isEmpty()) {
               Toast.makeText(context, "Hãy chọn ít nhất 1 mức zoom", Toast.LENGTH_SHORT).show()
               return
           }
           
           val regionId = UUID.randomUUID().toString().substring(0, 8)
           val rawTileSourceIndex = PrefsHelper.getInt(context, "tile_source", 0)
           val tileSourceIndex = if (rawTileSourceIndex >= getTileSources().size) 0 else rawTileSourceIndex

           val intent = Intent(context, OfflineDownloadService::class.java).apply {
               action = OfflineDownloadService.ACTION_START_DOWNLOAD
               putExtra("REGION_ID", regionId)
               putExtra("REGION_NAME", regionName)
               putExtra("ZOOMS", zooms.toIntArray())
               putExtra("MIN_LAT", minLat)
               putExtra("MAX_LAT", maxLat)
               putExtra("MIN_LON", minLon)
               putExtra("MAX_LON", maxLon)
               putExtra("TILE_SOURCE_INDEX", tileSourceIndex)
           }
           
           if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
               context.startForegroundService(intent)
           } else {
               context.startService(intent)
           }
           
           NavigationRepository.setOfflineSelectionMode(false)
           
           // Mở OfflineMapActivity
           val mapIntent = Intent(context, OfflineMapActivity::class.java)
           startActivity(mapIntent)
       }

       class SelectionOverlay(val context: Context) : org.osmdroid.views.overlay.Overlay() {
           private val paint = Paint().apply {
               color = Color.parseColor("#007AFF")
               style = Paint.Style.STROKE
               strokeWidth = 6f
               pathEffect = DashPathEffect(floatArrayOf(15f, 10f), 0f)
               isAntiAlias = true
           }
           private val fillPaint = Paint().apply {
               color = Color.parseColor("#15007AFF")
               style = Paint.Style.FILL
               isAntiAlias = true
           }

           override fun draw(canvas: Canvas?, mapView: MapView?, shadow: Boolean) {
               if (shadow || canvas == null || mapView == null) return
               val w = mapView.width
               val h = mapView.height
               val rectSize = Math.min(w, h) * 0.7f
               val left = (w - rectSize) / 2
               val top = (h - rectSize) / 2
               val right = left + rectSize
               val bottom = top + rectSize
               
               canvas.drawRect(left, top, right, bottom, fillPaint)
               canvas.drawRect(left, top, right, bottom, paint)
           }
       }

    override fun onResume() {
        super.onResume()
        val index = PrefsHelper.getInt(requireContext(), "tile_source", 0)
        binding.mapView.setTileSource(getTileSources()[if (index < getTileSources().size) index else 0])
        binding.mapView.onResume()
        compassOverlay?.enableCompass()
        orientationProvider?.startOrientationProvider(this)
        
        // Ensure shared location is processed
        handleSharedLocation()
    }

    override fun onPause() {
        super.onPause()
        val center = binding.mapView.mapCenter
        PrefsHelper.putFloat(requireContext(), "last_map_lat", center.latitude.toFloat())
        PrefsHelper.putFloat(requireContext(), "last_map_lon", center.longitude.toFloat())
        PrefsHelper.putFloat(requireContext(), "last_map_zoom", binding.mapView.zoomLevelDouble.toFloat())

        compassOverlay?.disableCompass()
        orientationProvider?.stopOrientationProvider()
        binding.mapView.onPause()
    }

    override fun onOrientationChanged(orientation: Float, source: IOrientationProvider?) {
        val rawOrientation = (orientation + 360f) % 360f
        // Setting "Bù góc la bàn" (compass_offset_mode): bù thêm 0/90/180/270 vào góc cảm biến thô
        val compassOffset = PrefsHelper.getInt(requireContext(), "compass_offset_mode", 0).toFloat()
        // Setting "Đảo chiều la bàn khi đứng yên" (invert_heading): bù thêm 180° khi đứng yên
        val invertHeading = PrefsHelper.getBoolean(requireContext(), "invert_heading", false)
        val baseHeading = (rawOrientation + compassOffset) % 360f
        val correctedHeading = (baseHeading + if (invertHeading) 180f else 0f) % 360f
        lastHeading = correctedHeading
        NavigationRepository.updateCompassHeading(rawOrientation)

        // osmdroid vẽ flat marker: total = mapOrientation - marker.rotation
        // nên set marker.rotation = -heading (bản vá đối xứng 10h -> 2h); flat marker
        // tự xoay theo bản đồ nên hướng nhìn luôn đúng kể cả khi xoay map bằng tay.
        val northUpOrientation = (-correctedHeading + 360f) % 360f

        lifecycleScope.launch(Dispatchers.Main) {
            val currentLoc = NavigationRepository.gpsLocation.value
            val speed = currentLoc?.speed ?: 0f
            
            // Chỉ cập nhật la bàn khi xe đang ĐỨNG YÊN (tốc độ <= 1.2 m/s)
            if (speed <= 1.2f) {
                val isTrackUp = NavigationRepository.isTrackUpMode.value
                if (isFollowing) {
                    if (isTrackUp) {
                        // CHẾ ĐỘ TRACK-UP (XOAY BẢN ĐỒ):
                        // - Bản đồ xoay ngược heading đã hiệu chỉnh (-correctedHeading)
                        // - Nón xanh rotation = -heading => chỉ thẳng đứng 12H
                        animateMapRotation(binding.mapView.mapOrientation, -correctedHeading)
                        animateMarkerRotation(northUpOrientation)
                    } else {
                        // CHẾ ĐỘ NORTH-UP (BẢN ĐỒ HƯỚNG BẮC): 
                        // - Bản đồ cố định hướng Bắc (0f)
                        // - Nón xanh xoay theo northUpOrientation (hướng nhìn thực tế)
                        animateMapRotation(binding.mapView.mapOrientation, 0f)
                        animateMarkerRotation(northUpOrientation)
                    }
                } else {
                    animateMarkerRotation(northUpOrientation)
                }
            }
            
            binding.mapView.postInvalidateDelayed(100)
        }
    }

    private fun showSpeedLimitSettingsDialog() {
        val context = context ?: return
        val dialog = BottomSheetDialog(context)
        val dialogView = layoutInflater.inflate(R.layout.dialog_speed_limit_settings, null)
        dialog.setContentView(dialogView)

        val etCustomSpeed = dialogView.findViewById<EditText>(R.id.etCustomSpeed)
        val swAutoSpeedLimit = dialogView.findViewById<SwitchMaterial>(R.id.swAutoSpeedLimit)
        val swSpeedWarning = dialogView.findViewById<SwitchMaterial>(R.id.swSpeedWarning)
        val swVoiceWarning = dialogView.findViewById<SwitchMaterial>(R.id.swVoiceWarning)
        val swEspWarning = dialogView.findViewById<SwitchMaterial>(R.id.swEspWarning)
        val btnSave = dialogView.findViewById<Button>(R.id.btnSaveSpeedLimit)

        val currentThreshold = PrefsHelper.getInt(context, "speed_threshold", 60)
        val currentAutoSpeed = PrefsHelper.getBoolean(context, "auto_speed_limit", true)
        val currentSpeedWarning = PrefsHelper.getBoolean(context, "speed_warning", true)
        val currentVoiceWarning = PrefsHelper.getBoolean(context, "voice_speed_warning", true)
        val currentEspWarning = PrefsHelper.getBoolean(context, "esp_speed_warning", true)

        etCustomSpeed?.setText(currentThreshold.toString())
        swAutoSpeedLimit?.isChecked = currentAutoSpeed
        swSpeedWarning?.isChecked = currentSpeedWarning
        swVoiceWarning?.isChecked = currentVoiceWarning
        swEspWarning?.isChecked = currentEspWarning

        val presetButtons = mapOf(
            R.id.btnSpeed30 to 30,
            R.id.btnSpeed40 to 40,
            R.id.btnSpeed50 to 50,
            R.id.btnSpeed60 to 60,
            R.id.btnSpeed70 to 70,
            R.id.btnSpeed80 to 80,
            R.id.btnSpeed90 to 90,
            R.id.btnSpeed100 to 100
        )

        var selectedIntervalSec = PrefsHelper.getInt(context, "speed_warning_interval", 15)

        val intervalButtons = mapOf(
            R.id.btnInterval5s to 5,
            R.id.btnInterval10s to 10,
            R.id.btnInterval15s to 15,
            R.id.btnInterval30s to 30,
            R.id.btnInterval60s to 60
        )

        fun updateIntervalButtonSelection(selected: Int) {
            intervalButtons.forEach { (id, interval) ->
                val btn = dialogView.findViewById<Button>(id)
                if (interval == selected) {
                    btn?.setBackgroundColor(Color.parseColor("#00E5FF"))
                    btn?.setTextColor(Color.parseColor("#0F172A"))
                } else {
                    btn?.setBackgroundColor(Color.TRANSPARENT)
                    btn?.setTextColor(Color.parseColor("#F8FAFC"))
                }
            }
        }

        updateIntervalButtonSelection(selectedIntervalSec)

        intervalButtons.forEach { (id, interval) ->
            dialogView.findViewById<Button>(id)?.setOnClickListener {
                selectedIntervalSec = interval
                updateIntervalButtonSelection(selectedIntervalSec)
            }
        }

        presetButtons.forEach { (id, valSpeed) ->
            dialogView.findViewById<Button>(id)?.setOnClickListener {
                etCustomSpeed?.setText(valSpeed.toString())
            }
        }

        btnSave?.setOnClickListener {
            val inputVal = etCustomSpeed?.text?.toString()?.toIntOrNull() ?: currentThreshold
            val finalThreshold = inputVal.coerceIn(10, 200)

            PrefsHelper.putInt(context, "speed_threshold", finalThreshold)
            PrefsHelper.putInt(context, "manual_speed_threshold", finalThreshold)
            PrefsHelper.putInt(context, "speed_warning_interval", selectedIntervalSec)
            PrefsHelper.putBoolean(context, "auto_speed_limit", swAutoSpeedLimit?.isChecked == true)
            PrefsHelper.putBoolean(context, "speed_warning", swSpeedWarning?.isChecked == true)
            PrefsHelper.putBoolean(context, "voice_speed_warning", swVoiceWarning?.isChecked == true)
            PrefsHelper.putBoolean(context, "esp_speed_warning", swEspWarning?.isChecked == true)

            // Cập nhật giao diện cảnh báo & biển báo tức thì
            val isWarnEnabled = swSpeedWarning?.isChecked == true
            if (isWarnEnabled && finalThreshold > 0) {
                binding.cardSpeedLimitSign.visibility = View.VISIBLE
                binding.tvSpeedLimitSignValue.text = "$finalThreshold"
                val currentLoc = NavigationRepository.gpsLocation.value
                val currentSpeedKmh = currentLoc?.let { (it.speed * 3.6f).toInt() } ?: 0
                binding.ivSpeedWarning.visibility = if (currentSpeedKmh > finalThreshold) View.VISIBLE else View.GONE
            } else {
                binding.cardSpeedLimitSign.visibility = View.GONE
                binding.ivSpeedWarning.visibility = View.GONE
            }

            Toast.makeText(context, "Đã lưu tốc độ giới hạn: $finalThreshold km/h", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        dialog.show()
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
