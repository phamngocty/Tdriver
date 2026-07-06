package com.example.tymap.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.tymap.R
import com.example.tymap.databinding.FragmentMapBinding
import com.example.tymap.repository.NavigationRepository
import com.example.tymap.service.NavigationService
import com.example.tymap.service.RoutingEngine
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
import android.widget.EditText
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
    private var isTrackUp = true
    private var isFirstLocation = true
    private var lastHeading: Float = 0f

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
    private val mapCnPositron = XYTileSource("CartoDB Positron", 1, 20, 256, ".png",
        arrayOf("https://a.basemaps.cartocdn.com/light_all/", "https://b.basemaps.cartocdn.com/light_all/", "https://c.basemaps.cartocdn.com/light_all/"),
        "© OpenStreetMap contributors, © CARTO")

    private val mapCnDark = XYTileSource("CartoDB Dark Matter", 1, 20, 256, ".png",
        arrayOf("https://a.basemaps.cartocdn.com/dark_all/", "https://b.basemaps.cartocdn.com/dark_all/", "https://c.basemaps.cartocdn.com/dark_all/"),
        "© OpenStreetMap contributors, © CARTO")

    private val mapCnVoyager = XYTileSource("CartoDB Voyager", 1, 20, 256, ".png",
        arrayOf("https://a.basemaps.cartocdn.com/rastertiles/voyager/", "https://b.basemaps.cartocdn.com/rastertiles/voyager/", "https://c.basemaps.cartocdn.com/rastertiles/voyager/"),
        "© OpenStreetMap contributors, © CARTO")

    private val satelliteSource = object : XYTileSource("Satellite (ESRI)", 1, 20, 256, "",
        arrayOf("https://services.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/"),
        "© ESRI") {
        override fun getTileURLString(pMapTileIndex: Long): String {
            val z = org.osmdroid.util.MapTileIndex.getZoom(pMapTileIndex)
            val x = org.osmdroid.util.MapTileIndex.getX(pMapTileIndex)
            val y = org.osmdroid.util.MapTileIndex.getY(pMapTileIndex)
            return "https://services.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/$z/$y/$x"
        }
    }

    private fun getTileSources(): List<ITileSource> {
        val list = mutableListOf<ITileSource>()
        list.add(mapCnPositron)
        list.add(TileSourceFactory.MAPNIK)
        list.add(mapCnDark)
        list.add(mapCnVoyager)
        list.add(satelliteSource)

        val customUrl = PrefsHelper.getString(requireContext(), "custom_tile_url", "")
        if (customUrl.isNotEmpty() && customUrl.contains("{z}")) {
            try {
                val baseUrl = customUrl.substringBefore("{z}")
                val ext = "." + customUrl.substringAfterLast(".")
                list.add(XYTileSource("Tùy chỉnh", 1, 20, 256, ext, arrayOf(baseUrl), "Custom"))
            } catch (e: Exception) {}
        }
        return list
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        Configuration.getInstance().load(requireContext(), requireContext().getSharedPreferences("tymap_osmdroid", Context.MODE_PRIVATE))
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
                setFlat(false) // Rotation relative to Screen (0 = 12h)
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
                        binding.mapView.setTileSource(mapCnPositron)
                        PrefsHelper.putInt(requireContext(), "tile_source", 0)
                        android.widget.Toast.makeText(context, "Lỗi tải ảnh vệ tinh, chuyển về bản đồ Positron", android.widget.Toast.LENGTH_SHORT).show()
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
            val url = "https://nominatim.openstreetmap.org/reverse?lat=$lat&lon=$lon&format=json"
            val request = Request.Builder().url(url).header("User-Agent", "TYMAP").build()
            try {
                httpClient.newCall(request).execute().use { response ->
                    val body = response.body.string()
                    val json = JSONObject(body)
                    val name = json.optString("display_name", "Vị trí đã chọn")
                    withContext(Dispatchers.Main) {
                        onPlaceSelected(lat, lon, name)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onPlaceSelected(lat, lon, "Vị trí đã thả ghim")
                }
            } finally {
                withContext(Dispatchers.Main) { binding.searchProgress.visibility = View.GONE }
            }
        }
    }

    private fun clearDestination() {
        destinationMarker?.let { binding.mapView.overlays.remove(it) }
        destinationMarker = null
        bottomSheetBehavior.state = BottomSheetBehavior.STATE_HIDDEN
        binding.mapView.invalidate()
    }

    private fun setupBottomSheet() {
        bottomSheetBehavior = BottomSheetBehavior.from(binding.bottomSheet.navigationBottomSheet)
        bottomSheetBehavior.state = BottomSheetBehavior.STATE_HIDDEN
        routeAlternativeAdapter = RouteAlternativeAdapter { selectRoute(it) }
        binding.bottomSheet.rvAlternatives.layoutManager = LinearLayoutManager(requireContext())
        binding.bottomSheet.rvAlternatives.adapter = routeAlternativeAdapter

        binding.bottomSheet.btnEndNav.setOnClickListener {
            // Thay vì dừng Service, chúng ta chỉ dừng chế độ dẫn đường
            NavigationRepository.setNavigationRunning(false)
            NavigationRepository.updateRoutes(emptyList()) // Xóa polyline
            clearDestination()
        }
    }

    private fun setupSearch() {
        suggestionAdapter = SuggestionAdapter { item ->
            val lat = item.optDouble("lat")
            val lon = item.optDouble("lon")
            val name = item.optString("display_name")
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

        binding.etSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (binding.etSearch.hasFocus()) performSearch(s.toString())
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
    }

    private fun performSearch(query: String) {
        if (query.length < 2) {
            binding.suggestionsCard.visibility = View.GONE
            return
        }
        binding.searchProgress.visibility = View.VISIBLE
        val currentLoc = NavigationRepository.gpsLocation.value
        
        lifecycleScope.launch(Dispatchers.IO) {
            val results = mutableListOf<JSONObject>()
            if (getString(R.string.current_location).contains(query, true)) {
                currentLoc?.let {
                    results.add(JSONObject().apply {
                        put("display_name", getString(R.string.current_location))
                        put("lat", it.latitude); put("lon", it.longitude); put("is_current_location", true)
                    })
                }
            }

            val photonUrl = "https://photon.komoot.io/api/?q=$query&limit=5" +
                    (currentLoc?.let { "&lat=${it.latitude}&lon=${it.longitude}" } ?: "")
            var photonSuccess = false
            try {
                httpClient.newBuilder().connectTimeout(3, java.util.concurrent.TimeUnit.SECONDS).build()
                    .newCall(Request.Builder().url(photonUrl).build()).execute().use { response ->
                        if (response.isSuccessful) {
                            val json = JSONObject(response.body.string())
                            val features = json.getJSONArray("features")
                            for (i in 0 until features.length()) {
                                val feat = features.getJSONObject(i)
                                val prop = feat.getJSONObject("properties")
                                val geom = feat.getJSONObject("geometry").getJSONArray("coordinates")
                                val displayName = listOfNotNull(prop.optString("name"), prop.optString("city"), prop.optString("country"))
                                    .joinToString(", ")
                                results.add(JSONObject().apply {
                                    put("display_name", displayName)
                                    put("lat", geom.getDouble(1)); put("lon", geom.getDouble(0))
                                })
                            }
                            photonSuccess = true
                        }
                    }
            } catch (e: Exception) {}

            if (!photonSuccess) {
                val nominatimUrl = "https://nominatim.openstreetmap.org/search?q=$query&format=json&limit=5"
                try {
                    httpClient.newCall(Request.Builder().url(nominatimUrl).header("User-Agent", "TYMAP").build()).execute().use { response ->
                        if (response.isSuccessful) {
                            val array = JSONArray(response.body.string())
                            for (i in 0 until array.length()) results.add(array.getJSONObject(i))
                        }
                    }
                } catch (e: Exception) {}
            }

            withContext(Dispatchers.Main) {
                suggestionAdapter.submitList(results)
                binding.suggestionsCard.visibility = if (results.isNotEmpty()) View.VISIBLE else View.GONE
                binding.searchProgress.visibility = View.GONE
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
    }

    private fun setupButtons() {
        binding.fabLocation.setOnClickListener {
            if (!isFollowing) {
                // Nếu đang ở chế độ xem tự do -> Bật chế độ đi theo (North Up mặc định)
                isFollowing = true
                isTrackUp = false
                binding.btnRecenter.hide()
            } else {
                // Nếu đang ở chế độ đi theo -> Chuyển đổi giữa North Up và Track Up
                isTrackUp = !isTrackUp
            }
            
            updateLocationButtonState()
            
            NavigationRepository.gpsLocation.value?.let {
                val point = GeoPoint(it.latitude, it.longitude)
                val targetZoom = binding.mapView.zoomLevelDouble
                val targetRotation = if (isTrackUp) -it.bearing else 0f
                
                binding.mapView.controller.animateTo(point, targetZoom, 200L)
                animateMapRotation(binding.mapView.mapOrientation, targetRotation)
            }
        }

        binding.fabLayers.setOnClickListener {
            val sources = getTileSources()
            val sourceNames = sources.map { it.name() }.toMutableList()
            sourceNames.add("Ảnh chụp Google Map")
            
            val currentIdx = PrefsHelper.getInt(requireContext(), "tile_source", 0)
            val currentCaptureMode = PrefsHelper.getInt(requireContext(), "map_capture_mode", 0)
            
            val checkedItem = if (currentCaptureMode == 1) sourceNames.size - 1 else currentIdx
            
            AlertDialog.Builder(requireContext())
                .setTitle("Nguồn bản đồ")
                .setSingleChoiceItems(sourceNames.toTypedArray(), checkedItem) { dialog, which ->
                    if (which == sourceNames.size - 1) {
                        startGoogleMapsCapture()
                    } else {
                        PrefsHelper.putInt(requireContext(), "map_capture_mode", 0)
                        PrefsHelper.putInt(requireContext(), "tile_source", which)
                        binding.mapView.setTileSource(sources[which])
                        Toast.makeText(requireContext(), sources[which].name(), Toast.LENGTH_SHORT).show()
                    }
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
                if (isTrackUp) binding.mapView.mapOrientation = -it.bearing
            }
            bottomSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
        }
    }

    private fun startGoogleMapsCapture() {
        val mpm = requireContext().getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenCaptureLauncher.launch(mpm.createScreenCaptureIntent())
    }

    private fun updateLocationButtonState() {
        val context = requireContext()
        if (!isFollowing) {
            binding.fabLocation.imageTintList = android.content.res.ColorStateList.valueOf(Color.BLACK)
            binding.fabLocation.setImageResource(R.drawable.ic_my_location)
        } else {
            binding.fabLocation.imageTintList = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(context, R.color.blue_primary))
            if (isTrackUp) {
                // Chế độ Track Up: Icon mũi tên định hướng
                binding.fabLocation.setImageResource(R.drawable.ic_navigation_arrow) 
            } else {
                // Chế độ North Up: Icon vị trí tiêu chuẩn
                binding.fabLocation.setImageResource(R.drawable.ic_my_location)
            }
        }
    }

    private var mapRotationAnimator: android.animation.ValueAnimator? = null
    
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
                    
                    val point = GeoPoint(location.latitude, location.longitude)
                    
                    // 1. Cập nhật vị trí dấu chấm xanh
                    userMarker?.setPosition(point)
                    
                    // 2. Xử lý xoay bản đồ và Marker theo chế độ
                    val speed = location.speed
                    val bearing = if (speed > 1.5f && location.hasBearing()) location.bearing else lastHeading
                    
                    if (isFollowing) {
                        // Rule APP-25: Dùng animateTo với 200ms
                        binding.mapView.controller.animateTo(point, binding.mapView.zoomLevelDouble, 200L)
                        
                        if (isTrackUp) {
                            // Chế độ Track Up: Bản đồ xoay ngược bearing, Marker hướng thẳng (0)
                            animateMapRotation(binding.mapView.mapOrientation, -bearing)
                            animateMarkerRotation(0f)
                        } else {
                            // Chế độ North Up: Bản đồ hướng Bắc (0), Marker xoay theo bearing
                            animateMapRotation(binding.mapView.mapOrientation, 0f)
                            animateMarkerRotation(bearing)
                        }
                    } else {
                        // Khi không follow: Bản đồ không tự xoay, Marker xoay theo hướng thực tế
                        animateMarkerRotation(bearing)
                    }
                    
                    if (isFirstLocation) {
                        val zoom = PrefsHelper.getFloat(requireContext(), "default_zoom", 15f).toDouble()
                        binding.mapView.controller.animateTo(point, zoom, 200L)
                        isFirstLocation = false
                    }
                    
                    val speedLimit = PrefsHelper.getInt(requireContext(), "speed_threshold", 60)
                    binding.ivSpeedWarning.visibility = if (location.speed * 3.6 > speedLimit) View.VISIBLE else View.GONE
                    
                    // Rule APP-23: invalidate tối đa 1 lần mỗi 100ms
                    binding.mapView.postInvalidateDelayed(100)
                }
            }
        }

        // Quan sát danh sách lộ trình để tự động vẽ lại Polyline khi có tính toán lại
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                NavigationRepository.routes.collect { routes ->
                    triggerDrawRoutes(routes)
                }
            }
        }

        lifecycleScope.launch {
            NavigationRepository.navigationState.collect { running ->
                if (running) {
                    isFollowing = true
                    isTrackUp = true
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
                        animateMarkerRotation(0f)
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
                        binding.bottomSheet.ivNavIcon.setImageResource(maneuverIconRes(hud.iconIndex))
                    }
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

    private fun showRoutePreview(lat: Double, lon: Double) {
        // Disable following to focus on route preview
        isFollowing = false
        updateLocationButtonState()
        binding.btnRecenter.show()

        val startLoc = NavigationRepository.gpsLocation.value ?: return
        val context = requireContext()
        lifecycleScope.launch(Dispatchers.IO) {
            val routes = routingEngine.fetchOsrmAndValhalla(context, startLoc.latitude, startLoc.longitude, lat, lon)
            withContext(Dispatchers.Main) {
                if (!routes.isNullOrEmpty()) {
                    NavigationRepository.updateRoutes(routes)
                    routeAlternativeAdapter.submitList(routes)
                    val boundingBox = org.osmdroid.util.BoundingBox.fromGeoPoints(routes[0].polyline.map { GeoPoint(it.first, it.second) })
                    binding.mapView.zoomToBoundingBox(boundingBox, true, 150)
                    binding.bottomSheet.layoutPlaceInfo.visibility = View.GONE
                    binding.bottomSheet.layoutRoutePreview.visibility = View.VISIBLE
                    binding.bottomSheet.btnStartFromPreview.setOnClickListener { startNavigation(lat, lon) }
                    bottomSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
                } else {
                    Toast.makeText(requireContext(), "Không tìm thấy tuyến đường", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun getEngineName(index: Int) = when(index) { 1 -> "OpenRouteService"; 2 -> "GraphHopper"; 3 -> "Valhalla"; 4 -> "Mapbox"; else -> "OSRM" }

    private fun selectRoute(index: Int) {
        val currentRoutes = NavigationRepository.routes.value
        val updatedRoutes = currentRoutes.mapIndexed { i, route -> route.copy(isSelected = i == index) }
        NavigationRepository.updateRoutes(updatedRoutes)
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
                binding.mapView.postInvalidateDelayed(50)
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
                // Ghim điểm và để người dùng nhấn "Bắt đầu" (sẽ lấy GPS hiện tại)
                onPlaceSelected(destLat, destLon, label)
            }
        }
    }

    private fun fetchCustomRoute(startLat: Double, startLon: Double, destLat: Double, destLon: Double) {
        val context = requireContext()
        lifecycleScope.launch(Dispatchers.IO) {
            val preferredEngine = getEngineName(PrefsHelper.getInt(context, "routing_engine", 0))
            val priorityList = mutableListOf(preferredEngine, "Mapbox", "GraphHopper", "OSRM")
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
            isTrackUp = false
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
           val tileSourceIndex = PrefsHelper.getInt(context, "tile_source", 0)

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
        lastHeading = orientation
        NavigationRepository.updateCompassHeading(orientation)
        lifecycleScope.launch(Dispatchers.Main) {
            val currentLoc = NavigationRepository.gpsLocation.value
            val speed = currentLoc?.speed ?: 0f
            
            // Chỉ cập nhật nếu đang đứng yên hoặc tốc độ cực thấp
            if (speed <= 1.2) {
                if (isFollowing) {
                    if (isTrackUp) {
                        animateMapRotation(binding.mapView.mapOrientation, -orientation)
                        animateMarkerRotation(0f)
                    } else {
                        animateMapRotation(binding.mapView.mapOrientation, 0f)
                        animateMarkerRotation(orientation)
                    }
                } else {
                    animateMarkerRotation(orientation)
                }
            }
            
            binding.mapView.postInvalidateDelayed(100)
        }
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
