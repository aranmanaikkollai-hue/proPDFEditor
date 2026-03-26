"""
setup_project.py - Creates all Android resource files needed by ProPDF Editor.
All icons use android.R.drawable references in code, so no custom icons needed.
"""
import os, struct, zlib

def mkdir(p): os.makedirs(p, exist_ok=True)
def write(path, content):
    if not os.path.exists(path):
        with open(path, "w") as f: f.write(content)
        print(f"  created: {path}")

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

write("app/src/main/res/values/strings.xml", '''\
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">ProPDF Editor</string>
    <string name="open_pdf">Open PDF</string>
    <string name="nav_home">Home</string>
    <string name="nav_tools">Tools</string>
    <string name="nav_scan">Scan</string>
    <string name="nav_settings">Settings</string>
</resources>
''')

write("app/src/main/res/values/colors.xml", '''\
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="primary">#1A73E8</color>
    <color name="primary_dark">#1557B0</color>
    <color name="accent">#E53935</color>
    <color name="white">#FFFFFF</color>
    <color name="black">#000000</color>
    <color name="viewer_background">#424242</color>
</resources>
''')

write("app/src/main/res/values/themes.xml", '''\
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <style name="Theme.ProPDF" parent="Theme.MaterialComponents.DayNight.NoActionBar">
        <item name="colorPrimary">@color/primary</item>
        <item name="colorPrimaryDark">@color/primary_dark</item>
        <item name="colorAccent">@color/accent</item>
        <item name="android:statusBarColor">@color/primary_dark</item>
        <item name="android:windowBackground">@color/white</item>
    </style>
</resources>
''')

write("app/src/main/res/xml/file_paths.xml", '''\
<?xml version="1.0" encoding="utf-8"?>
<paths>
    <files-path          name="internal"    path="."/>
    <cache-path          name="cache"       path="."/>
    <external-path       name="external"    path="."/>
    <external-files-path name="app_ext"     path="."/>
    <external-cache-path name="ext_cache"   path="."/>
    <external-path       name="downloads"   path="Download/"/>
</paths>
''')

write("app/src/main/res/color/bottom_nav_color.xml", '''\
<?xml version="1.0" encoding="utf-8"?>
<selector xmlns:android="http://schemas.android.com/apk/res/android">
    <item android:color="#1A73E8" android:state_checked="true"/>
    <item android:color="#757575"/>
</selector>
''')

write("app/src/main/res/navigation/nav_graph.xml", '''\
<?xml version="1.0" encoding="utf-8"?>
<navigation xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:id="@+id/nav_graph"
    app:startDestination="@id/homeFragment">
    <fragment android:id="@+id/homeFragment"
        android:name="com.propdf.editor.ui.HomeFragment"
        android:label="Home"/>
</navigation>
''')

# FAB uses a text-based approach -- no custom vector needed
# But we still generate ic_add for the FAB srcCompat reference
icons = {
    "ic_add":     "M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z",
    "ic_home":    "M10 20v-6h4v6h5v-8h3L12 3 2 12h3v8z",
    "ic_edit":    "M3 17.25V21h3.75L17.81 9.94l-3.75-3.75L3 17.25zM20.71 7.04c.39-.39.39-1.02 0-1.41l-2.34-2.34c-.39-.39-1.02-.39-1.41 0l-1.83 1.83 3.75 3.75 1.83-1.83z",
    "ic_folder":  "M10 4H4c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V8c0-1.1-.9-2-2-2h-8l-2-2z",
    "ic_settings":"M19.14 12.94c.04-.3.06-.61.06-.94s-.02-.64-.07-.94l2.03-1.58c.18-.14.23-.41.12-.61l-1.92-3.32c-.12-.22-.37-.29-.59-.22l-2.39.96c-.5-.38-1.03-.7-1.62-.94l-.36-2.54c-.04-.24-.24-.41-.48-.41h-3.84c-.24 0-.43.17-.47.41l-.36 2.54c-.59.24-1.13.57-1.62.94l-2.39-.96c-.22-.08-.47 0-.59.22L2.74 8.87c-.12.21-.08.47.12.61l2.03 1.58c-.05.3-.09.63-.09.94s.02.64.07.94l-2.03 1.58c-.18.14-.23.41-.12.61l1.92 3.32c.12.22.37.29.59.22l2.39-.96c.5.38 1.03.7 1.62.94l.36 2.54c.05.24.24.41.48.41h3.84c.24 0 .44-.17.47-.41l.36-2.54c.59-.24 1.13-.56 1.62-.94l2.39.96c.22.08.47 0 .59-.22l1.92-3.32c.12-.22.07-.47-.12-.61l-2.01-1.58zM12 15.6c-1.98 0-3.6-1.62-3.6-3.6s1.62-3.6 3.6-3.6 3.6 1.62 3.6 3.6-1.62 3.6-3.6 3.6z",
}

for name, path in icons.items():
    fpath = f"app/src/main/res/drawable/{name}.xml"
    if os.path.exists(fpath): continue
    with open(fpath, "w") as f:
        f.write(
            '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            '    android:width="24dp" android:height="24dp"\n'
            '    android:viewportWidth="24" android:viewportHeight="24"\n'
            '    android:tint="@color/white">\n'
            f'    <path android:fillColor="#FFFFFFFF" android:pathData="{path}"/>\n'
            '</vector>\n'
        )
    print(f"  created: {fpath}")

# Launcher icons (blue circle with white P)
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
            with open(p, "wb") as f: f.write(make_png(size, 26, 115, 232))
            print(f"  created: {p}")

# Stub fragments
frags_dir = "app/src/main/java/com/propdf/editor/ui"
os.makedirs(frags_dir, exist_ok=True)
for frag in ["HomeFragment","FilesFragment","SettingsFragment"]:
    p = f"{frags_dir}/{frag}.kt"
    if not os.path.exists(p):
        with open(p, "w") as f:
            f.write(
                f"package com.propdf.editor.ui\n"
                f"import android.os.Bundle\nimport android.view.*\n"
                f"import android.widget.TextView\n"
                f"import androidx.fragment.app.Fragment\n"
                f"class {frag}:Fragment(){{\n"
                f"    override fun onCreateView(i:LayoutInflater,c:ViewGroup?,s:Bundle?):View=\n"
                f"        TextView(requireContext()).apply{{text=\"{frag}\"}}\n}}\n"
            )
        print(f"  created: {p}")

print("\nsetup_project.py complete")
