package com.example.launchertest

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = TextView(this).apply {
            text = "Сборка через CircleCI работает"
            textSize = 22f
            gravity = Gravity.CENTER
        }
        setContentView(root)
    }
}
