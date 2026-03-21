import os

# ── Create nav_graph.xml ─────────────────────────────────────
os.makedirs("app/src/main/res/navigation", exist_ok=True)

nav_xml = (
    '<?xml version="1.0" encoding="utf-8"?>\n'
    '<navigation xmlns:android="http://schemas.android.com/apk/res/android"\n'
    '    xmlns:app="http://schemas.android.com/apk/res-auto"\n'
    '    android:id="@+id/nav_graph"\n'
    '    app:startDestination="@id/homeFragment">\n'
    '    <fragment android:id="@+id/homeFragment"\n'
    '        android:name="com.propdf.editor.ui.HomeFragment"\n'
    '        android:label="Home" />\n'
    '    <fragment android:id="@+id/filesFragment"\n'
    '        android:name="com.propdf.editor.ui.FilesFragment"\n'
    '        android:label="Files" />\n'
    '    <fragment android:id="@+id/cloudFragment"\n'
    '        android:name="com.propdf.editor.ui.CloudFragment"\n'
    '        android:label="Cloud" />\n'
    '    <fragment android:id="@+id/settingsFragment"\n'
    '        android:name="com.propdf.editor.ui.SettingsFragment"\n'
    '        android:label="Settings" />\n'
    '</navigation>\n'
)

with open("app/src/main/res/navigation/nav_graph.xml", "w") as f:
    f.write(nav_xml)
print("Created nav_graph.xml")

# ── Create stub Fragment files ────────────────────────────────
frags_dir = "app/src/main/java/com/propdf/editor/ui"
os.makedirs(frags_dir, exist_ok=True)

for frag in ["HomeFragment", "FilesFragment", "CloudFragment", "SettingsFragment"]:
    path = os.path.join(frags_dir, frag + ".kt")
    if not os.path.exists(path):
        code = (
            "package com.propdf.editor.ui\n"
            "import android.os.Bundle\n"
            "import android.view.LayoutInflater\n"
            "import android.view.View\n"
            "import android.view.ViewGroup\n"
            "import android.widget.TextView\n"
            "import androidx.fragment.app.Fragment\n"
            "class " + frag + " : Fragment() {\n"
            "    override fun onCreateView(\n"
            "        inflater: LayoutInflater,\n"
            "        container: ViewGroup?,\n"
            "        savedInstanceState: Bundle?\n"
            "    ): View = TextView(requireContext()).apply { text = \"" + frag + "\" }\n"
            "}\n"
        )
        with open(path, "w") as f:
            f.write(code)
        print("Created " + frag + ".kt")

# ── Create MainAcivity stub if missing ───────────────────────
main_dir = "app/src/main/java/com/propdf/editor/ui"
main_path = os.path.join(main_dir, "MainActivity.kt")
if not os.path.exists(main_path):
    root_path = "MainActivity.kt"
    if os.path.exists(root_path):
        import shutil
        shutil.move(root_path, main_path)
        print("Moved MainActivity.kt to ui/")

print("setup_project.py done")
