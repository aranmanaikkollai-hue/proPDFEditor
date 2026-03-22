import os, shutil

# ── Nav graph ────────────────────────────────────────────────
os.makedirs("app/src/main/res/navigation", exist_ok=True)
with open("app/src/main/res/navigation/nav_graph.xml", "w") as f:
    f.write('<?xml version="1.0" encoding="utf-8"?>\n'
    '<navigation xmlns:android="http://schemas.android.com/apk/res/android"\n'
    '    xmlns:app="http://schemas.android.com/apk/res-auto"\n'
    '    android:id="@+id/nav_graph"\n'
    '    app:startDestination="@id/homeFragment">\n'
    '    <fragment android:id="@+id/homeFragment" android:name="com.propdf.editor.ui.HomeFragment" android:label="Home" />\n'
    '    <fragment android:id="@+id/filesFragment" android:name="com.propdf.editor.ui.FilesFragment" android:label="Files" />\n'
    '    <fragment android:id="@+id/cloudFragment" android:name="com.propdf.editor.ui.CloudFragment" android:label="Cloud" />\n'
    '    <fragment android:id="@+id/settingsFragment" android:name="com.propdf.editor.ui.SettingsFragment" android:label="Settings" />\n'
    '</navigation>\n')
print("Created nav_graph.xml")

# ── strings.xml ──────────────────────────────────────────────
os.makedirs("app/src/main/res/values", exist_ok=True)
if not os.path.exists("app/src/main/res/values/strings.xml"):
    with open("app/src/main/res/values/strings.xml", "w") as f:
        f.write('<?xml version="1.0" encoding="utf-8"?>\n'
        '<resources>\n'
        '    <string name="app_name">ProPDF Editor</string>\n'
        '    <string name="nav_home">Home</string>\n'
        '    <string name="nav_files">Files</string>\n'
        '    <string name="nav_cloud">Cloud</string>\n'
        '    <string name="nav_settings">Settings</string>\n'
        '    <string name="open_pdf">Open PDF</string>\n'
        '    <string name="search_pdf">Search</string>\n'
        '    <string name="edit_pdf">Edit PDF</string>\n'
        '    <string name="share">Share</string>\n'
        '    <string name="cancel">Cancel</string>\n'
        '    <string name="done">Done</string>\n'
        '</resources>\n')
    print("Created strings.xml")

# ── colors.xml ───────────────────────────────────────────────
if not os.path.exists("app/src/main/res/values/colors.xml"):
    with open("app/src/main/res/values/colors.xml", "w") as f:
        f.write('<?xml version="1.0" encoding="utf-8"?>\n'
        '<resources>\n'
        '    <color name="primary">#1A73E8</color>\n'
        '    <color name="primary_dark">#1557B0</color>\n'
        '    <color name="accent">#E53935</color>\n'
        '    <color name="white">#FFFFFF</color>\n'
        '    <color name="black">#000000</color>\n'
        '    <color name="viewer_background">#3A3A3A</color>\n'
        '    <color name="control_overlay_bg">#99000000</color>\n'
        '    <color name="bottom_nav_color">#1A73E8</color>\n'
        '</resources>\n')
    print("Created colors.xml")

# ── themes.xml ───────────────────────────────────────────────
if not os.path.exists("app/src/main/res/values/themes.xml"):
    with open("app/src/main/res/values/themes.xml", "w") as f:
        f.write('<?xml version="1.0" encoding="utf-8"?>\n'
        '<resources>\n'
        '    <style name="Theme.ProPDF" parent="Theme.MaterialComponents.DayNight.NoActionBar">\n'
        '        <item name="colorPrimary">@color/primary</item>\n'
        '        <item name="colorPrimaryDark">@color/primary_dark</item>\n'
        '        <item name="colorAccent">@color/accent</item>\n'
        '    </style>\n'
        '    <style name="Theme.ProPDF.Viewer" parent="Theme.ProPDF">\n'
        '        <item name="android:windowBackground">@color/viewer_background</item>\n'
        '    </style>\n'
        '    <style name="Theme.ProPDF.Scanner" parent="Theme.MaterialComponents.NoActionBar">\n'
        '        <item name="android:windowBackground">@android:color/black</item>\n'
        '    </style>\n'
        '    <style name="BottomSheetDialogTheme" parent="ThemeOverlay.MaterialComponents.BottomSheetDialog" />\n'
        '</resources>\n')
    print("Created themes.xml")

# ── file_paths.xml ───────────────────────────────────────────
os.makedirs("app/src/main/res/xml", exist_ok=True)
if not os.path.exists("app/src/main/res/xml/file_paths.xml"):
    with open("app/src/main/res/xml/file_paths.xml", "w") as f:
        f.write('<?xml version="1.0" encoding="utf-8"?>\n'
        '<paths xmlns:android="http://schemas.android.com/apk/res/android">\n'
        '    <files-path name="internal_files" path="." />\n'
        '    <external-path name="external_files" path="." />\n'
        '    <external-files-path name="app_external" path="." />\n'
        '    <cache-path name="cache" path="." />\n'
        '</paths>\n')
    print("Created file_paths.xml")

# ── launcher icons (minimal PNG via Python) ──────────────────
import struct, zlib

def make_png(size, r, g, b):
    def chunk(name, data):
        c = zlib.crc32(name + data) & 0xffffffff
        return struct.pack('>I', len(data)) + name + data + struct.pack('>I', c)
    ihdr = struct.pack('>IIBBBBB', size, size, 8, 2, 0, 0, 0)
    raw = b''
    for y in range(size):
        raw += b'\x00'
        for x in range(size):
            # Blue circle on white background
            cx, cy = size//2, size//2
            dist = ((x-cx)**2 + (y-cy)**2) ** 0.5
            if dist < size*0.45:
                raw += bytes([r, g, b])
            else:
                raw += bytes([255, 255, 255])
    compressed = zlib.compress(raw)
    png = b'\x89PNG\r\n\x1a\n'
    png += chunk(b'IHDR', ihdr)
    png += chunk(b'IDAT', compressed)
    png += chunk(b'IEND', b'')
    return png

for density, size in [('mdpi',48),('hdpi',72),('xhdpi',96),('xxhdpi',144),('xxxhdpi',192)]:
    folder = f"app/src/main/res/mipmap-{density}"
    os.makedirs(folder, exist_ok=True)
    png = make_png(size, 26, 115, 232)  # #1A73E8 blue
    for name in ['ic_launcher.png', 'ic_launcher_round.png']:
        path = os.path.join(folder, name)
        if not os.path.exists(path):
            with open(path, 'wb') as f:
                f.write(png)
print("Created launcher icons")

# ── bottom_nav_colors.xml ────────────────────────────────────
if not os.path.exists("app/src/main/res/color/bottom_nav_colors.xml"):
    os.makedirs("app/src/main/res/color", exist_ok=True)
    with open("app/src/main/res/color/bottom_nav_colors.xml", "w") as f:
        f.write('<?xml version="1.0" encoding="utf-8"?>\n'
        '<selector xmlns:android="http://schemas.android.com/apk/res/android">\n'
        '    <item android:color="@color/primary" android:state_checked="true" />\n'
        '    <item android:color="#757575" />\n'
        '</selector>\n')
    print("Created bottom_nav_colors.xml")

# ── minimal drawable icons ────────────────────────────────────
os.makedirs("app/src/main/res/drawable", exist_ok=True)
icons = {
    'ic_add': 'M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z',
    'ic_home': 'M10 20v-6h4v6h5v-8h3L12 3 2 12h3v8z',
    'ic_folder': 'M10 4H4c-1.1 0-1.99.9-1.99 2L2 18c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V8c0-1.1-.9-2-2-2h-8l-2-2z',
    'ic_cloud': 'M19.35 10.04C18.67 6.59 15.64 4 12 4 9.11 4 6.6 5.64 5.35 8.04 2.34 8.36 0 10.91 0 14c0 3.31 2.69 6 6 6h13c2.76 0 5-2.24 5-5 0-2.64-2.05-4.78-4.65-4.96z',
    'ic_settings': 'M19.14,12.94c0.04-0.3,0.06-0.61,0.06-0.94c0-0.32-0.02-0.64-0.07-0.94l2.03-1.58c0.18-0.14,0.23-0.41,0.12-0.61 l-1.92-3.32c-0.12-0.22-0.37-0.29-0.59-0.22l-2.39,0.96c-0.5-0.38-1.03-0.7-1.62-0.94L14.4,2.81c-0.04-0.24-0.24-0.41-0.48-0.41 h-3.84c-0.24,0-0.43,0.17-0.47,0.41L9.25,5.35C8.66,5.59,8.12,5.92,7.63,6.29L5.24,5.33c-0.22-0.08-0.47,0-0.59,0.22L2.74,8.87 C2.62,9.08,2.66,9.34,2.86,9.48l2.03,1.58C4.84,11.36,4.8,11.69,4.8,12s0.02,0.64,0.07,0.94l-2.03,1.58 c-0.18,0.14-0.23,0.41-0.12,0.61l1.92,3.32c0.12,0.22,0.37,0.29,0.59,0.22l2.39-0.96c0.5,0.38,1.03,0.7,1.62,0.94l0.36,2.54 c0.05,0.24,0.24,0.41,0.48,0.41h3.84c0.24,0,0.44-0.17,0.47-0.41l0.36-2.54c0.59-0.24,1.13-0.56,1.62-0.94l2.39,0.96 c0.22,0.08,0.47,0,0.59-0.22l1.92-3.32c0.12-0.22,0.07-0.47-0.12-0.61L19.14,12.94z M12,15.6c-1.98,0-3.6-1.62-3.6-3.6 s1.62-3.6,3.6-3.6s3.6,1.62,3.6,3.6S13.98,15.6,12,15.6z',
    'ic_edit': 'M3 17.25V21h3.75L17.81 9.94l-3.75-3.75L3 17.25zM20.71 7.04c.39-.39.39-1.02 0-1.41l-2.34-2.34c-.39-.39-1.02-.39-1.41 0l-1.83 1.83 3.75 3.75 1.83-1.83z',
    'ic_search': 'M15.5 14h-.79l-.28-.27C15.41 12.59 16 11.11 16 9.5 16 5.91 13.09 3 9.5 3S3 5.91 3 9.5 5.91 16 9.5 16c1.61 0 3.09-.59 4.23-1.57l.27.28v.79l5 4.99L20.49 19l-4.99-5zm-6 0C7.01 14 5 11.99 5 9.5S7.01 5 9.5 5 14 7.01 14 9.5 11.99 14 9.5 14z',
    'ic_bookmark': 'M17 3H7c-1.1 0-1.99.9-1.99 2L5 21l7-3 7 3V5c0-1.1-.9-2-2-2z',
    'ic_more_vert': 'M12 8c1.1 0 2-.9 2-2s-.9-2-2-2-2 .9-2 2 .9 2 2 2zm0 2c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2zm0 6c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2z',
    'ic_flash_on': 'M7 2v11h3v9l7-12h-4l4-8z',
    'ic_flash_off': 'M3.27 3L2 4.27l5 5V13h3v9l3.58-6.14L17.73 20 19 18.73 3.27 3zM17 10h-4l4-8H7v2.18l8.46 8.46L17 10z',
}
for name, path in icons.items():
    fpath = f"app/src/main/res/drawable/{name}.xml"
    if not os.path.exists(fpath):
        with open(fpath, "w") as f:
            f.write(f'<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
                    f'    android:width="24dp" android:height="24dp"\n'
                    f'    android:viewportWidth="24" android:viewportHeight="24">\n'
                    f'    <path android:fillColor="#FF000000" android:pathData="{path}"/>\n'
                    f'</vector>\n')
print("Created drawable icons")

# ── bg_bottom_sheet.xml ──────────────────────────────────────
with open("app/src/main/res/drawable/bg_bottom_sheet.xml", "w") as f:
    f.write('<shape xmlns:android="http://schemas.android.com/apk/res/android">\n'
            '    <solid android:color="#FFFFFF"/>\n'
            '    <corners android:topLeftRadius="16dp" android:topRightRadius="16dp"/>\n'
            '</shape>\n')

# ── Stub Fragments ───────────────────────────────────────────
frags_dir = "app/src/main/java/com/propdf/editor/ui"
os.makedirs(frags_dir, exist_ok=True)
for frag in ["HomeFragment", "FilesFragment", "CloudFragment", "SettingsFragment"]:
    path = os.path.join(frags_dir, frag + ".kt")
    if not os.path.exists(path):
        with open(path, "w") as f:
            f.write(f"package com.propdf.editor.ui\n"
                    f"import android.os.Bundle\n"
                    f"import android.view.LayoutInflater\n"
                    f"import android.view.View\n"
                    f"import android.view.ViewGroup\n"
                    f"import android.widget.TextView\n"
                    f"import androidx.fragment.app.Fragment\n"
                    f"class {frag} : Fragment() {{\n"
                    f"    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View =\n"
                    f"        TextView(requireContext()).apply {{ text = \"{frag}\" }}\n"
                    f"}}\n")
        print(f"Created {frag}.kt")

print("setup_project.py done!")
