package com.yellastrodev.dwij.activities

import android.Manifest
import android.Manifest.permission.READ_EXTERNAL_STORAGE
import android.R.id.message
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
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
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.NavController
import androidx.navigation.NavOptions
import androidx.navigation.fragment.NavHostFragment
import com.google.android.material.snackbar.Snackbar
import com.yellastrodev.dwij.R
import com.yellastrodev.dwij.data.repo.PlayerRepository
import com.yellastrodev.dwij.models.PlayerModel
import com.yellastrodev.dwij.yApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.logging.Logger
import kotlin.getValue


class MainActivity : AppCompatActivity() {

    companion object {
        val FIRST_TRACKLIST = "tracklist"
        val FIRST_HOME = "home"
        val FIRST_PLAYER = "player"
        val FIRST_PLLIST = "playlist"
        val FIRST_TYPES = listOf<String>(FIRST_PLAYER, FIRST_PLLIST, FIRST_TRACKLIST)

        val LOG = Logger.getLogger("MainActivity")
        val RECORD_REQUEST_CODE = 31437

    }


    lateinit var mNavController: NavController

    val playerRepo: PlayerRepository by lazy {
        (application as yApplication).playerRepo
    }

    val playerModel: PlayerModel by viewModels {
        PlayerModel.Factory(
            playerRepo = (application as yApplication).playerRepo,
            trackRepo = (application as yApplication).trackRepository,
            coverRepo = (application as yApplication).coverRepository,
            playlistRepo = (application as yApplication).playlistRepository
        )
    }

    @SuppressLint("MissingInflatedId")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.lay_main)
        applySystemBarInsets(
            rootView = findViewById(R.id.main_lay),
            useDarkSystemBarIcons = false,
        )
        val navHostFragment =
            supportFragmentManager.findFragmentById(R.id.fragmentContainerView) as NavHostFragment
        mNavController = navHostFragment.navController

//        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
//            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
//                != PackageManager.PERMISSION_GRANTED
//            ) {
//                ActivityCompat.requestPermissions(
//                    this,
//                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
//                    1001
//                )
//            }
//        }

    }

}
