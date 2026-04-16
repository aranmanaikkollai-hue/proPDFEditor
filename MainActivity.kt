package com.propdf.editor.ui

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager    // ADDED
import androidx.recyclerview.widget.LinearLayoutManager  // ADDED
import androidx.recyclerview.widget.RecyclerView         // ADDED
import com.propdf.editor.ui.viewer.ViewerActivity
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private var currentViewMode = 0 // 0: List, 1: Grid, 2: Tiles
    private lateinit var recyclerView: RecyclerView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Your layout initialization here
    }

    // Logic for the View Toggle (Grid/List/Tiles)
    private fun toggleDashboardView() {
        if (!::recyclerView.isInitialized) return
        
        currentViewMode = (currentViewMode + 1) % 3
        
        when (currentViewMode) {
            0 -> recyclerView.layoutManager = LinearLayoutManager(this)
            1 -> recyclerView.layoutManager = GridLayoutManager(this, 2)
            2 -> recyclerView.layoutManager = GridLayoutManager(this, 3)
        }
        recyclerView.adapter?.notifyDataSetChanged()
    }

    // Logic for Long-Press Subcategory
    private fun setupCategoryLongClick(view: View, categoryName: String) {
        view.setOnLongClickListener {
            val input = EditText(this).apply { hint = "Subcategory Name" }
            android.app.AlertDialog.Builder(this)
                .setTitle("Add Subcategory to $categoryName")
                .setView(input)
                .setPositiveButton("Add") { _, _ ->
                    val name = input.text.toString()
                    Toast.makeText(this, "Created $name", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
            true
        }
    }
}
