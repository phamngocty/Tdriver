# Liquid Navigation & Cockpit Glassmorphism App Shell Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement a modern Liquid Floating Bubble Bottom Navigation (`LiquidBubbleNavView`) and Cockpit Dark Glassmorphic App Shell for the TYMAP Android App.

**Architecture:** Replace the standard flat `BottomNavigationView` with a custom floating capsule layout (`LiquidBubbleNavView`) featuring a animated floating circle bubble Y-offset (-16dp) for the active tab using `OvershootInterpolator`. Connect seamlessly with `ViewPager2` in `MainActivity.kt`.

**Tech Stack:** Android SDK (Kotlin), ViewBinding, ConstraintLayout, Android Animation (`ValueAnimator`, `OvershootInterpolator`), Gradle (`./gradlew assembleDebug`).

---

### Task 1: Declare Cockpit Glassmorphic Color Tokens & Drawables

**Files:**
- Modify: `TYMAP/app/src/main/res/values/colors.xml`
- Create: `TYMAP/app/src/main/res/drawable/bg_liquid_nav_capsule.xml`
- Create: `TYMAP/app/src/main/res/drawable/bg_liquid_active_bubble.xml`

- [ ] **Step 1: Add new color tokens to `colors.xml`**

Add the dark cockpit glassmorphism palette (`colorBackgroundDeep`, `colorSurfaceGlass`, `colorAccentCyan`, `colorAccentPurple`, `colorTextMuted`) to `TYMAP/app/src/main/res/values/colors.xml`.

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="purple_200">#FFBB86FC</color>
    <color name="purple_500">#FF6200EE</color>
    <color name="purple_700">#FF3700B3</color>
    <color name="teal_200">#FF03DAC5</color>
    <color name="teal_700">#FF018786</color>
    <color name="black">#FF000000</color>
    <color name="white">#FFFFFFFF</color>
    
    <!-- Cockpit Dark Glassmorphism Palette -->
    <color name="colorBackgroundDeep">#090D16</color>
    <color name="colorSurfaceGlass">#D90F172A</color>
    <color name="colorSurfaceGlassBorder">#1FFFFFFF</color>
    <color name="colorAccentCyan">#38BDF8</color>
    <color name="colorAccentPurple">#8B5CF6</color>
    <color name="colorAccentPurpleStart">#6366F1</color>
    <color name="colorTextMuted">#64748B</color>
</resources>
```

- [ ] **Step 2: Create Capsule Background `bg_liquid_nav_capsule.xml`**

Create `TYMAP/app/src/main/res/drawable/bg_liquid_nav_capsule.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android"
    android:shape="rectangle">
    <solid android:color="@color/colorSurfaceGlass" />
    <corners android:radius="32dp" />
    <stroke
        android:width="1dp"
        android:color="@color/colorSurfaceGlassBorder" />
</shape>
```

- [ ] **Step 3: Create Active Bubble Drawable `bg_liquid_active_bubble.xml`**

Create `TYMAP/app/src/main/res/drawable/bg_liquid_active_bubble.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android"
    android:shape="oval">
    <gradient
        android:angle="135"
        android:endColor="@color/colorAccentPurple"
        android:startColor="@color/colorAccentPurpleStart"
        android:type="linear" />
</shape>
```

- [ ] **Step 4: Verify build**

Run: `cmd /c "cd /d d:\Documents\PlatformIO\Tdriver\TYMAP && gradlew.bat assembleDebug"`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add TYMAP/app/src/main/res/values/colors.xml TYMAP/app/src/main/res/drawable/bg_liquid_nav_capsule.xml TYMAP/app/src/main/res/drawable/bg_liquid_active_bubble.xml
git commit -m "feat(ui): add color tokens and glassmorphism drawables for liquid nav"
```

---

### Task 2: Implement `LiquidBubbleNavView` Custom Component

**Files:**
- Create: `TYMAP/app/src/main/res/layout/layout_liquid_bubble_nav.xml`
- Create: `TYMAP/app/src/main/java/com/example/tymap/ui/LiquidBubbleNavView.kt`

- [ ] **Step 1: Create Layout XML `layout_liquid_bubble_nav.xml`**

Create `TYMAP/app/src/main/res/layout/layout_liquid_bubble_nav.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="64dp"
    android:background="@drawable/bg_liquid_nav_capsule"
    android:clipChildren="false"
    android:clipToPadding="false"
    android:elevation="12dp"
    android:paddingStart="8dp"
    android:paddingEnd="8dp">

    <LinearLayout
        android:id="@+id/navContainer"
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:clipChildren="false"
        android:clipToPadding="false"
        android:gravity="center_vertical"
        android:orientation="horizontal">

        <!-- Tab 0: Map -->
        <LinearLayout
            android:id="@+id/tabMap"
            android:layout_width="0dp"
            android:layout_height="match_parent"
            android:layout_weight="1"
            android:clipChildren="false"
            android:clipToPadding="false"
            android:gravity="center"
            android:orientation="vertical">

            <FrameLayout
                android:id="@+id/bubbleMap"
                android:layout_width="42dp"
                android:layout_height="42dp"
                android:gravity="center">

                <ImageView
                    android:id="@+id/iconMap"
                    android:layout_width="22dp"
                    android:layout_height="22dp"
                    android:layout_gravity="center"
                    android:src="@drawable/ic_map"
                    app:tint="@color/colorTextMuted" />
            </FrameLayout>

            <TextView
                android:id="@+id/labelMap"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:layout_marginTop="2dp"
                android:text="@string/nav_map"
                android:textColor="@color/colorTextMuted"
                android:textSize="10sp" />
        </LinearLayout>

        <!-- Tab 1: Settings -->
        <LinearLayout
            android:id="@+id/tabSettings"
            android:layout_width="0dp"
            android:layout_height="match_parent"
            android:layout_weight="1"
            android:clipChildren="false"
            android:clipToPadding="false"
            android:gravity="center"
            android:orientation="vertical">

            <FrameLayout
                android:id="@+id/bubbleSettings"
                android:layout_width="42dp"
                android:layout_height="42dp"
                android:gravity="center">

                <ImageView
                    android:id="@+id/iconSettings"
                    android:layout_width="22dp"
                    android:layout_height="22dp"
                    android:layout_gravity="center"
                    android:src="@drawable/ic_settings"
                    app:tint="@color/colorTextMuted" />
            </FrameLayout>

            <TextView
                android:id="@+id/labelSettings"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:layout_marginTop="2dp"
                android:text="Cài đặt"
                android:textColor="@color/colorTextMuted"
                android:textSize="10sp" />
        </LinearLayout>

        <!-- Tab 2: Notifications -->
        <LinearLayout
            android:id="@+id/tabNotifications"
            android:layout_width="0dp"
            android:layout_height="match_parent"
            android:layout_weight="1"
            android:clipChildren="false"
            android:clipToPadding="false"
            android:gravity="center"
            android:orientation="vertical">

            <FrameLayout
                android:id="@+id/bubbleNotifications"
                android:layout_width="42dp"
                android:layout_height="42dp"
                android:gravity="center">

                <ImageView
                    android:id="@+id/iconNotifications"
                    android:layout_width="22dp"
                    android:layout_height="22dp"
                    android:layout_gravity="center"
                    android:src="@drawable/ic_notifications"
                    app:tint="@color/colorTextMuted" />
            </FrameLayout>

            <TextView
                android:id="@+id/labelNotifications"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:layout_marginTop="2dp"
                android:text="@string/nav_notifications"
                android:textColor="@color/colorTextMuted"
                android:textSize="10sp" />
        </LinearLayout>

        <!-- Tab 3: Render (Dynamic) -->
        <LinearLayout
            android:id="@+id/tabRender"
            android:layout_width="0dp"
            android:layout_height="match_parent"
            android:layout_weight="1"
            android:clipChildren="false"
            android:clipToPadding="false"
            android:gravity="center"
            android:orientation="vertical"
            android:visibility="gone">

            <FrameLayout
                android:id="@+id/bubbleRender"
                android:layout_width="42dp"
                android:layout_height="42dp"
                android:gravity="center">

                <ImageView
                    android:id="@+id/iconRender"
                    android:layout_width="22dp"
                    android:layout_height="22dp"
                    android:layout_gravity="center"
                    android:src="@drawable/ic_layers"
                    app:tint="@color/colorTextMuted" />
            </FrameLayout>

            <TextView
                android:id="@+id/labelRender"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:layout_marginTop="2dp"
                android:text="Render"
                android:textColor="@color/colorTextMuted"
                android:textSize="10sp" />
        </LinearLayout>

    </LinearLayout>
</FrameLayout>
```

- [ ] **Step 2: Create Custom View Class `LiquidBubbleNavView.kt`**

Create `TYMAP/app/src/main/java/com/example/tymap/ui/LiquidBubbleNavView.kt`:

```kotlin
package com.example.tymap.ui

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.example.tymap.R
import com.example.tymap.databinding.LayoutLiquidBubbleNavBinding

class LiquidBubbleNavView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val binding: LayoutLiquidBubbleNavBinding =
        LayoutLiquidBubbleNavBinding.inflate(LayoutInflater.from(context), this, true)

    private var selectedIndex = 0
    private var onItemSelectedListener: ((Int) -> Unit)? = null

    private val tabs: List<View> by lazy {
        listOf(binding.tabMap, binding.tabSettings, binding.tabNotifications, binding.tabRender)
    }

    private val bubbles: List<FrameLayout> by lazy {
        listOf(binding.bubbleMap, binding.bubbleSettings, binding.bubbleNotifications, binding.bubbleRender)
    }

    private val icons: List<ImageView> by lazy {
        listOf(binding.iconMap, binding.iconSettings, binding.iconNotifications, binding.iconRender)
    }

    private val labels: List<TextView> by lazy {
        listOf(binding.labelMap, binding.labelSettings, binding.labelNotifications, binding.labelRender)
    }

    init {
        clipChildren = false
        clipToPadding = false

        tabs.forEachIndexed { index, tabView ->
            tabView.setOnClickListener {
                if (selectedIndex != index) {
                    setSelectedTab(index, animate = true)
                    onItemSelectedListener?.invoke(index)
                }
            }
        }
        
        // Initial setup
        post {
            setSelectedTab(0, animate = false)
        }
    }

    fun setOnItemSelectedListener(listener: (Int) -> Unit) {
        this.onItemSelectedListener = listener
    }

    fun setSelectedTab(position: Int, animate: Boolean = true) {
        if (position < 0 || position >= tabs.size) return
        selectedIndex = position

        tabs.forEachIndexed { index, _ ->
            val bubble = bubbles[index]
            val icon = icons[index]
            val label = labels[index]
            val isSelected = index == position

            val targetTranslationY = if (isSelected) -dpToPx(14f) else 0f
            val targetScale = if (isSelected) 1.12f else 1.0f

            if (isSelected) {
                bubble.setBackgroundResource(R.drawable.bg_liquid_active_bubble)
                icon.setColorFilter(ContextCompat.getColor(context, R.color.white))
                label.setTextColor(ContextCompat.getColor(context, R.color.colorAccentCyan))
            } else {
                bubble.background = null
                icon.setColorFilter(ContextCompat.getColor(context, R.color.colorTextMuted))
                label.setTextColor(ContextCompat.getColor(context, R.color.colorTextMuted))
            }

            if (animate) {
                bubble.animate()
                    .translationY(targetTranslationY)
                    .scaleX(targetScale)
                    .scaleY(targetScale)
                    .setDuration(280)
                    .setInterpolator(OvershootInterpolator(1.4f))
                    .start()
            } else {
                bubble.translationY = targetTranslationY
                bubble.scaleX = targetScale
                bubble.scaleY = targetScale
            }
        }
    }

    fun setRenderTabVisible(visible: Boolean) {
        binding.tabRender.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun dpToPx(dp: Float): Float {
        return dp * context.resources.displayMetrics.density
    }
}
```

- [ ] **Step 3: Verify build**

Run: `cmd /c "cd /d d:\Documents\PlatformIO\Tdriver\TYMAP && gradlew.bat assembleDebug"`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add TYMAP/app/src/main/res/layout/layout_liquid_bubble_nav.xml TYMAP/app/src/main/java/com/example/tymap/ui/LiquidBubbleNavView.kt
git commit -m "feat(ui): implement LiquidBubbleNavView custom component with fluid animation"
```

---

### Task 3: Integrate `LiquidBubbleNavView` into `MainActivity` Layout & Activity

**Files:**
- Modify: `TYMAP/app/src/main/res/layout/activity_main.xml`
- Modify: `TYMAP/app/src/main/java/com/example/tymap/MainActivity.kt`

- [ ] **Step 1: Replace BottomNavigationView in `activity_main.xml`**

Update `TYMAP/app/src/main/res/layout/activity_main.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<androidx.constraintlayout.widget.ConstraintLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="@color/colorBackgroundDeep">

    <androidx.viewpager2.widget.ViewPager2
        android:id="@+id/viewPager"
        android:layout_width="match_parent"
        android:layout_height="0dp"
        app:layout_constraintBottom_toTopOf="@id/bottomNavigation"
        app:layout_constraintTop_toTopOf="parent" />

    <com.example.tymap.ui.LiquidBubbleNavView
        android:id="@+id/bottomNavigation"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_marginStart="16dp"
        android:layout_marginEnd="16dp"
        android:layout_marginBottom="12dp"
        app:layout_constraintBottom_toBottomOf="parent"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintStart_toStartOf="parent" />

</androidx.constraintlayout.widget.ConstraintLayout>
```

- [ ] **Step 2: Connect `LiquidBubbleNavView` in `MainActivity.kt`**

Modify `setupViewPager()`, `setupBottomNavigation()`, and `updateRenderTabVisibility()` in `TYMAP/app/src/main/java/com/example/tymap/MainActivity.kt`:

```kotlin
    private fun setupViewPager() {
        val adapter = MainPagerAdapter(this)
        binding.viewPager.adapter = adapter
        binding.viewPager.isUserInputEnabled = false // Disable swiping
        binding.viewPager.offscreenPageLimit = 3 // Keep all tabs in memory (4 fragments)

        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                binding.bottomNavigation.setSelectedTab(position, animate = true)
            }
        })
    }

    private fun setupBottomNavigation() {
        binding.bottomNavigation.setOnItemSelectedListener { position ->
            binding.viewPager.currentItem = position
        }
    }

    fun updateRenderTabVisibility() {
        val isUnlocked = com.example.tymap.utils.PrefsHelper.getBoolean(this, "render_tab_unlocked", false)
        binding.bottomNavigation.setRenderTabVisible(isUnlocked)
    }
```

- [ ] **Step 3: Run full APK build to verify zero compilation errors**

Run: `cmd /c "cd /d d:\Documents\PlatformIO\Tdriver\TYMAP && gradlew.bat assembleDebug"`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add TYMAP/app/src/main/res/layout/activity_main.xml TYMAP/app/src/main/java/com/example/tymap/MainActivity.kt
git commit -m "feat(ui): integrate LiquidBubbleNavView into MainActivity"
```

---

### Task 4: Final Verification & Plan Completion

- [ ] **Step 1: Execute complete debug build**

Run: `cmd /c "cd /d d:\Documents\PlatformIO\Tdriver\TYMAP && gradlew.bat assembleDebug"`
Expected: BUILD SUCCESSFUL (APK generated at `TYMAP/app/build/outputs/apk/debug/app-debug.apk`)

- [ ] **Step 2: Commit plan completion**

```bash
git commit --allow-empty -m "chore: complete Liquid Bottom Navigation implementation"
```
