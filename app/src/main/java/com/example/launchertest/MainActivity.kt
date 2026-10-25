package com.example.ioslauncher // <-- ОСТАВЬ СВОЙ PACKAGE ИЗ СТАРОГО ФАЙЛА!

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.*
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.*
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.min

class MainActivity : Activity() {

    // Модели данных
    data class AppInfo(
        val label: String,
        val packageName: String,
        val icon: Drawable
    )

    sealed class HomeItem {
        data class App(val appInfo: AppInfo) : HomeItem()
        data class Folder(var name: String, val apps: MutableList<AppInfo>) : HomeItem()
    }

    enum class WidgetType { CLOCK, BATTERY, CALENDAR }

    private lateinit var prefs: SharedPreferences
    private val allApps = mutableListOf<AppInfo>()
    private val homeItems = mutableListOf<HomeItem>()
    private val activeWidgets = mutableListOf<WidgetType>()

    // Настройки
    private var isHorizontalMode = true // true = свайп влево-вправо, false = сверху-вниз
    private var currentPage = 0
    private val columns = 4
    private val itemsPerPage = 16 // 4x4 на странице (если есть виджеты, всё помещается идеально)

    // UI элементы
    private lateinit var rootLayout: FrameLayout
    private lateinit var mainColumn: LinearLayout
    private lateinit var widgetsContainer: LinearLayout
    private lateinit var appsContainer: FrameLayout
    private lateinit var pageIndicatorLayout: LinearLayout
    private lateinit var searchBar: LinearLayout
    private lateinit var dockLayout: LinearLayout

    private val clockHandler = Handler(Looper.getMainLooper())
    private val clockRunnable = object : Runnable {
        override fun run() {
            updateWidgetsUI()
            clockHandler.postDelayed(this, 10000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupTransparentSystemBars()

        prefs = getSharedPreferences("ios_launcher_prefs", Context.MODE_PRIVATE)
        isHorizontalMode = prefs.getBoolean("horizontal_mode", true)

        loadActiveWidgets()
        loadInstalledApps()
        buildHomeItemsFromState()

        setupUI()
    }

    override fun onResume() {
        super.onResume()
        clockHandler.post(clockRunnable)
        // Обновляем список на случай удаления приложения
        val prevCount = allApps.size
        loadInstalledApps()
        if (allApps.size != prevCount) {
            buildHomeItemsFromState()
            renderAppsArea()
            renderDock()
        }
    }

    override fun onPause() {
        super.onPause()
        clockHandler.removeCallbacks(clockRunnable)
    }

    override fun onBackPressed() {
        // В лаунчере кнопка "Назад" не должна закрывать рабочий стол
    }

    private fun setupTransparentSystemBars() {
        window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
        window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
    }

    // ==================== ЗАГРУЗКА ДАННЫХ И СОХРАНЕНИЕ ====================

    private fun loadInstalledApps() {
        allApps.clear()
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolveInfos = pm.queryIntentActivities(intent, 0)
        for (ri in resolveInfos) {
            val pkg = ri.activityInfo.packageName
            if (pkg == packageName) continue
            val label = ri.loadLabel(pm).toString()
            val icon = ri.loadIcon(pm)
            allApps.add(AppInfo(label, pkg, icon))
        }
        allApps.sortBy { it.label.lowercase(Locale.getDefault()) }
    }

    private fun loadActiveWidgets() {
        activeWidgets.clear()
        val saved = prefs.getString("active_widgets", "CLOCK") ?: "CLOCK"
        if (saved.isNotEmpty()) {
            saved.split(",").forEach { name ->
                runCatching { activeWidgets.add(WidgetType.valueOf(name)) }
            }
        }
    }

    private fun saveActiveWidgets() {
        prefs.edit().putString("active_widgets", activeWidgets.joinToString(",") { it.name }).apply()
    }

    private fun buildHomeItemsFromState() {
        homeItems.clear()
        val appMap = allApps.associateBy { it.packageName }.toMutableMap()

        // Загружаем папки из JSON
        val foldersJson = prefs.getString("folders_json", "[]") ?: "[]"
        runCatching {
            val arr = JSONArray(foldersJson)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val folderName = obj.getString("name")
                val pkgs = obj.getJSONArray("apps")
                val folderApps = mutableListOf<AppInfo>()
                for (j in 0 until pkgs.length()) {
                    val pkg = pkgs.getString(j)
                    appMap.remove(pkg)?.let { folderApps.add(it) }
                }
                if (folderApps.isNotEmpty()) {
                    homeItems.add(HomeItem.Folder(folderName, folderApps))
                }
            }
        }

        // Загружаем порядок остальных приложений
        val orderCsv = prefs.getString("apps_order", "") ?: ""
        if (orderCsv.isNotEmpty()) {
            orderCsv.split(",").forEach { pkg ->
                appMap.remove(pkg)?.let { homeItems.add(HomeItem.App(it)) }
            }
        }

        // Добавляем оставшиеся новые приложения
        appMap.values.forEach { homeItems.add(HomeItem.App(it)) }
    }

    private fun saveHomeState() {
        val foldersArr = JSONArray()
        val orderList = mutableListOf<String>()

        for (item in homeItems) {
            when (item) {
                is HomeItem.Folder -> {
                    if (item.apps.isNotEmpty()) {
                        val obj = JSONObject()
                        obj.put("name", item.name)
                        val pkgs = JSONArray()
                        item.apps.forEach { pkgs.put(it.packageName) }
                        obj.put("apps", pkgs)
                        foldersArr.put(obj)
                    }
                }
                is HomeItem.App -> {
                    orderList.add(item.appInfo.packageName)
                }
            }
        }

        prefs.edit()
            .putString("folders_json", foldersArr.toString())
            .putString("apps_order", orderList.joinToString(","))
            .apply()
    }

    // ==================== ПОСТРОЕНИЕ ИНТЕРФЕЙСА ====================

    private fun setupUI() {
        rootLayout = FrameLayout(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            setOnLongClickListener {
                showLauncherSettingsDialog()
                true
            }
        }

        mainColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // Отступы сверху под статус-бар и снизу под жесты навигации
            setPadding(dp(12), dp(42), dp(12), dp(24))
        }

        // 1. Зона виджетов
        widgetsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        mainColumn.addView(
            widgetsContainer,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        // 2. Зона сетки приложений (занимает всё свободное место посередине)
        appsContainer = FrameLayout(this)
        mainColumn.addView(
            appsContainer,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            ).apply {
                topMargin = dp(8)
                bottomMargin = dp(4)
            }
        )

        // 3. Индикатор страниц (точки как в iOS)
        pageIndicatorLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        mainColumn.addView(
            pageIndicatorLayout,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(20)
            )
        )

        // 4. Строка поиска + кнопка настроек лаунчера
        searchBar = createSearchBar()
        mainColumn.addView(
            searchBar,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(44)
            ).apply {
                topMargin = dp(6)
                bottomMargin = dp(10)
                leftMargin = dp(8)
                rightMargin = dp(8)
            }
        )

        // 5. Нижний док (4 иконки)
        dockLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = createGlassDrawable(cornerRadiusDp = 28f, alpha = 85)
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        mainColumn.addView(
            dockLayout,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(88)
            ).apply {
                leftMargin = dp(4)
                rightMargin = dp(4)
            }
        )

        rootLayout.addView(
            mainColumn,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        setContentView(rootLayout)

        updateWidgetsUI()
        renderAppsArea()
        renderDock()
    }

    // ==================== ВИДЖЕТЫ (ИНТЕРАКТИВНЫЕ) ====================

    private fun updateWidgetsUI() {
        widgetsContainer.removeAllViews()
        if (activeWidgets.isEmpty()) return

        for ((index, widgetType) in activeWidgets.withIndex()) {
            val widgetView = when (widgetType) {
                WidgetType.CLOCK -> buildClockWidget()
                WidgetType.BATTERY -> buildBatteryWidget()
                WidgetType.CALENDAR -> buildCalendarWidget()
            }

            // Долгий тап по любому виджету открывает меню управления виджетами
            widgetView.setOnLongClickListener {
                showWidgetContextMenu(widgetType, index)
                true
            }

            widgetsContainer.addView(
                widgetView,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(8)
                }
            )
        }
    }

    private fun buildClockWidget(): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = createGlassDrawable(cornerRadiusDp = 26f, alpha = 90)
            setPadding(dp(22), dp(16), dp(22), dp(18))
            isClickable = true
            // Клик по виджету часов открывает системный будильник / часы
            setOnClickListener {
                runCatching {
                    startActivity(Intent(AlarmClock.ACTION_SHOW_ALARMS).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    })
                }.onFailure {
                    Toast.makeText(
                        this@MainActivity,
                        "Удерживайте виджет для настройки",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        val dateFormat = SimpleDateFormat("EEEE, d MMMM", Locale("ru"))
        val now = Date()

        val timeTv = TextView(this).apply {
            text = timeFormat.format(now)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 52f)
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            setShadowLayer(8f, 0f, 2f, Color.argb(80, 0, 0, 0))
        }

        val dateTv = TextView(this).apply {
            text = dateFormat.format(now)
            setTextColor(Color.argb(235, 255, 255, 255))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setShadowLayer(6f, 0f, 1f, Color.argb(80, 0, 0, 0))
        }

        box.addView(timeTv)
        box.addView(dateTv)
        return box
    }

    private fun buildBatteryWidget(): View {
        val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = createGlassDrawable(cornerRadiusDp = 22f, alpha = 90)
            setPadding(dp(20), dp(14), dp(20), dp(14))
            setOnClickListener {
                runCatching { startActivity(Intent(Intent.ACTION_POWER_USAGE_SUMMARY)) }
            }
        }

        val titleTv = TextView(this).apply {
            text = "🔋 Заряд аккумулятора: $level%"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.DEFAULT_BOLD
        }

        box.addView(titleTv)
        return box
    }

    private fun buildCalendarWidget(): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = createGlassDrawable(cornerRadiusDp = 22f, alpha = 90)
            setPadding(dp(20), dp(14), dp(20), dp(14))
            setOnClickListener {
                showLauncherSettingsDialog()
            }
        }

        val noteText = prefs.getString("custom_widget_note", "Нажмите и удерживайте для настройки виджетов и папок")
        val tv = TextView(this).apply {
            text = "📌 Заметка дня:\n$noteText"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        }
        box.addView(tv)
        return box
    }

    private fun showWidgetContextMenu(widgetType: WidgetType, index: Int) {
        val options = arrayOf(
            "➕ Добавить другой виджет",
            "⬆️ Переместить виджет выше",
            "⬇️ Переместить виджет ниже",
            "✏️ Изменить текст заметки (для виджета заметки)",
            "🗑️ Удалить этот виджет"
        )

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Управление виджетом")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showAddWidgetDialog()
                    1 -> if (index > 0) {
                        Collections.swap(activeWidgets, index, index - 1)
                        saveActiveWidgets()
                        updateWidgetsUI()
                    }
                    2 -> if (index < activeWidgets.size - 1) {
                        Collections.swap(activeWidgets, index, index + 1)
                        saveActiveWidgets()
                        updateWidgetsUI()
                    }
                    3 -> showEditNoteDialog()
                    4 -> {
                        activeWidgets.removeAt(index)
                        saveActiveWidgets()
                        updateWidgetsUI()
                    }
                }
            }
            .show()
    }

    private fun showAddWidgetDialog() {
        val names = arrayOf("🕒 Большие часы (iOS Glass)", "🔋 Виджет батареи", "📌 Виджет быстрой заметки")
        val types = arrayOf(WidgetType.CLOCK, WidgetType.BATTERY, WidgetType.CALENDAR)

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Добавить виджет на экран")
            .setItems(names) { _, which ->
                val selected = types[which]
                if (!activeWidgets.contains(selected)) {
                    activeWidgets.add(selected)
                    saveActiveWidgets()
                    updateWidgetsUI()
                } else {
                    Toast.makeText(this, "Этот виджет уже добавлен", Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun showEditNoteDialog() {
        val input = EditText(this).apply {
            setText(prefs.getString("custom_widget_note", ""))
            setTextColor(Color.WHITE)
        }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Текст заметки в виджете")
            .setView(input)
            .setPositiveButton("Сохранить") { _, _ ->
                prefs.edit().putString("custom_widget_note", input.text.toString()).apply()
                updateWidgetsUI()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    // ==================== СЕТКА ПРИЛОЖЕНИЙ (ГОРИЗОНТАЛЬНАЯ И ВЕРТИКАЛЬНАЯ) ====================

    private fun renderAppsArea() {
        appsContainer.removeAllViews()
        pageIndicatorLayout.removeAllViews()

        if (isHorizontalMode) {
            renderHorizontalPagedGrid()
        } else {
            renderVerticalScrollGrid()
        }
    }

    // Режим 1: Свайп влево-вправо (страницы как в iOS)
    private fun renderHorizontalPagedGrid() {
        pageIndicatorLayout.visibility = View.VISIBLE
        val totalPages = maxOf(1, ceil(homeItems.size.toDouble() / itemsPerPage).toInt())
        if (currentPage >= totalPages) currentPage = totalPages - 1

        val pageContainer = FrameLayout(this)

        val startIndex = currentPage * itemsPerPage
        val endIndex = min(startIndex + itemsPerPage, homeItems.size)
        val pageSlice = if (startIndex < homeItems.size) homeItems.subList(startIndex, endIndex) else emptyList()

        val gridView = buildGridForItems(pageSlice, startIndex)
        pageContainer.addView(
            gridView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        // Обработка свайпов влево и вправо
        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            private val SWIPE_THRESHOLD = 90
            private val SWIPE_VELOCITY_THRESHOLD = 90

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                if (e1 == null) return false
                val diffX = e2.x - e1.x
                val diffY = e2.y - e1.y
                if (abs(diffX) > abs(diffY) &&
                    abs(diffX) > SWIPE_THRESHOLD &&
                    abs(velocityX) > SWIPE_VELOCITY_THRESHOLD
                ) {
                    if (diffX < 0 && currentPage < totalPages - 1) {
                        currentPage++
                        renderAppsArea()
                        return true
                    } else if (diffX > 0 && currentPage > 0) {
                        currentPage--
                        renderAppsArea()
                        return true
                    }
                }
                return false
            }
        })

        appsContainer.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            true
        }

        // Пробрасываем свайп даже если палец начал движение поверх иконки
        gridView.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            false
        }

        appsContainer.addView(pageContainer)
        updatePageDots(totalPages)
    }

    private fun updatePageDots(totalPages: Int) {
        pageIndicatorLayout.removeAllViews()
        for (i in 0 until totalPages) {
            val dot = View(this).apply {
                val size = if (i == currentPage) dp(8) else dp(6)
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    leftMargin = dp(4)
                    rightMargin = dp(4)
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(
                        if (i == currentPage) Color.WHITE
                        else Color.argb(110, 255, 255, 255)
                    )
                }
            }
            pageIndicatorLayout.addView(dot)
        }
    }

    // Режим 2: Свайп сверху-вниз (вертикальный скролл без наезжания на часы и док)
    private fun renderVerticalScrollGrid() {
        pageIndicatorLayout.visibility = View.GONE
        appsContainer.setOnTouchListener(null)

        val scrollView = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
        }

        val gridView = buildGridForItems(homeItems, 0)
        scrollView.addView(gridView)
        appsContainer.addView(
            scrollView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
    }

    private fun buildGridForItems(items: List<HomeItem>, globalOffset: Int): LinearLayout {
        val verticalRows = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val totalRows = ceil(items.size.toDouble() / columns).toInt()
            for (r in 0 until totalRows) {
                val rowLayout = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    weightSum = columns.toFloat()
                }
                for (c in 0 until columns) {
                    val localIdx = r * columns + c
                    if (localIdx < items.size) {
                        val globalIdx = globalOffset + localIdx
                        val cell = createHomeItemView(items[localIdx], globalIdx)
                        rowLayout.addView(
                            cell,
                            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                                topMargin = dp(8)
                                bottomMargin = dp(8)
                            }
                        )
                    } else {
                        rowLayout.addView(
                            Space(this@MainActivity),
                            LinearLayout.LayoutParams(0, dp(10), 1f)
                        )
                    }
                }
                addView(rowLayout)
            }
        }
        return verticalRows
    }

    // ==================== ОТРИСОВКА ИКОНКИ ИЛИ ПАПКИ ====================

    private fun createHomeItemView(item: HomeItem, globalIndex: Int): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }

        when (item) {
            is HomeItem.App -> {
                val iconView = ImageView(this).apply {
                    setImageDrawable(item.appInfo.icon)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }
                val labelView = TextView(this).apply {
                    text = item.appInfo.label
                    setTextColor(Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    gravity = Gravity.CENTER
                    setShadowLayer(5f, 0f, 1f, Color.BLACK)
                }

                container.addView(iconView, LinearLayout.LayoutParams(dp(58), dp(58)))
                container.addView(
                    labelView,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(4) }
                )

                // Обычный тап — запуск приложения
                container.setOnClickListener {
                    launchApp(item.appInfo.packageName)
                }

                // Долгий тап — всплывающее окно действий (Удалить, О приложении, Папки, Перемещение)
                container.setOnLongClickListener {
                    showAppLongPressPopup(container, item.appInfo, globalIndex)
                    true
                }
            }

            is HomeItem.Folder -> {
                // Иконка папки с мини-превью 2x2 внутри стеклянного квадрата
                val folderBox = GridLayout(this).apply {
                    rowCount = 2
                    columnCount = 2
                    background = createGlassDrawable(cornerRadiusDp = 16f, alpha = 110)
                    setPadding(dp(6), dp(6), dp(6), dp(6))
                }

                val previewApps = item.apps.take(4)
                for (app in previewApps) {
                    val miniIcon = ImageView(this).apply {
                        setImageDrawable(app.icon)
                        layoutParams = GridLayout.LayoutParams().apply {
                            width = dp(20)
                            height = dp(20)
                            setMargins(dp(2), dp(2), dp(2), dp(2))
                        }
                    }
                    folderBox.addView(miniIcon)
                }

                val labelView = TextView(this).apply {
                    text = item.name
                    setTextColor(Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    gravity = Gravity.CENTER
                    setShadowLayer(5f, 0f, 1f, Color.BLACK)
                }

                container.addView(folderBox, LinearLayout.LayoutParams(dp(58), dp(58)))
                container.addView(
                    labelView,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(4) }
                )

                container.setOnClickListener {
                    showFolderDialog(item, globalIndex)
                }

                container.setOnLongClickListener {
                    showFolderLongPressMenu(item, globalIndex)
                    true
                }
            }
        }

        return container
    }

    // ==================== ВСПЛЫВАЮЩЕЕ МЕНЮ ПРИ УДЕРЖАНИИ ТАПА НА ПРИЛОЖЕНИИ ====================

    private fun showAppLongPressPopup(anchor: View, app: AppInfo, index: Int) {
        val popupLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(Color.argb(235, 38, 38, 42))
                setStroke(dp(1), Color.argb(90, 255, 255, 255))
            }
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }

        lateinit var popupWindow: PopupWindow

        fun addMenuRow(title: String, textColor: Int = Color.WHITE, onClick: () -> Unit) {
            val tv = TextView(this).apply {
                text = title
                setTextColor(textColor)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setPadding(dp(16), dp(11), dp(16), dp(11))
                setOnClickListener {
                    popupWindow.dismiss()
                    onClick()
                }
            }
            popupLayout.addView(tv)
        }

        addMenuRow("ℹ️  О приложении") {
            openAppSettings(app.packageName)
        }
        addMenuRow("🗑️  Удалить приложение", Color.rgb(255, 95, 87)) {
            uninstallApp(app.packageName)
        }
        addMenuRow("📁  В папку / Создать папку") {
            showMoveToFolderDialog(app, index)
        }
        addMenuRow("⬅️  Сдвинуть влево / выше") {
            if (index > 0) {
                Collections.swap(homeItems, index, index - 1)
                saveHomeState()
                renderAppsArea()
            }
        }
        addMenuRow("➡️  Сдвинуть вправо / ниже") {
            if (index < homeItems.size - 1) {
                Collections.swap(homeItems, index, index + 1)
                saveHomeState()
                renderAppsArea()
            }
        }

        popupWindow = PopupWindow(
            popupLayout,
            dp(240),
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            elevation = 24f
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            showAsDropDown(anchor, 0, -dp(20))
        }
    }

    // ==================== РАБОТА С ПАПКАМИ ====================

    private fun showMoveToFolderDialog(app: AppInfo, currentIndex: Int) {
        val existingFolders = homeItems.filterIsInstance<HomeItem.Folder>()
        val options = mutableListOf("➕ Создать новую папку")
        existingFolders.forEach { options.add("📁 ${it.name}") }

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Переместить «${app.label}»")
            .setItems(options.toTypedArray()) { _, which ->
                if (which == 0) {
                    // Создаём новую папку
                    val input = EditText(this).apply {
                        hint = "Название папки"
                        setText("Новая папка")
                        setTextColor(Color.WHITE)
                    }
                    AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                        .setTitle("Создать папку")
                        .setView(input)
                        .setPositiveButton("Создать") { _, _ ->
                            val name = input.text.toString().ifBlank { "Папка" }
                            homeItems[currentIndex] = HomeItem.Folder(name, mutableListOf(app))
                            saveHomeState()
                            renderAppsArea()
                        }
                        .setNegativeButton("Отмена", null)
                        .show()
                } else {
                    val targetFolder = existingFolders[which - 1]
                    targetFolder.apps.add(app)
                    homeItems.removeAt(currentIndex)
                    saveHomeState()
                    renderAppsArea()
                }
            }
            .show()
    }

    private fun showFolderDialog(folder: HomeItem.Folder, folderIndex: Int) {
        val dialogLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
            background = createGlassDrawable(cornerRadiusDp = 28f, alpha = 215)
        }

        val titleView = TextView(this).apply {
            text = folder.name
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(14))
        }
        dialogLayout.addView(titleView)

        val grid = GridLayout(this).apply {
            columnCount = 3
        }

        lateinit var alert: AlertDialog

        for ((appIdx, app) in folder.apps.withIndex()) {
            val itemBox = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(10), dp(10), dp(10), dp(10))
                setOnClickListener {
                    alert.dismiss()
                    launchApp(app.packageName)
                }
                setOnLongClickListener {
                    alert.dismiss()
                    // Долгий тап внутри папки — вытащить из папки или удалить
                    val actions = arrayOf(
                        "Вытащить на главный экран",
                        "О приложении",
                        "Удалить приложение"
                    )
                    AlertDialog.Builder(this@MainActivity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                        .setTitle(app.label)
                        .setItems(actions) { _, w ->
                            when (w) {
                                0 -> {
                                    folder.apps.removeAt(appIdx)
                                    homeItems.add(HomeItem.App(app))
                                    if (folder.apps.isEmpty()) homeItems.remove(folder)
                                    saveHomeState()
                                    renderAppsArea()
                                }
                                1 -> openAppSettings(app.packageName)
                                2 -> uninstallApp(app.packageName)
                            }
                        }
                        .show()
                    true
                }
            }

            val icon = ImageView(this).apply {
                setImageDrawable(app.icon)
                layoutParams = LinearLayout.LayoutParams(dp(50), dp(50))
            }
            val label = TextView(this).apply {
                text = app.label
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            itemBox.addView(icon)
            itemBox.addView(label)
            grid.addView(itemBox)
        }

        dialogLayout.addView(grid)

        alert = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setView(dialogLayout)
            .setNeutralButton("Переименовать") { _, _ ->
                renameFolderDialog(folder)
            }
            .setNegativeButton("Закрыть", null)
            .create()

        alert.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        alert.show()
    }

    private fun showFolderLongPressMenu(folder: HomeItem.Folder, index: Int) {
        val items = arrayOf(
            "✏️ Переименовать папку",
            "⬅️ Сдвинуть влево",
            "➡️ Сдвинуть вправо",
            "📂 Расформировать папку (вернуть иконки на экран)"
        )
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(folder.name)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> renameFolderDialog(folder)
                    1 -> if (index > 0) {
                        Collections.swap(homeItems, index, index - 1)
                        saveHomeState()
                        renderAppsArea()
                    }
                    2 -> if (index < homeItems.size - 1) {
                        Collections.swap(homeItems, index, index + 1)
                        saveHomeState()
                        renderAppsArea()
                    }
                    3 -> {
                        homeItems.removeAt(index)
                        folder.apps.forEach { homeItems.add(HomeItem.App(it)) }
                        saveHomeState()
                        renderAppsArea()
                    }
                }
            }
            .show()
    }

    private fun renameFolderDialog(folder: HomeItem.Folder) {
        val input = EditText(this).apply {
            setText(folder.name)
            setTextColor(Color.WHITE)
        }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Переименовать папку")
            .setView(input)
            .setPositiveButton("Сохранить") { _, _ ->
                folder.name = input.text.toString().ifBlank { "Папка" }
                saveHomeState()
                renderAppsArea()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    // ==================== ПОИСК, ДОК И НАСТРОЙКИ ЛАУНЧЕРА ====================

    private fun createSearchBar(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = createGlassDrawable(cornerRadiusDp = 22f, alpha = 90)
            setPadding(dp(18), dp(6), dp(14), dp(6))
        }

        val searchTv = TextView(this).apply {
            text = "🔍  Поиск приложений..."
            setTextColor(Color.argb(220, 255, 255, 255))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { showSearchDialog() }
        }

        val settingsBtn = TextView(this).apply {
            text = "⚙️"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setPadding(dp(8), dp(2), dp(4), dp(2))
            setOnClickListener { showLauncherSettingsDialog() }
        }

        bar.addView(searchTv)
        bar.addView(settingsBtn)
        return bar
    }

    private fun showSearchDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        val input = EditText(this).apply {
            hint = "Введите название приложения..."
        }
        val listView = ListView(this)
        layout.addView(input)
        layout.addView(listView)

        val dialog = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Поиск")
            .setView(layout)
            .setNegativeButton("Закрыть", null)
            .create()

        fun updateList(query: String) {
            val filtered = allApps.filter { it.label.contains(query, ignoreCase = true) }
            val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, filtered.map { it.label })
            listView.adapter = adapter
            listView.setOnItemClickListener { _, _, pos, _ ->
                dialog.dismiss()
                launchApp(filtered[pos].packageName)
            }
        }

        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                updateList(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        updateList("")
        dialog.show()
    }

    private fun showLauncherSettingsDialog() {
        val currentModeText = if (isHorizontalMode) "Свайп влево-вправо (iOS страницы)" else "Свайп сверху-вниз (Вертикально)"
        val items = arrayOf(
            "🔄 Режим прокрутки: $currentModeText",
            "➕ Добавить виджет на главный экран",
            "📁 Создать новую папку",
            "♻️ Сбросить расположение иконок"
        )

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Настройки лаунчера")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> {
                        isHorizontalMode = !isHorizontalMode
                        prefs.edit().putBoolean("horizontal_mode", isHorizontalMode).apply()
                        currentPage = 0
                        renderAppsArea()
                        val modeName = if (isHorizontalMode) "Свайп влево-вправо" else "Свайп сверху-вниз"
                        Toast.makeText(this, "Включен режим: $modeName", Toast.LENGTH_SHORT).show()
                    }
                    1 -> showAddWidgetDialog()
                    2 -> {
                        if (allApps.isNotEmpty()) {
                            val firstAppIndex = homeItems.indexOfFirst { it is HomeItem.App }
                            if (firstAppIndex != -1) {
                                val app = (homeItems[firstAppIndex] as HomeItem.App).appInfo
                                showMoveToFolderDialog(app, firstAppIndex)
                            }
                        }
                    }
                    3 -> {
                        prefs.edit().remove("folders_json").remove("apps_order").apply()
                        buildHomeItemsFromState()
                        renderAppsArea()
                    }
                }
            }
            .show()
    }

    private fun renderDock() {
        dockLayout.removeAllViews()
        val dockApps = allApps.take(4)
        for (app in dockApps) {
            val iconView = ImageView(this).apply {
                setImageDrawable(app.icon)
                layoutParams = LinearLayout.LayoutParams(0, dp(60), 1f)
                scaleType = ImageView.ScaleType.FIT_CENTER
                setOnClickListener { launchApp(app.packageName) }
                setOnLongClickListener {
                    showAppLongPressPopup(this, app, 0)
                    true
                }
            }
            dockLayout.addView(iconView)
        }
    }

    // ==================== СИСТЕМНЫЕ ДЕЙСТВИЯ (ЗАПУСК, УДАЛЕНИЕ, О ПРИЛОЖЕНИИ) ====================

    private fun launchApp(pkg: String) {
        val launchIntent = packageManager.getLaunchIntentForPackage(pkg)
        if (launchIntent != null) {
            startActivity(launchIntent)
        }
    }

    private fun uninstallApp(pkg: String) {
        val intent = Intent(Intent.ACTION_DELETE).apply {
            data = Uri.parse("package:$pkg")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(intent)
    }

    private fun openAppSettings(pkg: String) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:$pkg")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(intent)
    }

    // ==================== ВСПОМОГАТЕЛЬНЫЕ МЕТОДЫ СТИЛЯ ====================

    private fun createGlassDrawable(cornerRadiusDp: Float, alpha: Int): Drawable {
        return GradientDrawable().apply {
            cornerRadius = dp(cornerRadiusDp.toInt()).toFloat()
            setColor(Color.argb(alpha, 255, 255, 255))
            setStroke(dp(1), Color.argb(130, 255, 255, 255))
        }
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()
    }
}

package com.example.launchertest

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = TextView(this).apply {
            text = "Сборка через CircleCI работает"
            textSize = 22f
            gravity = Gravity.CENTER
        }
        setContentView(root)
    }
}
