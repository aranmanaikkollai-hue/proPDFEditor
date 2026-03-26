"""
setup_project.py — Creates all Android resource files needed by ProPDF Editor.
"""
import os, struct, zlib

def mkdir(p): os.makedirs(p, exist_ok=True)
def write(path, content):
    if not os.path.exists(path):
        with open(path, "w") as f: f.write(content)
        print(f"  created: {path}")

# ── Directories ────────────────────────────────────────────
for d in [
    "app/src/main/res/layout",
    "app/src/main/res/values",
    "app/src/main/res/menu",
    "app/src/main/res/xml",
    "app/src/main/res/navigation",
    "app/src/main/res/drawable",
    "app/src/main/res/color",
]:
    mkdir(d)
for density in ["mdpi","hdpi","xhdpi","xxhdpi","xxxhdpi"]:
    mkdir(f"app/src/main/res/mipmap-{density}")

# ── strings.xml ────────────────────────────────────────────
write("app/src/main/res/values/strings.xml", '''\
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">ProPDF Editor</string>
    <string name="open_pdf">Open PDF</string>
    <string name="tools">Tools</string>
    <string name="scan">Scan</string>
    <string name="settings">Settings</string>
    <string name="cancel">Cancel</string>
    <string name="done">Done</string>
    <string name="share">Share</string>
    <string name="nav_home">Home</string>
    <string name="nav_tools">Tools</string>
    <string name="nav_scan">Scan</string>
    <string name="nav_settings">Settings</string>
</resources>
''')

# ── colors.xml — NO bottom_nav_color here (it lives in res/color/ as a selector) ──
write("app/src/main/res/values/colors.xml", '''\
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="primary">#1A73E8</color>
    <color name="primary_dark">#1557B0</color>
    <color name="accent">#E53935</color>
    <color name="white">#FFFFFF</color>
    <color name="black">#000000</color>
    <color name="viewer_background">#2B2B2B</color>
</resources>
''')

# ── themes.xml ─────────────────────────────────────────────
write("app/src/main/res/values/themes.xml", '''\
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <style name="Theme.ProPDF" parent="Theme.MaterialComponents.DayNight.NoActionBar">
        <item name="colorPrimary">@color/primary</item>
        <item name="colorPrimaryDark">@color/primary_dark</item>
        <item name="colorAccent">@color/accent</item>
        <item name="android:statusBarColor">@color/primary_dark</item>
    </style>
</resources>
''')

# ── file_paths.xml ─────────────────────────────────────────
write("app/src/main/res/xml/file_paths.xml", '''\
<?xml version="1.0" encoding="utf-8"?>
<paths>
    <files-path          name="internal_files"  path="."/>
    <cache-path          name="cache_files"     path="."/>
    <external-path       name="external_files"  path="."/>
    <external-files-path name="app_external"    path="."/>
    <external-cache-path name="app_ext_cache"   path="."/>
</paths>
''')

# ── bottom_nav_color selector (color state list — NOT in values/) ──
write("app/src/main/res/color/bottom_nav_color.xml", '''\
<?xml version="1.0" encoding="utf-8"?>
<selector xmlns:android="http://schemas.android.com/apk/res/android">
    <item android:color="#1A73E8" android:state_checked="true"/>
    <item android:color="#757575"/>
</selector>
''')

# ── Nav graph ──────────────────────────────────────────────
write("app/src/main/res/navigation/nav_graph.xml", '''\
<?xml version="1.0" encoding="utf-8"?>
<navigation xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:id="@+id/nav_graph"
    app:startDestination="@id/homeFragment">
    <fragment android:id="@+id/homeFragment"     android:name="com.propdf.editor.ui.HomeFragment"     android:label="Home"/>
    <fragment android:id="@+id/filesFragment"    android:name="com.propdf.editor.ui.FilesFragment"    android:label="Files"/>
    <fragment android:id="@+id/settingsFragment" android:name="com.propdf.editor.ui.SettingsFragment" android:label="Settings"/>
</navigation>
''')

# ── Drawable vector icons ──────────────────────────────────
icons = {
    "ic_add":      "M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z",
    "ic_home":     "M10 20v-6h4v6h5v-8h3L12 3 2 12h3v8z",
    "ic_folder":   "M10 4H4c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V8c0-1.1-.9-2-2-2h-8l-2-2z",
    "ic_edit":     "M3 17.25V21h3.75L17.81 9.94l-3.75-3.75L3 17.25zM20.71 7.04c.39-.39.39-1.02 0-1.41l-2.34-2.34c-.39-.39-1.02-.39-1.41 0l-1.83 1.83 3.75 3.75 1.83-1.83z",
    "ic_settings": "M12 15.5A3.5 3.5 0 0 1 8.5 12 3.5 3.5 0 0 1 12 8.5a3.5 3.5 0 0 1 3.5 3.5 3.5 3.5 0 0 1-3.5 3.5m7.43-2.92c.04-.36.07-.73.07-1.08s-.03-.73-.07-1.08l2.32-1.82c.21-.16.27-.45.14-.68l-2.2-3.82c-.13-.22-.41-.3-.64-.22l-2.74 1.1c-.57-.44-1.18-.8-1.84-1.07L14.2 2.42c-.04-.25-.25-.42-.5-.42h-4.4c-.25 0-.46.17-.5.42l-.41 2.91c-.66.27-1.27.63-1.84 1.07L4.11 5.3c-.23-.08-.51 0-.64.22l-2.2 3.82c-.14.23-.07.52.14.68L3.73 11.5c-.04.36-.07.73-.07 1.08s.03.73.07 1.08L1.41 15.48c-.21.16-.27.45-.14.68l2.2 3.82c.13.22.41.3.64.22l2.74-1.1c.57.44 1.18.8 1.84 1.07l.41 2.91c.04.25.25.42.5.42h4.4c.25 0 .46-.17.5-.42l.41-2.91c.66-.27 1.27-.63 1.84-1.07l2.74 1.1c.23.08.51 0 .64-.22l2.2-3.82c.14-.23.07-.52-.14-.68l-2.32-1.82z",
    "ic_search":   "M15.5 14h-.79l-.28-.27A6.471 6.471 0 0 0 16 9.5 6.5 6.5 0 0 0 9.5 3 6.5 6.5 0 0 0 3 9.5 6.5 6.5 0 0 0 9.5 16c1.61 0 3.09-.59 4.23-1.57l.27.28v.79l5 4.99L20.49 19l-4.99-5zm-6 0C7.01 14 5 11.99 5 9.5S7.01 5 9.5 5 14 7.01 14 9.5 11.99 14 9.5 14z",
    "ic_flash_on": "M7 2v11h3v9l7-12h-4l4-8z",
    "ic_flash_off":"M3.27 3L2 4.27l5 5V13h3v9l3.58-6.14L17.73 20 19 18.73 3.27 3zM17 10h-4l4-8H7v2.18l8.46 8.46L17 10z",
    "ic_more_vert":"M12 8c1.1 0 2-.9 2-2s-.9-2-2-2-2 .9-2 2 .9 2 2 2zm0 2c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2zm0 6c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2z",
    "ic_bookmark": "M17 3H7c-1.1 0-1.99.9-1.99 2L5 21l7-3 7 3V5c0-1.1-.9-2-2-2z",
}
for name, path in icons.items():
    fpath = f"app/src/main/res/drawable/{name}.xml"
    if os.path.exists(fpath): continue
    with open(fpath, "w") as f:
        f.write(f'<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
                f'    android:width="24dp" android:height="24dp"\n'
                f'    android:viewportWidth="24" android:viewportHeight="24">\n'
                f'    <path android:fillColor="#FF000000" android:pathData="{path}"/>\n'
                f'</vector>\n')
    print(f"  created: {fpath}")

# bg_bottom_sheet
fpath = "app/src/main/res/drawable/bg_bottom_sheet.xml"
if not os.path.exists(fpath):
    with open(fpath, "w") as f:
        f.write('<shape xmlns:android="http://schemas.android.com/apk/res/android">\n'
                '    <solid android:color="#FFFFFF"/>\n'
                '    <corners android:topLeftRadius="16dp" android:topRightRadius="16dp"/>\n'
                '</shape>\n')

# ── Launcher PNG icons ─────────────────────────────────────
def make_png(size, r, g, b):
    def chunk(tag, data):
        crc = zlib.crc32(tag + data) & 0xffffffff
        return struct.pack('>I', len(data)) + tag + data + struct.pack('>I', crc)
    ihdr = struct.pack('>IIBBBBB', size, size, 8, 2, 0, 0, 0)
    raw  = b''
    cx, cy = size//2, size//2
    for y in range(size):
        raw += b'\x00'
        for x in range(size):
            d = ((x-cx)**2 + (y-cy)**2) ** 0.5
            raw += bytes([r,g,b]) if d < size*0.45 else bytes([240,244,255])
    png  = b'\x89PNG\r\n\x1a\n'
    png += chunk(b'IHDR', ihdr)
    png += chunk(b'IDAT', zlib.compress(raw))
    png += chunk(b'IEND', b'')
    return png

for density, size in [("mdpi",48),("hdpi",72),("xhdpi",96),("xxhdpi",144),("xxxhdpi",192)]:
    for name in ["ic_launcher.png","ic_launcher_round.png"]:
        p = f"app/src/main/res/mipmap-{density}/{name}"
        if not os.path.exists(p):
            with open(p,"wb") as f: f.write(make_png(size,26,115,232))
            print(f"  created: {p}")

# ── Stub fragments ─────────────────────────────────────────
frags_dir = "app/src/main/java/com/propdf/editor/ui"
os.makedirs(frags_dir, exist_ok=True)
for frag in ["HomeFragment","FilesFragment","SettingsFragment"]:
    p = f"{frags_dir}/{frag}.kt"
    if not os.path.exists(p):
        with open(p,"w") as f:
            f.write(f"package com.propdf.editor.ui\n"
                    f"import android.os.Bundle\nimport android.view.*\nimport android.widget.TextView\n"
                    f"import androidx.fragment.app.Fragment\n"
                    f"class {frag}:Fragment(){{\n"
                    f"    override fun onCreateView(i:LayoutInflater,c:ViewGroup?,s:Bundle?):View=\n"
                    f"        TextView(requireContext()).apply{{text=\"{frag}\"}}\n}}\n")
        print(f"  created: {p}")

print("\nsetup_project.py complete ✅")
