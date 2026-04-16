package com.propdf.editor.ui

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager    // FIXED IMPORT
import androidx.recyclerview.widget.LinearLayoutManager  // FIXED IMPORT
import androidx.recyclerview.widget.RecyclerView         // FIXED IMPORT
import com.propdf.editor.ui.viewer.ViewerActivity
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private var currentViewMode = 0 // 0: List, 1: Grid, 2: Tiles
    private lateinit var fileRecyclerView: RecyclerView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Your existing UI initialization...
    }

    // Improved Toggle Logic
    private fun toggleDashboardView() {
        if (!::fileRecyclerView.isInitialized) return
        
        currentViewMode = (currentViewMode + 1) % 3
        when (currentViewMode) {
            0 -> fileRecyclerView.layoutManager = LinearLayoutManager(this)
            1 -> fileRecyclerView.layoutManager = GridLayoutManager(this, 2)
            2 -> fileRecyclerView.layoutManager = GridLayoutManager(this, 3)
        }
        fileRecyclerView.adapter?.notifyDataSetChanged()
    }

    // Improved Subcategory Logic
    private fun onCategoryLongClick(categoryName: String) {
        val input = EditText(this).apply { hint = "New folder name" }
        android.app.AlertDialog.Builder(this)
            .setTitle("Create Subcategory in $categoryName")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                Toast.makeText(this, "Created: ${input.text}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
