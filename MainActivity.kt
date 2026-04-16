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
    private lateinit var fileRecyclerView: RecyclerView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Setup your UI...
        
        // Example of connecting the long-press to a category view
        val categoryView = findViewById<View>(android.R.id.content) // Replace with your Category View ID
        categoryView.setOnLongClickListener {
            showAddSubcategoryDialog()
            true
        }
    }

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

    private fun showAddSubcategoryDialog() {
        val input = EditText(this).apply { hint = "Subcategory Name" }
        android.app.AlertDialog.Builder(this)
            .setTitle("Add Subcategory")
            .setView(input)
            .setPositiveButton("Add") { _, _ ->
                val name = input.text.toString()
                Toast.makeText(this, "Added: $name", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}

