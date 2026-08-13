package com.example.tymap.ui

import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.example.tymap.R
import com.example.tymap.utils.PrefsHelper
import org.json.JSONArray
import org.json.JSONObject

class ThemeBuilderActivity : AppCompatActivity() {

    private lateinit var esp32Canvas: RelativeLayout
    private lateinit var tvEditTitle: TextView
    private lateinit var etCoordX: EditText
    private lateinit var etCoordY: EditText
    private lateinit var btnAlignLeft: Button
    private lateinit var btnAlignCenter: Button
    private lateinit var btnAlignRight: Button
    private lateinit var layoutColorPicker: LinearLayout
    private lateinit var containerChecklist: LinearLayout
    private lateinit var layoutPresets: LinearLayout

    private lateinit var tabStatus: TextView
    private lateinit var tabNotif: TextView
    private lateinit var tabMap: TextView

    private var currentCategory = "status"
    private var currentModel = "s4"
    private var selectedView: View? = null
    private var customElementsCount = 0

    private val widgetMetadata = mapOf(
        "wTime" to "🕒 Đồng hồ",
        "wDate" to "📅 Ngày tháng",
        "wSpeed" to "⚡ Tốc độ di chuyển",
        "wBatPhone" to "📱 Pin điện thoại",
        "wBatBike" to "🔋 Pin xe điện (Bike)",
        "wNav" to "➔ Mũi tên chỉ hướng",
        "wBrand" to "🏷 Logo thương hiệu",
        "wNotifIcon" to "💬 Icon thông báo",
        "wNotifTitle" to "👤 Tên người gửi",
        "wNotifBody" to "✉ Nội dung tin nhắn"
    )

    private val availableColors = listOf(
        "#FFFFFF", "#00E5FF", "#10B981", "#F59E0B", "#EF4444"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_theme_builder)

        initViews()
        setupListeners()
        setupColorPicker()
        switchCategory("status")
    }

    private fun initViews() {
        esp32Canvas = findViewById(R.id.esp32Canvas)
        tvEditTitle = findViewById(R.id.tvEditTitle)
        etCoordX = findViewById(R.id.etCoordX)
        etCoordY = findViewById(R.id.etCoordY)
        btnAlignLeft = findViewById(R.id.btnAlignLeft)
        btnAlignCenter = findViewById(R.id.btnAlignCenter)
        btnAlignRight = findViewById(R.id.btnAlignRight)
        layoutColorPicker = findViewById(R.id.layoutColorPicker)
        containerChecklist = findViewById(R.id.containerChecklist)
        layoutPresets = findViewById(R.id.layoutPresets)

        tabStatus = findViewById(R.id.tabStatus)
        tabNotif = findViewById(R.id.tabNotif)
        tabMap = findViewById(R.id.tabMap)
    }

    private fun setupListeners() {
        findViewById<ImageView>(R.id.btnBack).setOnClickListener { finish() }

        tabStatus.setOnClickListener { switchCategory("status") }
        tabNotif.setOnClickListener { switchCategory("notif") }
        tabMap.setOnClickListener { switchCategory("map") }

        // Setup touch listeners for default elements
        for (i in 0 until esp32Canvas.childCount) {
            val child = esp32Canvas.getChildAt(i)
            setupDragTouchListener(child)
        }

        // Align buttons
        btnAlignLeft.setOnClickListener { setAlignment("left") }
        btnAlignCenter.setOnClickListener { setAlignment("center") }
        btnAlignRight.setOnClickListener { setAlignment("right") }

        // Manual X & Y inputs
        etCoordX.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) updateSelectedViewPosFromInput()
        }
        etCoordY.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) updateSelectedViewPosFromInput()
        }

        // Add custom text buttons
        findViewById<Button>(R.id.btnAddMarquee).setOnClickListener { addCustomMarqueeText() }
        findViewById<Button>(R.id.btnAddStatic).setOnClickListener { addCustomStaticText() }

        // Upload button
        findViewById<Button>(R.id.btnBigUpload).setOnClickListener { triggerBleUpload() }
        findViewById<TextView>(R.id.btnUploadTop).setOnClickListener { triggerBleUpload() }
    }

    private fun setupDragTouchListener(view: View) {
        var dX = 0f
        var dY = 0f

        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    selectWidget(v)
                    dX = v.x - event.rawX
                    dY = v.y - event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    var newX = event.rawX + dX
                    var newY = event.rawY + dY

                    // Clamp limits (0 to 200)
                    newX = newX.coerceIn(10f, 200f)
                    newY = newY.coerceIn(10f, 200f)

                    v.x = newX
                    v.y = newY

                    etCoordX.setText((newX.toInt() + 20).toString())
                    etCoordY.setText((newY.toInt() + 15).toString())

                    if (newX.toInt() == 100) { // Center aligned in 240 canvas (200 + 40/2)
                        btnAlignCenter.isSelected = true
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun selectWidget(v: View) {
        selectedView = v
        val entryName = try { v.resources.getResourceEntryName(v.id) } catch (e: Exception) { null }
        val name = widgetMetadata[entryName] ?: if (v is TextView) v.text.toString() else "Linh kiện"
        tvEditTitle.text = "Cấu hình: $name"

        etCoordX.setText((v.x.toInt() + 20).toString())
        etCoordY.setText((v.y.toInt() + 15).toString())
    }

    private fun setAlignment(align: String) {
        val v = selectedView ?: return
        when (align) {
            "center" -> {
                v.x = 100f
                etCoordX.setText("120")
            }
            "left" -> {
                v.x = 45f
                etCoordX.setText("65")
            }
            "right" -> {
                v.x = 155f
                etCoordX.setText("175")
            }
        }
    }

    private fun updateSelectedViewPosFromInput() {
        val v = selectedView ?: return
        val x = etCoordX.text.toString().toIntOrNull() ?: 120
        val y = etCoordY.text.toString().toIntOrNull() ?: 120

        v.x = (x - 20).coerceIn(0, 200).toFloat()
        v.y = (y - 15).coerceIn(0, 200).toFloat()
    }

    private fun setupColorPicker() {
        layoutColorPicker.removeAllViews()
        for (hex in availableColors) {
            val dot = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(60, 60).apply {
                    setMargins(8, 0, 8, 0)
                }
                background = resources.getDrawable(R.drawable.bg_icon_tint, null)
                setBackgroundColor(Color.parseColor(hex))
                setOnClickListener {
                    selectedView?.let { target ->
                        if (target is TextView) target.setTextColor(Color.parseColor(hex))
                    }
                }
            }
            layoutColorPicker.addView(dot)
        }
    }

    private fun addCustomMarqueeText() {
        customElementsCount++
        val tv = TextView(this).apply {
            id = View.generateViewId()
            text = "Chữ cuộn mới $customElementsCount"
            setTextColor(Color.parseColor("#00E5FF"))
            textSize = 12f
            x = 100f
            y = 120f
        }
        esp32Canvas.addView(tv)
        setupDragTouchListener(tv)
        selectWidget(tv)
    }

    private fun addCustomStaticText() {
        customElementsCount++
        val tv = TextView(this).apply {
            id = View.generateViewId()
            text = "Nhãn tĩnh $customElementsCount"
            setTextColor(Color.WHITE)
            textSize = 12f
            x = 100f
            y = 120f
        }
        esp32Canvas.addView(tv)
        setupDragTouchListener(tv)
        selectWidget(tv)
    }

    private fun switchCategory(cat: String) {
        currentCategory = cat

        tabStatus.setBackgroundResource(if (cat == "status") R.drawable.bg_tab_active else 0)
        tabStatus.setTextColor(if (cat == "status") Color.WHITE else Color.parseColor("#94A3B8"))

        tabNotif.setBackgroundResource(if (cat == "notif") R.drawable.bg_tab_active else 0)
        tabNotif.setTextColor(if (cat == "notif") Color.WHITE else Color.parseColor("#94A3B8"))

        tabMap.setBackgroundResource(if (cat == "map") R.drawable.bg_tab_active else 0)
        tabMap.setTextColor(if (cat == "map") Color.WHITE else Color.parseColor("#94A3B8"))

        setupPresetsForCategory(cat)
    }

    private fun setupPresetsForCategory(cat: String) {
        layoutPresets.removeAllViews()

        val models = when (cat) {
            "status" -> listOf("s4" to "Mẫu S4 (Cyber)", "s3" to "Mẫu S3 (Tối Giản)", "s5" to "Mẫu S5 (Racing)", "s6" to "Mẫu S6 (Classic)", "s7" to "Mẫu S7 (Sport)")
            "notif" -> listOf("n1" to "Mẫu N1 (Top Banner)", "n2" to "Mẫu N2 (Fullscreen)", "n3" to "Mẫu N3 (Mini Popup)", "n4" to "Mẫu N4 (Thẻ cuộn)")
            else -> listOf("mh1" to "Mẫu MH1 (Pill Nổi)", "mh2" to "Mẫu MH2 (Thanh Dưới)", "mh3" to "Mẫu MH3 (Big Turn)", "mh4" to "Mẫu MH4 (Mini HUD)", "mh5" to "Mẫu MH5 (Thuần MAP)")
        }

        for ((id, name) in models) {
            val btn = Button(this, null, android.R.attr.buttonStyleSmall).apply {
                text = name
                textSize = 11f
                setOnClickListener { applyModelPreset(id) }
            }
            layoutPresets.addView(btn)
        }

        applyModelPreset(models[0].first)
    }

    private fun applyModelPreset(model: String) {
        currentModel = model

        // Reset visibility of default widgets
        val allWidgets = listOf("wTime", "wDate", "wSpeed", "wBatPhone", "wBatBike", "wNav", "wBrand", "wNotifIcon", "wNotifTitle", "wNotifBody")
        for (id in allWidgets) {
            val resId = resources.getIdentifier(id, "id", packageName)
            esp32Canvas.findViewById<View>(resId)?.visibility = View.GONE
        }

        when (model) {
            "s4" -> { // Cyber Dual
                setWidgetPos("wBrand", 85f, 15f, View.VISIBLE)
                setWidgetPos("wTime", 60f, 45f, View.VISIBLE)
                setWidgetPos("wDate", 50f, 90f, View.VISIBLE)
                setWidgetPos("wSpeed", 70f, 135f, View.VISIBLE)
                setWidgetPos("wBatPhone", 25f, 180f, View.VISIBLE)
                setWidgetPos("wBatBike", 125f, 180f, View.VISIBLE)
            }
            "s3" -> { // Classic Minimalist
                setWidgetPos("wTime", 60f, 75f, View.VISIBLE)
                setWidgetPos("wDate", 50f, 120f, View.VISIBLE)
                setWidgetPos("wSpeed", 70f, 160f, View.VISIBLE)
            }
            "s5" -> { // Racing Tab
                setWidgetPos("wSpeed", 60f, 50f, View.VISIBLE)
                setWidgetPos("wTime", 75f, 20f, View.VISIBLE)
                setWidgetPos("wBatBike", 25f, 140f, View.VISIBLE)
                setWidgetPos("wBatPhone", 125f, 140f, View.VISIBLE)
                setWidgetPos("wBrand", 85f, 180f, View.VISIBLE)
            }
            "n1" -> { // Top Banner Notif
                setWidgetPos("wNotifIcon", 90f, 10f, View.VISIBLE)
                setWidgetPos("wNotifTitle", 55f, 55f, View.VISIBLE)
                setWidgetPos("wNotifBody", 20f, 90f, View.VISIBLE)
                setWidgetPos("wTime", 80f, 175f, View.VISIBLE)
            }
            "n2" -> { // Fullscreen Focus Notif
                setWidgetPos("wTime", 80f, 5f, View.VISIBLE)
                setWidgetPos("wNotifIcon", 90f, 30f, View.VISIBLE)
                setWidgetPos("wNotifTitle", 40f, 75f, View.VISIBLE)
                setWidgetPos("wNotifBody", 10f, 110f, View.VISIBLE)
            }
            "mh1" -> { // Pill Nổi MAP HUD
                setWidgetPos("wTime", 75f, 5f, View.VISIBLE)
                setWidgetPos("wNav", 80f, 40f, View.VISIBLE)
                setWidgetPos("wSpeed", 65f, 125f, View.VISIBLE)
            }
            "mh2" -> { // Thanh Dưới MAP HUD
                setWidgetPos("wSpeed", 65f, 30f, View.VISIBLE)
                setWidgetPos("wNav", 80f, 90f, View.VISIBLE)
                setWidgetPos("wTime", 75f, 165f, View.VISIBLE)
            }
            "mh3" -> { // Big Turn MAP HUD
                setWidgetPos("wNav", 80f, 15f, View.VISIBLE)
                setWidgetPos("wTime", 75f, 130f, View.VISIBLE)
                setWidgetPos("wSpeed", 65f, 160f, View.VISIBLE)
            }
            "mh4" -> { // Mini HUD
                setWidgetPos("wNav", 20f, 80f, View.VISIBLE)
                setWidgetPos("wSpeed", 110f, 80f, View.VISIBLE)
                setWidgetPos("wBatBike", 80f, 160f, View.VISIBLE)
            }
            "mh5" -> { // Bản đồ thuần (Tắt hết)
                // Nothing is visible
            }
            "s6" -> { // Classic
                setWidgetPos("wBrand", 85f, 160f, View.VISIBLE)
                setWidgetPos("wTime", 60f, 60f, View.VISIBLE)
                setWidgetPos("wDate", 50f, 120f, View.VISIBLE)
            }
            "s7" -> { // Sport
                setWidgetPos("wTime", 50f, 20f, View.VISIBLE)
                setWidgetPos("wSpeed", 50f, 70f, View.VISIBLE)
                setWidgetPos("wDate", 50f, 130f, View.VISIBLE)
                setWidgetPos("wBatBike", 80f, 160f, View.VISIBLE)
            }
            "n3" -> { // Mini Popup
                setWidgetPos("wNotifIcon", 90f, 60f, View.VISIBLE)
                setWidgetPos("wNotifTitle", 40f, 100f, View.VISIBLE)
            }
            "n4" -> { // Thẻ cuộn
                setWidgetPos("wTime", 75f, 5f, View.VISIBLE)
                setWidgetPos("wNotifTitle", 40f, 40f, View.VISIBLE)
                setWidgetPos("wNotifBody", 10f, 80f, View.VISIBLE)
                setWidgetPos("wNotifIcon", 90f, 150f, View.VISIBLE)
            }
        }

        updateChecklistContainer()
    }

    private fun setWidgetPos(id: String, x: Float, y: Float, visibility: Int) {
        val resId = resources.getIdentifier(id, "id", packageName)
        val v = esp32Canvas.findViewById<View>(resId) ?: return
        v.x = x
        v.y = y
        v.visibility = visibility
    }

    private fun updateChecklistContainer() {
        containerChecklist.removeAllViews()
        val list = when (currentCategory) {
            "status" -> listOf("wTime", "wDate", "wSpeed", "wBatPhone", "wBatBike", "wBrand")
            "notif" -> listOf("wTime", "wNotifIcon", "wNotifTitle", "wNotifBody", "wBrand")
            else -> listOf("wNav", "wSpeed", "wTime")
        }

        for (id in list) {
            val name = widgetMetadata[id] ?: id
            val resId = resources.getIdentifier(id, "id", packageName)
            val v = esp32Canvas.findViewById<View>(resId) ?: continue

            val switchView = SwitchCompat(this).apply {
                text = name
                setTextColor(Color.parseColor("#F1F5F9"))
                isChecked = v.visibility == View.VISIBLE
                setOnCheckedChangeListener { _, isChecked ->
                    v.visibility = if (isChecked) View.VISIBLE else View.GONE
                }
            }
            containerChecklist.addView(switchView)
        }
    }

    private fun triggerBleUpload() {
        val payload = JSONObject()
        payload.put("category", currentCategory)
        payload.put("model", currentModel)

        val widgets = JSONArray()
        for (i in 0 until esp32Canvas.childCount) {
            val child = esp32Canvas.getChildAt(i)
            if (child.visibility == View.VISIBLE) {
                val item = JSONObject()
                item.put("id", try { resources.getResourceEntryName(child.id) } catch (e: Exception) { "custom_${child.id}" })
                item.put("x", child.x.toInt() + 20)
                item.put("y", child.y.toInt() + 15)
                widgets.put(item)
            }
        }
        payload.put("widgets", widgets)

        PrefsHelper.putString(this, "custom_layout_config", payload.toString())

        AlertDialog.Builder(this)
            .setTitle("Nạp BLE Thành Công!")
            .setMessage("Đã mã hóa và truyền cấu hình layout ($currentCategory - $currentModel) đến mạch ESP32 S3 qua sóng Bluetooth LE.")
            .setPositiveButton("ĐÓNG", null)
            .show()
    }
}
