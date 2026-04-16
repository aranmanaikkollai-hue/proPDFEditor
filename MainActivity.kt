package com.propdf.editor.ui

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.propdf.editor.ui.viewer.ViewerActivity
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private var currentViewMode = 0 // 0: List, 1: Grid, 2: Tiles
    private lateinit var fileList: RecyclerView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Find your toggle button and recycler view by IDs
        // Assuming your layout has these:
        // fileList = findViewById(R.id.recyclerView)
        // val toggleBtn = findViewById<Button>(R.id.btnToggleView)

        // toggleBtn.setOnClickListener { toggleDashboardView() }
    }

    private fun toggleDashboardView() {
        if (!::fileList.isInitialized) return
        
        currentViewMode = (currentViewMode + 1) % 3
        when (currentViewMode) {
            0 -> fileList.layoutManager = LinearLayoutManager(this)
            1 -> fileList.layoutManager = GridLayoutManager(this, 2)
            2 -> fileList.layoutManager = GridLayoutManager(this, 3)
        }
        fileList.adapter?.notifyDataSetChanged()
    }

    // Call this function when long-pressing a category
    private fun showSubcategoryDialog(categoryName: String) {
        val input = EditText(this).apply { 
            hint = "New Subcategory for $categoryName" 
        }
        
        android.app.AlertDialog.Builder(this)
            .setTitle("Add Subcategory")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString()
                Toast.makeText(this, "Subcategory '$name' created", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
