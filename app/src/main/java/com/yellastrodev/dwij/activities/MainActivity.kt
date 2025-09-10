package com.yellastrodev.dwij.activities

import android.Manifest
import android.Manifest.permission.READ_EXTERNAL_STORAGE
import android.R.id.message
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.database.Cursor
import android.graphics.drawable.AnimationDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.view.View.OnClickListener
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.navigation.NavController
import androidx.navigation.NavOptions
import androidx.navigation.fragment.NavHostFragment
import com.google.android.material.snackbar.Snackbar
import com.yellastrodev.dwij.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.logging.Logger


class MainActivity : AppCompatActivity() {

    companion object {
        val FIRST_TRACKLIST = "tracklist"
        val FIRST_HOME = "home"
        val FIRST_PLAYER = "player"
        val FIRST_PLLIST = "playlist"
        val FIRST_TYPES = listOf<String>(FIRST_PLAYER, FIRST_PLLIST, FIRST_TRACKLIST)

        lateinit var LOG: Logger
        val RECORD_REQUEST_CODE = 31437

    }


    lateinit var mNavController: NavController

    @SuppressLint("MissingInflatedId")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.lay_main)
        val navHostFragment =
            supportFragmentManager.findFragmentById(R.id.fragmentContainerView) as NavHostFragment
        mNavController = navHostFragment.navController
        mNavController.addOnDestinationChangedListener(){controller, destination, arguments ->

        }
        LOG = Logger.getLogger("MainActivity")


    }

}