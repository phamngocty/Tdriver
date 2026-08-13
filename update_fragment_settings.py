with open('TYMAP/app/src/main/res/layout/fragment_settings.xml', 'r', encoding='utf-8') as f:
    xml = f.read()

# 1. Wrap OLED options in layoutOledSettings
# Find exact start and end strings
start_str = '                    <!-- Threshold -->\n'
end_str = '                        <com.google.android.material.switchmaterial.SwitchMaterial android:id="@+id/switchOledFilterInvert" android:layout_width="match_parent" android:layout_height="wrap_content" android:text="Đảo kết quả lọc" />\n                    </LinearLayout>'

if start_str in xml and end_str in xml:
    parts = xml.split(start_str, 1) # Split only at first occurrence
    before_oled = parts[0]
    rest = parts[1]
    
    parts2 = rest.split(end_str, 1) # Split only at first occurrence
    oled_content = parts2[0]
    after_oled = parts2[1]
    
    new_oled_content = '''
                    <LinearLayout
                        android:id="@+id/layoutOledSettings"
                        android:layout_width="match_parent"
                        android:layout_height="wrap_content"
                        android:orientation="vertical"
                        android:visibility="gone">

                    <!-- Threshold -->
''' + oled_content + end_str + '''
                    </LinearLayout>'''
    
    xml = before_oled + new_oled_content + after_oled
else:
    print("Could not find start or end tags for wrapping OLED settings")

# 2. Add switchManualShowOled to CardSystem
system_insert_point = '                    <!-- Time format -->\n'
new_switch = """                    <!-- Hiển thị OLED thủ công -->
                    <LinearLayout
                        android:layout_width="match_parent"
                        android:layout_height="wrap_content"
                        android:gravity="center_vertical"
                        android:orientation="horizontal"
                        android:paddingVertical="4dp">
                        <FrameLayout android:layout_width="34dp" android:layout_height="34dp" android:background="@drawable/bg_icon_tint" android:padding="7dp">
                            <ImageView android:layout_width="match_parent" android:layout_height="match_parent" android:src="@drawable/ic_layers" app:tint="@color/colorAccentCyan" />
                        </FrameLayout>
                        <LinearLayout
                            android:layout_width="0dp"
                            android:layout_height="wrap_content"
                            android:layout_weight="1"
                            android:layout_marginStart="12dp"
                            android:orientation="vertical">
                            <TextView android:layout_width="wrap_content" android:layout_height="wrap_content" android:text="Hiện Cài đặt OLED (Thủ công)" android:textSize="13sp" android:textColor="#F1F5F9" />
                            <TextView android:layout_width="wrap_content" android:layout_height="wrap_content" android:text="Tự động ẩn khi không dùng màn OLED" android:textSize="10sp" android:textColor="#94A3B8" />
                        </LinearLayout>
                        <com.google.android.material.switchmaterial.SwitchMaterial
                            android:id="@+id/switchManualShowOled"
                            android:layout_width="wrap_content"
                            android:layout_height="wrap_content" />
                    </LinearLayout>

                    <!-- Time format -->
"""

if system_insert_point in xml:
    xml = xml.replace(system_insert_point, new_switch)
else:
    print("Could not find system insert point")

with open('TYMAP/app/src/main/res/layout/fragment_settings.xml', 'w', encoding='utf-8') as f:
    f.write(xml)
print("Updated fragment_settings.xml flawlessly")
